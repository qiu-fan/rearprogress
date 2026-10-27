package com.operit.rearprogress;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * 统计 Operit 中「目前进行中」的对话数。
 *
 * 口径：最近 5 分钟内有更新的对话（updatedAt >= now - 5min）。
 * 数据源：/data/data/com.ai.assistance.operit/databases/app_database 的 chats 表。
 * 只读打开 + 后台线程 + 10 秒缓存，避免拖慢通知构建。
 */
final class ChatCounter {

    private static final String DB_PATH =
            "/data/data/com.ai.assistance.operit/databases/app_database";
    /** 判定"进行中"的时间窗口 */
    private static final long WINDOW_MS = 5 * 60_000L;
    /** 结果缓存时长 */
    private static final long REFRESH_MS = 10_000L;

    private static volatile int sActive = 0;
    private static volatile long sLastAt = 0L;
    private static volatile boolean sRunning = false;

    private ChatCounter() {}

    /** 主通知正文用：如 "进行中：2" */
    static String text() {
        refreshIfNeeded();
        int n = sActive;
        return n > 0 ? ("进行中：" + n) : "进行中：-";
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
            c = db.rawQuery("SELECT COUNT(*) FROM chats WHERE updatedAt >= ?",
                    new String[]{String.valueOf(since)});
            if (c.moveToFirst()) return c.getInt(0);
            return 0;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }
}
