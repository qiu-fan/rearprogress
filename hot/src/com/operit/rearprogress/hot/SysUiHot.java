package com.operit.rearprogress.hot;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * SystemUI 侧热更新 hook 逻辑（外挂 dex）。
 *
 * 由模块 APK 里的引导器从 /data/local/tmp/rp_hook.dex 加载，
 * 因此改这里的代码只需重新生成 dex + 重启 SystemUI，不需要重装模块、不需要重启设备。
 *
 * 设计纪律：
 *  - findClass 里只做「记录 + 起一次性线程」，绝不加载类 / 不反射 / 不写日志刷屏；
 *  - 所有拦截器 try/catch 包死，异常一律走原逻辑。
 */
public final class SysUiHot {

    private static final String TAG = "RearProgress";
    private static final String TARGET = "miui.systemui.notification.NotificationSettingsManager";

    private static XposedModule sModule;
    private static final Set<Object> sTried = ConcurrentHashMap.newKeySet();

    private SysUiHot() {}

    /** 引导器入口 */
    public static void install(XposedModule module, ClassLoader sysUiCl) {
        sModule = module;
        Log.i(TAG, "HOT v1 loaded, sysUiCl=" + (sysUiCl == null ? "null" : sysUiCl.getClass().getName()));
        watchPluginLoader(sysUiCl);
        startDiagThread();
    }

    /**
     * 监听插件类加载，拿到插件自己的 ClassLoader。
     * 这个位置在 ClassLoader.findClass 的关键路径上，只允许做最轻的事。
     */
    private static void watchPluginLoader(ClassLoader sysUiCl) {
        try {
            Class<?> filter = Class.forName(
                    "com.android.systemui.shared.plugins.PluginInstance$ClassLoaderFilter",
                    false, sysUiCl);
            int hooked = 0;
            for (Method m : filter.getDeclaredMethods()) {
                if (!"findClass".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                try {
                    sModule.hook(m).intercept(chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (self != null && sTried.add(self)) kick(self);
                        } catch (Throwable ignored) {}
                        return chain.proceed();
                    });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "HOT watcher installed: " + hooked);
        } catch (Throwable t) {
            Log.w(TAG, "HOT watcher failed: " + t);
        }
    }

    private static void kick(final Object filterObj) {
        try {
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                    hookFocus(filterObj);
                } catch (Throwable ignored) {}
            }, "rp-hot-hook").start();
        } catch (Throwable ignored) {}
    }

    /** 从 filter 实例（及其内部 ClassLoader 字段）里找出真正能加载插件类的 loader */
    private static void hookFocus(Object filterObj) {
        Class<?> cls = null;
        String used = "none";
        for (ClassLoader cl : loaders(filterObj)) {
            try {
                cls = Class.forName(TARGET, true, cl);
                used = cl.getClass().getName();
                break;
            } catch (Throwable ignored) {}
        }
        if (cls == null) {
            Log.w(TAG, "HOT no loader carries " + TARGET);
            return;
        }
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
                        Log.i(TAG, "HOT " + nm + "(" + arg + ") -> " + r);
                    } catch (Throwable ignored) {}
                    return r;
                });
                n++;
            } catch (Throwable ignored) {}
        }
        Log.i(TAG, "HOT focus hooks installed: " + n + " via " + used);
    }

    private static List<ClassLoader> loaders(Object filterObj) {
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

    private static void startDiagThread() {
        try {
            new Thread(() -> {
                try {
                    Thread.sleep(10000);
                    Log.i(TAG, "HOT diag: filters=" + sTried.size());
                } catch (Throwable ignored) {}
            }, "rp-hot-diag").start();
        } catch (Throwable ignored) {}
    }
}
