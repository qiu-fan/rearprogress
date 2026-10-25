package com.operit.rearprogress;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;

import org.json.JSONObject;

/**
 * FocusNotify —— 往小米「焦点通知 / 灵动岛」发一条可原地更新的常驻卡片
 *
 * 依据（实测 dumpsys 抓到的美团样本）：
 *   extras["miui.focus.param"] = {
 *     "orderId":"...",           // 唯一 ID，同 ID 更新不新增
 *     "scene":"foodDelivery",    // carHailing / foodDelivery / music / sports / timer
 *     "title":"预计24分钟后送达",
 *     "content":"徐州肉筋卷饼",
 *     "desc1":"骑手正在送货",
 *     "progress":62,             // 0-100
 *     "updatable":true,          // 允许更新
 *     "enableFloat":false,       // 不弹出
 *     "sequence":<时间戳>,        // 每次更新递增
 *     "protocol":1,
 *     "param_island": {...}      // 灵动岛布局（可省略，走默认）
 *   }
 *
 * 副屏中心 SmartAssistantNotificationListenerService 会捕获带这个键的通知，
 * 交给 U1.r0 走 widget 渲染 —— 与便签完全独立的一条路。
 */
final class FocusNotify {

    private static final String CHANNEL_ID = "agent_rear_progress";
    private static final String CHANNEL_NAME = "Agent 进度";
    private static final int NOTIF_ID = 0x4147454E; // 'AGEN'
    private static final String ORDER_ID = "operit_agent_progress";
    private static final String TAG = "RearProgress";

    private static NotificationManager sNm;
    private static Context sCtx;
    private static long sSeq = 1L;
    private static String sLastTitle;

    private FocusNotify() {}

    /** 在副屏中心进程里初始化（借用它的 Context 发通知） */
    static void init(Context ctx) {
        if (ctx == null) return;
        sCtx = ctx.getApplicationContext();
        if (sCtx == null) sCtx = ctx;
        sNm = (NotificationManager) sCtx.getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
    }

