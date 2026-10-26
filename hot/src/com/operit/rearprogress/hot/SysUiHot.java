package com.operit.rearprogress.hot;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * SystemUI 侧热更新 hook 逻辑（外挂 dex）v4。
 *
 * 热更新：bash scripts/build_hot.sh → cp dex → 重启 SystemUI 即生效。
 *
 * 日志：同时写 logcat 和 /data/local/tmp/rp/hot.log
 *（logcat 会被 Operit 的大量输出挤出，所以以文件为准）
 */
public final class SysUiHot {

    private static final String TAG = "RearProgress";
    private static final String TARGET = "miui.systemui.notification.NotificationSettingsManager";
    private static final String LOGFILE = "/data/local/tmp/rp/hot.log";

    private static XposedModule sModule;
    private static final Set<Object> sSeen = ConcurrentHashMap.newKeySet();

    private SysUiHot() {}

    static void wlog(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {}
        try {
            FileWriter w = new FileWriter(LOGFILE, true);
            w.write(System.currentTimeMillis() + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {}
    }

    public static void install(XposedModule module, ClassLoader sysUiCl) {
        sModule = module;
        wlog("HOT v4 loaded");
        try {
            watchLoadClass();
        } catch (Throwable t) {
            wlog("HOT watchLoadClass threw: " + t);
        }
        wlog("HOT install done");
    }

    /**
     * hook java.lang.ClassLoader.loadClass(String)：
     * 目标类真正被加载的那一刻拿到 Class，就地挂钩。
     * 其它类只多一次字符串 equals。
     */
    private static void watchLoadClass() throws Exception {
        Method m = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
        m.setAccessible(true);
        sModule.hook(m).intercept(chain -> {
            String name = null;
            try {
                Object a = chain.getArg(0);
                if (a instanceof String) name = (String) a;
            } catch (Throwable ignored) {}
            Object r = chain.proceed();
            try {
                if (TARGET.equals(name) && r instanceof Class) hookMethods((Class<?>) r);
            } catch (Throwable ignored) {}
            return r;
        });
        wlog("HOT loadClass watcher installed");
    }

    private static void hookMethods(Class<?> cls) {
        if (!sSeen.add(cls)) return;
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            final String nm = m.getName();
            if (!"canCustomFocus".equals(nm) && !"canPassXMSPermission".equals(nm)) continue;
            try {
                sModule.hook(m).intercept(chain -> {
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
                        wlog(nm + "(" + arg + ") -> " + r);
                    } catch (Throwable ignored) {}
                    return r;
                });
                n++;
            } catch (Throwable ignored) {}
        }
        wlog("hooked " + n + " methods on " + cls.getName()
                + " loader=" + cls.getClassLoader());
    }
}