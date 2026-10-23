package com.operit.rearprogress;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentProgress —— 背屏进度卡的状态机与内容组装。
 *
 * 三段状态（标题固定三种）：
 *   READY      刚进 Operit，还没工具调用        →「已就绪」
 *   RUNNING    有工具调用了                     →「任务进行中」
 *   DONE       收到 Operit 的结束消息            →「已完成」
 *
 * 展示优先级：
 *   1. 若 AI 主动上传了进度文字 → 标题用 AI 的文字
 *   2. 否则用状态标题
 *   工具行始终保留在下方，最新在上，最多 3 条
 */
final class AgentProgress {

    enum State { READY, RUNNING, DONE }

    private static final int MAX_LINES = 3;

    private static volatile State sState = State.READY;

    /** AI 主动上传的标题，空串表示没上传 */
    private static volatile String sAiTitle = "";
    /** AI 主动上传的工具名，空串表示没上传 */
    private static volatile String sAiTool = "";
    /** AI 主动上传的内容，空串表示没上传 */
    private static volatile String sAiBody = "";
    /** AI 上传的百分比，<0 表示未知 */
    private static volatile int sAiPercent = -1;
    /** AI 上传的时间戳，用于过期判断 */
    private static volatile long sAiAt = 0L;
    /** 最后一次工具调用时间戳，用于任务结束判断 */
    private static volatile long sLastToolAt = 0L;
    /** 任务结束后通知自动消失的延迟（10分钟） */
    private static final long AUTO_HIDE_DELAY = 10 * 60 * 1000L;

    /** 最近几条工具行（最新在末尾） */
    private static final List<String[]> sRecent = new ArrayList<>();
    /** 本任务工具调用计数 */
    private static volatile int sToolCount = 0;
    /** 最近一次文件操作的 diff 统计 */
    private static volatile String sLastDiffStats = "";
    /** 最近一次 AI 响应的 token 数据 */
    private static volatile String sLastTokenUsage = "";

    /** 终端命令历史（最近 N 条） */
    private static final List<String> sCommandHistory = new ArrayList<>();
    /** 终端输出历史 */
    private static final List<String> sOutputHistory = new ArrayList<>();
    /** 最大历史记录数 */
    private static final int MAX_HISTORY = 10;