    /**
     * 在 Operit 进程里（跨进程使用）自愈初始化：
     * 通过 ActivityThread.currentApplication() 拿到本进程的 Context。
     */
    private static synchronized void initFromActivityThread() {
        if (sNm != null && sCtx != null) return;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                init((Context) app);
                android.util.Log.i(TAG, "FocusNotify self-init from ActivityThread");
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "initFromActivityThread failed", t);
        }
    }

    private static void createChannel() {
        if (sNm == null) return;
        try {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            sNm.createNotificationChannel(ch);
        } catch (Throwable ignored) {}
    }

    /**
     * 推送/更新进度卡片。同 ORDER_ID 反复调用＝原地静默更新。
     *
     * @param text  进度文字，如 "3/5 正在反编译 APK"
     * @param progress 0-100，传 -1 表示不显示进度条
     */
    static void push(String text, int progress) {
        // 旧接口：只用 AI 上传的文字，没有工具行
        pushRaw(text, "", progress);
    }

    /** 停止显示：撤掉这条常驻卡 */
    static void stop() {
        try {
            if (sNm != null) sNm.cancel(NOTIF_ID);
            sLastTitle = null;
            sLastBody = null;
        } catch (Throwable ignored) {}
    }

    private static String sLastBody;

    /**
     * 三段式上报（对应背屏模板的三行）：
     *   title     → 主标题（AI 进度，或状态「已就绪/任务进行中/已完成」）
     *   toolName  → 副标题，模板里放大加粗
     *   body      → 参数，一行一条，模板里小字弱化
     *   percent   → 0-100 进度条，<0 表示不显示
     */
    static void pushState(String title, String toolName, String body, int percent) {
        try {
            // 检查开关文件：不存在则不推送通知
            if (!isEnabled()) {
                // 开关关闭时，撤掉可能存在的通知
                if (sNm != null) {
                    sNm.cancel(NOTIF_ID);
                    sLastTitle = null;
                    sLastBody = null;
                }
                return;
            }

            if (sNm == null || sCtx == null) {
                // 在 Operit 进程里调用时还没 init，这里自愈
                initFromActivityThread();
            }
            if (sNm == null || sCtx == null) return;
            if (title == null) title = "";
            if (toolName == null) toolName = "";
            if (body == null) body = "";
            title = title.trim();
            toolName = toolName.trim();
            body = body.trim();
            body = shortenPaths(body);  // 替换长路径为简短别名
            if (title.isEmpty() && body.isEmpty()) return;

            String key = title + "\u0001" + toolName + "\u0001" + body + "\u0001" + percent;
            if (key.equals(sLastTitle)) return;   // 完全没变就不发
            sLastTitle = key;
            LogX.i(TAG, "pushState title=[" + title + "] tool=[" + toolName + "] body=["
                    + (body.length() > 40 ? body.substring(0, 40) + "…" : body) + "]");

            int pct = (percent < 0) ? 0 : Math.min(100, percent);
            boolean hasBar = percent >= 0;

            Notification n = buildNotification(sCtx, title, toolName, body, pct, hasBar);
            sNm.notify(NOTIF_ID, n);
            sSeq++;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "pushState failed", t);
        }
    }

    /** 检查开关文件是否存在 */
    private static boolean isEnabled() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/agent_progress.enabled");
            return f.exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 替换 body 里的长路径为简短别名（灵动岛显示空间有限）
     * 例如：
     *   /data/user/0/com.ai.assistance.operit/files/workspace/xxx/... → 工作区/...
     *   /storage/emulated/0/Android/code/... → 代码/...
     *   /storage/emulated/0/AOperit/skills/... → skills/...
     */
    private static String shortenPaths(String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            // 工作区路径：/data/user/0/com.ai.assistance.operit/files/workspace/<uuid>/...
            text = text.replaceAll(
                "/data/user/0/com\\.ai\\.assistance\\.operit/files/workspace/[0-9a-f-]+/",
                "工作区/"
            );
            // 代码目录：/storage/emulated/0/Android/code/...
            text = text.replaceAll(
                "/storage/emulated/0/Android/code/",
                "代码/"
            );
            // Skills 目录
            text = text.replaceAll(
                "/storage/emulated/0/AOperit/skills/",
                "skills/"
            );
            // 下载目录
            text = text.replaceAll(
                "/storage/emulated/0/Download/",
                "Download/"
            );
        } catch (Throwable ignored) {}
        return text;
    }

    /** 兼容旧调用：没有单独的工具名 */
    static void pushRaw(String title, String body, int percent) {
        pushState(title, "", body, percent);
    }

    /**
     * 构建通知（无进度条样式）
     */
    @SuppressWarnings("deprecation")
    private static Notification buildNotification(Context ctx, String title, String toolName,
                                                  String body, int percent, boolean hasBar) {
        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setContentTitle(title);
        // 工具名走 subText，副屏中心 m2.b.c() 会把 extras 的
        // android.subText 映射成 lu_sub_text，正好对应模板第二行
        if (toolName != null && !toolName.isEmpty()) {
            b.setSubText(toolName);
        }
        b.setContentText(body);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        b.setPriority(Notification.PRIORITY_HIGH);  // 必须 HIGH，否则后台通知被降级，灵动岛不显示

        // 用 BigTextStyle 显示完整内容（无进度条）
        try {
            Notification.BigTextStyle bigText = new Notification.BigTextStyle()
                    .bigText(body)
                    .setBigContentTitle(title)
                    .setSummaryText(toolName);
            b.setStyle(bigText);
        } catch (Throwable ignored) {}

        Notification n = b.build();
        // 扩展 extras：供展开态 MAML 模板使用（副屏中心会映射成 lu_* 变量）
        try {
            // token 实时趋势（JSON 数组，供图表）
            n.extras.putString("lu_token_history", TokenHook.getHistoryJson());
            // token 趋势 SVG path（Java 算好，MAML Path 直接画）
            n.extras.putString("lu_token_path", TokenHook.getHistoryPath(240, 60));
            // 命中率（cached/input 百分比）
            n.extras.putString("lu_hit_rate", TokenHook.getHitRate() + "%");
            // 当前 token 明细
            n.extras.putString("lu_token_in", TokenHook.fmt(TokenHook.getLastIn()));
            n.extras.putString("lu_token_out", TokenHook.fmt(TokenHook.getLastOut()));
            // 工具流动（最近工具名，逗号分隔）
            n.extras.putString("lu_tool_flow", AgentProgress.getToolFlow());
        } catch (Throwable ignored) {}
        // 关键：副屏中心 NLS 的 b() 判定 flags & 0x2 (FLAG_ONGOING_EVENT)，
        // 不满足就直接丢弃。这里在 build 后强制补上，防止 Style/recoverBuilder 丢掉。
        n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_ONLY_ALERT_ONCE;
        return n;
    }
}