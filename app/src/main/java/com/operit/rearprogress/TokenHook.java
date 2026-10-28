package com.operit.rearprogress;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * TokenHook —— 在 Operit 进程内实时捕获 token 消耗（方案 B）。
 *
 * 数据来源（反编译 classes12.dex 确认）：
 *   com.ai.assistance.operit.api.chat.EnhancedAIService
 *     private long accumulatedInputTokenCount;
 *     private long accumulatedOutputTokenCount;
 *     private long accumulatedCachedInputTokenCount;
 *     private long currentRequestInputTokenCount;
 *     private long currentRequestOutputTokenCount;
 *     private long currentRequestCachedInputTokenCount;
 *
 * 权威合成公式（与 Operit 自身 captureCurrentTurnTokenSnapshot 完全一致）：
 *     input  = accumulatedInput  + currentRequestInput
 *     output = accumulatedOutput + currentRequestOutput
 *     cached = accumulatedCached + currentRequestCached
 *
 * 实时触发点：Kotlin onTokensUpdated 回调每次流式 usage 更新时，
 * 会顺序调用三个 synthetic 桥接方法：
 *     access$setCurrentRequestInputTokenCount$p(EnhancedAIService, J)V
 *     access$setCurrentRequestOutputTokenCount$p(EnhancedAIService, J)V
 *     access$setCurrentRequestCachedInputTokenCount$p(EnhancedAIService, J)V
 * Hook 它们即可获得真正的实时 token 增长。
 *
 * 另 Hook captureCurrentTurnTokenSnapshot()（无参，返回 TurnTokenSnapshot），
 * 在每轮对话结束时拿到最终精确值并强制落地一次。
 *
 * 纯观察，不修改任何字段与返回值。
 */
final class TokenHook {

    private static final String TAG = "RearProgress";

    private static final String CLS_ENHANCED = "com.ai.assistance.operit.api.chat.EnhancedAIService";

    // ---- 反射缓存 ----
    private static Field fAccIn, fAccOut, fAccCached;
    private static Field fCurIn, fCurOut, fCurCached;

    // ---- 节流状态 ----
    private static volatile long sLastIn = -1, sLastOut = -1, sLastCached = -1;
    private static volatile long sLastPushAt = 0L;
    private static volatile boolean sHasLive = false;
    /** 图表刷新基准间隔：1Hz（默认/最低刷新率/AOD 与熄屏锁定值） */
    private static final long BASE_INTERVAL_MS = 1000L;
    /** 活跃时最快间隔（快照密集时允许 300ms） */
    private static final long FAST_INTERVAL_MS = 300L;
    /** 活跃判定窗口：窗内快照数 >= BUSY_MIN_SNAPSHOTS 视为活跃 */
    private static final long BUSY_WINDOW_MS = 3_000L;
    private static final int BUSY_MIN_SNAPSHOTS = 3;
    /** PowerManager 缓存（熄屏/AOD 检测） */
    private static volatile android.os.PowerManager sPm;
    /** token 停止增长多久判定为「输出完成」并计算费用 */
    private static final long FINALIZE_DELAY_MS = 10_000L;
    /** 是否已落地为最终值 */
    private static volatile boolean sFinalized = false;
    /** 延迟落地的调度器 */
    private static volatile java.util.concurrent.ScheduledFuture<?> sFinalizeTask;
    private static java.util.concurrent.ScheduledExecutorService sScheduler;

    // ---- token 历史（环形缓冲，供展开态图表） ----
    private static final int HISTORY_SIZE = 40;
    private static final long[] sHistIn = new long[HISTORY_SIZE];
    private static final long[] sHistOut = new long[HISTORY_SIZE];
    private static final long[] sHistAt = new long[HISTORY_SIZE];
    private static volatile int sHistCount = 0;
    private static volatile int sHistHead = 0;
    /** 历史首/末时间戳（算图表时间段） */
    private static volatile long sHistFirstAt = 0L;
    private static volatile long sHistLastAt = 0L;
    /** 上一轮结束时的累计总 token（供 hit 行显示"上一轮总"） */
    private static volatile long sPrevTotalIn = 0L;
    private static volatile long sPrevTotalOut = 0L;

