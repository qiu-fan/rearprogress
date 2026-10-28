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
    /** 静默通道：主通知走这条 → 通知栏里看不到它，但 NLS 仍会收到，背屏照常取数。
     *  （通知栏里只留镜像那条"焦点通知"，避免出现两条重复） */
    private static final String CHANNEL_ID_QUIET = "agent_rear_progress_data";
    private static final String CHANNEL_NAME_QUIET = "Agent 后台数据";
    private static final int NOTIF_ID = 0x4147454E; // 'AGEN'
    private static final String ORDER_ID = "operit_agent_progress";
    private static final String TAG = "RearProgress";
    /** Agent 卡片标记：标题以该前缀开头表示这是 Agent 卡片（模板据此前缀判断显示蓝色 "Agent"）。用 ASCII 箭头对，MAML 稳定支持 */
    private static final String AGENT_MARK = "«";

    private static NotificationManager sNm;
    private static Context sCtx;
    private static long sSeq = 1L;
    private static String sLastTitle;

    /** 是否由副屏中心代发通知。
     *  实测结论：代发能让前台也显示岛，但通知包名变成 com.xiaomi.subscreencenter 后，
     *  副屏中心按 (pkg, business) 查模板时报 "No widget path for: com.xiaomi.subscreencenter"，
     *  背屏会完全空白。所以默认关闭，仍由 Operit 自己发（背屏模板链路依赖 Operit 包名）。 */
    static final boolean USE_DELEGATE = false;

    /** 镜像通知标记：副屏中心进程发的那条"带 param"的通知（只为前台灵动岛服务，
     *  主通知仍由 Operit 自己发、不带 param，保证背屏模板按 Operit 包名正常命中）。 */
    static volatile boolean sForceParam = false;
    /** 速率/图表由 Operit 进程算好，通过广播 extras 传给 subscreencenter（那边 TokenHook 是空的） */
    static volatile String sRate = "";
    static volatile String sSparkline = "";
    static volatile String sInterval = "";
    static volatile String sSpinner = "";
    static volatile int sActiveCount = 0;   // 进行中对话数（广播传入，用于槽位9）
    static final String ACTION_MIRROR = "com.operit.rearprogress.MIRROR";
    /** 是否注入 miui.focus.param（岛展开态差异）。
     *  实测：注入后系统生成 rear_paramv2，副屏中心走 OS2,3 新分支按 (pkg,business)
     *  查模板，报 "No widget path ... business: notification_island" → 背屏整条丢弃变空白，
     *  同时前台岛被抑制。所以默认关闭，通知交回系统自动生成岛。 */
    static final boolean INJECT_ISLAND_PARAM = false;
    /** 运行时热开关：/data/local/tmp/agent_param.enabled 存在即开启 param 注入 */
    static boolean injParam() {
        try {
            return new java.io.File("/data/local/tmp/agent_param.enabled").exists();
        } catch (Throwable t) {
            return INJECT_ISLAND_PARAM;
        }
    }

    /** 运行时热配置 business（文件首行），默认 notification_island */
    static String injBusiness() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/agent_param.business");
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                String s = r.readLine();
                r.close();
                if (s != null && !s.trim().isEmpty()) return s.trim();
            }
        } catch (Throwable ignored) {}
        return "notification_island";
    }

    /**
     * 运行时 param 覆写：/data/local/tmp/agent_param.json 里的顶层 key
     * 会覆盖 param_v2 的默认字段，改完下一次通知刷新即生效（无需重启）。
     * 例：{"protocol":3,"business":"notification_island","islandFirstFloat":true}
     */
    static void applyParamOverrides(org.json.JSONObject pv2) {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/agent_param.json");
            if (!f.exists()) return;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            org.json.JSONObject ov = new org.json.JSONObject(sb.toString());
            java.util.Iterator<String> it = ov.keys();
            int n = 0;
            while (it.hasNext()) {
                String k = it.next();
                Object v = ov.opt(k);
                if (v == null || v == org.json.JSONObject.NULL) continue;
                pv2.put(k, v);
                n++;
            }
            android.util.Log.i("RearProgress", "param override applied: " + n + " keys");
        } catch (Throwable t) {
            android.util.Log.w("RearProgress", "param override failed", t);
        }
    }
    static final String ACTION_PUSH = "com.operit.rearprogress.PUSH_STATE";
    static final String ACTION_STOP = "com.operit.rearprogress.STOP_NOTIFY";
    /** 副屏中心代发成功后回执（Operit 侧据此判断是否需要本地兜底） */
    static final String ACTION_ACK = "com.operit.rearprogress.PUSH_ACK";
    private static final String PKG_SCREEN = "com.xiaomi.subscreencenter";
    private static final String PKG_OPERIT = "com.ai.assistance.operit";
    /** 最近一次收到代发回执的时间戳 */
    private static volatile long sLastAckAt = 0L;
    private static volatile boolean sAckReceiverReady = false;

    /** 注册回执接收器（Operit 进程用，判断代发是否生效） */
    static void installAckReceiver() {
        if (sAckReceiverReady) return;
        try {
            Context ctx = currentAppContext();
            if (ctx == null) return;
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    sLastAckAt = System.currentTimeMillis();
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(ACTION_ACK);
            ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            sAckReceiverReady = true;
            android.util.Log.i(TAG, "ack receiver ready");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "installAckReceiver failed", t);
        }
    }

    private FocusNotify() {}

    /** 当前是否运行在 Operit 进程里（需要转发给副屏中心代发） */
    private static boolean isOperitProcess() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return PKG_OPERIT.equals(((Context) app).getPackageName());
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 取当前进程的 Application Context */
    private static Context currentAppContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 转发推送请求给副屏中心（由它用自己的包名发通知） */
    private static void forwardPush(String title, String toolName, String body, int percent) {
        try {
            Context ctx = currentAppContext();
            if (ctx == null) return;
            android.content.Intent i = new android.content.Intent(ACTION_PUSH);
            i.putExtra("title", title == null ? "" : title);
            i.putExtra("tool", toolName == null ? "" : toolName);
            i.putExtra("body", body == null ? "" : body);
            i.putExtra("percent", percent);
            i.setPackage(PKG_SCREEN);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "forwardPush failed", t);
        }
    }

    /** 转发"撤掉通知"请求给副屏中心 */
    private static void forwardStop() {
        try {
            Context ctx = currentAppContext();
            if (ctx == null) {
                android.util.Log.w(TAG, "forwardStop: ctx is null, cannot cancel mirror");
                return;
            }
            android.content.Intent i = new android.content.Intent(ACTION_STOP);
            i.setPackage(PKG_SCREEN);
            ctx.sendBroadcast(i);
            android.util.Log.i(TAG, "forwardStop: ACTION_STOP broadcast sent to subscreencenter");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "forwardStop failed", t);
        }
    }

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
            // 静默通道：MIN 重要性 → 通知栏不显示，但 NLS 仍会派发（背屏取数用）
            NotificationChannel quiet = new NotificationChannel(
                    CHANNEL_ID_QUIET, CHANNEL_NAME_QUIET, NotificationManager.IMPORTANCE_LOW);
            quiet.setShowBadge(false);
            quiet.enableLights(false);
            quiet.enableVibration(false);
            quiet.setSound(null, null);
            quiet.setShowBadge(false);
            sNm.createNotificationChannel(quiet);
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

    /** 停止显示：撤掉这条常驻卡（本地 + 镜像两条都要撤） */
    static void stop() {
        if (isOperitProcess()) {
            stopLocal();
            forwardStop();
            return;
        }
        if (USE_DELEGATE && isOperitProcess()) {
            forwardStop();
            return;
        }
        stopLocal();
    }

    /** 真正撤掉通知（副屏中心进程执行） */
    static void stopLocal() {
        try {
            android.util.Log.i(TAG, "stopLocal: sNm=" + (sNm != null ? "ok" : "null")
                    + " pkg=" + (sCtx != null ? sCtx.getPackageName() : "?"));
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
        // 【revert 5242bef 测试】移除 Ready 覆盖：isIdle 抖动会让 title 在 Shell(root)↔Ready 切换，
        //   副屏中心把 Ready 那条按普通通知渲染 → 焦点↔普通来回切 = 岛/背屏闪（待验证是否闪源）

        // READY 态（刚进软件 / 没有任何工具调用）不推送通知：
        // 否则会闪一下「Agent"，右侧无内容」的岛，通知栏也多一条「已就绪」空卡片。
        try {
            if (AgentProgress.state() == AgentProgress.State.READY) {
                String b = body == null ? "" : body.trim();
                String t = toolName == null ? "" : toolName.trim();
                String ti = title == null ? "" : title.trim();
                // AI/测试上传了真实标题（不是默认的 "Agent"）→ 允许推送
                //   （"测进度(纯)"只有 title，tool/body 为空，不能被误挡）
                boolean hasRealTitle = !ti.isEmpty() && !"Agent".equals(ti);
                if (!hasRealTitle && (t.isEmpty() || "已就绪".equals(b) || b.isEmpty())) {
                    return;
                }
            }
        } catch (Throwable ignored) {}
        // 在 Operit 进程里：转发给副屏中心代发（用它的包名，绕过 MIUI 前台抑制）
        // 用 ordered broadcast 同步回执：副屏中心成功代发会 setResultCode(1)，
        // 收不到（比如副屏中心没在跑）才本地兜底发，避免重复通知。
        if (USE_DELEGATE && isOperitProcess()) {
            final String fT = title, fTool = toolName, fBody = body;
            final int fPct = percent;
            try {
                Context ctx = currentAppContext();
                if (ctx != null) {
                    android.content.Intent i = new android.content.Intent(ACTION_PUSH);
                    i.putExtra("title", title == null ? "" : title);
                    i.putExtra("tool", toolName == null ? "" : toolName);
                    i.putExtra("body", body == null ? "" : body);
                    i.putExtra("percent", percent);
                    i.setPackage(PKG_SCREEN);
                    ctx.sendOrderedBroadcast(i, null, new android.content.BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, android.content.Intent it) {
                            if (getResultCode() == 1) return;   // 代发成功
                            android.util.Log.w(TAG, "delegate failed (code="
                                    + getResultCode() + "), fallback to local");
                            try {
                                initFromActivityThread();
                                pushStateLocal(fT, fTool, fBody, fPct);
                            } catch (Throwable ignored) {}
                        }
                    }, null, 0, null, null);
                    return;
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "forwardPush failed, fallback", t);
            }
            // 拿不到 context 时直接本地发
            pushStateLocal(title, toolName, body, percent);
            return;
        }
        pushStateLocal(title, toolName, body, percent);
        // 双通知解耦：再让副屏中心代发一条"带 param"的镜像通知。
        //   主通知（Operit 自己发、不带 param）→ 背屏模板按 Operit 包名命中，正常显示
        //   镜像通知（副屏中心包名、带 param）→ 它不是前台应用，MIUI 不抑制 → 前台也有岛
        if (isOperitProcess()) {
            writeLiveParams();   // 跨进程共享：模块界面读这个文件
            forwardMirror(title, toolName, body, percent);
        }
    }

    /** 实时参数共享文件（模块界面在另一进程，静态数据拿不到，只能读文件） */
    private static final String LIVE_PARAMS = "/data/local/tmp/rp/live_params.txt";

    /** Operit 进程推送时把 6 项实时参数写文件，供 MainActivity 进程读取 */
    private static void writeLiveParams() {
        try {
            java.io.File dir = new java.io.File("/data/local/tmp/rp");
            if (!dir.exists()) dir.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("状态   : ").append(AgentProgress.title()).append('\n');
            sb.append("频率   : ").append(AgentProgress.getToolIntervalText()).append('\n');
            sb.append("对话   : ").append(ChatCounter.text()).append('\n');
            sb.append("工具   : ").append(AgentProgress.getLastToolName()).append('\n');
            sb.append("token  : ").append(AgentProgress.toolName()).append('\n');
            String sp = AgentProgress.spinner();
            sb.append("转轮   : ").append(sp.isEmpty() ? "（隐藏）" : sp).append('\n');
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(dir, "live_params.txt"), false);
            w.write(sb.toString());
            w.close();
        } catch (Throwable ignored) {}
    }

    /** 让副屏中心代发"带 param"的镜像通知（只为前台灵动岛 + 展开态差异服务） */
    private static void forwardMirror(String title, String toolName, String body, int percent) {
        try {
            Context ctx = currentAppContext();
            if (ctx == null) return;
            android.content.Intent i = new android.content.Intent(ACTION_MIRROR);
            i.putExtra("title", title == null ? "" : title);
            i.putExtra("tool", toolName == null ? "" : toolName);
            i.putExtra("body", body == null ? "" : body);
            i.putExtra("percent", percent);
            i.putExtra("active", ChatCounter.activeCount());  // 进行中→展开副标题
            // 在 Operit 进程算好速率和图表（TokenHook 数据只在这里有）
            i.putExtra("rate", TokenHook.getRate10s());
            i.putExtra("spark", TokenHook.getSparkline(8));
            // 工具调用频率（30s 窗口）在 Operit 进程算好，跨进程传给副屏中心
            i.putExtra("interval", AgentProgress.getToolIntervalText());
            // 转轮已拼进 subText(token 行)（必须进 newKey 才能走 100ms 刷新分支）；
            //   这里再传 extra 会让副屏中心在另一个槽位再渲染一个 → 用户看到"两个进度动画"，故不再传。
            i.putExtra("spinner", "");
            i.setPackage(PKG_SCREEN);
            ctx.sendBroadcast(i);
            android.util.Log.i(TAG, "forwardMirror sent: tool=" + toolName);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "forwardMirror failed", t);
        }
    }

    /** 副屏中心进程里：发镜像通知（强制注入 param） */
    static void pushMirrorLocal(String title, String toolName, String body, int percent) {
        pushMirrorLocal(title, toolName, body, percent, "", "", "", "", 0);
    }

    static void pushMirrorLocal(String title, String toolName, String body, int percent,
                                String rate, String sparkline, String interval,
                                String spinner, int active) {
        boolean old = sForceParam;
        String oldRate = sRate;
        String oldSpark = sSparkline;
        String oldInterval = sInterval;
        String oldSpinner = sSpinner;
        sForceParam = true;
        sRate = rate == null ? "" : rate;
        sSparkline = sparkline == null ? "" : sparkline;
        sInterval = interval == null ? "" : interval;
        sSpinner = spinner == null ? "" : spinner;
        sActiveCount = active;
        try {
            pushStateLocal(title, toolName, body, percent);
            android.util.Log.i(TAG, "mirror notify sent (pkg="
                    + (sCtx == null ? "?" : sCtx.getPackageName()) + ")");
        } finally {
            sForceParam = old;
            sRate = oldRate;
            sSparkline = oldSpark;
            sInterval = oldInterval;
            sSpinner = oldSpinner;
            sActiveCount = 0;
        }
    }

    /** 真正的通知发送节流：距离上次实际发送不足节流下限就跳过（避免高频重渲染闪空窗） */
    private static volatile long sLastSendAt = 0L;
    /** 非转轮活跃时的最快刷新间隔（防"一次工具调用内 4ms 连发"导致背屏重渲染抖动） */
    private static final long THROTTLE_MS = 50L;  // 低于转轮帧率100ms,保证每帧都推上不跳帧  // 用户要求:100ms刷新+内容变立刻推送
    /** 空闲心跳间隔：内容没变也按 1Hz 刷新（图表、工具调用频率需要节拍） */
    private static final long HEARTBEAT_MS = 1000L;
    /** 转轮活跃时的心跳间隔：100ms（用户要求"快速连续不断旋转"，仅工具调用后15秒窗口内） */
    private static final long FAST_TICK_MS = 50L;  // revert 测试：100ms→300ms（100ms高频更新致SystemUI重建焦点视图间隙=岛闪）
    private static volatile boolean sHeartbeatOn = false;

    /**
     * 1Hz 空闲心跳：用当前实时状态重推一次通知。
     * 作用：没有工具调用、token 不增长时，背屏图表 / 工具调用频率也按 1Hz 刷新
     *      （AOD 下同样是 1Hz）。
     * pushStateLocal 内部有"内容没变且 <HEARTBEAT_MS 就跳过"的判断，所以这里无脑每秒调。
     */
    static void startHeartbeat() {
        if (sHeartbeatOn) return;
        sHeartbeatOn = true;
        try {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        try {
                            // 动态间隔：转轮活跃（最近 15s 有工具调用）→ 300ms，空闲 → 1Hz。
                            //   转轮字符本身按 300ms 轮换，只有通知也以 300ms 刷新才看得见。
                            long interval = AgentProgress.spinnerActive() ? FAST_TICK_MS : HEARTBEAT_MS;
                            Thread.sleep(interval);
                        } catch (InterruptedException e) {
                            return;
                        }
                        try {
                            if (AgentProgress.idleForClear()) {
                                stop();
                                continue;
                            }
                            pushState(AgentProgress.title(), AgentProgress.toolName(),
                                    AgentProgress.body(), AgentProgress.percent());
                        } catch (Throwable ignored) {}
                    }
                }
            }, "rp-heartbeat");
            t.setDaemon(true);
            t.start();
            LogX.i(TAG, "heartbeat started (1Hz)");
        } catch (Throwable t) {
            sHeartbeatOn = false;
        }
    }

    /** 真正构建并发通知（由副屏中心进程执行，通知 pkg = com.xiaomi.subscreencenter） */
    static void pushStateLocal(String title, String toolName, String body, int percent) {
        try {
            // 节流：内容确实变了但离上次发送 <2s 就跳过，防止每秒 notify → 副屏中心每秒重渲染
            //   （空窗现象：渲染间隙闪系统默认占位"左边Agent右边Thinking"）
            //   例外：title 变化（工具切换）必须立刻发，否则会卡住新工具名
            if (sLastTitle != null) {
                String newKey = title + "\u0001" + toolName + "\u0001" + body + "\u0001" + percent;
                long sinceLast = System.currentTimeMillis() - sLastSendAt;
                if (newKey.equals(sLastTitle)) {
                    // 内容完全没变：用户要求"没有工具调用也要刷新"——按 1Hz 心跳重推，
                    //   让背屏图表 / 工具调用频率持续跳动
                    if (sinceLast < HEARTBEAT_MS) return;
                } else if (sinceLast < THROTTLE_MS) {  // revert 测试：回固定300ms（原动态放宽到FAST_TICK 100ms致高频更新）
                    // 严格节流：防"一次工具调用内多次 pushState 在 4ms 内连发"导致背屏重渲染抖动。
                    //   转轮活跃（工具调用后 15 秒窗口内）时下限放宽到 FAST_TICK_MS(100ms)，
                    //   让字符画快速连续旋转（用户要求）；其余场景维持 300ms。
                    return;
                }
            }
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
            // token 副标题保底：body 有值但 toolName 为空时（GrepCode/ReadPart 等工具
            // 参数未被记录到 sRecent，导致 toolName() 走不到 token 分支返回空串），
            // 直接取 token 用量，避免背屏第二行 token 消失
            if (!body.isEmpty() && toolName.isEmpty()) {
                String tok = AgentProgress.toolName();
                if (tok != null && tok.startsWith("↑")) toolName = tok;
            }
            // Agent 卡片标记：不前插标题（系统通知栏会原样显示），改由 buildNotification
            // 把标记写进 bigText 末尾——背屏 MAML 读 lu_big_text 能拿到，
            // 灵动岛展开态读的是 android.title，不受影响。
            if (title.trim().isEmpty() && body.isEmpty()) return;

            String key = title + "\u0001" + toolName + "\u0001" + body + "\u0001" + percent;
            sLastTitle = key;
            // 副标题去重：与标题/内容相同则不显示（避免岛上左右/上下重复文字）
            if (toolName.equalsIgnoreCase(title) || toolName.equalsIgnoreCase(body)) {
                toolName = "";
            }
            LogX.i(TAG, "pushState title=[" + title + "] tool=[" + toolName + "] body=["
                    + (body.length() > 40 ? body.substring(0, 40) + "…" : body) + "] pct=" + percent);

            int pct = (percent < 0) ? 0 : Math.min(100, percent);
            boolean hasBar = percent >= 0;

            Notification n = buildNotification(sCtx, title, toolName, body, pct, hasBar);
            sNm.notify(NOTIF_ID, n);
            sLastSendAt = System.currentTimeMillis();  // 记录实际发送时刻（节流用）
            sSeq++;
            startHeartbeat();  // 首次发送成功后启动 1Hz 空闲心跳
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
            // 工作区路径（精确，先处理）：.../files/workspace/<uuid>/ → WORKSPACE/
            // 不区分大小写、uuid/id 都吃掉，避免出现 workspace/xxx 残留
            text = text.replaceAll(
                "(?i)/data/user/0/com\\.ai\\.assistance\\.operit/files/workspace(/[^/\\s]*)?/?",
                "WORKSPACE/"
            );
            // 兜底：任何残留的 workspace 目录（其他路径形态）→ WORKSPACE/
            text = text.replaceAll("(?i)(?<![\\w/])workspace/", "WORKSPACE/");
            // Operit data 路径（兜底）：/data/user/0/com.ai.assistance.operit/ → Operit//
            text = text.replaceAll(
                "/data/user/0/com\\.ai\\.assistance\\.operit/",
                "Operit//"
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
     * 构建展开态（点开通知）的丰富文本：ASCII 字符图表 + 统计 + 工具流动 + 参数。
     * 收起态（岛上）仍是 title/subText 不变。
     */
    private static String buildExpandedText(String title, String toolName, String body) {
        StringBuilder sb = new StringBuilder();
        // 统计行
        sb.append("↑").append(TokenHook.fmt(TokenHook.getLastIn()))
          .append(" ↓").append(TokenHook.fmt(TokenHook.getLastOut()))
          .append("   hit:").append(String.format(java.util.Locale.US, "%.2f", TokenHook.getHitRate())).append("%\n");
        // ASCII 趋势图（用 token 历史生成柱状字符）
        String bars = buildAsciiChart();
        if (!bars.isEmpty()) sb.append(bars).append('\n');
        // 工具流动
        String flow = AgentProgress.getToolFlow();
        if (!flow.isEmpty()) sb.append("最近: ").append(flow).append('\n');
        // 分隔 + 参数
        sb.append("───────────\n").append(body);
        return sb.toString();
    }

    /** 用 token 历史生成 ASCII 柱状图（▁▂▃▄▅▆▇█），每字符一个采样点 */
    private static String buildAsciiChart() {
        String hist = TokenHook.getHistoryJson();
        if (hist == null || hist.length() < 10) return "";
        try {
            // 简易解析 [[in,out],...] 取 in
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\[(\\d+),(\\d+)\\]").matcher(hist);
            java.util.List<Long> vals = new java.util.ArrayList<>();
            while (m.find()) vals.add(Long.parseLong(m.group(1)));
            int n = vals.size();
            if (n < 2) return "";
            // 采样最多 32 个字符宽
            int maxCols = 32;
            int step = Math.max(1, n / maxCols);
            long max = 1;
            for (long v : vals) if (v > max) max = v;
            // 8 级柱状字符（低→高）
            char[] blocks = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < n; i += step) {
                int lvl = (int) (vals.get(i) * 7 / max);
                if (lvl < 0) lvl = 0;
                if (lvl > 7) lvl = 7;
                bar.append(blocks[lvl]);
            }
            return bar.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 构建通知（无进度条样式）。
     *
     * 展示位置：
     *   收起态（灵动岛 / 背屏）  → title / toolName / body
     *   展开态（下拉通知）      → 上面三行 + buildExpandedBody(body) 追加内容
     *
     * setContentText(body) 保持不变（背屏 MAML 读 extras.android.text）。
     */
    /** 图表占位：8个最低块字符，固定宽度避免跳动 */
    private static String placeholderSpark() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append('\u2581');
        return sb.toString();
    }

    /** 命中率后缀：" · 84.83%"，无数据时返回空串 */
    private static String hitSuffix() {
        try {
            if (AgentProgress.toolName() == null || AgentProgress.toolName().equals("ready")) return "";
            double rate = TokenHook.getHitRate();
            if (rate <= 0) return "";
            return " · " + String.format(java.util.Locale.US, "%.2f", rate) + "%";
        } catch (Throwable t) {
            return "";
        }
    }

/**
     * 终端风格图标 ">_" —— 程序化绘制，不用图片资源，任何分辨率都清晰。
     * 用等宽字体 + 加粗 + 居中，白色透明底（通知小图标要求单色）。
     */
    private static android.graphics.drawable.Icon sTermIcon;
    private static android.graphics.drawable.Icon terminalIcon(Context ctx) {
        if (sTermIcon != null) return sTermIcon;
        try {
            int size = 144;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(0xFFFFFFFF);                       // 白色
            p.setTextSize(size * 0.48f);                   // 字号约占 48%
            p.setTypeface(android.graphics.Typeface.MONOSPACE);
            p.setFakeBoldText(true);
            p.setAntiAlias(true);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            // 垂直居中：用 ascent/descent 计算基线偏移
            float cx = size / 2f;
            float cy = size / 2f - (p.descent() + p.ascent()) / 2f;
            canvas.drawText(">_", cx, cy, p);
            sTermIcon = android.graphics.drawable.Icon.createWithBitmap(bmp);
        } catch (Throwable t) {
            // 兜底：万一绘制失败，用系统自带图标
            sTermIcon = android.graphics.drawable.Icon.createWithResource(
                    ctx, android.R.drawable.stat_sys_download_done);
        }
        return sTermIcon;
    }

    @SuppressWarnings("deprecation")
    private static Notification buildNotification(Context ctx, String title, String toolName,
                                                  String body, int percent, boolean hasBar) {
        Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        // 【槽位测试】全部写死标注，用户看哪个槽位在哪个位置
        final boolean SLOT_TEST = false;
        String t1 = SLOT_TEST ? "【1-通知标题】" : title;
        String t2 = SLOT_TEST ? "【2-通知副标题】" : toolName;
        // 用户指定（1.4.1）：正文显示"目前进行的对话数"，不再显示工具调用累计数
        String t3 = ChatCounter.text();

        b.setContentTitle(t1);
        if (t2 != null && !t2.isEmpty()) {
            b.setSubText(t2);
        }
        b.setContentText(t3);
        b.setSmallIcon(terminalIcon(ctx));
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        b.setPriority(Notification.PRIORITY_HIGH);

        try {
            Notification.BigTextStyle bigText = new Notification.BigTextStyle()
                    .bigText(SLOT_TEST ? "【4-展开大文本】多行示例\n第二行\n第三行" : AGENT_MARK + body)
                    .setBigContentTitle(SLOT_TEST ? "【5-展开大标题】" : title)
                    .setSummaryText(SLOT_TEST ? "【6-展开摘要】" : toolName);
            b.setStyle(bigText);
        } catch (Throwable ignored) {}

        Notification n = b.build();
        try {
            n.extras.putString("agent_mark", "1");
            n.extras.putString("token_history", TokenHook.getHistoryJson());
            n.extras.putString("token_path", TokenHook.getHistoryPath(630, 110));
            String hitRate = AgentProgress.toolName().equals("ready") ? ""
                    : String.format(java.util.Locale.US, "%.2f", TokenHook.getHitRate()) + "%";
            n.extras.putString("hit_rate", hitRate);
            n.extras.putString("token_in", TokenHook.fmt(TokenHook.getPrevTotalIn()));
            n.extras.putString("token_out", TokenHook.fmt(TokenHook.getPrevTotalOut()));
            n.extras.putString("tool_flow", AgentProgress.getToolFlow());
            n.extras.putString("tool_name", AgentProgress.getLastToolName());
            n.extras.putString("time_range", TokenHook.getTimeRange());
            n.extras.putString("task_time", TokenHook.getTaskTime());
        } catch (Throwable ignored) {}

        try {
            String toolTitle = (title == null || title.isEmpty()) ? "Agent" : title;
            String bodyText = (body == null || body.isEmpty()) ? "" : body;
            String tok = (toolName == null || toolName.isEmpty()) ? "" : toolName;
            boolean isMirror = sForceParam;
            if (injParam() || isMirror) {
            final String PIC_KEY = "operit_island_icon";
            org.json.JSONObject picInfo = new org.json.JSONObject()
                    .put("type", 1).put("pic", PIC_KEY);

            // 焦点通知展开卡（下拉岛后看到的卡）
            org.json.JSONObject iconTextInfo = new org.json.JSONObject()
                    .put("animIconInfo", new org.json.JSONObject().put("type", 0).put("src", PIC_KEY))
                    // title  → 状态标题（工具名）；SLOT_TEST 时显示槽位测试文案
                    // 注意：不能用 toolName，它实际是 token 用量（102bd62 修的 bug 根因）
                    .put("title",    SLOT_TEST ? "【7-焦点卡标题】" : (sInterval.isEmpty() ? "--.-s/次" : sInterval))
                    // content → 命令正文；多行会被 MIUI 折叠成一行，所以显式用 · 连接
                    .put("content",  SLOT_TEST ? "【8-焦点卡内容】多行示例·用·连接" : (sSparkline.isEmpty() ? placeholderSpark() : sSparkline))
                    // subContent → token 用量 + 命中率（三个槽位全部塞满，尽量一行多信息）
                    .put("subContent", SLOT_TEST ? "【9-焦点卡副内容】"
                            : (tok + (sSpinner.isEmpty() ? "" : " " + sSpinner)
                                    // 用户要求：进行中：N 显示在展开副标题（不再进岛右侧 subText）
                                    + (sActiveCount > 1 ? " 进行中：" + sActiveCount : "")));

            // 大岛：左=工具名（10），右=token 数（11）——用户指定
            org.json.JSONObject bigLeft = new org.json.JSONObject()
                    .put("type", 1)
                    .put("picInfo", picInfo)
                    .put("textInfo", new org.json.JSONObject()
                            .put("title", SLOT_TEST ? "【10-大岛左标题】"
                                    : (AgentProgress.idleForReady() ? "Agent" : toolTitle)));
            org.json.JSONObject bigRight = new org.json.JSONObject()
                    .put("type", 2)
                    .put("textInfo", new org.json.JSONObject()
                            .put("title", SLOT_TEST ? "【11-大岛右标题】"
                                    : (AgentProgress.idleForReady() ? "Ready"
                                            : (tok + (sSpinner.isEmpty() ? "" : " " + sSpinner)))));
            org.json.JSONObject bigArea = new org.json.JSONObject()
                    .put("imageTextInfoLeft", bigLeft)
                    .put("imageTextInfoRight", bigRight);

            // 小岛：只有图标，无文字槽位
            org.json.JSONObject smallArea = new org.json.JSONObject().put("picInfo", picInfo);

            org.json.JSONObject island = new org.json.JSONObject()
                    .put("islandPriority", 1)
                    .put("islandTimeout", Integer.MAX_VALUE)
                    .put("islandProperty", 1)
                    .put("bigIslandArea", bigArea)
                    .put("smallIslandArea", smallArea);

            org.json.JSONObject pv2 = new org.json.JSONObject()
                    .put("protocol", 1)
                    .put("business", injBusiness())
                    .put("updatable", true)
                    .put("enableFloat", false)
                    .put("islandFirstFloat", false)
                    .put("ticker", SLOT_TEST ? "【12-ticker】" : toolTitle)
                    .put("tickerPic", PIC_KEY)
                    .put("notifyId", (isMirror && sCtx != null
                            ? sCtx.getPackageName() : "com.ai.assistance.operit") + NOTIF_ID)
                    .put("param_island", island)
                    .put("iconTextInfo", iconTextInfo);
            applyParamOverrides(pv2);

            n.extras.putString("miui.focus.param",
                    new org.json.JSONObject().put("param_v2", pv2).toString());

            android.os.Bundle pics = new android.os.Bundle();
            pics.putParcelable(PIC_KEY, terminalIcon(ctx));
            n.extras.putBundle("miui.focus.pics", pics);
            }
        } catch (Throwable ignored) {}

        // 关键：副屏中心 NLS 的 b() 判定 flags & 0x2 (FLAG_ONGOING_EVENT)，
        // 不满足就直接丢弃。这里在 build 后强制补上，防止 Style/recoverBuilder 丢掉。
        n.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_ONLY_ALERT_ONCE;
        return n;
    }
}