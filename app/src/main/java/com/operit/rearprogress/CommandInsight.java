package com.operit.rearprogress;

/**
 * 从工具命令原文里识别"当前在干什么"以及关键参数（版本号），供灵动岛大岛左右两栏显示。
 *
 * 识别是纯文本正则，不需要 hook：
 *   左栏 = action()  动作标签（BUILD / INSTALL / GIT / INSPECT ...）
 *   右栏 = meta()    版本号（v1.2.4），识别不到时由调用方回落 token
 *
 * 版本号两条来源：
 *   1) 命令原文里的 vX.Y.Z（如 git tag v1.2.4、cp app-1.2.4.apk）
 *   2) BUILD/INSTALL 动作时读工作区 app/build.gradle.kts 的 versionName（缓存 60s）
 */
final class CommandInsight {

    private CommandInsight() {}

    // 动作规则：顺序即优先级（INSTALL 先于 BUILD，因为安装命令里常含 gradle 路径）
    private static final String[][] RULES = {
            {"INSTALL",  "(?i)(pm|adb)\\s+install"},
            {"REMOVE",   "(?i)pm\\s+uninstall"},
            {"OPTIMIZE", "(?i)cmd\\s+package\\s+compile"},
            {"BUILD",    "(?i)gradlew|assembleDebug|assembleRelease|\\bjavac\\b|\\bd8\\s"},
            {"GIT",      "(?i)\\bgit\\s+(commit|push|checkout|merge|tag|add)\\b"},
            {"PACKAGE",  "(?i)\\b(zip|tar|unzip)\\b"},
            {"DEPLOY",   "(?i)\\bcp\\s+-f\\b"},
            {"RESTART",  "(?i)kill\\s+-9|force-stop|pkill"},
            {"CAPTURE",  "(?i)screencap|screenshot"},
            {"INSPECT",  "(?i)logcat|dumpsys|grep\\s|awk\\s|find\\s+-|\\bls\\s"},
            {"BUS",      "(?i)msg\\.sh|7788|curl\\s"},
            {"FS",       "(?i)\\b(mkdir|chmod|touch|rm\\s|mv\\s)\\b"},
    };

    private static final java.util.regex.Pattern VER =
            java.util.regex.Pattern.compile("v?(\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.]+)?)");

    /** 动作标签：BUILD / INSTALL / GIT ...；识别不到返回空串（调用方回落工具名） */
    static String action(String body) {
        if (body == null || body.isEmpty()) return "";
        for (String[] r : RULES) {
            if (java.util.regex.Pattern.compile(r[1]).matcher(body).find()) return r[0];
        }
        return "";
    }

    /**
     * 版本号：返回 "v1.2.4" 形式；识别不到返回空串。
     * @param ctx   用于读工作区 build.gradle.kts（可为 null，此时只用命令原文）
     * @param act   action() 的结果，决定是否去读 gradle 文件
     */
    static String meta(android.content.Context ctx, String act, String body) {
        // 1) 命令原文里已经有版本号 → 优先（如 git tag v1.2.4）
        if (body != null) {
            java.util.regex.Matcher m = VER.matcher(body);
            String last = null;
            while (m.find()) last = m.group(1);
            if (last != null) return "v" + last;
        }
        // 2) BUILD / INSTALL 时读 gradle 版本（很多构建命令本身不带版本号）
        if (ctx != null && ("BUILD".equals(act) || "INSTALL".equals(act) || "PACKAGE".equals(act))) {
            String gv = gradleVersion(ctx);
            if (!gv.isEmpty()) return "v" + gv;
        }
        return "";
    }

    // ---------------------------------------------------- gradle versionName 缓存

    private static volatile String sVer;
    private static volatile long sVerAt;

    private static String gradleVersion(android.content.Context ctx) {
        long now = System.currentTimeMillis();
        if (sVer != null && now - sVerAt < 60_000L) return sVer;
        String found = "";
        try {
            java.io.File wsRoot = new java.io.File(ctx.getFilesDir(), "workspace");
            java.io.File[] dirs = wsRoot.listFiles();
            if (dirs != null) {
                java.io.File best = null;
                long bestT = 0L;
                for (java.io.File d : dirs) {
                    java.io.File g = new java.io.File(d, "app/build.gradle.kts");
                    if (g.exists() && g.lastModified() > bestT) {
                        bestT = g.lastModified();
                        best = g;
                    }
                }
                if (best != null) {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(best));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null && sb.length() < 8192) sb.append(line);
                    r.close();
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("versionName\\s*=\\s*\"([^\"]+)\"").matcher(sb);
                    if (m.find()) found = m.group(1);
                }
            }
        } catch (Throwable ignored) {
        }
        sVer = found;
        sVerAt = now;
        return found;
    }
}
