package io.github.wxmyyds.discordautotranslate.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent, bounded translation cache.
 *
 * Cache keys are SHA-256 hashes of the exact source text and the complete translation
 * configuration version. The database stores only the hash and translated result, never
 * the original message text, API key, or request headers.
 *
 * The database lives in Discord's private application storage. It survives process restarts,
 * Discord relogins, and device reboots; clearing Discord app data removes it.
 */
public final class TranslationCache {
    private static final int MEMORY_ENTRIES = 512;
    private static final int DEFAULT_DISK_ENTRIES = 100000;
    private static final String DATABASE_NAME = "boundless_translation_cache.db";
    private static final String TABLE = "translations";

    private static volatile Context sContext;
    private static volatile TranslationCache sInstance;

    /** Initialize with the target app context before translation hooks are activated. */
    public static void initialize(@Nullable Context context) {
        if (context == null) return;
        sContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
    }

    public static TranslationCache get() {
        TranslationCache cache = sInstance;
        if (cache == null) {
            synchronized (TranslationCache.class) {
                cache = sInstance;
                if (cache == null) {
                    cache = new TranslationCache(MEMORY_ENTRIES);
                    sInstance = cache;
                }
            }
        }
        return cache;
    }

    /**
     * Drop process-local objects during LSPosed hot reload. Do not delete the database:
     * persistent translations must remain available to the next runtime generation.
     */
    public static void reset() {
        synchronized (TranslationCache.class) {
            TranslationCache old = sInstance;
            sInstance = null;
            if (old != null) old.closeDatabase();
        }
    }

    private final int maxMemoryEntries;
    private volatile int maxDiskEntries = DEFAULT_DISK_ENTRIES;
    private int writesSincePrune;
    @Nullable private volatile java.util.function.IntConsumer countListener;
    private final LinkedHashMap<String, String> memory;
    private DatabaseHelper helper;
    private SQLiteDatabase database;

    TranslationCache(int maxEntries) {
        maxMemoryEntries = Math.max(1, maxEntries);
        memory = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > TranslationCache.this.maxMemoryEntries;
            }
        };
    }

    /** Memory lookup first, then a persistent SQLite lookup on a miss. */
    @Nullable
    public synchronized String get(String key) {
        if (key == null || key.isEmpty()) return null;
        String cached = memory.get(key);
        if (cached != null) return cached;

        SQLiteDatabase db = database();
        if (db == null) return null;
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE, new String[]{"translated"},
                    "cache_key=?", new String[]{key}, null, null, null, "1");
            if (!cursor.moveToFirst()) return null;
            cached = cursor.getString(0);
            if (cached == null || cached.isEmpty()) return null;
            memory.put(key, cached);
            ContentValues touch = new ContentValues();
            touch.put("last_access", System.currentTimeMillis());
            db.update(TABLE, touch, "cache_key=?", new String[]{key});
            return cached;
        } catch (Throwable ignored) {
            // A damaged/unavailable cache must never prevent a normal API translation.
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** Change the persistent LRU capacity without deleting entries unnecessarily. */
    public synchronized void setMaxDiskEntries(int maxEntries) {
        int bounded = Math.max(1, Math.min(maxEntries, 1000000));
        maxDiskEntries = bounded;
        SQLiteDatabase db = database();
        if (db != null) {
            prune(db);
            publishEntryCount();
        }
    }

    /** 设置跨进程缓存数量监听；仅发布计数，不暴露原文或译文。 */
    public void setCountListener(@Nullable java.util.function.IntConsumer listener) {
        countListener = listener;
        if (listener != null) listener.accept(getEntryCount());
    }

    /** 返回持久化译文条目数，供设置页展示。 */
    public synchronized int getEntryCount() {
        SQLiteDatabase db = database();
        if (db == null) return 0;
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (Throwable ignored) {
            return 0;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private void publishEntryCount() {
        java.util.function.IntConsumer listener = countListener;
        if (listener != null) {
            try { listener.accept(getEntryCount()); } catch (Throwable ignored) { }
        }
    }

    private void prune(SQLiteDatabase db) {
        try {
            db.execSQL("DELETE FROM " + TABLE
                    + " WHERE cache_key NOT IN (SELECT cache_key FROM " + TABLE
                    + " ORDER BY last_access DESC LIMIT " + maxDiskEntries + ")");
        } catch (Throwable ignored) {
            // Capacity maintenance must not interrupt translation.
        }
    }

    /** Store only successful, non-empty translations. */
    public synchronized void put(String key, String translated) {
        if (key == null || key.isEmpty() || translated == null || translated.isEmpty()) return;
        memory.put(key, translated);

        SQLiteDatabase db = database();
        if (db == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put("cache_key", key);
            values.put("translated", translated);
            values.put("last_access", System.currentTimeMillis());
            db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            // Batch LRU maintenance to avoid a full-table prune on every insert at 1M entries.
            if (++writesSincePrune >= 32) {
                writesSincePrune = 0;
                prune(db);
                publishEntryCount();
            }
        } catch (Throwable ignored) {
            // Memory cache remains useful even when persistent storage is unavailable.
        }
    }

    public synchronized void remove(String key) {
        if (key == null) return;
        memory.remove(key);
        SQLiteDatabase db = database();
        if (db != null) {
            try {
                db.delete(TABLE, "cache_key=?", new String[]{key});
            } catch (Throwable ignored) {
                // Cache invalidation failure must not break message rendering.
            }
        }
    }

    /** Explicit full clear; normal configuration changes do not call this. */
    public synchronized void clear() {
        memory.clear();
        writesSincePrune = 0;
        SQLiteDatabase db = database();
        if (db != null) {
            try {
                db.delete(TABLE, null, null);
            publishEntryCount();
            } catch (Throwable ignored) {
                // Cache clearing is best-effort if storage is unavailable.
            }
        }
    }

    @Nullable
    private SQLiteDatabase database() {
        if (database != null && database.isOpen()) return database;
        Context context = sContext;
        if (context == null) return null;
        try {
            if (helper == null) helper = new DatabaseHelper(context);
            database = helper.getWritableDatabase();
            return database;
        } catch (Throwable ignored) {
            database = null;
            return null;
        }
    }

    private synchronized void closeDatabase() {
        if (database != null) {
            try {
                database.close();
            } catch (Throwable ignored) {
                // Best-effort close during runtime teardown.
            }
            database = null;
        }
        if (helper != null) {
            try {
                helper.close();
            } catch (Throwable ignored) {
                // Best-effort close during runtime teardown.
            }
            helper = null;
        }
    }

    private static final class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, 1);
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + "cache_key TEXT PRIMARY KEY NOT NULL,"
                    + "translated TEXT NOT NULL,"
                    + "last_access INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX idx_" + TABLE + "_last_access ON "
                    + TABLE + "(last_access)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Version 1 is the first persistent schema. Future upgrades must preserve entries.
        }
    }
}
