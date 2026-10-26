package com.operit.rearprogress.hot;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;

import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * SystemUI 侧热更新 hook 逻辑 v5。
 *
 * 思路照抄 HyperIsland 的 UnlockAllFocusHook（1812z/HyperIsland）：
 *   1) 先用 SystemUI 自己的 classloader 直接找目标类（部分机型类就在主 loader）
 *   2) 找不到则 hook com.android.systemui.shared.plugins.PluginInstance$PluginFactory
 *      的 createPluginContext，从返回的 Context 里拿插件 classloader
 *   3) 在插件 loader 上把下面三个判定强制改成 true：
 *        miui.systemui.notification.NotificationSettingsManager.canShowFocus
 *        miui.systemui.notification.NotificationSettingsManager.canCustomFocus
 *        miui.systemui.notification.focus.SignatureChecker.checkSignatures
 *
 * 热更新：bash scripts/build_hot.sh → cp dex → 重启 SystemUI
 * 日志：/data/local/tmp/rp/hot.log（另有一份写 logcat）
 */
public final class SysUiHot {

    private static final String TAG = "RearProgress";
    private static final String LOGFILE = "/data/local/tmp/rp/hot.log";

    private static final String TARGET_CLASS =
            "miui.systemui.notification.NotificationSettingsManager";
    private static final String SIGNATURE_CHECKER_CLASS =
            "miui.systemui.notification.focus.SignatureChecker";
    private static final String PLUGIN_FACTORY_CLASS =
            "com.android.systemui.shared.plugins.PluginInstance$PluginFactory";

    private static XposedModule sModule;
    private static final Set<ClassLoader> sHooked =
            Collections.synchronizedSet(Collections.newSetFromMap(
                    new WeakHashMap<ClassLoader, Boolean>()));

    private SysUiHot() {}

    static void wlog(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {}
        // 走 LSPosed 自己的日志通道，不会被 logcat 缓冲冲掉
        try {
            if (sModule != null) sModule.log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {}
    }

    public static void install(XposedModule module, ClassLoader sysUiCl) {
        sModule = module;
        wlog("HOT v5 loaded");
        boolean direct = false;
        try {
            direct = hookWhitelist(sysUiCl);
        } catch (Throwable t) {
            wlog("HOT direct hook threw: " + t);
        }
        wlog("HOT direct hook = " + direct);
        if (!direct) {
            try {
                hookPluginFactory(sysUiCl);
            } catch (Throwable t) {
                wlog("HOT factory hook threw: " + t);
            }
        }
    }

    /** 从 PluginFactory.createPluginContext 的返回值里拿插件 classloader */
    private static void hookPluginFactory(ClassLoader sysUiCl) throws Exception {
        Class<?> fc = Class.forName(PLUGIN_FACTORY_CLASS, false, sysUiCl);
        int n = 0;
        for (Method m : fc.getDeclaredMethods()) {
            if (!"createPluginContext".equals(m.getName())) continue;
            try {
                sModule.hook(m).intercept(chain -> {
                    Object r = chain.proceed();
                    try {
                        if (r instanceof Context) {
                            ClassLoader pcl = ((Context) r).getClassLoader();
                            if (pcl != null && !sHooked.contains(pcl)) {
                                wlog("HOT got plugin loader, hooking whitelist");
                                hookWhitelist(pcl);
                            }
                        }
                    } catch (Throwable t) {
                        wlog("HOT plugin loader hook failed: " + t);
                    }
                    return r;
                });
                n++;
            } catch (Throwable ignored) {}
        }
        wlog("HOT createPluginContext hooks: " + n);
    }

    /** 把焦点通知的授权判定强制改成 true */
    private static boolean hookWhitelist(ClassLoader cl) {
        if (cl == null) return false;
        if (sHooked.contains(cl)) return true;
        boolean hooked = false;

        try {
            Class<?> c = Class.forName(TARGET_CLASS, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                String nm = m.getName();
                if (!"canShowFocus".equals(nm) && !"canCustomFocus".equals(nm)) continue;
                if (m.getReturnType() != boolean.class) continue;
                try {
                    sModule.hook(m).intercept(chain -> true);
                    hooked = true;
                    wlog("HOT unlocked " + nm);
                } catch (Throwable t) {
                    wlog("HOT hook " + nm + " failed: " + t);
                }
            }
        } catch (Throwable ignored) {
            // 该类在新机型上由插件 loader 加载，直接忽略
        }

        try {
            Class<?> c = Class.forName(SIGNATURE_CHECKER_CLASS, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"checkSignatures".equals(m.getName())) continue;
                if (m.getReturnType() != boolean.class) continue;
                try {
                    sModule.hook(m).intercept(chain -> true);
                    hooked = true;
                    wlog("HOT unlocked SignatureChecker.checkSignatures");
                } catch (Throwable t) {
                    wlog("HOT hook checkSignatures failed: " + t);
                }
            }
        } catch (Throwable ignored) {
        }

        if (hooked) sHooked.add(cl);
        wlog("HOT whitelist hooked=" + hooked + " loader=" + cl.getClass().getName());
        return hooked;
    }
}