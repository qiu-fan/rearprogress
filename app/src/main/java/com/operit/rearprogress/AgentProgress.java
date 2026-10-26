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
        TOOL_NAME_MAP.put("find_chat", "FindChat");
        TOOL_NAME_MAP.put("get_chat_messages", "Chat");
        TOOL_NAME_MAP.put("list_character_cards", "Cards");

        // super_admin 包
        TOOL_NAME_MAP.put("super_admin:shell", "Shell(root)");
        TOOL_NAME_MAP.put("super_admin:terminal", "Term(root)");
        TOOL_NAME_MAP.put("super_admin:terminal_wait", "TermWait(root)");
        TOOL_NAME_MAP.put("super_admin:terminal_getscreen", "TermScr(root)");
        TOOL_NAME_MAP.put("super_admin:terminal_input", "TermIn(root)");
        TOOL_NAME_MAP.put("execute_in_terminal_session", "TermExec");

        // 文件操作（驼峰命名，省宽度易读）
        TOOL_NAME_MAP.put("read_file", "ReadFile");
        TOOL_NAME_MAP.put("read_file_part", "ReadPart");
        TOOL_NAME_MAP.put("read_file_full", "ReadFull");
        TOOL_NAME_MAP.put("read_file_binary", "ReadBinary");
        TOOL_NAME_MAP.put("create_file", "CreateFile");
        TOOL_NAME_MAP.put("edit_file", "EditFile");
        TOOL_NAME_MAP.put("delete_file", "DeleteFile");
        TOOL_NAME_MAP.put("write_file", "WriteFile");
        TOOL_NAME_MAP.put("write_file_binary", "WriteFile");
        TOOL_NAME_MAP.put("list_files", "ListFile");
        TOOL_NAME_MAP.put("find_files", "FindFiles");
        TOOL_NAME_MAP.put("make_directory", "MakeDir");
        TOOL_NAME_MAP.put("file_exists", "FileExist");
        TOOL_NAME_MAP.put("grep_code", "GrepCode");
        TOOL_NAME_MAP.put("grep_context", "GrepContext");
        TOOL_NAME_MAP.put("move_file", "MoveFile");
        TOOL_NAME_MAP.put("copy_file", "CopyFile");
        TOOL_NAME_MAP.put("rename_file", "RenameFile");
        TOOL_NAME_MAP.put("file_info", "FileInfo");
        TOOL_NAME_MAP.put("apply_file", "ApplyFile");

        // 网络/搜索
        TOOL_NAME_MAP.put("visit_web", "Web");
        TOOL_NAME_MAP.put("download_file", "Download");
        TOOL_NAME_MAP.put("upload_file", "Upload");
        TOOL_NAME_MAP.put("http_request", "HTTP");
        TOOL_NAME_MAP.put("package_proxy", "Proxy");
        TOOL_NAME_MAP.put("various_search", "Search");

        // 记忆（驼峰）
        TOOL_NAME_MAP.put("query_memory", "QueryMemory");
        TOOL_NAME_MAP.put("get_memory_by_title", "MemoryTitle");
        TOOL_NAME_MAP.put("create_memory", "CreateMemory");
        TOOL_NAME_MAP.put("update_memory", "UpdateMemory");
        TOOL_NAME_MAP.put("delete_memory", "DeleteMemory");

        // 代码执行（run + 语言）
        TOOL_NAME_MAP.put("code_runner:run_javascript_node", "runJS");
        TOOL_NAME_MAP.put("code_runner:run_python", "runPython");
        TOOL_NAME_MAP.put("code_runner:run_ruby", "runRuby");
        TOOL_NAME_MAP.put("code_runner:run_go", "runGo");
        TOOL_NAME_MAP.put("code_runner:run_rust", "runRust");
        TOOL_NAME_MAP.put("code_runner:run_c", "runC");
        TOOL_NAME_MAP.put("code_runner:run_cpp", "runC++");
        TOOL_NAME_MAP.put("code_runner:run_java", "runJava");
        TOOL_NAME_MAP.put("code_runner:run_typescript", "runTS");
        TOOL_NAME_MAP.put("code_runner:run_shell", "runShell");
        TOOL_NAME_MAP.put("code_runner:run_php", "runPHP");

        // 系统/设备
        TOOL_NAME_MAP.put("get_device_info", "DeviceInfo");
        TOOL_NAME_MAP.put("screen_control", "Screen");
        TOOL_NAME_MAP.put("ui_automation", "UI");
        TOOL_NAME_MAP.put("settings_put", "Setting");
        TOOL_NAME_MAP.put("app_install", "Install");
        TOOL_NAME_MAP.put("app_uninstall", "Uninstall");
        TOOL_NAME_MAP.put("send_intent", "Intent");

        // 通讯/社交
        TOOL_NAME_MAP.put("send_sms", "SMS");
        TOOL_NAME_MAP.put("make_phone_call", "Call");
        TOOL_NAME_MAP.put("send_wechat", "WeChat");
        TOOL_NAME_MAP.put("send_qq", "QQ");
        TOOL_NAME_MAP.put("post_moments", "Moments");

        // 媒体/文件转换
        TOOL_NAME_MAP.put("file_converter", "Convert");
        TOOL_NAME_MAP.put("ffmpeg", "FFmpeg");
        TOOL_NAME_MAP.put("image_convert", "ImgConv");
        TOOL_NAME_MAP.put("video_convert", "VidConv");

        // 文档
        TOOL_NAME_MAP.put("create_docx", "Docx");
        TOOL_NAME_MAP.put("create_pdf", "PDF");
        TOOL_NAME_MAP.put("create_xlsx", "Xlsx");
        TOOL_NAME_MAP.put("create_pptx", "Pptx");

        // APK 逆向
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_inspect", "APKInfo");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_decode", "APKDecode");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_jadx", "JADX");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_search_text", "APKSearch");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_search_address", "APKAddr");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_build", "APKBuild");
        TOOL_NAME_MAP.put("apk_reverse:apk_reverse_sign", "APKSign");

        // 终端/工具包
        TOOL_NAME_MAP.put("super_admin:shell", "Shell(root)");
        TOOL_NAME_MAP.put("super_admin:terminal", "Term(root)");
        TOOL_NAME_MAP.put("super_admin:terminal_wait", "TermWait(root)");
        TOOL_NAME_MAP.put("super_admin:terminal_getscreen", "TermScr(root)");
        TOOL_NAME_MAP.put("super_admin:terminal_input", "TermIn(root)");
        TOOL_NAME_MAP.put("execute_in_terminal_session", "TermExec");
        TOOL_NAME_MAP.put("create_terminal_session", "TermNew");

        // 其他内置
        TOOL_NAME_MAP.put("query_time", "Time");
        TOOL_NAME_MAP.put("time:format_time", "Time");
        TOOL_NAME_MAP.put("time:current_time", "Time");
        TOOL_NAME_MAP.put("get_weather", "Weather");
        TOOL_NAME_MAP.put("set_reminder", "Remind");
        TOOL_NAME_MAP.put("set_alarm", "Alarm");
        TOOL_NAME_MAP.put("open_app", "OpenApp");
        TOOL_NAME_MAP.put("take_screenshot", "Screenshot");
        TOOL_NAME_MAP.put("get_location", "Location");
        TOOL_NAME_MAP.put("read_notification", "ReadNotif");
        TOOL_NAME_MAP.put("github", "GitHub");
        TOOL_NAME_MAP.put("workflow", "Workflow");
        TOOL_NAME_MAP.put("workflow:create_workflow", "Workflow");
        TOOL_NAME_MAP.put("workflow:trigger_workflow", "WorkflowRun");
        TOOL_NAME_MAP.put("workflow:delete_workflow", "WorkflowDel");
        TOOL_NAME_MAP.put("workflow:update_workflow", "WorkflowEdit");
        TOOL_NAME_MAP.put("workflow:enable_workflow", "WorkflowOn");
        TOOL_NAME_MAP.put("workflow:disable_workflow", "WorkflowOff");
        TOOL_NAME_MAP.put("workflow:query_workflow", "WorkflowQry");
        TOOL_NAME_MAP.put("worldbook", "WorldBook");
        TOOL_NAME_MAP.put("worldbook:create_worldbook", "WorldBook");
        TOOL_NAME_MAP.put("worldbook:update_worldbook", "WorldBookEdit");
        TOOL_NAME_MAP.put("worldbook:delete_worldbook", "WorldBookDel");
        TOOL_NAME_MAP.put("extended_file_tools", "FileTools");
        TOOL_NAME_MAP.put("extended_memory_tools", "MemTools");
        TOOL_NAME_MAP.put("extended_chat", "ChatTools");
        TOOL_NAME_MAP.put("browser", "Browser");
        TOOL_NAME_MAP.put("crossref", "CrossRef");
        TOOL_NAME_MAP.put("time", "Time");
        TOOL_NAME_MAP.put("daily_life", "DailyLife");
        TOOL_NAME_MAP.put("file_converter", "Convert");
        TOOL_NAME_MAP.put("ffmpeg", "FFmpeg");
        TOOL_NAME_MAP.put("siliconflow_draw", "Draw");
        TOOL_NAME_MAP.put("system_tools", "SysTools");
        TOOL_NAME_MAP.put("automatic_ui_base", "UIAuto");
        TOOL_NAME_MAP.put("qqbot", "QQBot");
        TOOL_NAME_MAP.put("miui-notes", "MiNotes");
        TOOL_NAME_MAP.put("deepseek-export", "DSExport");
        TOOL_NAME_MAP.put("rikkahub-backup", "RikkaBak");
        TOOL_NAME_MAP.put("apk_reverse", "APKRev");
        TOOL_NAME_MAP.put("code_runner", "CodeRun");
        TOOL_NAME_MAP.put("super_admin", "RootTools");
        TOOL_NAME_MAP.put("various_search", "Search");
        TOOL_NAME_MAP.put("12306_ticket", "Train12306");
        TOOL_NAME_MAP.put("extended_http_tools", "HTTPT");
        TOOL_NAME_MAP.put("operit_editor", "OpEditor");
        TOOL_NAME_MAP.put("operit_editor:restart_mcp_window", "McpRestart");
        TOOL_NAME_MAP.put("operit_editor:restart_mcp", "McpRestart");
        TOOL_NAME_MAP.put("operit_editor:read_config", "OpCfgRead");
        TOOL_NAME_MAP.put("operit_editor:write_config", "OpCfgWrite");
        TOOL_NAME_MAP.put("worldbook_tools", "WBookT");
        TOOL_NAME_MAP.put("github", "GitHub");
        TOOL_NAME_MAP.put("rea", "REA");
        TOOL_NAME_MAP.put("rea:inspect_android_package", "REA");
        TOOL_NAME_MAP.put("rea:inspect_apk", "REA");
        TOOL_NAME_MAP.put("rea:inspect", "REA");
    }

    private AgentProgress() {}

    // ---------------------------------------------------------------- 状态

    static void markReady() {
        sState = State.READY;
        sLastToolAt = System.currentTimeMillis();  // 就绪计时的起点，供 1 分钟静默自动清除使用
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

    /** 距离最后一次工具调用是否已超过指定毫秒（无活动） */
    static boolean isIdleFor(long ms) {
        if (sLastToolAt == 0L) return false;
        return System.currentTimeMillis() - sLastToolAt > ms;
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
    /** Operit 进程启动时刻（elapsedRealtime 毫秒），用于过滤启动扫描 */
    private static final long sProcessStartMs = android.os.SystemClock.elapsedRealtime();
    /** 启动窗口：进程启动后此毫秒内的工具调用视为启动扫描（如安装新模块时的 copy_file），不记录不显示 */
    private static final long STARTUP_WINDOW_MS = 5000L;
    /** 模拟模式开关：测试按钮触发时不走启动窗口过滤 */
    private static volatile boolean sSimulateMode = false;
    static {
        HIDDEN_TOOLS.add("execute_hidden_terminal_command");
        HIDDEN_TOOLS.add("execute_shell");
        HIDDEN_TOOLS.add("http_request");  // 内部网络请求，不显示
        HIDDEN_TOOLS.add("make_directory");  // 启动扫描，不显示
        HIDDEN_TOOLS.add("file_exists");     // 启动扫描，不显示
        // 启动扫描 / 发消息时的上下文读取，不显示
        HIDDEN_TOOLS.add("read_file_full");
        HIDDEN_TOOLS.add("find_chat");
        HIDDEN_TOOLS.add("get_chat_messages");
        HIDDEN_TOOLS.add("list_character_cards");
        // write_file 移出 HIDDEN（文件写入有效，需显示）
        HIDDEN_TOOLS.add("create_terminal_session");
    }

    /**
     * 记录一次工具调用。
     * 存 [工具名, 参数摘要, 是否成功] 三段，UI 侧可以分别加粗/缩小。
     */
    static synchronized void recordTool(String toolName, String detail, boolean success) {
        String name = (toolName == null || toolName.isEmpty()) ? "tool" : toolName;
        // 内部/启动扫描工具：完全不参与（不改状态、不计数、不显示）
        if (HIDDEN_TOOLS.contains(name)) {
            return;
        }
        // 启动窗口内的工具调用（如安装新模块时 Operit 自己跑的 copy_file/read_file）：不记录不显示
        // 但模拟模式（测试按钮）例外，否则进程刚启动时点击会被全部吞掉
        if (!sSimulateMode
                && android.os.SystemClock.elapsedRealtime() - sProcessStartMs < STARTUP_WINDOW_MS) {
            return;
        }

        markRunning();
        sToolCount++;
        sLastToolAt = System.currentTimeMillis();

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
        if (hasAiTitle()) {
            LogX.i("RearProgress", "title() -> AI title: [" + sAiTitle + "]");
            return sAiTitle;
        }

        // 2. 有工具调用时，标题用工具名
        synchronized (AgentProgress.class) {
            if (!sRecent.isEmpty()) {
                String[] last = sRecent.get(sRecent.size() - 1);
                String toolName = last[0];

                // 文件操作追加 diff 统计（驼峰映射后的名字）
                if (sLastDiffStats != null && !sLastDiffStats.isEmpty()
                        && (toolName.startsWith("EditFile") || toolName.startsWith("CreateFile")
                            || toolName.startsWith("WriteFile") || toolName.startsWith("DeleteFile")
                            || toolName.startsWith("ApplyFile"))) {
                    LogX.i("RearProgress", "title() -> tool+diff: [" + toolName + " " + sLastDiffStats + "]");
                    return toolName + " " + sLastDiffStats;
                }
                LogX.i("RearProgress", "title() -> tool only: [" + toolName + "]");
                return toolName;
            }
        }

        // 3. 否则用状态文字（标题固定 Agent，内容在 body 里给状态）
        switch (sState) {
            case RUNNING:
                return "任务进行中";
            case DONE:
                return "已完成";
            case READY:
            default:
                return "";  // 通知标题留空：背屏只用蓝色 "Agent" 标签，避免白字重复。
                            // 正面岛的 "Agent" 文字由 miui.focus.param 的 islandTitle 提供。
        }
    }

    /**
     * 副标题（subText）：token 用量。
     *   AI 上传工具名 → 优先显示
     *   否则 → token 数量
     */
    static String toolName() {
        // 1. AI 上传的工具名优先
        if (hasAiTool()) return sAiTool;

        // 2. token 数量（副标题显示 token）
        if (sLastTokenUsage != null && !sLastTokenUsage.isEmpty()) {
            return sLastTokenUsage;
        }

        // 3. token 还没拿到时：
        //    无工具（ready）→ 空串（不显示，避免岛上「已就绪/ready」来回切换）；有工具 → ↑0 ↓0（token 显示 0）
        synchronized (AgentProgress.class) {
            if (sRecent.isEmpty()) return "";
            return "↑0 ↓0";  // 有工具但无 token → 显示 ↑0 ↓0
        }
    }

    /**
     * 内容（body）：工具参数摘要（最新在上，最多 3 行）。
     *   AI 上传内容 → 优先显示
     *   否则 → 工具参数摘要
     */
    static String body() {
        // 1. AI 上传的内容优先
        if (hasAiBody()) return sAiBody;

        // 2. 工具参数摘要
        synchronized (AgentProgress.class) {
            if (!sRecent.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                for (int i = sRecent.size() - 1; i >= 0; i--) {
                    String[] r = sRecent.get(i);
                    String d = (r[1] == null) ? "" : r[1].trim();
                    if (d.isEmpty()) continue;  // 跳过空参数
                    seen.add(d);  // 去重：同一参数被 Hook 多次记录只显示一次
                }
                for (String d : seen) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(d);
                }
                return sb.toString();
            }
        }

        // 3. 兜底状态
        switch (sState) {
            case RUNNING:
                return "正在准备…";
            case DONE:
                return "任务已结束";
            case READY:
            default:
                return "已就绪";  // 与初始状态一致
        }
    }

    /** 进度：仅 AI 上传了有效 percent 时显示，否则 -1（不显示百分比/进度圈） */
    static int percent() {
        if (sAiPercent >= 0) return Math.min(100, sAiPercent);
        return -1;
    }

    static int toolCount() {
        return sToolCount;
    }

    /** 启动窗口时长（毫秒），供初始状态推送对齐 */
    static long startupWindowMs() {
        return STARTUP_WINDOW_MS;
    }

    /** 最近工具名流动（逗号分隔，供展开态显示），如 "Edit,Grep,Shell(root)" */
    static String getToolFlow() {
        synchronized (AgentProgress.class) {
            if (sRecent.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = sRecent.size() - 1; i >= 0; i--) {
                if (sb.length() > 0) sb.append(',');
                sb.append(sRecent.get(i)[0]);
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- 终端历史

    /**
     * 模拟一次工具调用（测试按钮用）：直接走 recordTool + pushState，
     * 复刻 OperitHook 里 executeTool 拦截后做的事。
     */
    static void simulateTool(String name, String detail, boolean success) {
        // 模拟不受启动窗口限制：置开关位，绕过 STARTUP_WINDOW_MS 过滤
        sSimulateMode = true;
        AgentProgress.recordTool(name, detail, success);
        sSimulateMode = false;
        FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(),
                AgentProgress.body(), AgentProgress.percent());
        LogX.i("RearProgress", "simulateTool -> " + name);
    }

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