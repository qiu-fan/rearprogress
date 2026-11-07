package com.operit.rearprogress;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
/**
 * 统计 Operit 中「目前进行中」的对话数。
 *
 * 口径（2026-10-27 修复）：改用 messages 表的最后消息时间，窗口 3 分钟。
 *   旧口径 chats.updatedAt >= now-5min 会把「57分钟没消息的对话」「空对话（一条消息都没有）」
 *   也算成进行中（updatedAt 被 touch 过就会更新），实测误报成 4 个。
 *   新口径只数「3分钟内真正有消息交互」的对话，实测 1 个（符合用户实际）。
 * 数据源：/data/data/com.ai.assistance.operit/databases/app_database
 * 只读打开 + 后台线程 + 10 秒缓存，避免拖慢通知构建。
 * 【3.3 A/B标签 2026-10-30】活跃对话按最后消息时间排序,头部=当前归属方;稳定字母映射
 *   (A/B/C...,活跃期保持,退出活跃释放);前缀只在≥2活跃时显示(单任务不加噪音)。
 *   归因=最后完成消息的对话(v1近似:双开交错期,长回合进行中的AI可能暂时拿不到归属)。
 */
final class ChatCounter {
    private static final String DB_PATH =
            "/data/data/com.ai.assistance.operit/databases/app_database";
    /** 判定"进行中"的时间窗口：最近 3 分钟内有消息交互 */
    private static final long WINDOW_MS = 3 * 60_000L;
    /** 【v1.5】并发标签候选窗口(身份池):10min内有消息行的对话持有字母身份,长回合不掉 */
    private static final long PREFIX_WINDOW_MS = 10 * 60_000L;
    /** 【2026-11-01 用户令】活跃窗:60s内有消息才算"正在执行"。①≥2活跃才显示前缀
     *  ②done(最新行=assistant)且非top的持有者,新对话可偷其最低字母(A执行完,新对话接A位)
     *  ③恰好1个活跃持续60s→清空全部槽位+取消显示(reset后从A重排)。 */
    private static final long FRESH_MS = 60_000L;
    /** done持有者被偷/释放前的宽限期(防连发间隙误伤) */
    private static final long DONE_GRACE_MS = 10_000L;
    /** 结果缓存时长 */
    private static final long REFRESH_MS = 10_000L;
    private static volatile int sActive = 0;
    private static volatile long sLastAt = 0L;
    private static volatile boolean sRunning = false;
    /** 最近完成消息最新的对话id（并发标签归属方,按 mx 降序头部） */
    private static volatile String sTopChatId = "";
    /** 候选窗口内对话数(≥2才显示前缀) */
    private static volatile int sCandidates = 0;
    /** 稳定字母映射: 活跃chatId -> A/B/C...（活跃期保持,退出活跃释放字母） */
    private static final LinkedHashMap<String, String> sLetterMap = new LinkedHashMap<>();
    /** 【用户令③】单活跃持续60s后进入清空态: 抑制前缀直到≥2活跃 */
    private static volatile boolean sSuppressed = false;
    /** 恰好1个活跃的起始时刻(0=当前非单活跃) */
    private static volatile long sSingleSince = 0L;
    /** messages 最后一行的 role 列名(PRAGMA探测, null=不可用→退化无偷盗) */
    private static volatile String sRoleCol = null;
    /** 【2026-11-01 用户令】原生通知"运行对话 N"实时计数(Operit内部状态,最准) */
    private static volatile int sNativeN = -1;
    private static volatile long sNativeAt = 0L;

    /** OperitHook 的 NotificationManager.notify hook 解析到"运行对话 N"时回调 */
    static void onNativeRunning(int n) {
        if (n != sNativeN) LogX.i("RearProgress", "native 运行对话 -> " + n);
        sNativeN = n;
        sNativeAt = System.currentTimeMillis();
    }
    private ChatCounter() {}

