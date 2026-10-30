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
        // 让"应用在前台就屏蔽岛"的判定永远为 false
        try {
            hookBlockTopActivity(sysUiCl);
        } catch (Throwable t) {
            wlog("HOT block hook threw: " + t);
        }
        // 让"应用内/小窗时也要有岛"的请求返回 true
        try {
            hookForceBoolean(sysUiCl,
                    "com.android.systemui.statusbar.notification.DynamicIslandPluginController",
                    "requestHasIslandInAppOrMiniWindow", true);
        } catch (Throwable t) {
            wlog("HOT request hook threw: " + t);
        }
        if (!direct) {
            try {
                hookPluginFactory(sysUiCl);
            } catch (Throwable t) {
                wlog("HOT factory hook threw: " + t);
            }
        }
        // 岛右侧文字换自定义字体
        try {
            hookRightTextFont(sysUiCl);
        } catch (Throwable t) {
            wlog("HOT rightTextFont hook threw: " + t);
        }
        // 【1.8.1】焦点卡渲染日志注入：直接记录岛实际显示的文本（用户要求拿一手显示内容，
        //   不再靠描述）。hook TextView.setText，调用堆栈含 focus/island 才记 —— 免精确类名。
        try {
            hookFocusRenderLog();
        } catch (Throwable t) {
            wlog("HOT renderLog hook threw: " + t);
        }
    }

    /** 焦点卡/岛渲染文本日志 + 【动效引擎】跳点视图捕获 */
    private static void hookFocusRenderLog() throws Exception {
        Method setText = android.widget.TextView.class.getMethod("setText", CharSequence.class);
        sModule.hook(setText).intercept(chain -> {
            Object r = chain.proceed();
            try {
                CharSequence t = (CharSequence) chain.getArg(0);
                if (t != null && t.length() > 0) {
                    String ts = t.toString();
                    // 内容排噪：岛时钟/纯时间不记
                    if (ts.matches("\\s*\\d{1,2}:\\d{2}.*")) return r;
                    StackTraceElement[] st = new Throwable().getStackTrace();
                    for (StackTraceElement e : st) {
                        String cn = e.getClassName();
                        if (cn.contains("focus") || cn.contains("Focus")
                                || cn.contains("Island") || cn.contains("island")
                                || cn.contains("otification") || cn.contains("emoteView")) {
                            Object v = chain.getThisObject();
                            String who = v != null ? v.getClass().getSimpleName() : "?";
                            // 【动效引擎】捕获岛 title 视图（内容为 Thinking* 的那一个），供直接驱动跳点
                            if (v instanceof android.widget.TextView && ts.startsWith("Thinking")) {
                                sThinkingView = (android.widget.TextView) v;
                                startDotsEngine();
                            }
                            String line = "SYSUI_SHOW [" + who + "] " + t;
                            Log.i(TAG, line);
                            wlog(line);
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return r;
        });
        wlog("HOT focusRenderLog hooked (TextView.setText)");
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
                                // 插件 loader 里也有岛的 ViewHolder，一并 hook 字体
                                hookRightTextFont(pcl);
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
        if (hooked) {
            // 应用内/小窗也允许岛（真正的判定点在这个 Coordinator 上）
            hookForceBoolean(cl,
                    "miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator",
                    "requestHasIslandInAppOrMiniWindow", true);
        }
        return hooked;
    }

    /** 通用：把名字含 keyword 的 boolean 方法强制返回 value */
    private static void hookForceBoolean(ClassLoader cl, String cls, String keyword, boolean value) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().contains(keyword)) continue;
                if (m.getReturnType() != boolean.class
                        && !m.getReturnType().getName().equals("java.lang.Boolean")) continue;
                try {
                    sModule.hook(m).intercept(chain -> value);
                    n++;
                    wlog("HOT hooked " + m.getName() + " -> " + value);
                } catch (Throwable t) {
                    wlog("HOT hook " + m.getName() + " failed: " + t);
                }
            }
            wlog("HOT " + keyword + " hooks=" + n);
        } catch (Throwable t) {
            wlog("HOT " + cls + " missing: " + t);
        }
    }

    /** 把"应用在前台就屏蔽岛"的判定改成 false */
    private static void hookBlockTopActivity(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.android.systemui.statusbar.notification.DynamicIslandSceneHandler",
                    false, cl);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                String nm = m.getName();
                if (!nm.contains("shouldBlockTopActivity")) continue;
                if (m.getReturnType() != boolean.class) continue;
                try {
                    sModule.hook(m).intercept(chain -> false);
                    n++;
                    wlog("HOT hooked " + nm + " -> false");
                } catch (Throwable t) {
                    wlog("HOT hook " + nm + " failed: " + t);
                }
            }
            wlog("HOT shouldBlockTopActivity hooks=" + n);
        } catch (Throwable t) {
            wlog("HOT SceneHandler missing: " + t);
        }
    }

    /**
     * 岛右侧文字换自定义字体（徕卡改造版：数字等宽 + K/M/B 窄）。
     * 照抄 HyperIsland IslandFontTuningHook 的 hook 思路，
     * 但只针对 IslandRightTextViewHolder（右侧文字）。
     */
    private static android.graphics.Typeface sCustomFont;

    private static android.graphics.Typeface loadCustomFont() {
        if (sCustomFont != null) return sCustomFont;
        // 优先从 KSU 模块读，其次从 sdcard 读
        String[] paths = {
            "/system/fonts/rp_leica_mono.otf",
            "/sdcard/Android/code/rp_leica_mono.otf",
        };
        for (String p : paths) {
            try {
                java.io.File f = new java.io.File(p);
                if (f.exists()) {
                    sCustomFont = android.graphics.Typeface.createFromFile(f);
                    wlog("HOT custom font loaded from " + p
                        + " weight=" + sCustomFont.getWeight());
                    return sCustomFont;
                }
            } catch (Throwable t) {
                wlog("HOT font load failed " + p + ": " + t);
            }
        }
        wlog("HOT custom font NOT FOUND, using system default");
        return null;
    }

    private static void hookRightTextFont(ClassLoader cl) {
        android.graphics.Typeface font = loadCustomFont();
        if (font == null) return;

        // 右侧文字 Holder（照抄 HyperIsland IslandTextAlignHook 的 HOLDER_CLASSES）
        String[] holderClasses = {
            "miui.systemui.dynamicisland.module.IslandRightTextViewHolder",
            "miui.systemui.dynamicisland.module.IslandTextViewHolder",
        };
        // 文本字段名（照抄 HyperIsland IslandFontTuningHook 的 TEXT_FIELD_NAMES）
        String[] fieldNames = {"title", "frontTitle", "content", "titleView"};

        int hooked = 0;
        for (String clsName : holderClasses) {
            try {
                Class<?> clazz = Class.forName(clsName, false, cl);
                // 解析文本字段
                java.util.List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
                for (String fn : fieldNames) {
                    try {
                        java.lang.reflect.Field f = clazz.getDeclaredField(fn);
                        f.setAccessible(true);
                        fields.add(f);
                    } catch (NoSuchFieldException ignored) {}
                }
                if (fields.isEmpty()) {
                    wlog("HOT " + clsName + " no text fields found");
                    continue;
                }
                // hook bind / updatePartial（照抄 HyperIsland hookTextBind）
                for (Method m : clazz.getDeclaredMethods()) {
                    if (!m.getName().equals("bind") && !m.getName().equals("updatePartial")) continue;
                    if (m.getParameterTypes().length != 2) continue;
                    try {
                        sModule.hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object holder = chain.getThisObject();
                                for (java.lang.reflect.Field f : fields) {
                                    Object v = f.get(holder);
                                    if (v instanceof android.widget.TextView) {
                                        android.widget.TextView tv = (android.widget.TextView) v;
                                        tv.setTypeface(font);
                                    }
                                }
                            } catch (Throwable ignored) {}
                            return result;
                        });
                        hooked++;
                        wlog("HOT hooked " + clsName + "#" + m.getName() + " -> custom font");
                    } catch (Throwable t) {
                        wlog("HOT hook " + clsName + "#" + m.getName() + " failed: " + t);
                    }
                }
            } catch (ClassNotFoundException ignored) {
                wlog("HOT " + clsName + " not found");
            } catch (Throwable t) {
                wlog("HOT " + clsName + " error: " + t);
            }
        }
        wlog("HOT rightTextFont hooks=" + hooked);
    }

    /** 只打印方法签名，不做任何改写（探针，安全） */
    private static void probeClass(ClassLoader cl, String cls) {
        try {
            Class<?> c = Class.forName(cls, false, cl);
            Method[] ms = c.getDeclaredMethods();
            wlog("HOT PROBE " + cls + " methods=" + ms.length);
            for (Method m : ms) {
                StringBuilder line = new StringBuilder("HOT M " + m.getName() + "(");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) line.append(',');
                    line.append(ps[i].getSimpleName());
                }
                line.append(") -> ").append(m.getReturnType().getSimpleName());
                wlog(line.toString());
            }
        } catch (Throwable t) {
            wlog("HOT probe miss " + cls + ": " + t);
        }
    }

    // ================= 【1.8.3-dev 动效引擎】=================
    // 用户方向：直接更新岛的文本（SystemUI 进程内），不更新通知 → 不触发 requestFocusNotificationView
    // 重建 → 跳点每秒变也不闪。通知侧 title 保持固定 "Thinking"。
    private static volatile android.widget.TextView sThinkingView = null;
    private static volatile boolean sDotsEngineOn = false;

    private static void startDotsEngine() {
        if (sDotsEngineOn) return;
        sDotsEngineOn = true;
        Thread t = new Thread(() -> {
            wlog("HOT dots engine started");
            while (true) {
                try { Thread.sleep(1000L); } catch (Throwable ignored) {}
                try {
                    android.widget.TextView v = sThinkingView;
                    if (v == null) continue;
                    long sec = System.currentTimeMillis() / 1000L;
                    int n = (int) (sec % 3) + 1;
                    StringBuilder sb = new StringBuilder("Thinking");
                    for (int i = 0; i < n; i++) sb.append('.');
                    final String txt = sb.toString();
                    v.post(() -> { try { v.setText(txt); } catch (Throwable ignored) {} });
                } catch (Throwable ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
