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
    /** AI 上传的百分比，<0 表示未知 */
    private static volatile int sAiPercent = -1;
    /** AI 上传的时间戳，用于过期判断 */
    private static volatile long sAiAt = 0L;

    /** 最近几条工具行（最新在末尾） */
    private static final List<String[]> sRecent = new ArrayList<>();
    /** 本任务工具调用计数 */
    private static volatile int sToolCount = 0;

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

    // ---------------------------------------------------------------- AI 上传

    static synchronized void setAiProgress(String title, int percent) {
        sAiTitle = title == null ? "" : title.trim();
        sAiPercent = percent;
        sAiAt = System.currentTimeMillis();
    }

    static synchronized void clearAiProgress() {
        sAiTitle = "";
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

    // ---------------------------------------------------------------- 工具行

    /**
     * 记录一次工具调用。
     * 存 [工具名, 参数摘要, 是否成功] 三段，UI 侧可以分别加粗/缩小。
     */
    static synchronized void recordTool(String toolName, String detail, boolean success) {
        markRunning();
        sToolCount++;

        String name = (toolName == null || toolName.isEmpty()) ? "tool" : toolName;
        String d = detail == null ? "" : detail.trim().replaceAll("\\s+", " ");
        if (d.length() > 64) d = d.substring(0, 64) + "…";

        sRecent.add(new String[]{name, d, success ? "1" : "0"});
        while (sRecent.size() > MAX_LINES) sRecent.remove(0);
    }

    // ---------------------------------------------------------------- 输出

    /**
     * 标题：主标题行。
     *   有工具调用 → 显示工具名（灵动岛/背屏都用这个）
     *   AI 上传进度 → 显示 AI 文字（置顶优先）
     *   否则 → 状态文字（已就绪 / 任务进行中 / 已完成）
     */
    static String title() {
        // 有工具调用时，标题直接用工具名（灵动岛显示工具名）
        synchronized (AgentProgress.class) {
            if (!sRecent.isEmpty()) {
                String[] last = sRecent.get(sRecent.size() - 1);
                return last[0];  // 只返回工具名，不带成功/失败标记
            }
        }
        if (hasAiTitle()) return sAiTitle;
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
     * 没有工具调用时留空，模板会隐藏这一行。
     */
    static String toolName() {
        synchronized (AgentProgress.class) {
            if (sRecent.isEmpty()) return "";
            String[] last = sRecent.get(sRecent.size() - 1);
            return "1".equals(last[2]) ? last[0] : last[0] + " ✗";
        }
    }

    /**
     * 内容：调用的参数，一行一条（最新在上），小字弱化显示。
     */
    static String body() {
        synchronized (AgentProgress.class) {
            if (sRecent.isEmpty()) {
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
            StringBuilder sb = new StringBuilder();
            for (int i = sRecent.size() - 1; i >= 0; i--) {
                String[] r = sRecent.get(i);
                if (sb.length() > 0) sb.append('\n');
                String d = (r[1] == null || r[1].isEmpty()) ? "—" : r[1];
                sb.append(d);
            }
            return sb.toString();
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
}