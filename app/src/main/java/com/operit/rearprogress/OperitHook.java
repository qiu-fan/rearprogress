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
    /**
     * 模拟指令文件：测试按钮写入，本进程（Operit 进程）读取后逐行执行。
     * 这样「模拟」走的是与真实 hook 完全相同的链路（同一个包、同一个 channel、
     * 同一套 AgentProgress + FocusNotify 状态），通知栏看到的也是同一条卡片。
     * 每行格式：工具名|参数摘要
     */
    private static final String SIM_FILE = "/data/local/tmp/agent_progress.sim";
    /** 模拟文件上次处理的 mtime，用于去重（防止每秒重复执行导致通知闪烁） */
    private static long sLastSimMtime = -1L;
    /** 静默阈值：超过该时间无新推送则整条撤掉通知（含背屏 + 灵动岛） */
    private static final long IDLE_THRESHOLD_MS = 60_000L;
    /** 上次显示通知的时间戳，供 watcher 检测静默状态 */
    private static volatile long sLastNotifyAt = 0L;
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
                // 自适应显示：解析特定工具参数，临时覆盖 title/toolName
                String[] adaptive = parseAdaptiveDisplay(name, detail);
                if (adaptive != null) {
                    AgentProgress.setAdaptiveDisplay(adaptive[0], adaptive[1]);
                    LogX.i(TAG, "adaptive display: " + adaptive[0] + " / " + adaptive[1]);
                }
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
                        // 若 TokenHook 已提供实时数据，则不用数据库旧值覆盖
                        if (!TokenHook.hasLiveData()) {
                            String tokenUsage = readTokenUsage();
                            if (tokenUsage != null && !tokenUsage.isEmpty()) {
                                AgentProgress.setLastTokenUsage(tokenUsage);
                                LogX.i(TAG, "token usage (db fallback): " + tokenUsage);
                            }
                        }
                    } catch (Throwable ignored) {}
                } catch (Throwable e) {
                    success = false;
                    throw e;
                } finally {
                    AgentProgress.clearAdaptiveDisplay();
                    AgentProgress.recordTool(name, detail, success);
                    FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(), AgentProgress.body(),
                            AgentProgress.percent());
                    LogX.i(TAG, "tool " + (success ? "ok" : "fail") + " -> " + name
                            + " (" + AgentProgress.toolCount() + ")");
                }
                return result;
            });
            LogX.i(TAG, "HOOKED Operit AIToolHandler.executeTool -> auto progress");
            installReplyCompletedHook(xposed, cl);
            // 实时 token 捕获（方案 B）
            TokenHook.install(xposed, cl);
            pushInitialStateLater();
            startFileWatcher();
            // 所有工具执行前必调 notifyToolExecutionStarted(AITool) —— 统一捕获点
            installToolStartedHook(xposed, cl, aiToolCls);
        } catch (Throwable e) {
            LogX.e(TAG, "installOperitToolHook failed", e);
        }
    }

    /**
     * 解析特定工具参数，返回自适应显示 [title, sub] 或 null（无自适应）。
     * - sleep（独立工具或 shell 命令）→ "sleep" + 毫秒数
     * - shell 编译（gradle/assembleDebug/bump/compile）→ "Compiling" + 版本号/hash
     */
    private static String[] parseAdaptiveDisplay(String name, String detail) {
        if (name == null || detail == null) {
            LogX.i("RearProgress", "adaptive skip: name=" + name + " detail=" + detail);
            return null;
        }
        String lower = name.toLowerCase();
        String cmd = detail.toLowerCase();
        LogX.i("RearProgress", "adaptive check: name=[" + lower + "] cmd=[" + cmd.substring(0, Math.min(60, cmd.length())) + "]");

        // ① 编译场景（优先：耗时最长、信息最有价值）
        if (lower.contains("shell") || lower.contains("terminal")) {
            if (cmd.contains("gradle") || cmd.contains("assembledebug") || cmd.contains("assemble release")
                    || cmd.contains("bump") || cmd.contains("cmd package compile")) {
                String ver = extractVersionOrHash(detail);
                return new String[]{"Compiling", ver != null ? ver : ""};
            }
        }

        // ② git 场景：按动作给标题，右侧给分支/目标
        String gitAct = gitAction(cmd);
        if (gitAct != null) {
            return new String[]{gitAct, gitTarget(detail, gitAct)};
        }

        // ③ sleep 场景（精确匹配，避免 commit message 里出现 sleep 误判）
        if (lower.contains("sleep") || hasSleepCommand(cmd)) {
            Long ms = extractSleepMs(detail);
            return new String[]{"sleep", ms != null ? ms + "ms" : ""};
        }
        // ④ logcat 场景：左侧显示 Logcat，右侧显示它在搜的关键词（grep 模式/-s 标签/pidof 包名）；
        //    识别不出 → sub 传 null → toolName() 不覆盖，右侧保持原样 token（"右边不用动"）
        if (hasLogcatCommand(cmd)) {
            String q = extractLogcatQuery(detail);
            return new String[]{"Logcat", (q == null || q.isEmpty()) ? null : q};
        }
        return null;
    }

    /** logcat 是否作为【命令】出现（行首 / | ; & 之后），避免 "grep logcat 字样" 这类文本误判 */
    private static boolean hasLogcatCommand(String cmd) {
        String c = cmd.replaceAll("\\s+", " ").trim();
        return java.util.regex.Pattern.compile("(^|[|;&]\\s*)logcat\\b").matcher(c).find();
    }

    /** 识别 logcat 在搜什么：grep 模式 > -s 标签 > pidof 包名 > --pid=N；识别不出返回空串 */
    private static String extractLogcatQuery(String s) {
        try {
            // 1) grep 引号内的完整模式（可含空格，如 "tool start"）
            java.util.regex.Matcher q = java.util.regex.Pattern
                    .compile("\\bgrep\\s+(?:-\\S+\\s+)*[\"']([^\"']+)[\"']").matcher(s);
            if (q.find()) return trim(q.group(1), 20);
            // 2) grep 裸词（跳过 -iE/-c/-A2 等选项）
            java.util.regex.Matcher b = java.util.regex.Pattern
                    .compile("\\bgrep\\s+(?:-\\S+\\s+)*(\\S+)").matcher(s);
            if (b.find() && !b.group(1).startsWith("-")) return trim(b.group(1), 20);
            // 3) logcat -s TAG:PRIO → TAG
            java.util.regex.Matcher t = java.util.regex.Pattern
                    .compile("\\s-s\\s+([A-Za-z0-9_.]+)").matcher(s);
            if (t.find()) return trim(t.group(1), 20);
            // 4) pidof 包名
            java.util.regex.Matcher p = java.util.regex.Pattern
                    .compile("pidof\\s+([a-zA-Z0-9_.]+)").matcher(s);
            if (p.find()) return trim(p.group(1), 24);
            // 5) --pid=12345
            java.util.regex.Matcher d = java.util.regex.Pattern
                    .compile("--pid=(\\d+)").matcher(s);
            if (d.find()) return "pid" + d.group(1);
        } catch (Throwable ignored) {}
        return "";
    }

    /** 识别 git 子命令 → 显示动作名（无 git 返回 null）。
     *  注意真实命令常带 -c/-C 参数（git -c user.name=x commit），所以不能要求 "git commit" 紧邻。 */
    private static String gitAction(String cmd) {
        int gi = cmd.lastIndexOf("git ");
        if (gi < 0) return null;
        String tail = cmd.substring(gi).replaceAll("\\s+", " ");
        String[][] acts = {
                {"commit", "Commit"}, {"merge", "Merge"}, {"push", "Push"}, {"pull", "Pull"},
                {"fetch", "Fetch"}, {"checkout", "Switch"}, {"switch", "Switch"}, {"branch", "Branch"},
                {"clone", "Clone"}, {"rebase", "Rebase"}, {"reset", "Reset"}, {"stash", "Stash"},
                {"add", "Add"}, {"status", "Status"}, {"diff", "Diff"}, {"log", "Log"}, {"tag", "Tag"}
        };
        for (String[] a : acts) {
            if (tail.matches("(?s).*\\b" + a[0] + "\\b.*")) return a[1];
        }
        return "Git";
    }

    /** 从 git 命令里取右侧内容：分支/目标；commit 取消息摘要 */
    private static String gitTarget(String s, String action) {
        try {
            if ("Commit".equals(action)) {
                // -m "消息" / -m '消息' / -m 消息
                java.util.regex.Matcher cm = java.util.regex.Pattern
                        .compile("-m\\s+[\"']?([^\"'\\n]{1,40})").matcher(s);
                if (cm.find()) return trim(cm.group(1).trim(), 24);
                return "";
            }
            int gi = s.lastIndexOf("git ");
            if (gi < 0) return "";
            String tail = s.substring(gi).replaceAll("\\s+", " ");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\b(?:push|pull|merge|checkout|switch|branch|clone|rebase|reset|fetch)\\b(.*)$")
                    .matcher(tail);
            if (!m.find()) return "";
            String[] toks = m.group(1).trim().split("\\s+");
            String first = null, last = null;
            for (String t : toks) {
                if (t.isEmpty() || t.startsWith("-")) continue;
                if (first == null) first = t;
                last = t;
            }
            // push/pull 常见 "origin dev" → 取最后一个（分支）；其余取第一个（目标分支/路径）
            String t = ("Push".equals(action) || "Pull".equals(action)) ? last : first;
            if (t == null || t.isEmpty()) return "";
            int i = t.lastIndexOf('/');
            if (i > 0) t = t.substring(i + 1);
            t = t.replaceAll("[\"'`;|&]$", "");
            return trim(t, 20);
        } catch (Throwable ignored) {}
        return "";
    }

    /** 命令里是否真的在调用 sleep（开头/管道/&&/;之后，允许 sleep30 无空格），避免误判参数里的 sleep 字样 */
    private static boolean hasSleepCommand(String cmd) {
        String c = cmd.replaceAll("\\s+", " ").trim();
        // (^|&&|;|||) + sleep + 可选空格 + 数字
        if (java.util.regex.Pattern.compile("(?:^|[&;|]\\s*)sleep\\s*[0-9]").matcher(c).find()) return true;
        if (c.startsWith("sleep ") || c.contains("&& sleep ") || c.contains("; sleep ") || c.contains("| sleep ")) return true;
        return false;
    }

    /** 提取 sleep 参数并换算成毫秒（sleep30→30000；sleep 0.5→500；支持 30s/500ms） */
    private static Long extractSleepMs(String s) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("sleep\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(ms|s)?")
                    .matcher(s.toLowerCase());
            if (!m.find()) return null;
            double v = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            if ("ms".equals(unit)) return Math.round(v);
            return Math.round(v * 1000.0);   // 默认按秒
        } catch (Throwable t) {
            return null;
        }
    }

    /** 截断字符串（超长加 …） */
    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    /** 从命令里提取版本号或 git hash（versionCode=数字 或 7-40 位 hex）；
     *  都识别不出时（如 ./gradlew assembleDebug）→ 从项目 build.gradle.kts 读 versionName */
    private static String extractVersionOrHash(String s) {
        java.util.regex.Matcher v = java.util.regex.Pattern
                .compile("version(?:Code)?[=:\\s]+(\\d+)").matcher(s);
        if (v.find()) return v.group(1);
        java.util.regex.Matcher h = java.util.regex.Pattern
                .compile("\\b[0-9a-f]{7,40}\\b").matcher(s);
        if (h.find()) return h.group();
        // 编译场景兜底：读项目版本号（versionName 如 1.6.0，读不到再试 versionCode）
        String ver = readProjectVersion(s);
        if (ver != null) return ver;
        return null;
    }

    /** 编译命令识别不出版本时：从命令里的 cd <目录> 找 build.gradle.kts 读 versionName。
     *  支持 proot 工作副本 ~/rpb_*、/root/rpb_* → 映射回 /sdcard/Android/code/rp_work（它们是它的拷贝）。 */
    private static String readProjectVersion(String cmd) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\bcd\\s+(\\S+)").matcher(cmd);
            if (!m.find()) return null;
            String dir = m.group(1).replaceAll("[;&|]+$", "");
            // proot 里的 home 目录（~/rpb_xxx、/root/rpb_xxx）在 Operit 进程里读不到，映射回源工作树
            if (dir.startsWith("~/") || dir.startsWith("/root/")) {
                if (dir.contains("rpb_")) {
                    dir = "/sdcard/Android/code/rp_work";
                } else {
                    return null;
                }
            }
            java.io.File f = new java.io.File(dir, "app/build.gradle.kts");
            if (!f.exists()) f = new java.io.File(dir, "build.gradle.kts");
            if (!f.exists() || !f.isFile()) return null;
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n++ < 300) sb.append(line).append('\n');
            try { br.close(); } catch (Throwable ignored) {}
            String txt = sb.toString();
            java.util.regex.Matcher vn = java.util.regex.Pattern
                    .compile("versionName\\s*=\\s*\"([^\"]+)\"").matcher(txt);
            if (vn.find()) return vn.group(1);
            java.util.regex.Matcher vc = java.util.regex.Pattern
                    .compile("versionCode\\s*=\\s*(\\d+)").matcher(txt);
            if (vc.find()) return vc.group(1);
        } catch (Throwable ignored) {}
        return null;
    }

    /** Hook AIToolHandler.notifyToolExecutionStarted(AITool) —— 所有工具执行前必调 */
    private static void installToolStartedHook(final XposedInterface xposed, ClassLoader cl,
                                               Class<?> aiToolCls) {
        try {
            Class<?> handlerCls = Class.forName(
                    "com.ai.assistance.operit.core.tools.AIToolHandler", true, cl);
            Method target = null;
            for (Method m : handlerCls.getDeclaredMethods()) {
                if (!"notifyToolExecutionStarted".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && aiToolCls.isAssignableFrom(ps[0])) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                LogX.w(TAG, "notifyToolExecutionStarted not found");
                return;
            }
            final Method t = target;
            final Class<?> tc = aiToolCls;
            xposed.hook(t).intercept(chain -> {
                try {
                    Object tool = chain.getArg(0);
                    String name = str(field(tc, tool, "name"));
                    if (name != null && !name.isEmpty()) {
                        AgentProgress.recordTool(name, "", true);
                        FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(),
                                AgentProgress.body(), AgentProgress.percent());
                        LogX.i(TAG, "tool-started -> " + name);
                    }
                } catch (Throwable ignored) {}
                return chain.proceed();
            });
            LogX.i(TAG, "HOOKED notifyToolExecutionStarted -> all tools");
        } catch (Throwable e) {
            LogX.e(TAG, "installToolStartedHook failed", e);
        }
    }

    /** 已 Hook 的 executor 类（避免重复 Hook） */
    private static final java.util.Set<String> sHookedExecCls =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    /**
     * Hook AIToolHandler.getToolExecutor(String) —— 所有工具的统一出口。
     * 返回 executor 时，反射 Hook 它的 invoke(AITool) 方法（每个类只 Hook 一次）。
     * 这样 list_files/read_file 等匿名 ToolExecutor 也能覆盖。
     */
    private static void installGetToolExecutorHook(final XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> handlerCls = Class.forName(
                    "com.ai.assistance.operit.core.tools.AIToolHandler", true, cl);
            Class<?> toolCls = Class.forName(
                    "com.ai.assistance.operit.data.model.AITool", true, cl);
            Class<?> executorCls = Class.forName(
                    "com.ai.assistance.operit.core.tools.ToolExecutor", true, cl);

            Method target = null;
            for (Method m : handlerCls.getDeclaredMethods()) {
                if (!"getToolExecutor".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == String.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                LogX.w(TAG, "getToolExecutor not found");
                return;
            }
            final Method t = target;
            final XposedInterface x = xposed;
            final Class<?> tc = toolCls;
            final Class<?> ec = executorCls;

            xposed.hook(t).intercept(chain -> {
                Object exec = chain.proceed();
                try {
                    if (exec != null && ec.isInstance(exec)) {
                        maybeHookExecutorInvoke(x, exec.getClass(), tc);
                    }
                } catch (Throwable ignored) {}
                return exec;
            });
            LogX.i(TAG, "HOOKED getToolExecutor -> dynamic executor invoke hook");
        } catch (Throwable e) {
            LogX.e(TAG, "installGetToolExecutorHook failed", e);
        }
    }

    /** 反射 Hook 某个 executor 类的 invoke(AITool)（每个类只做一次） */
    private static void maybeHookExecutorInvoke(final XposedInterface x,
                                                Class<?> execCls, Class<?> toolCls) {
        String key = execCls.getName();
        if (!sHookedExecCls.add(key)) return;   // 已 Hook 过
        try {
            Method inv = null;
            for (Method m : execCls.getDeclaredMethods()) {
                if (!"invoke".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0].isAssignableFrom(toolCls)) {
                    inv = m;
                    break;
                }
            }
            if (inv == null) {
                // invoke 可能在接口上，找同名即可
                inv = findMethodByName(execCls, "invoke");
            }
            if (inv == null) {
                sHookedExecCls.remove(key);
                return;
            }
            inv.setAccessible(true);
            final String toolKey = key;
            x.hook(inv).intercept(chain -> {
                // 工具名：从 invoke 参数的 AITool.name 提取
                String name = null;
                try {
                    Object tool = chain.getArg(0);
                    if (tool != null) {
                        Method g = toolCls.getMethod("getName");
                        name = (String) g.invoke(tool);
                    }
                } catch (Throwable ignored) {}
                if (name == null || name.isEmpty()) name = toolKey;

                AgentProgress.recordTool(name, "", true);
                FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(),
                        AgentProgress.body(), AgentProgress.percent());
                LogX.i(TAG, "tool-exec start -> " + name);
                boolean ok = true;
                try {
                    return chain.proceed();
                } catch (Throwable e) {
                    ok = false;
                    throw e;
                } finally {
                    AgentProgress.recordTool(name, "", ok);
                }
            });
            LogX.i(TAG, "tool-exec HOOKED invoke: " + key);
        } catch (Throwable e) {
            sHookedExecCls.remove(key);
            LogX.w(TAG, "maybeHookExecutorInvoke " + key + " failed: " + e);
        }
    }

    /**
     * Hook ToolExecutor 接口的执行方法 —— 捕获内置工具
     * （list_files / find_files / read_file / query_memory 等不走 AIToolHandler 的工具）。
     *
     * 实现策略：运行时反射扫描接口方法，枚举已知实现类，逐个 Hook。
     * 工具名从参数里启发式提取（String 工具名 / AITool.name / Map 的 name 键）。
     */
    private static void installToolExecutorHook(final XposedInterface xposed, ClassLoader cl) {
        try {
            Class<?> iface = Class.forName(
                    "com.ai.assistance.operit.core.tools.ToolExecutor", true, cl);
            // 找接口里的执行方法（非 default、非 static）
            Method execMethod = null;
            for (Method m : iface.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(mod)) continue;
                if (m.isDefault()) continue;
                execMethod = m;   // 取第一个抽象方法（接口通常只有一个）
                break;
            }
            if (execMethod == null) {
                // 退化：取任意非 static 方法
                Method[] ms = iface.getDeclaredMethods();
                if (ms.length > 0) execMethod = ms[0];
            }
            if (execMethod == null) {
                LogX.w(TAG, "ToolExecutor: no method found");
                return;
            }

            // 已知实现类（反编译确认的内置工具执行器）
            String[] impls = {
                    "com.ai.assistance.operit.core.tools.defaultTool.standard.MemoryQueryToolExecutor",
                    "com.ai.assistance.operit.core.tools.PackageToolExecutor",
                    "com.ai.assistance.operit.core.tools.ADBToolExecutor",
                    "com.ai.assistance.operit.core.tools.defaultTool.accessbility.AccessibilityDeviceInfoToolExecutor",
                    "com.ai.assistance.operit.core.tools.defaultTool.admin.AdminDeviceInfoToolExecutor",
                    "com.ai.assistance.operit.core.tools.defaultTool.debugger.DebuggerDeviceInfoToolExecutor",
                    "com.ai.assistance.operit.core.tools.defaultTool.root.RootDeviceInfoToolExecutor",
            };

            final Method target = execMethod;
            int hooked = 0;
            for (String implName : impls) {
                try {
                    Class<?> impl = Class.forName(implName, false, cl);
                    Method m;
                    try {
                        m = impl.getDeclaredMethod(target.getName(), target.getParameterTypes());
                    } catch (NoSuchMethodException ns) {
                        // 找同名方法（签名可能被协程包装改变）
                        m = findMethodByName(impl, target.getName());
                        if (m == null) continue;
                    }
                    m.setAccessible(true);
                    hookToolMethod(xposed, m, impl);
                    hooked++;
                } catch (Throwable ignored) {
                    // 该实现类不存在，跳过
                }
            }
            LogX.i(TAG, "ToolExecutor hook installed: method=" + target.getName()
                    + " impls=" + hooked);
        } catch (Throwable t) {
            LogX.e(TAG, "installToolExecutorHook failed", t);
        }
    }

    private static Method findMethodByName(Class<?> cls, String name) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    /** 给单个工具执行方法装 Hook：开始记录 + 结束记录 */
    private static void hookToolMethod(final XposedInterface xposed, Method m, Class<?> impl) {
        xposed.hook(m).intercept(chain -> {
            String name = extractToolName(chain.getArgs(), impl);
            AgentProgress.recordTool(name, "", true);
            FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(),
                    AgentProgress.body(), AgentProgress.percent());
            LogX.i(TAG, "tool-exec start -> " + name);
            Object result = null;
            boolean success = true;
            try {
                result = chain.proceed();
                return result;
            } catch (Throwable e) {
                success = false;
                throw e;
            } finally {
                AgentProgress.recordTool(name, "", success);
            }
        });
    }

    /** 从参数里启发式提取工具名 */
    private static String extractToolName(java.util.List<Object> args, Class<?> impl) {
        if (args == null || args.isEmpty()) return impl.getSimpleName();
        for (Object a : args) {
            if (a instanceof String) {
                String s = ((String) a).trim();
                // 工具名一般是 snake_case 短词，不含空格和路径
                if (!s.isEmpty() && s.length() < 64 && !s.contains(" ")
                        && !s.contains("/") && s.contains("_")) {
                    return s;
                }
            }
            if (a != null) {
                // 尝试 AITool.name 字段
                try {
                    Method getName = a.getClass().getMethod("getName");
                    Object v = getName.invoke(a);
                    if (v instanceof String && !((String) v).isEmpty()) {
                        return (String) v;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return impl.getSimpleName();
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

    /** 启动后先推「Agent / 已就绪」初始状态到背屏（延时避开启动扫描窗口，且只在尚无真实工具时推） */
    private static void pushInitialStateLater() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(AgentProgress.startupWindowMs() + 500L);
                // 若启动窗口内已有真实工具记录（不该有），则不覆盖
                if (AgentProgress.toolCount() > 0) return;
                AgentProgress.markReady();
                // 初始状态：标题固定 Agent；副标题留空（避免与标题重复）；内容=已就绪。
                // 背屏模板 ready 态也会把 subtext 渲染为「已就绪」（Agent + 已就绪）。
                FocusNotify.pushState("Agent", "", "已就绪", -1);
                LogX.i(TAG, "initial state pushed -> Agent / (空副标题) / 已就绪");
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
            boolean idled = false;
            while (true) {
                try {
                    Thread.sleep(1000L);
                    java.io.File f = new java.io.File(ENABLED_FILE);
                    boolean enabled = f.exists();

                    // 自动隐藏：任务结束 10 分钟后删 enabled（只在文件真实存在时执行）
                    if (enabled && AgentProgress.shouldAutoHide()) {
                        if (f.exists() && f.delete()) {
                            enabled = false;
                            LogX.i(TAG, "auto-hide: deleted enabled file");
                        }
                    }

                    // 静默检测：上次推送距今超过阈值 → 整条撤掉，不留痕迹
                    // 仅限"有真实活动"后才计时（sLastNotifyAt > 0，即 pushState 曾被调用）
                    if (enabled && !idled && sLastNotifyAt > 0) {
                        long lastAt = sLastNotifyAt;
                        if (System.currentTimeMillis() - lastAt > IDLE_THRESHOLD_MS) {
                            idled = true;
                            FocusNotify.stop();
                            boolean deleted = f.exists() && f.delete();
                            enabled = false;
                            LogX.i(TAG, "silent " + (IDLE_THRESHOLD_MS / 1000)
                                    + "s -> notification removed, file deleted=" + deleted);
                        }
                    } else if (!enabled) {
                        // 文件已消失，重置静默标志，允许下次有活动时重新显示
                        idled = false;
                    }

                    // 模拟指令文件：测试按钮写入，这里按真实 hook 的链路执行
                    // （同一个 Operit 包 / 同一个 channel / 同一套 AgentProgress 状态）
                    // ⚠️ 必须防止重复执行：文件由 root 写入，Operit 进程删不掉，
                    //    用 mtime 去重 + 尝试清空内容，否则每秒重跑一次导致通知疯狂闪烁。
                    java.io.File sim = new java.io.File(SIM_FILE);
                    if (sim.exists()) {
                        long mt = sim.lastModified();
                        if (mt != sLastSimMtime) {
                            String content = readAll(sim);
                            sLastSimMtime = mt;
                            // 尽量清空，避免下次启动重复执行
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(sim)) {
                                fos.write(new byte[0]);
                            } catch (Throwable ignored2) {
                                sim.delete();
                            }
                            if (content != null && !content.trim().isEmpty()) {
                                Thread st = new Thread(() -> runSimulation(content),
                                        "RearProgressSimulate");
                                st.setDaemon(true);
                                st.start();
                            }
                        }
                    } else {
                        sLastSimMtime = -1L;
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

    /**
     * 执行模拟指令：逐行解析 "工具名|参数摘要"，按真实 hook 完全相同的调用链执行。
     * 关键点：这里运行在 Operit 进程里，通知由 Operit 发出（同一个包/同一个 channel），
     * 与真实 hook 产生的卡片没有任何区别。
     */
    private static void runSimulation(String content) {
        try {
            String[] lines = content.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String name = line;
                String detail = "";
                int sep = line.indexOf('|');
                if (sep >= 0) {
                    name = line.substring(0, sep).trim();
                    detail = line.substring(sep + 1).trim();
                }
                if (name.isEmpty()) continue;
                // ↓↓↓ 与 executeTool hook 里完全一致的两步 ↓↓↓
                AgentProgress.recordTool(name, detail, true);
                FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(),
                        AgentProgress.body(), AgentProgress.percent());
                sLastNotifyAt = System.currentTimeMillis();
                LogX.i(TAG, "simulate -> " + name);
                Thread.sleep(1500L);
            }
            LogX.i(TAG, "simulation finished");
        } catch (Throwable e) {
            LogX.e(TAG, "runSimulation failed", e);
        }
    }

    /** 统一推送当前状态（标题 + 工具行 + 进度） */
    private static void pushState(String reason) {
        String title = AgentProgress.title();
        String body = AgentProgress.body();
        int pct = AgentProgress.percent();
        sLastNotifyAt = System.currentTimeMillis();
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
                    if (sb.length() > 200) break;  // 60->200: git 长前缀(cd + -c user.name...)会把 push/pull/commit 挤出截断点
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