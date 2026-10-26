package com.operit.rearprogress;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * RearProgress v0.4 —— 背屏进度卡「原地改字」模块
 * 基于新版 libxposed API 102（LSPosed 2.2.1）
 *
 * 反编译依据（com.xiaomi.subscreencenter）：
 *   i2.D.a            = PinManager 唯一实例（i2.E）
 *   i2.E.e            = ArrayList<Lj2/a> 所有 pin 条目（内存态）
 *   Lj2/a.extra       = Bundle，文字在 "__PIN_CONTENT_TEXT__"
 *   U1.c0.a           = PersistenceManager 单例（U1.d0）
 *   U1.d0 内有 U1.r0 字段 = SmartAssistantManager（widget 渲染源）
 *   U1.r0.a           = ArrayList<U1/q0> widget 列表
 *   U1/q0.l           = Bundle
 *   U1.r0.o(q0)       = 单条更新；读 "force_popup"/"enableFloat" 决定是否弹出
 *
 * 策略：改内存 Bundle → force_popup=false → 单条 o(q0) 静默刷新。
 * 不新增、不删除、id 不变。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "RearProgress";
    private static final String PKG_SCREEN = "com.xiaomi.subscreencenter";
    private static final String PKG_OPERIT = "com.ai.assistance.operit";
    private static final String PKG_SYSTEMUI = "com.android.systemui";

    private static final String PROGRESS_FILE = "/data/local/tmp/agent_progress.txt";
    private static final String MARKER = "[AGENT]";
    private static final long POLL_MS = 1200L;

    private ClassLoader mCl;

    // 反射句柄
    private volatile Object sPinManager;       // i2.E
    private volatile Field sPinListField;      // i2.E.e
    private volatile Object sAssistant;        // U1.r0
    private volatile Field sWidgetListField;   // U1.r0.a
    private volatile Method sUpdateWidget;     // U1.r0.o(q0)
    private volatile Object sPersistence;      // U1.c0.a (= U1.d0)
    private volatile Method sPersistMethod;    // U1.d0.i(ArrayList)
    private volatile Method sRefreshMethod;    // U1.r0.I() —— 触发背屏重绘
    private volatile Object sScreenManager;    // D.c.f —— SubScreenWidgetManager 单例
    private volatile Method sPublishMethod;    // D.c.Q(List,int,int) —— 最终推屏
    private volatile android.content.Context sAppContext;

    private volatile String sLastRead;
    private long sLastMtime = -1L;
    private volatile String sLastApplied;
    private volatile boolean sStarted;

    public MainHook() {
        super();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        try {
            String pkg = param.getPackageName();
            if (PKG_OPERIT.equals(pkg)) {
                // Operit 侧：只挂工具调用 Hook，负责自动上报进度
                mCl = param.getClassLoader();
                log(android.util.Log.INFO, TAG, "operit ready: " + pkg);
                OperitHook.install(this, param.getClassLoader());
                return;
            }
            if (PKG_SYSTEMUI.equals(pkg)) {
                mCl = param.getClassLoader();
                log(android.util.Log.INFO, TAG, "systemui ready: watch plugin loader");
                installPluginLoaderWatcher();
                return;
            }
            if (!PKG_SCREEN.equals(pkg)) return;

            mCl = param.getClassLoader();
            log(android.util.Log.INFO, TAG, "package ready: " + pkg);

            resolveHandles();
            installMamlTemplateHook();
            installLiveUpdateSwitchHook();
            installNlsTraceHook();
            installNlsGateTraceHook();
            installWidgetParseTraceHook();
            installRenderHook();
            resolveAppContext();
            startPoller();
            registerNotifyForwarder();
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "onPackageReady failed", t);
        }
    }

    // ------------------------------------------------------------ SystemUI 插件层（谨慎版）

    /**
     * 只做一件事：在插件类加载时记下那个 ClassLoader。
     *
     * 注意：这里处于 ClassLoader.findClass 的关键路径上，
     * 绝对不能在这里做任何类加载 / 反射 / 重活，否则会让 SystemUI 启动期死锁或崩溃。
     * 所以只做「赋值 + 投递到主线程」，真正的 hook 在 schedulePluginHooks 里做。
     */
    private void installPluginLoaderWatcher() {
        try {
            Class<?> filter = Class.forName(
                    "com.android.systemui.shared.plugins.PluginInstance$ClassLoaderFilter",
                    false, mCl);
            for (Method m : filter.getDeclaredMethods()) {
                if (!"findClass".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    hook(m).intercept(chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (self != null && sTriedFilters.add(self)) {
                                schedulePluginHooks(self);
                            }
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
                    log(android.util.Log.INFO, TAG, "plugin loader watcher installed");
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "installPluginLoaderWatcher failed: " + t);
        }
        // 诊断：10 秒后报告捕获到多少个 filter、内部 loader 是什么
        // 注意：onPackageReady 阶段主线程 Looper 可能尚未就绪，不能用 Handler，改用一次性线程。
        try {
            new Thread(() -> {
                try {
                    Thread.sleep(10000);
                    StringBuilder sb = new StringBuilder();
                    int i = 0;
                    for (Object o : sTriedFilters) {
                        if (i++ >= 4) break;
                        for (ClassLoader cl : collectLoaders(o)) {
                            sb.append(cl.getClass().getSimpleName()).append('@')
                              .append(Integer.toHexString(System.identityHashCode(cl))).append(',');
                        }
                    }
                    log(android.util.Log.INFO, TAG, "watcher diag: filters="
                            + sTriedFilters.size() + " loaders=" + sb);
                } catch (Throwable ignored) {}
            }, "rp-watchdiag").start();
        } catch (Throwable ignored) {}
    }

    /** 已处理过的 filter 实例（findClass 可能在多线程并发，必须用线程安全集合） */
    private final java.util.Set<Object> sTriedFilters =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 离开类加载路径后，在一次性后台线程里做具体 hook（不依赖主线程 Looper） */
    private void schedulePluginHooks(final Object filterObj) {
        try {
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                    installPluginFocusHooks(filterObj);
                } catch (Throwable ignored) {}
            }, "rp-plugin-hook").start();
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "schedulePluginHooks failed: " + t);
        }
    }

    /**
     * ClassLoaderFilter 只是一个白名单过滤层，真正的 loader 是它内部持有的字段，
     * 所以这里把 filter 自身 + 它所有 ClassLoader 类型字段都作为候选试一遍。
     *
     * 这一版只观察 MIUI 插件里焦点通知的两个授权判定，不做任何改写：
     *   NotificationSettingsManager.canCustomFocus(pkg)       —— 自定义焦点通知白名单
     *   NotificationSettingsManager.canPassXMSPermission(pkg) —— XMS 授权名单
     */
    private void installPluginFocusHooks(Object filterObj) {
        String target = "miui.systemui.notification.NotificationSettingsManager";
        Class<?> cls = null;
        String used = "none";
        for (ClassLoader cl : collectLoaders(filterObj)) {
            try {
                cls = Class.forName(target, true, cl);
                used = cl.getClass().getName();
                break;
            } catch (Throwable ignored) {}
        }
        if (cls == null) return;
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            String nm = m.getName();
            if (!"canCustomFocus".equals(nm) && !"canPassXMSPermission".equals(nm)) continue;
            try {
                hook(m).intercept(chain -> {
                    Object r;
                    try {
                        r = chain.proceed();
                    } catch (Throwable t2) {
                        r = "EX:" + t2.getClass().getSimpleName();
                    }
                    try {
                        String arg = "";
                        try {
                            Object a = chain.getArg(1);
                            if (a instanceof CharSequence) arg = a.toString();
                        } catch (Throwable ignored) {}
                        log(android.util.Log.INFO, TAG,
                                "PLUGFOCUS " + nm + "(" + arg + ") -> " + r);
                    } catch (Throwable ignored) {}
                    return r;
                });
                n++;
            } catch (Throwable ignored) {}
        }
        log(android.util.Log.INFO, TAG, "plugin focus hooks installed: " + n + " via " + used);
    }

    private List<ClassLoader> collectLoaders(Object filterObj) {
        List<ClassLoader> out = new ArrayList<>();
        try {
            if (filterObj instanceof ClassLoader) out.add((ClassLoader) filterObj);
        } catch (Throwable ignored) {}
        try {
            Class<?> c = filterObj.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (!ClassLoader.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        Object v = f.get(filterObj);
                        if (v instanceof ClassLoader && !out.contains(v)) out.add((ClassLoader) v);
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // ------------------------------------------------------------ 通知代发

    private volatile boolean sForwarderRegistered;

    /**
     * 注册"通知代发"广播接收器。
     *
     * Operit 进程的 FocusNotify 不再自己发通知，而是发广播到这里；
     * 由本进程（com.xiaomi.subscreencenter）用系统应用的包名发，
     * 这样 MIUI 不会因为"源头应用在前台"而抑制灵动岛。
     */
    private void registerNotifyForwarder() {
        if (sForwarderRegistered) return;
        // 后台重试：进程刚起时 Application 可能还没 attach，直接注册会拿到 null
        Thread t = new Thread(() -> {
            for (int i = 0; i < 40 && !sForwarderRegistered; i++) {
                try {
                    android.content.Context ctx = reliableAppContext();
                    if (ctx != null) {
                        if (registerForwarderWith(ctx)) return;
                    }
                    Thread.sleep(1500L);
                } catch (Throwable ignored) {}
            }
            log(android.util.Log.WARN, TAG, "notify forwarder gave up");
        }, "RearProgressForwarderReg");
        t.setDaemon(true);
        t.start();
    }

    /** 多路兜底拿 Application Context（副屏中心里 ActivityThread 可能还没就绪） */
    private android.content.Context reliableAppContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object o = at.getMethod("currentApplication").invoke(null);
            if (o instanceof android.content.Context) return (android.content.Context) o;
        } catch (Throwable ignored) {}
        try {
            Class<?> h = Class.forName("android.app.AndroidAppHelper");
            Object o = h.getMethod("currentApplication").invoke(null);
            if (o instanceof android.content.Context) return (android.content.Context) o;
        } catch (Throwable ignored) {}
        try {
            if (sAppContext == null) resolveAppContext();
            if (sAppContext != null) return sAppContext;
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean registerForwarderWith(android.content.Context ctx) {
        try {
            final android.content.Context app = ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx;
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent intent) {
                    try {
                        String action = intent.getAction();
                        FocusNotify.init(app);
                        if (FocusNotify.ACTION_STOP.equals(action)) {
                            FocusNotify.stopLocal();
                            log(android.util.Log.INFO, TAG, "forward: stop");
                            setResultCode(1);
                            return;
                        }
                        FocusNotify.pushStateLocal(
                                intent.getStringExtra("title"),
                                intent.getStringExtra("tool"),
                                intent.getStringExtra("body"),
                                intent.getIntExtra("percent", -1));
                        // 同步回执：告诉 Operit 侧"已代发"，它就不会再本地发一遍
                        setResultCode(1);
                    } catch (Throwable t) {
                        log(android.util.Log.WARN, TAG, "notify forward receive failed", t);
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(FocusNotify.ACTION_PUSH);
            f.addAction(FocusNotify.ACTION_STOP);
            app.registerReceiver(r, f, android.content.Context.RECEIVER_EXPORTED);
            sForwarderRegistered = true;
            log(android.util.Log.INFO, TAG, "notify forwarder registered, pkg="
                    + app.getPackageName());
            return true;
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "registerForwarderWith failed", t);
            return false;
        }
    }

    // ------------------------------------------------------------ 句柄

    private volatile Class<?> sPinManagerCls;

    private Class<?> pinManagerClsCached() throws ClassNotFoundException {
        if (sPinManagerCls == null) sPinManagerCls = Class.forName("i2.E", true, mCl);
        return sPinManagerCls;
    }

    private void resolveHandles() {
        try {
            sPinListField = pinManagerClsCached().getDeclaredField("e");
            sPinListField.setAccessible(true);
            log(android.util.Log.INFO, TAG, "i2.E.e resolved");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "resolve i2.E.e failed", t);
        }
    }

    private boolean ensureSingletons() {
        try {
            if (sPinManager == null) {
                Class<?> dCls = Class.forName("i2.D", true, mCl);
                Object v = dCls.getDeclaredField("a").get(null);
                if (v == null) return false;
                sPinManager = v;
                log(android.util.Log.INFO, TAG, "PinManager = i2.D.a");
            }
            if (sAssistant == null) {
                // U1.p0.a = static final U1.r0（SmartAssistantManager 单例）
                Object v = null;
                try {
                    Class<?> p0 = Class.forName("U1.p0", true, mCl);
                    v = p0.getDeclaredField("a").get(null);
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "U1.p0.a failed, try i2.E.i", t);
                }
                if (v == null) {
                    try {
                        v = pinManagerClsCached().getDeclaredField("i").get(sPinManager);
                    } catch (Throwable t) {
                        log(android.util.Log.WARN, TAG, "i2.E.i failed", t);
                    }
                }
                if (v == null) return false;

                sAssistant = v;
                Field wf = v.getClass().getDeclaredField("a");
                wf.setAccessible(true);
                sWidgetListField = wf;

                for (Method m : v.getClass().getDeclaredMethods()) {
                    if (!"o".equals(m.getName())) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (!"U1.q0".equals(m.getParameterTypes()[0].getName())) continue;
                    m.setAccessible(true);
                    sUpdateWidget = m;
                    break;
                }
                log(android.util.Log.INFO, TAG, "SmartAssistantManager resolved from "
                        + v.getClass().getName() + ", updateWidget=" + sUpdateWidget);
            }
            return sPinManager != null && sAssistant != null
                    && sWidgetListField != null && sUpdateWidget != null;
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "ensureSingletons failed", t);
            return false;
        }
    }

    // ------------------------------------------------------------ 渲染 Hook

    /**
     * 诊断用：观察 a()/b() 的判定结果，定位通知在哪一步被拦。
     */
    private void installNlsGateTraceHook() {
        try {
            Class<?> cls = Class.forName(
                    "com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService",
                    true, mCl);
            for (String name : new String[]{"a", "b"}) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (!name.equals(m.getName())) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                    final String tag2 = name;
                    hook(m).intercept(chain -> {
                        Object r = chain.proceed();
                        try {
                            Object sbn = chain.getArg(0);
                            Object pkg = sbn.getClass().getMethod("getPackageName").invoke(sbn);
                            if ("com.xiaomi.subscreencenter".equals(pkg)) {
                                log(android.util.Log.INFO, TAG, "GATE " + tag2 + "() -> " + r
                                        + (("b".equals(tag2) && !Boolean.TRUE.equals(r)) ? " (FORCED TRUE)" : ""));
                            }
                        } catch (Throwable ignored) {}
                        // b() 额外要求 isPromotedOngoing()==true，
                        // 那是系统内部标志，普通 App 无法设置。这里直接放行，
                        // 后续 m2.b.b() 仍会做真实的 Style 解析。
                        if ("b".equals(tag2) && !Boolean.TRUE.equals(r)) {
                            return Boolean.TRUE;
                        }
                        return r;
                    });
                    log(android.util.Log.INFO, TAG, "HOOKED GATE " + name + "()");
                }
            }
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "installNlsGateTraceHook failed", t);
        }
    }

    /**
     * 诊断用：观察 m2.b.b(Context, StatusBarNotification) -> Bundle 的返回值。
     * 返回 null 表示副屏中心认为该通知无法转成 widget，会被直接丢弃。
     */
    private void installWidgetParseTraceHook() {
        try {
            Class<?> cls = Class.forName("m2.b", true, mCl);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"b".equals(m.getName())) continue;
                if (m.getParameterCount() != 2) continue;
                if (m.getReturnType() != Bundle.class) continue;
                target = m;
                break;
            }
            if (target == null) {
                log(android.util.Log.ERROR, TAG, "m2.b.b not found");
                return;
            }
            hook(target).intercept(chain -> {
                Object result = null;
                try {
                    Object sbn = chain.getArg(1);
                    String pkg = "?";
                    try {
                        pkg = String.valueOf(sbn.getClass().getMethod("getPackageName").invoke(sbn));
                    } catch (Throwable ignored) {}
                    result = chain.proceed();
                    if ("com.xiaomi.subscreencenter".equals(pkg)) {
                        if (result instanceof Bundle) {
                            Bundle b = (Bundle) result;
                            StringBuilder sb = new StringBuilder();
                            for (String k : b.keySet()) {
                                Object v = b.get(k);
                                String sv = String.valueOf(v);
                                if (sv.length() > 80) sv = sv.substring(0, 80) + "...";
                                sb.append(k).append("=").append(sv).append(" | ");
                            }
                            log(android.util.Log.INFO, TAG, "PARSE_OK " + sb);
                        } else {
                            log(android.util.Log.WARN, TAG, "PARSE_NULL -> 通知被丢弃");
                        }
                    }
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "parse trace failed", t);
                }
                return result;
            });
            log(android.util.Log.INFO, TAG, "HOOKED m2.b.b -> widget parse trace active");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "installWidgetParseTraceHook failed", t);
        }
    }

    /**
     * 诊断用：记录 NLS.onNotificationPosted 是否被调用，以及通知的关键 extras。
     * 只观察、不修改行为。
     */
    private void installNlsTraceHook() {
        try {
            Class<?> cls = Class.forName(
                    "com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService",
                    true, mCl);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"onNotificationPosted".equals(m.getName())) continue;
                if (m.getParameterCount() != 1 && m.getParameterCount() != 2) continue;
                target = m;
                break;
            }
            if (target == null) {
                log(android.util.Log.ERROR, TAG, "NLS.onNotificationPosted not found");
                return;
            }
            final int argc = target.getParameterCount();
            hook(target).intercept(chain -> {
                try {
                    Object sbn = argc == 1 ? chain.getArg(0) : chain.getArg(0);
                    if (sbn != null) {
                        Class<?> c = sbn.getClass();
                        Object pkg = c.getMethod("getPackageName").invoke(sbn);
                        Object id = c.getMethod("getId").invoke(sbn);
                        Object notif = c.getMethod("getNotification").invoke(sbn);
                        String extraKeys = "";
                        String focusParam = "";
                        if (notif != null) {
                            Object ex = notif.getClass().getField("extras").get(notif);
                            if (ex instanceof Bundle) {
                                Bundle b = (Bundle) ex;
                                extraKeys = b.keySet().toString();
                                Object fp = b.get("miui.focus.param");
                                if (fp != null) focusParam = String.valueOf(fp);
                            }
                        }
                        log(android.util.Log.INFO, TAG, "NLS_RECV pkg=" + pkg + " id=" + id
                                + " keys=" + extraKeys
                                + (focusParam.isEmpty() ? "" : " FOCUS=" + focusParam));
                    }
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "NLS trace failed", t);
                }
                return chain.proceed();
            });
            log(android.util.Log.INFO, TAG, "HOOKED NLS.onNotificationPosted -> trace active");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "installNlsTraceHook failed", t);
        }
    }

    /**
     * Hook com.miui.maml.util.ZipResourceLoader.<init>(String,String,String)
     *
     * 背景：smart_assistant 下的模板每次副屏中心启动都会被重新从 APK 释放，
     * 直接改 live_update_progress 文件会被覆盖（已验证 md5 回滚到原版）。
     * 所以改在「读取前」重定向：同目录放 rp_<原名>，存在就用它。
     * 同目录保证副屏中心有读权限，且名字不在释放列表里不会被覆盖。
     */
    private static final String TPL_DIR =
            "/data/local/tmp/rp_tpl";

    private void installMamlTemplateHook() {
        try {
            Class<?> cls = Class.forName("com.miui.maml.util.ZipResourceLoader", true, mCl);
            int n = 0;
            // hook 全部 1/2/3 参数构造器（双参数构造器会委托到三参数，但
            // LSPosed 对委托链的参数改写不保证生效，所以逐个挂）
            for (java.lang.reflect.Constructor<?> c : cls.getDeclaredConstructors()) {
                int pc = c.getParameterCount();
                if (pc < 1 || pc > 3) continue;
                if (c.getParameterTypes()[0] != String.class) continue;
                hook(c).intercept(chain -> rewriteTemplateArg(chain));
                n++;
            }
            log(android.util.Log.INFO, TAG,
                    "HOOKED ZipResourceLoader x" + n + " -> template redirect");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "installMamlTemplateHook failed", t);
        }
        installWidgetSpecPathHook();
    }

    /**
     * Hook j2/a.f()Ljava/lang/String; —— widget 的模板路径来源。
     * q2/v.g(Lj2/a) 里 `v0 = p0.f()` 拿到路径后直接 new q2/k(v0,...)，
     * 这是模板加载的真正入口（不走 ZipResourceLoader 时也能拦住）。
     */
    /**
     * Hook m2.d.i(String,String)Ljava/lang/String; —— 模板路径的唯一解析出口。
     *
     * 反编译依据：
     *   m2/d.smali 801/839/854 三处都是
     *     sget-object p1, Lm2/a;->c   （静态映射 business -> 模板路径）
     *     invoke-interface {p1, p0}, Ljava/util/Map;->get(...)
     *   而 m2/a.a() 并没有被调用，所以只能在 i() 的返回值上改写。
     */
    private void installWidgetSpecPathHook() {
        installStaticTablePatch();
        try {
            Class<?> cls = Class.forName("m2.d", true, mCl);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"i".equals(m.getName())) continue;
                if (m.getParameterCount() != 2) continue;
                if (m.getReturnType() != String.class) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                target = m;
                break;
            }
            if (target == null) {
                log(android.util.Log.ERROR, TAG, "m2.d.i(String,String) not found");
                return;
            }
            hook(target).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    log(android.util.Log.INFO, TAG, "m2.d.i -> " + r);
                    if (r instanceof String) {
                        String custom = mapTemplatePath((String) r);
                        if (custom != null) {
                            log(android.util.Log.INFO, TAG,
                                    "PATH redirect: " + r + " -> " + custom);
                            return custom;
                        }
                    }
                } catch (Throwable ignored) {
                }
                return r;
            });
            log(android.util.Log.INFO, TAG, "HOOKED m2.d.i -> template path redirect");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "installWidgetSpecPathHook failed", t);
        }
    }

    /**
     * 直接改静态映射表 m2/a.c：business -> 模板路径。
     * m2/a.<clinit> 用 unmodifiableMap 包装，所以反射取出后新建可写 Map 再塞回去。
     * 这样无论上游从哪条路取路径，拿到的都是我们的 rp_ 模板。
     */
    private void installStaticTablePatch() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 40; i++) {
                try {
                    Class<?> a = Class.forName("m2.a", true, mCl);
                    java.lang.reflect.Field f = a.getDeclaredField("c");
                    f.setAccessible(true);
                    Object cur = f.get(null);
                    if (cur instanceof java.util.Map) {
                        java.util.Map<String, String> src = new java.util.HashMap<>();
                        for (Object o : ((java.util.Map<?, ?>) cur).entrySet()) {
                            java.util.Map.Entry<?, ?> e = (java.util.Map.Entry<?, ?>) o;
                            if (e.getKey() instanceof String && e.getValue() instanceof String) {
                                src.put((String) e.getKey(), (String) e.getValue());
                            }
                        }
                        boolean changed = false;
                        java.util.Map<String, String> dst = new java.util.HashMap<>(src);
                        for (java.util.Map.Entry<String, String> e : src.entrySet()) {
                            String custom = mapTemplatePath(e.getValue());
                            if (custom != null) {
                                dst.put(e.getKey(), custom);
                                changed = true;
                                log(android.util.Log.INFO, TAG, "TABLE patch: "
                                        + e.getKey() + " -> " + custom);
                            }
                        }
                        if (changed) {
                            java.lang.reflect.Field mod = null;
                            try {
                                mod = java.util.Map.class.getDeclaredField("modifiers");
                                mod.setAccessible(true);
                            } catch (Throwable ignored) {
                            }
                            f.set(null, java.util.Collections.unmodifiableMap(dst));
                            log(android.util.Log.INFO, TAG, "TABLE patched, entries=" + dst.size());
                            return;
                        }
                        log(android.util.Log.INFO, TAG,
                                "TABLE ready but no rp_ template found yet (try " + i + ")");
                    }
                } catch (Throwable e) {
                    if (i % 10 == 0) {
                        log(android.util.Log.WARN, TAG, "TABLE wait " + i + " : " + e);
                    }
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            log(android.util.Log.WARN, TAG, "TABLE patch gave up");
        }, "rp-table-patch");
        t.setDaemon(true);
        t.start();
    }

    private Object rewriteTemplateArg(io.github.libxposed.api.XposedInterface.Chain chain)
            throws Throwable {
        try {
            java.util.List<Object> args = chain.getArgs();
            if (!args.isEmpty() && args.get(0) instanceof String) {
                String orig = (String) args.get(0);
                String custom = mapTemplatePath(orig);
                if (custom != null) {
                    Object[] na = args.toArray();
                    na[0] = custom;
                    log(android.util.Log.INFO, TAG,
                            "MAML redirect: " + orig + " -> " + custom);
                    return chain.proceed(na);
                }
            }
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "rewriteTemplateArg err", t);
        }
        return chain.proceed();
    }

    /** .../smart_assistant/live_update_progress -> 同目录 rp_live_update_progress
     *  注意：表里的原始值可能是 ".../users/$user_id/subscreencenter/..." 这种字面量，
     *  也可能已被替换成真实用户号，两种都要认。 */
    private static String mapTemplatePath(String orig) {
        if (orig == null) return null;
        // 表里的值是字面量 ".../users/$user_id/..."，需要展开成真实用户目录
        String real = orig.replace("$user_id", "0");
        int idx = real.lastIndexOf("smart_assistant/");
        if (idx < 0) return null;
        String tail = real.substring(idx + "smart_assistant/".length());
        if (tail.isEmpty() || tail.contains("/")) return null;
        File f = new File(TPL_DIR, tail);
        return f.exists() ? f.getAbsolutePath() : null;
    }

    /**
     * Hook k2.I.i()Z —— 副屏中心的「实时更新总开关」。
     * NLS.onNotificationPosted 里 d() 调用它，返回 false 时直接 SWITCH_OFF，
     * 任何焦点通知都会被丢弃。开关存在 personalassistant 的 provider 里，
     * 我们直接让它恒为 true，绕过设置层。
     */
    private void installLiveUpdateSwitchHook() {
        try {
            Class<?> cls = Class.forName("k2.I", true, mCl);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"i".equals(m.getName())) continue;
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class) continue;
                target = m;
                break;
            }
            if (target == null) {
                log(android.util.Log.ERROR, TAG, "k2.I.i() not found");
                return;
            }
            final Method t = target;
            hook(t).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    boolean orig = Boolean.TRUE.equals(result);
                    if (!orig) {
                        log(android.util.Log.INFO, TAG, "live update switch was OFF, forced ON");
                    }
                } catch (Throwable ignored) {}
                return Boolean.TRUE;
            });
            log(android.util.Log.INFO, TAG, "HOOKED k2.I.i -> live update switch forced ON");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "installLiveUpdateSwitchHook failed", t);
        }
    }

    /**
     * Hook m2.d.v(Lj2/a, Z) -> Bundle
     * 这是 j2/a 转 U1/q0.l 的唯一转换点，每次渲染都会经过。
     * 在返回值里替换 pin_message，保证背屏永远拿到最新文字。
     */
    private void installRenderHook() {
        try {
            Class<?> md = Class.forName("m2.d", true, mCl);
            Method target = null;
            for (Method m : md.getDeclaredMethods()) {
                if (!"v".equals(m.getName())) continue;
                if (m.getParameterCount() != 2) continue;
                if (!"j2.a".equals(m.getParameterTypes()[0].getName())) continue;
                if (m.getParameterTypes()[1] != boolean.class) continue;
                target = m;
                break;
            }
            if (target == null) {
                log(android.util.Log.ERROR, TAG, "m2.d.v not found");
                return;
            }
            final Method t = target;
            hook(t).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    String text = sLastRead;
                    if (text != null && !text.isEmpty() && result instanceof Bundle) {
                        Bundle b = (Bundle) result;
                        Object cur = b.get("pin_message");
                        if (cur instanceof String && ((String) cur).startsWith(MARKER)) {
                            b.putString("pin_message", MARKER + " " + text);
                        }
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
            log(android.util.Log.INFO, TAG, "HOOKED m2.d.v -> render intercept active");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "installRenderHook failed", t);
        }
    }

    /**
     * Hook m2.d.i() —— 模板路径解析出口，同时在这里注入终端历史到模板变量
     */
    private void installTemplateVarHook() {
        try {
            Class<?> cls = Class.forName("m2.d", true, mCl);
            Method target = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!"i".equals(m.getName())) continue;
                if (m.getParameterCount() != 2) continue;
                if (m.getReturnType() != String.class) continue;
                target = m;
                break;
            }
            if (target == null) {
                log(android.util.Log.ERROR, TAG, "m2.d.i not found");
                return;
            }

            final Method t = target;
            hook(t).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    // 这里可以注入终端历史到模板变量
                    // 但 MAML 模板变量是通过 Bundle 传递的，需要在其他地方注入
                } catch (Throwable ignored) {}
                return r;
            });
            log(android.util.Log.INFO, TAG, "HOOKED m2.d.i -> template var hook");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "installTemplateVarHook failed", t);
        }
    }

    // ------------------------------------------------------------ Context

    /**
     * 取副屏中心 Application Context：
     * 1) AndroidApp / Application 静态实例
     * 2) U1.r0.u 字段
     * 3) 当前活动线程的 mInitialApplication
     */
    private void resolveAppContext() {
        try {
            try {
                Class<?> appCls = Class.forName("android.app.ActivityThread", true, mCl);
                Method cur = appCls.getDeclaredMethod("currentActivityThread");
                cur.setAccessible(true);
                Object at = cur.invoke(null);
                if (at != null) {
                    Method ga = appCls.getDeclaredMethod("getApplication");
                    ga.setAccessible(true);
                    Object app = ga.invoke(at);
                    if (app instanceof android.content.Context) {
                        sAppContext = (android.content.Context) app;
                        log(android.util.Log.INFO, TAG, "context from ActivityThread: "
                                + ((android.content.Context) app).getPackageName());
                        return;
                    }
                }
            } catch (Throwable ignored) {}

            if (sAssistant != null) {
                try {
                    Field uf = sAssistant.getClass().getDeclaredField("u");
                    uf.setAccessible(true);
                    Object c = uf.get(sAssistant);
                    if (c instanceof android.content.Context) {
                        sAppContext = (android.content.Context) c;
                        log(android.util.Log.INFO, TAG, "context from U1.r0.u");
                    }
                } catch (Throwable ignored) {}
            } else {
                // 直接从 U1.p0.a 取单例，不经 ensureSingletons 的全链路校验
                try {
                    Class<?> p0 = Class.forName("U1.p0", true, mCl);
                    Object v = p0.getDeclaredField("a").get(null);
                    if (v != null) {
                        sAssistant = v;
                        Field uf = v.getClass().getDeclaredField("u");
                        uf.setAccessible(true);
                        Object c = uf.get(v);
                        if (c instanceof android.content.Context) {
                            sAppContext = (android.content.Context) c;
                            log(android.util.Log.INFO, TAG, "context from U1.p0.a.u");
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "resolveAppContext failed", t);
        }
    }

    // ------------------------------------------------------------ 轮询

    private void startPoller() {
        // 新版：进度文件由 Operit 进程的 OperitHook 监听，副屏中心不再轮询
        if (true) return;
        if (sStarted) return;
        sStarted = true;

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final Handler main = new Handler(Looper.getMainLooper());
                while (true) {
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        final String text = readProgress();
                        if (text == null || text.isEmpty()) continue;
                        if (text.equals(sLastApplied)) continue;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                applyProgress(text);
                            }
                        });
                    } catch (Throwable e) {
                        log(android.util.Log.ERROR, TAG, "poll error", e);
                    }
                }
            }
        }, "RearProgressPoller");
        t.setDaemon(true);
        t.start();
        log(android.util.Log.INFO, TAG, "poller started (" + POLL_MS + "ms)");
    }

    // ------------------------------------------------------------ 应用进度

    private void pushFocusNotify(String text) {
        try {
            if (sAppContext == null) resolveAppContext();
            if (sAppContext == null) {
                log(android.util.Log.WARN, TAG, "no context, skip focus notify");
                return;
            }
            FocusNotify.init(sAppContext);
            FocusNotify.push(text, -1);
            log(android.util.Log.INFO, TAG, "focus notify pushed -> " + text);
        } catch (Throwable t) {
            log(android.util.Log.WARN, TAG, "pushFocusNotify failed", t);
        }
    }

    private void applyProgress(String text) {
        try {
            // 焦点通知通道不依赖副屏内部结构，先独立推一次
            pushFocusNotify(text);

            if (!ensureSingletons()) {
                log(android.util.Log.WARN, TAG, "singletons not ready");
                return;
            }

            boolean changed = false;

            // 1) PinManager 内存列表：j2.a.d 是 Bundle（文字在 __PIN_CONTENT_TEXT__）
            try {
                Object pinListObj = sPinListField.get(sPinManager);
                if (pinListObj instanceof List) {
                    for (Object item : (List<?>) pinListObj) {
                        if (item == null) continue;
                        Field df = item.getClass().getDeclaredField("d");
                        df.setAccessible(true);
                        Object bundleObj = df.get(item);
                        if (bundleObj instanceof Bundle && applyToBundle((Bundle) bundleObj, text)) {
                            changed = true;
                            log(android.util.Log.INFO, TAG, "pin item rewritten");
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                log(android.util.Log.WARN, TAG, "pin list rewrite failed", t);
            }
// 2) 触发重绘：I() -> g0.a() -> r0.f() -> m2/d 转换(pin_message) -> D/c.P() 推屏
            //    前提：第 1 步已改好 j2.a.d["__PIN_CONTENT_TEXT__"]，f() 会重新读它
            if (changed) {
                try {
                    if (sRefreshMethod == null) {
                        sRefreshMethod = sAssistant.getClass().getDeclaredMethod("I");
                        sRefreshMethod.setAccessible(true);
                    }
                    sRefreshMethod.invoke(sAssistant);
                    log(android.util.Log.INFO, TAG, "I() refresh invoked");
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "I() refresh failed", t);
                }
            }

            // 3) 【已禁用】落盘逻辑有问题：当 pin 列表为空时会把 [] 写进磁盘，导致数据丢失。
            //    在彻底搞清 U1.d0 与 i2.E.e 的同步关系前，不允许调用 persist。
            if (false) {
                try {
                    Object pinListObj = sPinListField.get(sPinManager);
                    if (changed && pinListObj instanceof ArrayList
                            && !((ArrayList<?>) pinListObj).isEmpty()) {
                        if (sPersistence == null) {
                            Class<?> c0 = Class.forName("U1.c0", true, mCl);
                            sPersistence = c0.getDeclaredField("a").get(null);
                            if (sPersistence != null) {
                                sPersistMethod = sPersistence.getClass()
                                        .getDeclaredMethod("i", ArrayList.class);
                                sPersistMethod.setAccessible(true);
                            }
                        }
                        if (sPersistMethod != null && sPersistence != null) {
                            sPersistMethod.invoke(sPersistence, pinListObj);
                            log(android.util.Log.INFO, TAG, "persisted to pin_info.json");
                        }
                    }
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "persist failed", t);
                }
            }

            if (changed) {
                sLastApplied = text;
                log(android.util.Log.INFO, TAG, "progress applied -> " + text);
                // 走焦点通知通道：系统原生同 ID 静默更新，不依赖副屏内部 UI 刷新
                try {
                    if (sAppContext != null) {
                        FocusNotify.init(sAppContext);
                        FocusNotify.push(text, -1);
                        log(android.util.Log.INFO, TAG, "focus notify pushed");
                    }
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "focus notify failed", t);
                }
            }
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "applyProgress failed", t);
        }
    }

    private boolean applyToBundle(Bundle b, String text) {
        Object cur = b.get("__PIN_CONTENT_TEXT__");
        if (!(cur instanceof String)) return false;
        String curText = (String) cur;
        if (!curText.startsWith(MARKER)) return false;

        String newText = MARKER + " " + text;
        if (newText.equals(curText)) return false;
        b.putString("__PIN_CONTENT_TEXT__", newText);
        return true;
    }

    // ------------------------------------------------------------ 读文件

    private String readProgress() {
        try {
            File f = new File(PROGRESS_FILE);
            if (!f.exists()) return null;

            long mtime = f.lastModified();
            if (mtime == sLastMtime) return sLastRead;

            StringBuilder sb = new StringBuilder();
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            } finally {
                if (br != null) try { br.close(); } catch (Throwable ignored) {}
            }
            sLastMtime = mtime;
            sLastRead = sb.toString().trim();
            return sLastRead;
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "readProgress failed", t);
            return sLastRead;
        }
    }
}