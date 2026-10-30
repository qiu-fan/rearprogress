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
    /** 【v1.5】并发标签候选窗口:回合必以用户消息行开始,10分钟内必在候选,长回合不掉出(3分钟实测缺口) */
    private static final long PREFIX_WINDOW_MS = 10 * 60_000L;
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
    private ChatCounter() {}

    static boolean isIdle() {
        refreshIfNeeded();
        return sActive <= 0;
    }
    /** 主通知正文用：如 "进行中：2" */
    static String text() {
        refreshIfNeeded();
        int n = sActive;
        return n > 0 ? ("进行中：" + n) : "进行中：-";
    }
    /** 当前进行中的对话数（供副标题右侧显示；<=1 时调用方不显示） */
    static int activeCount() {
        refreshIfNeeded();
        return sActive;
    }
    /** 【3.3 用户令】并发对话标签前缀: ≥2个活跃时返回 "A:" 形式（头部对话的稳定字母）,
     *  单任务返回空串不加噪音。用于双AI并发时用户辨识当前画面是谁。 */
    static String activePrefix() {
        refreshIfNeeded();
        if (sCandidates < 2) return "";   // v1.5: 候选窗口(10min)≥2并发,长回合不掉出;计数仍3min口径
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
            long since = nowMs - PREFIX_WINDOW_MS;   // v1.5: 查10min候选,hot按3min口径另算
            // 口径：每个对话取最后一条消息时间，只数窗口内有消息交互的
            //   （chats.updatedAt 会被 touch 刷新，导致空对话/久未说话的也被算成"进行中"）
            // 按 mx 降序: 头部=最近活跃(并发标签归属方)
            c = db.rawQuery(
                    "SELECT chatId, MAX(timestamp) AS mx FROM messages "
                            + "GROUP BY chatId HAVING mx >= CAST(? AS INTEGER) ORDER BY mx DESC",
                    new String[]{String.valueOf(since)});
            ArrayList<String> ids = new ArrayList<>();
            java.util.ArrayList<Long> mxList = new java.util.ArrayList<>();
            while (c.moveToNext()) {
                String id = c.getString(0);
                if (id != null && !id.isEmpty()) { ids.add(id); mxList.add(c.getLong(1)); }
            }
            // hot=3分钟口径(保持"进行中：N"语义不变),候选=10分钟全量
            long hotSince = nowMs - WINDOW_MS;
            int hot = 0;
            for (int i = 0; i < ids.size(); i++) { if (mxList.get(i) >= hotSince) hot++; }
            sActive = hot;
            sCandidates = ids.size();
            sTopChatId = ids.isEmpty() ? "" : ids.get(0);
            synchronized (sLetterMap) {
                sLetterMap.keySet().retainAll(ids);
                final String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
                for (String id : ids) {
                    if (sLetterMap.containsKey(id)) continue;
                    sLetterMap.put(id, sLetterMap.size() < letters.length()
                            ? String.valueOf(letters.charAt(sLetterMap.size())) : "?");
                }
            }
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }
}