    static boolean isIdle() {
        refreshIfNeeded();
        return sActive <= 0;
    }
    /** 主通知正文用：如 "进行中：2" */
    static String text() {
        refreshIfNeeded();
        int n = displayCount();
        return n > 0 ? ("进行中：" + n) : "进行中：-";
    }
    /** 当前进行中的对话数（供副标题右侧显示；<=1 时调用方不显示） */
    static int activeCount() {
        refreshIfNeeded();
        return displayCount();
    }
    /** 【2026-11-01 用户令】显示口径: 原生"运行对话N"90s内新鲜优先(实时内部状态),
     *  过期退回DB 3min推算(长回合中途不落行会漏数,原生值就是为修这个)。
     *  isIdle/字母槽位逻辑仍走DB口径,不受影响。 */
    private static int displayCount() {
        if (System.currentTimeMillis() - sNativeAt < 90_000L && sNativeN >= 0) return sNativeN;
        return sActive;
    }
    /** 【3.3 用户令】并发对话标签前缀: ≥2个活跃时返回 "A:" 形式（头部对话的稳定字母）,
     *  单任务返回空串不加噪音。用于双AI并发时用户辨识当前画面是谁。 */
    static String activePrefix() {
        refreshIfNeeded();
        if (sCandidates < 2) return "";   // v1.5: 候选窗口(10min)≥2并发,长回合不掉出;计数仍3min口径
        if (sSuppressed) return "";          // 【用户令③】单活跃60s清空态,≥2活跃才恢复
        String top = sTopChatId;
        if (top == null || top.isEmpty()) return "";
        synchronized (sLetterMap) {
            String l = sLetterMap.get(top);
            return l == null ? "" : l + ":";
        }
    }
    private static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - sLastAt < REFRESH_MS || sRunning) return;
        sRunning = true;
        try {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        refresh();
                    } catch (Throwable ignored) {
                    } finally {
                        sLastAt = System.currentTimeMillis();
                        sRunning = false;
                    }
                }
            }, "rp-chat-counter");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            sRunning = false;
        }
    }
    private static void refresh() {
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(DB_PATH, null, SQLiteDatabase.OPEN_READONLY);
            long nowMs = System.currentTimeMillis();
            long since = nowMs - PREFIX_WINDOW_MS;   // 身份池10min;hot=3min;活跃=60s 各自另算
            // role列探测(一次): mid-turn最新行=user(assistant行回合结束才落库),done时最新行=assistant
            if (sRoleCol == null) {
                Cursor pc = null;
                try {
                    pc = db.rawQuery("PRAGMA table_info(messages)", null);
                    while (pc.moveToNext()) {
                        String col = pc.getString(1);
                        if ("role".equals(col) || "sender".equals(col) || "type".equals(col)) { sRoleCol = col; break; }
                    }
                } catch (Throwable ignored) {
                } finally { if (pc != null) try { pc.close(); } catch (Throwable ignored) {} }
            }
            // 每对话取最后一条消息时间+其role;按 mx 降序: 头部=最近活跃(标签归属方)
            String sql;
            if (sRoleCol != null && sRoleCol.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                sql = "SELECT chatId, MAX(timestamp) AS mx, "
                        + "(SELECT m2." + sRoleCol + " FROM messages m2 WHERE m2.chatId = m.chatId "
                        + " ORDER BY m2.timestamp DESC LIMIT 1) AS lr "
                        + "FROM messages m GROUP BY chatId HAVING mx >= CAST(? AS INTEGER) ORDER BY mx DESC";
            } else {
                sql = "SELECT chatId, MAX(timestamp) AS mx, NULL AS lr FROM messages "
                        + "GROUP BY chatId HAVING mx >= CAST(? AS INTEGER) ORDER BY mx DESC";
            }
            c = db.rawQuery(sql, new String[]{String.valueOf(since)});
            ArrayList<String> ids = new ArrayList<>();
            java.util.ArrayList<Long> mxList = new java.util.ArrayList<>();
            java.util.ArrayList<Boolean> doneList = new java.util.ArrayList<>();
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id == null || id.isEmpty()) continue;
                ids.add(id); mxList.add(c.getLong(1));
                doneList.add("assistant".equals(c.getString(2)));
            }
            // hot=3分钟口径(保持"进行中：N"语义不变);fresh=60s活跃数(用户令①③口径)
            long hotSince = nowMs - WINDOW_MS;
            long freshSince = nowMs - FRESH_MS;
            int hot = 0, freshCount = 0;
            for (int i = 0; i < ids.size(); i++) {
                if (mxList.get(i) >= hotSince) hot++;
                if (mxList.get(i) >= freshSince) freshCount++;
            }
            sActive = hot;
            sCandidates = ids.size();
            String top = ids.isEmpty() ? "" : ids.get(0);
            sTopChatId = top;
            synchronized (sLetterMap) {
                sLetterMap.keySet().retainAll(ids);
                // 释放: done且非top且过10s宽限的持有者让出字母;mid-turn(最新行=user)永不释放
                //   (role列不可用时done恒false→退化为只靠清空态兜底,不会错杀)
                for (java.util.Iterator<String> it = sLetterMap.keySet().iterator(); it.hasNext(); ) {
                    String id = it.next();
                    int idx = ids.indexOf(id);
                    if (idx < 0 || id.equals(top)) continue;
                    if (doneList.get(idx) && nowMs - mxList.get(idx) > DONE_GRACE_MS) it.remove();
                }
                // 【用户令③】恰好1个活跃持续60s→清空全部槽位+抑制显示;≥2活跃恢复
                if (freshCount == 1) {
                    if (sSingleSince == 0L) sSingleSince = nowMs;
                    else if (nowMs - sSingleSince >= FRESH_MS) {
                        if (!sLetterMap.isEmpty()) sLetterMap.clear();
                        sSuppressed = true;
                    }
                } else {
                    sSingleSince = 0L;
                    if (freshCount >= 2) sSuppressed = false;
                }
                // 【用户令②】分配(仅≥2活跃且未抑制,只发60s活跃者): 新对话优先偷最低位done持有者
                //   的字母(A执行完→新对话自动接A位),无则拿最低空位——修掉 charAt(size) 撞车
                if (!sSuppressed && freshCount >= 2) {
                    final String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
                    for (int i = 0; i < ids.size(); i++) {
                        String id = ids.get(i);
                        if (mxList.get(i) < freshSince) continue;
                        if (sLetterMap.containsKey(id)) continue;
                        String stealFrom = null;
                        for (java.util.Map.Entry<String, String> e : sLetterMap.entrySet()) {
                            String hid = e.getKey();
                            if (hid.equals(top)) continue;
                            int hidx = ids.indexOf(hid);
                            if (hidx < 0 || !doneList.get(hidx)) continue;
                            if (nowMs - mxList.get(hidx) <= DONE_GRACE_MS) continue;
                            if (stealFrom == null || e.getValue().compareTo(sLetterMap.get(stealFrom)) < 0) stealFrom = hid;
                        }
                        if (stealFrom != null) {
                            sLetterMap.put(id, sLetterMap.remove(stealFrom));
                        } else {
                            for (int li = 0; li < letters.length(); li++) {
                                String ls = String.valueOf(letters.charAt(li));
                                if (!sLetterMap.containsValue(ls)) { sLetterMap.put(id, ls); break; }
                            }
                        }
                    }
                }
            }
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }
}