    private TokenHook() {}

    /**
     * 判断 token 是否还在增长（最近 3 秒内有更新）。
     * 用于推断 AI 正在回复中（思考/流式输出），供背屏显示 "Thinking"。
     */
    static boolean isTokenGrowing() {
        return sHasLive && (System.currentTimeMillis() - sLastPushAt) < 3000L;
    }

    /** PowerManager 懒加载缓存 */
    private static android.os.PowerManager pm() {
        android.os.PowerManager pm = sPm;
        if (pm != null) return pm;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                pm = (android.os.PowerManager) ((android.content.Context) app)
                        .getSystemService(android.content.Context.POWER_SERVICE);
                sPm = pm;
            }
        } catch (Throwable ignored) {}
        return pm;
    }

    /**
     * 自适应节流间隔（毫秒）。
     * - 熄屏/AOD（主屏 not interactive）：锁定 1Hz，避免息屏时高频刷新
     * - 活跃（3 秒窗口内快照 >= 3 个）：最快 300ms，跟得上流式生成
     * - 其余情况：1Hz 基准
     * 任何分支都 <= BASE_INTERVAL_MS，保证最低刷新率不会掉到 1Hz 以下。
     */
    private static long currentThrottleMs() {
        try {
            android.os.PowerManager p = pm();
            if (p != null && !p.isInteractive()) return BASE_INTERVAL_MS;  // AOD/熄屏锁 1Hz
        } catch (Throwable ignored) {}
        return countRecentSnapshots() >= BUSY_MIN_SNAPSHOTS ? FAST_INTERVAL_MS : BASE_INTERVAL_MS;
    }

    /** BUSY_WINDOW_MS 窗口内新快照数（从新往旧数，遇旧即停） */
    private static int countRecentSnapshots() {
        int n = sHistCount;
        if (n == 0) return 0;
        long cutoff = System.currentTimeMillis() - BUSY_WINDOW_MS;
        int start = (sHistHead - n + HISTORY_SIZE) % HISTORY_SIZE;
        int c = 0;
        for (int i = n - 1; i >= 0; i--) {
            int idx = (start + i) % HISTORY_SIZE;
            if (sHistAt[idx] >= cutoff) c++; else break;
        }
        return c;

    }

    /** 记录一次 token 快照（环形缓冲） */
    private static void recordHistory(long in, long out) {
        long now = System.currentTimeMillis();
        if (sHistCount == 0) sHistFirstAt = now;
        sHistLastAt = now;
        sHistIn[sHistHead] = in;
        sHistOut[sHistHead] = out;
        sHistAt[sHistHead] = now;
        sHistHead = (sHistHead + 1) % HISTORY_SIZE;
        if (sHistCount < HISTORY_SIZE) sHistCount++;
    }

    /** 取 token 历史（时间顺序，旧→新），供图表用。返回 JSON 数组 [[in,out],...] */
    static String getHistoryJson() {
        int n = sHistCount;
        if (n == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int start = (sHistHead - n + HISTORY_SIZE) % HISTORY_SIZE;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % HISTORY_SIZE;
            if (i > 0) sb.append(',');
            sb.append('[').append(sHistIn[idx]).append(',').append(sHistOut[idx]).append(']');
        }
        sb.append(']');
        return sb.toString();
    }

    /** 命中率 = cached / in（0-100，保留两位小数），input 为 0 时返回 0 */
    static double getHitRate() {
        if (sLastIn <= 0) return 0;
        return sLastCached * 100.0 / sLastIn;
    }

    /** 图表跨越的时长，如 "251s"，无数据返回空 */
    static String getTimeRange() {
        if (sHistFirstAt == 0 || sHistLastAt <= sHistFirstAt) return "";
        long spanSec = (sHistLastAt - sHistFirstAt) / 1000;
        if (spanSec >= 60) {
            long m = spanSec / 60;
            long s = spanSec % 60;
            return m + "m" + (s > 0 ? s + "s" : "");
        }
        return spanSec + "s";
    }

    /** 任务时间：开始-结束 (时长)，自适应格式 */
    static String getTaskTime() {
        if (sHistFirstAt == 0) return "";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        String start = f.format(new java.util.Date(sHistFirstAt));
        long spanSec = (sHistLastAt - sHistFirstAt) / 1000;
        String dur;
        if (spanSec >= 60) {
            long m = spanSec / 60;
            long s = spanSec % 60;
            dur = m + "m" + (s > 0 ? s + "s" : "");
        } else {
            dur = spanSec + "s";
        }
        if (sHistLastAt <= sHistFirstAt) return start + " - … (" + dur + ")";
        return start + " - " + f.format(new java.util.Date(sHistLastAt)) + " (" + dur + ")";
    }

    /** 上一轮结束的总 input/output（供 hit 行显示"上一轮总"） */
    static long getPrevTotalIn() { return Math.max(0, sPrevTotalIn); }
    static long getPrevTotalOut() { return Math.max(0, sPrevTotalOut); }

    /** 最近一次 input token */
    static long getLastIn() { return Math.max(0, sLastIn); }
    /** 最近一次 output token */
    static long getLastOut() { return Math.max(0, sLastOut); }

    /**
     * 过去 N 秒的 token 速率（tokens/s，一位小数）。
     * 从环形缓冲里找最接近 now-N 秒的快照，算 in+out 增量 / 实际秒数。
     * 数据不足时返回 "—"。
     */
    static String getRate10s() {
        return getRate(10_000L);
    }

    /** 通用速率计算：过去 windowMs 毫秒的 tokens/s */
    static String getRate(long windowMs) {
        int n = sHistCount;
        if (n < 2) return "--.-/s";
        int head = sHistHead;
        // 最新快照
        int lastIdx = (head - 1 + HISTORY_SIZE) % HISTORY_SIZE;
        long lastIn = sHistIn[lastIdx], lastOut = sHistOut[lastIdx];
        long lastAt = sHistAt[lastIdx];
        if (lastAt <= 0) return "--.-/s";
        long target = lastAt - windowMs;
        // 从旧往新找第一个时间戳 >= target 的快照作为基准
        int start = (head - n + HISTORY_SIZE) % HISTORY_SIZE;
        int baseIdx = -1;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % HISTORY_SIZE;
            if (sHistAt[idx] >= target) { baseIdx = idx; break; }
        }
        if (baseIdx < 0) baseIdx = start;  // 全部早于窗口，用最旧的
        long baseIn = sHistIn[baseIdx], baseOut = sHistOut[baseIdx];
        long baseAt = sHistAt[baseIdx];
        long dTok = lastOut - baseOut;  // 只统计下载（out）token
        long dMs = lastAt - baseAt;
        if (dMs <= 0) return "--.-/s";
        double rate = dTok * 1000.0 / dMs;
        return String.format(java.util.Locale.US, "%.1f/s", rate);
    }

    /**
     * 文字化图表（sparkline）：把 token 历史映射到 Unicode 块字符。
     * 每个字符 = 一个快照的 in 增量（每段新增量，不是累计值），
     * 归一化到 8 级：▁▂▃▄▅▆▇█。宽度固定 width 个字符（等距重采样）。
     */
    /** 图表归一化基准（平滑）：新峰值立刻上浮，回落只走3成，避免历史列被压矮 */
    private static volatile double sSmoothMax = 1.0;

    static String getSparkline(int width) {
        int n = sHistCount;
        if (n < 2) {
            // 占位：铺满最低块字符，固定宽度避免文本跳动
            return braillePlaceholder(width);
        }
        // 时间窗口：只取最近 10 秒内的快照
        long cutoff = System.currentTimeMillis() - 10_000L;
        int start = (sHistHead - n + HISTORY_SIZE) % HISTORY_SIZE;
        // 找窗口内第一个快照
        int winStart = start;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % HISTORY_SIZE;
            if (sHistAt[idx] >= cutoff) { winStart = idx; break; }
            winStart = (start + i + 1) % HISTORY_SIZE;
        }
        // 窗口内快照数
        int winCount = 0;
        int wi = winStart;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % HISTORY_SIZE;
            if (sHistAt[idx] >= cutoff) winCount++;
        }
        if (winCount < 2) {
            // 窗口内数据不足，铺满占位
            return braillePlaceholder(width);
        }
        start = winStart;
        n = winCount;
        // 每段增量
        double[] rate = new double[n];
        double maxR = 1;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % HISTORY_SIZE;
            int prevIdx = (start + Math.max(0, i - 1)) % HISTORY_SIZE;
            long d = sHistIn[idx] - sHistIn[prevIdx];
            if (d < 0) d = 0;
            long dt = sHistAt[idx] - sHistAt[prevIdx];
            if (dt <= 0) dt = 1;
            rate[i] = d * 1000.0 / dt;      // 速率 token/秒，与快照间隔无关
            if (rate[i] > maxR) maxR = rate[i];
        }
        // 基准平滑：新峰值立刻上浮；回落只走 3 成，历史列高度不被压矮
        if (maxR > sSmoothMax) sSmoothMax = maxR;
        else sSmoothMax = maxR * 0.7 + sSmoothMax * 0.3;
        double base = Math.max(maxR, sSmoothMax);
        // ---- Braille 点阵：width 个字符 = (width*2) 列 × 4 行 ----
        int cols = width * 2;
        boolean[][] grid = new boolean[cols][4];
        for (int c = 0; c < cols; c++) {
            // 分段重采样：取该列覆盖范围内的最大值，避免单点采样跳过峰值（吞数据）
            int i0 = (int) ((long) c * (n - 1) / Math.max(1, cols - 1));
            int i1 = (int) ((long) (c + 1) * (n - 1) / Math.max(1, cols - 1));
            if (i1 <= i0) i1 = i0 + 1;
            double segMax = 0;
            for (int k = i0; k < i1 && k < n; k++) if (rate[k] > segMax) segMax = rate[k];
            int level = (int) (segMax * 4 / base);   // 0~4 行（用平滑基准）
            if (level > 4) level = 4;
            if (level < 0) level = 0;
            if (level == 0 && maxR > 0) level = 1;   // 有数据但为 0 → 至少一条基线
            for (int r = 0; r < level; r++) grid[c][3 - r] = true;   // 自下向上填充
        }
        return brailleFrom(grid);
    }

    /**
     * Braille 点阵 → 字符串。每个 Braille 字符是 2 列 × 4 行，
     * 8 个字符 = 16×4 = 64 个点，分辨率是 8 格块字符的 8 倍。
     */
    private static String brailleFrom(boolean[][] grid) {
        // 位布局：col0 = {row0:0x01, row1:0x02, row2:0x04, row3:0x40}
        //         col1 = {row0:0x08, row1:0x10, row2:0x20, row3:0x80}
        final int[][] BIT = {
                {0x01, 0x02, 0x04, 0x40},
                {0x08, 0x10, 0x20, 0x80}
        };
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c + 1 < grid.length; c += 2) {
            int bits = 0;
            for (int col = 0; col < 2; col++) {
                for (int row = 0; row < 4; row++) {
                    if (grid[c + col][row]) bits |= BIT[col][row];
                }
            }
            sb.append((char) (0x2800 + bits));
        }
        return sb.toString();
    }

    /** 占位：宽度固定的 Braille 底部基线（一条平直的线） */
    private static String braillePlaceholder(int width) {
        boolean[][] grid = new boolean[width * 2][4];
        for (int c = 0; c < width * 2; c++) grid[c][3] = true;   // 最底行
        return brailleFrom(grid);
    }

    /**
     * 生成 token 趋势的 SVG path（Java 侧算好，MAML 直接 Path 画，零解析）。
     * @param w 图宽 @param h 图高（像素）
     * @return 如 "M0,50 L10,40 L20,30 ..."，无数据返回空串
     */
    static String getHistoryPath(int w, int h) {
        int n = sHistCount;
        if (n < 2) return "";
        // 取 in 的最大值归一化（趋势用输入量）
        long max = 1;
        int start = (sHistHead - n + HISTORY_SIZE) % HISTORY_SIZE;
        for (int i = 0; i < n; i++) {
            long v = sHistIn[(start + i) % HISTORY_SIZE];
            if (v > max) max = v;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            long v = sHistIn[(start + i) % HISTORY_SIZE];
            float x = (w * i) / (float) (n - 1);
            float y = h - (float) v / max * h;  // 归一化，y 向下
            if (i == 0) sb.append("M").append(round(x)).append(',').append(round(y));
            else sb.append(" L").append(round(x)).append(',').append(round(y));
        }
        return sb.toString();
    }

    private static String round(float v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    /** 是否已经拿到过实时数据（用于让数据库兜底让位） */
    static boolean hasLiveData() {
        return sHasLive;
    }

    // ------------------------------------------------------------ 安装

    static void install(final XposedInterface xposed, ClassLoader cl) {
        Class<?> cls;
        try {
            cls = Class.forName(CLS_ENHANCED, true, cl);
        } catch (Throwable t) {
            LogX.w(TAG, "TokenHook: EnhancedAIService not found: " + t);
            return;
        }

        // 1) 先把六个字段反射句柄准备好（失败则整体退化为只 Hook snapshot）
        boolean fieldsOk = resolveFields(cls);
        LogX.i(TAG, "TokenHook fields resolved = " + fieldsOk);

        // 2) 三个实时 setter
        int ok = 0;
        if (hookSetter(xposed, cls, "access$setCurrentRequestOutputTokenCount$p")) ok++;
        if (hookSetter(xposed, cls, "access$setCurrentRequestInputTokenCount$p")) ok++;
        if (hookSetter(xposed, cls, "access$setCurrentRequestCachedInputTokenCount$p")) ok++;

        // setter 名字若因版本变化找不到，退化为按前缀扫描
        if (ok == 0) {
            ok = hookSettersByScan(xposed, cls);
        }

        // 3) 回合结束的精确快照
        boolean snapOk = hookSnapshot(xposed, cls);

        LogX.i(TAG, "TokenHook installed: setters=" + ok + " snapshot=" + snapOk
                + " fields=" + fieldsOk);
    }

    /** 反射准备好六个计数字段 */
    private static boolean resolveFields(Class<?> cls) {
        try {
            fAccIn = findField(cls, "accumulatedInputTokenCount");
            fAccOut = findField(cls, "accumulatedOutputTokenCount");
            fAccCached = findField(cls, "accumulatedCachedInputTokenCount");
            fCurIn = findField(cls, "currentRequestInputTokenCount");
            fCurOut = findField(cls, "currentRequestOutputTokenCount");
            fCurCached = findField(cls, "currentRequestCachedInputTokenCount");
            return fAccIn != null && fAccOut != null && fAccCached != null
                    && fCurIn != null && fCurOut != null && fCurCached != null;
        } catch (Throwable t) {
            LogX.w(TAG, "resolveFields failed: " + t);
            return false;
        }
    }

    /** 在类及其父类里找字段（Operit 里这些字段都在本类） */
    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Hook 单个 static synthetic setter，签名 (EnhancedAIService, long) -> void。
     * 返回是否成功。
     */
    private static boolean hookSetter(final XposedInterface xposed,
                                      final Class<?> cls,
                                      String methodName) {
        try {
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2) continue;
                if (!ps[0].isAssignableFrom(cls) && !ps[0].equals(cls)) continue;
                if (ps[1] != long.class && ps[1] != Long.class) continue;
                target = m;
                break;
            }
            if (target == null) {
                LogX.w(TAG, "TokenHook setter not found: " + methodName);
                return false;
            }
            target.setAccessible(true);
            xposed.hook(target).intercept(chain -> {
                Object ret = chain.proceed();
                try {
                    Object self = chain.getArg(0);
                    if (self != null) refresh(self, true);
                } catch (Throwable t) {
                    LogX.w(TAG, "refresh in " + methodName + " failed: " + t);
                }
                return ret;
            });
            LogX.i(TAG, "TokenHook HOOKED " + methodName);
            return true;
        } catch (Throwable t) {
            LogX.w(TAG, "TokenHook hookSetter " + methodName + " failed: " + t);
            return false;
        }
    }

    /** 兜底：扫描类里所有 setCurrentRequest*TokenCount 方法并 Hook */
    private static int hookSettersByScan(final XposedInterface xposed, Class<?> cls) {
        int n = 0;
        try {
            for (Method m : cls.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.contains("setCurrentRequest")) continue;
                if (!name.contains("TokenCount")) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2) continue;
                if (ps[1] != long.class && ps[1] != Long.class) continue;
                m.setAccessible(true);
                xposed.hook(m).intercept(chain -> {
                    Object ret = chain.proceed();
                    try {
                        Object self = chain.getArg(0);
                        if (self != null) refresh(self, true);
                    } catch (Throwable ignored) {}
                    return ret;
                });
                n++;
                LogX.i(TAG, "TokenHook HOOKED by scan: " + name);
            }
        } catch (Throwable t) {
            LogX.w(TAG, "hookSettersByScan failed: " + t);
        }
        return n;
    }

    /**
     * Hook captureCurrentTurnTokenSnapshot() -> TurnTokenSnapshot
     * 这是每轮对话结束时的权威快照，强制推送一次最终值。
     */
    private static boolean hookSnapshot(final XposedInterface xposed, Class<?> cls) {
        try {
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"captureCurrentTurnTokenSnapshot".equals(m.getName())) continue;
                if (m.getParameterCount() != 0) continue;
                target = m;
                break;
            }
            if (target == null) {
                LogX.w(TAG, "TokenHook captureCurrentTurnTokenSnapshot not found");
                return false;
            }
            target.setAccessible(true);
            xposed.hook(target).intercept(chain -> {
                Object ret = chain.proceed();
                try {
                    // 直接用返回的 snapshot，不依赖字段名
                    long in = longGetter(ret, "getInputTokens", -1L);
                    long out = longGetter(ret, "getOutputTokens", -1L);
                    long cached = longGetter(ret, "getCachedInputTokens", -1L);
                    if (in >= 0 && out >= 0) {
                        publish(in, out, Math.max(0, cached), false, true);
                    }
                } catch (Throwable t) {
                    LogX.w(TAG, "snapshot refresh failed: " + t);
                }
                return ret;
            });
            LogX.i(TAG, "TokenHook HOOKED captureCurrentTurnTokenSnapshot");
            return true;
        } catch (Throwable t) {
            LogX.w(TAG, "hookSnapshot failed: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------ 读取 / 上报

    /**
     * 从 service 实例读出六个字段，按 Operit 权威公式合成后上报。
     *
     * @param streaming true 表示流式进行中（数值还会涨）
     */
    private static void refresh(Object service, boolean streaming) {
        if (fAccIn == null || fCurIn == null) {
            // 字段不可用，退化为只读 snapshot 路线
            return;
        }
        long in = safeGet(fAccIn, service, 0) + safeGet(fCurIn, service, 0);
        long out = safeGet(fAccOut, service, 0) + safeGet(fCurOut, service, 0);
        long cached = safeGet(fAccCached, service, 0) + safeGet(fCurCached, service, 0);
        publish(in, out, cached, streaming, false);
    }

    /**
     * 统一上报入口，含节流与去重。
     *
     * @param force 绕过时间节流（回合结束的最终值必须落地）
     */
    private static void publish(long in, long out, long cached, boolean streaming, boolean force) {
        if (in <= 0 && out <= 0) return;

        // 值没变就不推
        if (in == sLastIn && out == sLastOut && cached == sLastCached && !force) return;

        long now = System.currentTimeMillis();
        if (!force && now - sLastPushAt < currentThrottleMs()) return;

        sLastIn = in;
        sLastOut = out;
        sLastCached = cached;
        sLastPushAt = now;
        sHasLive = true;
        sFinalized = false;  // 新数据到来，回到流式状态
        recordHistory(in, out);  // 记录历史（供图表）

        String text = format(in, out, cached, true);  // 流式中带 …
        AgentProgress.setLastTokenUsage(text);
        LogX.i(TAG, "token live: " + text + " (raw in=" + in + " out=" + out
                + " cached=" + cached + ")");

        // 重置「输出完成」计时器：3 秒无更新 → 落地为总 token
        scheduleFinalize(in, out, cached);

        // 立刻把新内容推上岛
        try {
            FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(),
                    AgentProgress.body(), AgentProgress.percent());
        } catch (Throwable t) {
            LogX.w(TAG, "token push failed: " + t);
        }
    }

    /**
     * 安排一次「落地」：FINALIZE_DELAY_MS 后若 token 仍无更新，
     * 则把显示从「流式 …」切换为「总 token」。
     */
    private static void scheduleFinalize(final long in, final long out, final long cached) {
        try {
            if (sScheduler == null) {
                sScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "TokenFinalize");
                    t.setDaemon(true);
                    return t;
                });
            }
            // 取消上一次未执行的落地任务
            if (sFinalizeTask != null) sFinalizeTask.cancel(false);

            sFinalizeTask = sScheduler.schedule(() -> {
                try {
                    // 期间没有新数据（值未变）才算完成
                    if (sLastIn == in && sLastOut == out && sLastCached == cached && !sFinalized) {
                        sFinalized = true;
                        sPrevTotalIn = in;      // 存上一轮总
                        sPrevTotalOut = out;
                        String total = format(in, out, cached, false);
                        AgentProgress.setLastTokenUsage(total);
                        LogX.i(TAG, "token finalized (总): " + total);
                        FocusNotify.pushState(AgentProgress.title(), AgentProgress.toolName(),
                                AgentProgress.body(), AgentProgress.percent());
                    }
                } catch (Throwable t) {
                    LogX.w(TAG, "finalize failed: " + t);
                }
            }, FINALIZE_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LogX.w(TAG, "scheduleFinalize failed: " + t);
        }
    }

    // ------------------------------------------------------------ 格式化

    /**
     * 格式化为灵动岛用的紧凑字符串（方案1：只保留输入/输出，去掉 cache 省空间）：
     *   ↑1.05M ↓1.5K
     * 流式中在末尾加 "…" 表示还在增长。
     */
    static String format(long in, long out, long cached, boolean streaming) {
        StringBuilder sb = new StringBuilder();
        sb.append('↑').append(fmt(in));
        sb.append(' ').append('↓').append(fmt(out));
        return sb.toString();
    }

    /** 数字压缩：1234 → 1.2K，1234567 → 1.23M */
    static String fmt(long v) {
        if (v <= 0) return "0";
        if (v < 1000) return Long.toString(v);
        if (v < 1_000_000L) {
            double k = v / 1000.0;
            if (k < 10) return String.format(java.util.Locale.US, "%.1fK", k);
            return String.format(java.util.Locale.US, "%.0fK", k);
        }
        double m = v / 1_000_000.0;
        return String.format(java.util.Locale.US, "%.2fM", m);
    }

    // ------------------------------------------------------------ 工具

    private static long safeGet(Field f, Object obj, long dflt) {
        if (f == null) return dflt;
        try {
            Object v = f.get(obj);
            return (v instanceof Long) ? (Long) v : dflt;
        } catch (Throwable t) {
            return dflt;
        }
    }

    /** 调无参 long getter（用于 TurnTokenSnapshot 这种 data class） */
    private static long longGetter(Object obj, String method, long dflt) {
        if (obj == null) return dflt;
        try {
            Method m = obj.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            Object v = m.invoke(obj);
            return (v instanceof Long) ? (Long) v : dflt;
        } catch (Throwable t) {
            return dflt;
        }
    }
}