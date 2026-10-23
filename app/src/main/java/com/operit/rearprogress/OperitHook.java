package com.operit.rearprogress;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * OperitHook —— 挂在 Operit 进程里，自动捕获工具调用并上报背屏。
 *
 * Hook 点：com.ai.assistance.operit.core.tools.AIToolHandler
 *   executeTool(AITool) -> ToolResult      同步方法，一次拿到工具名和结果
 *
 * 只做观察，不改变任何返回值。
 */
final class OperitHook {

    private static final String TAG = "RearProgress";
    /** AI 主动上传进度的文件（可选，第一行=标题，第二行=百分比） */
    private static final String PROGRESS_FILE = "/data/local/tmp/agent_progress.txt";
    /** 开关文件：存在=显示通知，不存在=隐藏通知 */
    private static final String ENABLED_FILE = "/data/local/tmp/agent_progress.enabled";
    /** Operit 数据库路径 */
    private static final String DB_PATH = "/data/data/com.ai.assistance.operit/databases/app_database";

    private OperitHook() {}

    static void install(final XposedInterface xposed, ClassLoader cl) {
        try {
            LogX.i(TAG, "OperitHook.install enter v0.23");
            AgentProgress.markReady();
            startEnabledWatcher();
            Class<?> handlerCls = Class.forName(
                    "com.ai.assistance.operit.core.tools.AIToolHandler", true, cl);
            LogX.i(TAG, "AIToolHandler loaded");
            Class<?> aiToolCls = Class.forName(
                    "com.ai.assistance.operit.data.model.AITool", true, cl);
            LogX.i(TAG, "AITool loaded");

            Method target = null;
            for (Method m : handlerCls.getDeclaredMethods()) {
                if (!"executeTool".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!aiToolCls.isAssignableFrom(m.getParameterTypes()[0])) continue;
                target = m;
                break;
            }
            if (target == null) {
                LogX.w(TAG, "AIToolHandler.executeTool not found");
                return;
            }
            final Method t = target;
            xposed.hook(t).intercept(chain -> {
                Object tool = chain.getArg(0);
                String name = null;
                String detail = null;
                try {
                    name = str(field(aiToolCls, tool, "name"));
                    detail = summarizeParameters(field(aiToolCls, tool, "parameters"));

                    // 记录终端命令（如果是终端相关工具）
                    if (name != null && (name.contains("shell") || name.contains("terminal") || name.contains("command"))) {
                        AgentProgress.recordCommand(detail);
                    }
                } catch (Throwable ignored) {}

                // 工具开始时立即推送（让灵动岛实时显示当前调用的工具）
                AgentProgress.recordTool(name, detail, true);
                FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(), AgentProgress.body(),
                        AgentProgress.percent());
                LogX.i(TAG, "tool start -> " + name + " (" + AgentProgress.toolCount() + ")");

                Object result = null;
                boolean success = true;
                try {
                    result = chain.proceed();
                    try {
                        Object ok = field(result.getClass(), result, "success");
                        success = !(ok instanceof Boolean) || (Boolean) ok;

                        // 记录输出结果（如果是终端相关工具）
                        if (name != null && (name.contains("shell") || name.contains("terminal") || name.contains("command"))) {
                            String output = str(field(result.getClass(), result, "output"));
                            if (output != null) {
                                AgentProgress.recordOutput(output);
                            }
                        }

                        // 提取 diff 统计（edit_file/create_file 等文件操作工具）
                        if (name != null && (name.equals("edit_file") || name.equals("create_file") || name.equals("delete_file"))) {
                            String output = str(field(result.getClass(), result, "output"));
                            if (output != null) {
                                String diffStats = extractDiffStats(output);
                                if (diffStats != null && !diffStats.isEmpty()) {
                                    AgentProgress.setLastDiffStats(diffStats);
                                    LogX.i(TAG, "diff stats for " + name + ": " + diffStats);
                                }
                            }
                        }

                        // 读取 token 数据（每次工具调用都更新）
                        String tokenUsage = readTokenUsage();
                        if (tokenUsage != null && !tokenUsage.isEmpty()) {
                            AgentProgress.setLastTokenUsage(tokenUsage);
                            LogX.i(TAG, "token usage: " + tokenUsage);
                        }
                    } catch (Throwable ignored) {}
                } catch (Throwable e) {
                    success = false;
                    throw e;
                } finally {
                    AgentProgress.recordTool(name, detail, success);
                    LogX.i(TAG, "tool " + (success ? "ok" : "fail") + " -> " + name
                            + " (" + AgentProgress.toolCount() + ")");
                }
                return result;
            });
            LogX.i(TAG, "HOOKED Operit AIToolHandler.executeTool -> auto progress");
            installReplyCompletedHook(xposed, cl);
            pushInitialStateLater();
            startFileWatcher();
        } catch (Throwable e) {
            LogX.e(TAG, "installOperitToolHook failed", e);
        }
    }

    /**
     * Hook EnhancedAIService.notifyReplyCompleted(...) —— Operit 回复完成的收尾点。
     * 收到即置为「已完成」；若之后又有工具调用，会自动回到「任务进行中」（新任务）。
     */
    private static void installReplyCompletedHook(final XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(
                    "com.ai.assistance.operit.api.chat.EnhancedAIService", true, cl);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"notifyReplyCompleted".equals(m.getName())) continue;
                if (m.getReturnType() != void.class) continue;
                target = m;
                break;
            }
            if (target == null) {
                LogX.w(TAG, "EnhancedAIService.notifyReplyCompleted not found");
                return;
            }
            final Method t = target;
            xposed.hook(t).intercept(chain -> {
                try {
                    AgentProgress.markDone();
                    FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(), AgentProgress.body(),
                            AgentProgress.percent());
                    LogX.i(TAG, "reply completed -> 已完成");
                } catch (Throwable e) {
                    LogX.e(TAG, "replyCompleted hook body failed", e);
                }
                return chain.proceed();
            });
            LogX.i(TAG, "HOOKED EnhancedAIService.notifyReplyCompleted -> 已完成 触发点");
        } catch (Throwable e) {
            LogX.e(TAG, "installReplyCompletedHook failed", e);
        }
    }

    /** 启动后先把「已就绪」状态推到背屏 */
    private static void pushInitialStateLater() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2000L);
                AgentProgress.markReady();
                FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(), AgentProgress.body(),
                        AgentProgress.percent());
                LogX.i(TAG, "initial state pushed -> " + AgentProgress.title());
            } catch (Throwable e) {
                LogX.e(TAG, "pushInitialState failed", e);
            }
        }, "RearProgressInit");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 让 AI 可以主动上传进度（可选）：
     *   写 /data/local/tmp/agent_progress.txt
     *   第一行 = 上半部分标题，可选第二行 "NN" 表示百分比
     * 工具调用仍会自动填充下半部分。
     */
    private static void startFileWatcher() {
        Thread t = new Thread(() -> {
            long lastMtime = -1L;
            boolean hadFile = false;
            while (true) {
                try {
                    Thread.sleep(1000L);
                    java.io.File f = new java.io.File(PROGRESS_FILE);

                    // 文件被删除 / 从未存在 → 清掉 AI 上传并立刻回滚标题
                    if (!f.exists()) {
                        if (hadFile) {
                            hadFile = false;
                            lastMtime = -1L;
                            AgentProgress.clearAiProgress();
                            pushState("ai file removed");
                        }
                        continue;
                    }

                    long m = f.lastModified();
                    if (m == lastMtime) continue;
                    lastMtime = m;
                    hadFile = true;

                    String content = readAll(f);
                    if (content == null) continue;
                    content = content.trim();

                    // 空文件 → 清掉 AI 上传并立刻回滚标题
                    if (content.isEmpty()) {
                        AgentProgress.clearAiProgress();
                        pushState("ai cleared");
                        continue;
                    }

                    // 过期检查：AI 上传内容超过 5 分钟未更新 → 自动删除文件
                    long age = System.currentTimeMillis() - m;
                    if (age > 5 * 60 * 1000L) {
                        f.delete();
                        AgentProgress.clearAiProgress();
                        pushState("ai expired");
                        LogX.i(TAG, "ai progress expired (age=" + age + "ms), file deleted");
                        continue;
                    }

                    String[] lines = content.split("\n", 2);
                    String title = lines[0].trim();
                    int pct = -1;
                    if (lines.length > 1) {
                        try { pct = Integer.parseInt(lines[1].trim()); } catch (Throwable ignored) {}
                    }

                    // 判断是否为 JSON 格式
                    if (title.startsWith("{") || title.startsWith("[")) {
                        AgentProgress.setAiState(content);
                    } else {
                        AgentProgress.setAiProgress(title, pct);
                    }
                    pushState("ai upload");
                } catch (Throwable ignored) {}
            }
        }, "RearProgressFileWatcher");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 监控开关文件：存在=显示，不存在=隐藏并撤掉通知
     */
    private static void startEnabledWatcher() {
        Thread t = new Thread(() -> {
            boolean lastEnabled = false;
            while (true) {
                try {
                    Thread.sleep(1000L);
                    java.io.File f = new java.io.File(ENABLED_FILE);
                    boolean enabled = f.exists();

                    // 自动隐藏检查：任务结束后 10 分钟，自动删除 enabled 文件
                    if (enabled && AgentProgress.shouldAutoHide()) {
                        f.delete();
                        enabled = false;
                        LogX.i(TAG, "auto-hide: task done for 10min, deleted enabled file");
                    }

                    // 状态变化时触发
                    if (enabled != lastEnabled) {
                        lastEnabled = enabled;
                        if (!enabled) {
                            // 关闭时撤掉通知
                            FocusNotify.stop();
                            LogX.i(TAG, "enabled file removed -> notification stopped");
                        } else {
                            // 打开时推送当前状态
                            pushState("enabled");
                            LogX.i(TAG, "enabled file created -> notification started");
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }, "RearProgressEnabledWatcher");
        t.setDaemon(true);
        t.start();
    }

    /** 统一推送当前状态（标题 + 工具行 + 进度） */
    private static void pushState(String reason) {
        String title = AgentProgress.title();
        String body = AgentProgress.body();
        int pct = AgentProgress.percent();
        FocusNotify.pushRaw(title, body, pct);
        LogX.i(TAG, "push(" + reason + ") title=" + title);
    }

    private static String readAll(java.io.File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /** parameters 是 List<...>，取每个元素的文本拼成一句 */
    private static String summarizeParameters(Object parameters) {
        if (parameters == null) return null;
        try {
            if (parameters instanceof Iterable) {
                StringBuilder sb = new StringBuilder();
                for (Object p : (Iterable<?>) parameters) {
                    String s = extractText(p);
                    if (s == null || s.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(s);
                    if (sb.length() > 60) break;
                }
                return sb.toString();
            }
            return extractText(parameters);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从参数对象里挖出最有信息量的那个字符串字段（通常是 value / command / path） */
    private static String extractText(Object p) {
        if (p == null) return null;
        if (p instanceof CharSequence) return p.toString();
        Class<?> c = p.getClass();
        for (String fname : new String[]{"value", "command", "path", "text", "query", "name"}) {
            try {
                Object v = field(c, p, fname);
                if (v instanceof CharSequence && ((CharSequence) v).length() > 0) {
                    return v.toString();
                }
            } catch (Throwable ignored) {}
        }
        // 兜底：任意一个非空 String 字段
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(p);
                if (v instanceof CharSequence && ((CharSequence) v).length() > 0) {
                    return v.toString();
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object field(Class<?> cls, Object obj, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    /**
     * 从工具输出中提取 diff 统计信息
     * 例如："Successfully applied AI code to file: ...\nChanges: +36 -1 lines\n@@ ..."
     * 提取 "Changes: +36 -1 lines" 或 "36 insertions(+), 1 deletions(-)"
     */
    private static String extractDiffStats(String output) {
        if (output == null || output.isEmpty()) return null;
        
        // 格式1：Changes: +36 -1 lines
        java.util.regex.Matcher m1 = java.util.regex.Pattern.compile("Changes: \\+(\\d+) -(\\d+) lines?").matcher(output);
        if (m1.find()) {
            return "+" + m1.group(1) + " -" + m1.group(2);
        }
        
        // 格式2：36 insertions(+), 1 deletions(-)
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("(\\d+) insertions?\\(\\+\\).*?(\\d+) deletions?\\(-\\)").matcher(output);
        if (m2.find()) {
            return "+" + m2.group(1) + " -" + m2.group(2);
        }
        
        return null;
    }

    /**
     * 从 Operit 数据库读取当前对话的 token 数据
     * 返回格式："↑89743 ↓366" 或 null
     */
    private static String readTokenUsage() {
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    DB_PATH, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            
            // 查询最近一条 assistant 消息的 token 数据
            android.database.Cursor c = db.rawQuery(
                    "SELECT inputTokens, outputTokens, cachedInputTokens FROM messages " +
                    "WHERE sender = 'assistant' ORDER BY timestamp DESC LIMIT 1", null);
            
            if (c != null && c.moveToFirst()) {
                int inputTokens = c.getInt(0);
                int outputTokens = c.getInt(1);
                int cachedInputTokens = c.getInt(2);
                c.close();
                
                // 格式化：↑输入 ↓输出 (cache:缓存)
                if (cachedInputTokens > 0) {
                    return String.format("↑%d ↓%d (cache:%d)", inputTokens, outputTokens, cachedInputTokens);
                } else {
                    return String.format("↑%d ↓%d", inputTokens, outputTokens);
                }
            }
            
            if (c != null) c.close();
            return null;
        } catch (Throwable t) {
            LogX.w(TAG, "readTokenUsage failed: " + t);
            return null;
        } finally {
            if (db != null) {
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}