package com.operit.rearprogress;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * 统计 Operit 中「目前进行中」的对话数。
 *
 * 口径（2026-10-27 修复）：改用 messages 表的最后消息时间，窗口 3 分钟。
 *   旧口径 chats.updatedAt >= now-5min 会把「57分钟没消息的对话」「空对话（一条消息都没有）」
 *   也算成进行中（updatedAt 被 touch 过就会更新），实测误报成 4 个。
 *   新口径只数「3分钟内真正有消息交互」的对话，实测 1 个（符合用户实际）。
 * 数据源：/data/data/com.ai.assistance.operit/databases/app_database
 * 只读打开 + 后台线程 + 10 秒缓存，避免拖慢通知构建。
 */
final class ChatCounter {

    private static final String DB_PATH =
            "/data/data/com.ai.assistance.operit/databases/app_database";
    /** 判定"进行中"的时间窗口：最近 3 分钟内有消息交互 */
    private static final long WINDOW_MS = 3 * 60_000L;
    /** 结果缓存时长 */
    private static final long REFRESH_MS = 10_000L;

    private static volatile int sActive = 0;
    private static volatile long sLastAt = 0L;
    private static volatile boolean sRunning = false;

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

    private static void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - sLastAt < REFRESH_MS || sRunning) return;
        sRunning = true;
        try {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        sActive = count(WINDOW_MS);
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

    private static int count(long windowMs) {
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(DB_PATH, null, SQLiteDatabase.OPEN_READONLY);
            long since = System.currentTimeMillis() - windowMs;
            // 口径：每个对话取最后一条消息时间，只数窗口内有消息交互的
            //   （chats.updatedAt 会被 touch 刷新，导致空对话/久未说话的也被算成"进行中"）
            c = db.rawQuery(
                    "SELECT COUNT(*) FROM ("
                            + "SELECT chatId, MAX(timestamp) AS mx FROM messages "
                            + "GROUP BY chatId HAVING mx >= CAST(? AS INTEGER))",
                    new String[]{String.valueOf(since)});
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }
}