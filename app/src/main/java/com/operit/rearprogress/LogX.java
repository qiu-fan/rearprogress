package com.operit.rearprogress;

/** 统一日志出口：写 LSPosed 能抓到的 tag，也写 logcat */
final class LogX {
    private LogX() {}

    static void i(String tag, String msg) {
        android.util.Log.i(tag, msg);
        // LSPosed 的 verbose 日志会捕获 XposedModule.log，但我们拿不到实例，
        // 用文件兜底：/data/local/tmp/rearprogress.log（便于排查）
        append(tag + " I " + msg);
    }

    static void w(String tag, String msg) {
        android.util.Log.w(tag, msg);
        append(tag + " W " + msg);
    }

    static void e(String tag, String msg, Throwable t) {
        android.util.Log.e(tag, msg, t);
        append(tag + " E " + msg + " :: " + (t == null ? "" : t.toString()));
    }

    private static volatile String sPath;

    private static String path() {
        if (sPath != null) return sPath;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                java.io.File f = new java.io.File(
                        ((android.content.Context) app).getFilesDir(), "rearprogress.log");
                sPath = f.getAbsolutePath();
                return sPath;
            }
        } catch (Throwable ignored) {}
        sPath = "/data/local/tmp/rearprogress.log";
        return sPath;
    }

    private static void append(String line) {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(path(), true);
            fw.write(System.currentTimeMillis() + " " + line + "\n");
            fw.close();
        } catch (Throwable ignored) {}
    }
}