    /** 工具名映射表：原始名 -> 显示名（缩短） */
    private static final java.util.Map<String, String> TOOL_NAME_MAP = new java.util.HashMap<>();
    static {
        // 内部工具（Operit 内置）
        TOOL_NAME_MAP.put("execute_hidden_terminal_command", "HiddenCmd");
        TOOL_NAME_MAP.put("execute_shell", "Shell");
        TOOL_NAME_MAP.put("file_exists", "Exist");
        TOOL_NAME_MAP.put("find_chat", "FindChat");
        TOOL_NAME_MAP.put("get_chat_messages", "Chat");
        TOOL_NAME_MAP.put("http_request", "HTTP");
        TOOL_NAME_MAP.put("list_character_cards", "Cards");
        TOOL_NAME_MAP.put("read_file_full", "ReadFull");
        TOOL_NAME_MAP.put("super_admin", "Root");

        // super_admin 包
        TOOL_NAME_MAP.put("super_admin:shell", "Shell(root)");
        TOOL_NAME_MAP.put("super_admin:terminal", "Term(root)");
        TOOL_NAME_MAP.put("super_admin:terminal_wait", "TermWait(root)");
        TOOL_NAME_MAP.put("super_admin:terminal_getscreen", "TermScr(root)");
        TOOL_NAME_MAP.put("super_admin:terminal_input", "TermIn(root)");

        // 文件操作
        TOOL_NAME_MAP.put("read_file", "Read");
        TOOL_NAME_MAP.put("read_file_part", "ReadPart");
        TOOL_NAME_MAP.put("create_file", "Create");
        TOOL_NAME_MAP.put("edit_file", "Edit");
        TOOL_NAME_MAP.put("delete_file", "Delete");
        TOOL_NAME_MAP.put("list_files", "List");
        TOOL_NAME_MAP.put("find_files", "Find");
        TOOL_NAME_MAP.put("make_directory", "Mkdir");
        TOOL_NAME_MAP.put("grep_code", "Grep");
        TOOL_NAME_MAP.put("grep_context", "GrepCtx");

        // 网络/搜索
        TOOL_NAME_MAP.put("visit_web", "Web");
        TOOL_NAME_MAP.put("download_file", "Download");
        TOOL_NAME_MAP.put("package_proxy", "Proxy");

        // 记忆
        TOOL_NAME_MAP.put("query_memory", "Memory");
        TOOL_NAME_MAP.put("get_memory_by_title", "MemTitle");

        // 代码执行
        TOOL_NAME_MAP.put("code_runner:run_javascript_node", "JS");
        TOOL_NAME_MAP.put("code_runner:run_python", "Python");
        TOOL_NAME_MAP.put("code_runner:run_ruby", "Ruby");
        TOOL_NAME_MAP.put("code_runner:run_go", "Go");
        TOOL_NAME_MAP.put("code_runner:run_rust", "Rust");
        TOOL_NAME_MAP.put("code_runner:run_c", "C");
        TOOL_NAME_MAP.put("code_runner:run_cpp", "C++");

        // APK 逆向
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_decode", "APKDecode");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_jadx", "JADX");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_search_text", "APKSearch");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_build", "APKBuild");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_sign", "APKSign");
    }

    private AgentProgress() {}

    // ---------------------------------------------------------------- 状态

    static void markReady() {
        sState = State.READY;
    }

    /** 每次工具调用：READY → RUNNING（从 DONE 回到 RUNNING 表示新任务开始） */
    static synchronized void markRunning() {
        if (sState == State.DONE) {
            // 新任务：上一轮 AI 写的进度已失效，进度计数重置
            sAiTitle = "";
            sAiPercent = -1;
            sToolCount = 0;
            sLastDiffStats = "";  // 清空 diff 统计
            sLastTokenUsage = "";  // 清空 token 数据
            // 工具行保留，让背屏滚动显示最近动作，不突兀清空
        }
        sState = State.RUNNING;
    }

    /** 回复完成 → 已完成 */
    static synchronized void markDone() {
        if (sState == State.DONE) return;   // 幂等
        sState = State.DONE;
        sAiTitle = "";
        sAiPercent = -1;
        sLastToolAt = System.currentTimeMillis();  // 任务结束时间
    }

    /** 新任务开始：AI 主动上传失效时的重置 */
    static synchronized void startNewTask() {
        sAiTitle = "";
        sAiPercent = -1;
        sToolCount = 0;
        sState = State.READY;
    }

    static State state() {
        return sState;
    }

    /** 设置最近一次文件操作的 diff 统计 */
    static synchronized void setLastDiffStats(String stats) {
        sLastDiffStats = (stats == null) ? "" : stats.trim();
    }

    /** 获取最近一次文件操作的 diff 统计 */
    static String getLastDiffStats() {
        return sLastDiffStats;
    }

    /** 清空 diff 统计（新任务开始时） */
    static synchronized void clearDiffStats() {
        sLastDiffStats = "";
    }

    /** 设置最近一次 AI 响应的 token 数据 */
    static synchronized void setLastTokenUsage(String usage) {
        sLastTokenUsage = (usage == null) ? "" : usage.trim();
    }

    /** 获取最近一次 AI 响应的 token 数据 */
    static String getLastTokenUsage() {
        return sLastTokenUsage;
    }

    /** 清空 token 数据（新任务开始时） */
    static synchronized void clearTokenUsage() {
        sLastTokenUsage = "";
    }

    /**
     * 判断是否应该自动隐藏通知（超过 N 秒无工具调用）
     * 条件：状态为 DONE 或 RUNNING 且距离最后工具调用超过延迟时间
     */
    static boolean shouldAutoHide() {
        if (sLastToolAt == 0L) return false;
        return System.currentTimeMillis() - sLastToolAt > AUTO_HIDE_DELAY;
    }

    /** 获取最后工具调用时间戳 */
    static long getLastToolAt() {
        return sLastToolAt;
    }

    // ---------------------------------------------------------------- AI 上传

    static synchronized void setAiProgress(String title, int percent) {
        sAiTitle = title == null ? "" : title.trim();
        sAiTool = "";
        sAiBody = "";
        sAiPercent = percent;
        sAiAt = System.currentTimeMillis();
    }

    /** 解析 JSON 设置 AI 状态（title/tool/body/percent） */
    static synchronized void setAiState(String json) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            sAiTitle = obj.optString("title", "").trim();
            sAiTool = obj.optString("tool", "").trim();
            sAiBody = obj.optString("body", "").trim();
            sAiPercent = obj.has("percent") ? obj.optInt("percent", -1) : -1;
            sAiAt = System.currentTimeMillis();
        } catch (Throwable t) {
            // JSON 解析失败，当作纯文字处理
            setAiProgress(json, -1);
        }
    }

    static synchronized void clearAiProgress() {
        sAiTitle = "";
        sAiTool = "";
        sAiBody = "";
        sAiPercent = -1;
        sAiAt = 0L;
    }

    /** AI 上传只在 5 分钟内有效，避免残留把标题卡死 */
    static boolean hasAiTitle() {
        String t = sAiTitle;
        if (t == null || t.isEmpty()) return false;
        if (sAiAt == 0L) return false;
        return System.currentTimeMillis() - sAiAt < 5 * 60_000L;
    }

    /** AI 上传的工具名是否有效 */
    static boolean hasAiTool() {
        String t = sAiTool;
        if (t == null || t.isEmpty()) return false;
        if (sAiAt == 0L) return false;
        return System.currentTimeMillis() - sAiAt < 5 * 60_000L;
    }

    /** AI 上传的内容是否有效 */
    static boolean hasAiBody() {
        String t = sAiBody;
        if (t == null || t.isEmpty()) return false;
        if (sAiAt == 0L) return false;
        return System.currentTimeMillis() - sAiAt < 5 * 60_000L;
    }

    // ---------------------------------------------------------------- 工具行

    /** 内部执行命令，不显示在灵动岛（由外层工具触发） */
    private static final java.util.Set<String> HIDDEN_TOOLS = new java.util.HashSet<>();
    static {
        HIDDEN_TOOLS.add("execute_hidden_terminal_command");
        HIDDEN_TOOLS.add("execute_shell");
        HIDDEN_TOOLS.add("http_request");  // 内部网络请求，不显示
    }

    /**
     * 记录一次工具调用。
     * 存 [工具名, 参数摘要, 是否成功] 三段，UI 侧可以分别加粗/缩小。
     */
    static synchronized void recordTool(String toolName, String detail, boolean success) {
        markRunning();
        sToolCount++;
        sLastToolAt = System.currentTimeMillis();  // 更新最后工具调用时间

        String name = (toolName == null || toolName.isEmpty()) ? "tool" : toolName;
        // 内部执行命令不记录（由外层工具触发，如 code_runner → execute_hidden_terminal_command）
        if (HIDDEN_TOOLS.contains(name)) {
            return;
        }
        // 映射到短名
        name = TOOL_NAME_MAP.getOrDefault(name, name);
        String d = detail == null ? "" : detail.trim().replaceAll("\\s+", " ");
        if (d.length() > 64) d = d.substring(0, 64) + "…";

        sRecent.add(new String[]{name, d, success ? "1" : "0"});
        while (sRecent.size() > MAX_LINES) sRecent.remove(0);
    }

    // ---------------------------------------------------------------- 输出

    /**
     * 标题：主标题行（只放工具名 / AI 步骤名）。
     *   AI 上传进度 → 显示 AI 文字（置顶优先）
     *   有工具调用 → 显示工具名 + diff 统计
     *   否则 → 状态文字（已就绪 / 任务进行中 / 已完成）
     */
    static String title() {
        // 1. AI 上传进度优先级最高
        if (hasAiTitle()) return sAiTitle;

        // 2. 有工具调用时，标题用工具名
        synchronized (AgentProgress.class) {
            if (!sRecent.isEmpty()) {
                String[] last = sRecent.get(sRecent.size() - 1);
                String toolName = last[0];

                // 文件操作追加 diff 统计
                if (sLastDiffStats != null && !sLastDiffStats.isEmpty()
                        && (toolName.equals("Edit") || toolName.equals("Create") || toolName.equals("Delete"))) {
                    return toolName + " " + sLastDiffStats;
                }
                return toolName;
            }
        }

        // 3. 否则用状态文字
        switch (sState) {
            case RUNNING:
                return "任务进行中";
            case DONE:
                return "已完成";
            case READY:
            default:
                return "已就绪";
        }
    }

    /**
     * 副标题：当前工具名（模板里放大加粗，作为视觉主体）。
     *   AI 上传工具名 → 优先显示
     *   否则 → 最近工具名
     *   没有工具调用时留空，模板会隐藏这一行。
     */
    static String toolName() {
        // 1. AI 上传的工具名优先
        if (hasAiTool()) return sAiTool;
        
        // 2. 最近工具名
        synchronized (AgentProgress.class) {
            if (sRecent.isEmpty()) return "";
            String[] last = sRecent.get(sRecent.size() - 1);
            return "1".equals(last[2]) ? last[0] : last[0] + " ✗";
        }
    }

    /**
     * 内容：只显示 token 用量。
     *   AI 上传内容 → 优先显示
     *   否则 → token 数量
     */
    static String body() {
        // 1. AI 上传的内容优先
        if (hasAiBody()) return sAiBody;

        // 2. token 数量
        if (sLastTokenUsage != null && !sLastTokenUsage.isEmpty()) {
            return sLastTokenUsage;
        }

        // 3. 兜底状态
        synchronized (AgentProgress.class) {
            if (!sRecent.isEmpty()) {
                String[] r = sRecent.get(sRecent.size() - 1);
                return (r[1] == null || r[1].isEmpty()) ? "—" : r[1];
            }
        }
        switch (sState) {
            case RUNNING:
                return "正在准备…";
            case DONE:
                return "任务已结束";
            case READY:
            default:
                return "等待任务开始";
        }
    }

    /** 进度：AI 给的优先；否则按状态给一个合理值 */
    static int percent() {
        if (sAiPercent >= 0) return Math.min(100, sAiPercent);
        switch (sState) {
            case DONE:
                return 100;
            case READY:
                return 0;
            case RUNNING:
            default:
                // 渐进式，不封顶到 100，避免看起来已完成
                return Math.min(92, 10 + sToolCount * 9);
        }
    }

    static int toolCount() {
        return sToolCount;
    }

    // ---------------------------------------------------------------- 终端历史

    /** 记录终端命令 */
    static synchronized void recordCommand(String command) {
        if (command == null || command.isEmpty()) return;
        sCommandHistory.add(command);
        while (sCommandHistory.size() > MAX_HISTORY) {
            sCommandHistory.remove(0);
        }
    }

    /** 记录终端输出 */
    static synchronized void recordOutput(String output) {
        if (output == null || output.isEmpty()) return;
        // 截断过长的输出
        if (output.length() > 200) {
            output = output.substring(0, 200) + "...";
        }
        sOutputHistory.add(output);
        while (sOutputHistory.size() > MAX_HISTORY) {
            sOutputHistory.remove(0);
        }
    }

    /** 获取终端历史（用于通知显示） */
    static synchronized String getTerminalHistory() {
        if (sCommandHistory.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, sCommandHistory.size() - 3); // 只显示最近3条
        for (int i = start; i < sCommandHistory.size(); i++) {
            sb.append("$ ").append(sCommandHistory.get(i)).append("\n");
            // 如果有对应的输出
            if (i < sOutputHistory.size()) {
                String output = sOutputHistory.get(i);
                if (output != null && !output.isEmpty()) {
                    // 取第一行
                    String firstLine = output.split("\n")[0];
                    if (firstLine.length() > 40) firstLine = firstLine.substring(0, 40) + "...";
                    sb.append("  ").append(firstLine).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }
}