package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 有容量上限的双层翻译缓存。
 *
 * 缓存键由原文精确内容与完整翻译配置版本共同计算 SHA-256 得到。
 * 数据库只保存哈希键和译文，不保存原文、API 密钥或额外请求头。
 *
 * 数据库位于 Discord 的应用私有目录，可跨进程重启、重新登录和设备重启保留；
 * 清除 Discord 应用数据或卸载应用会删除缓存。
 */
public final class TranslationCache {
    private static final int MEMORY_ENTRIES = 512;
    private static final int DEFAULT_DISK_ENTRIES = 100000;
    private static final String DATABASE_NAME = "boundless_translation_cache.db";
    private static final String TABLE = "translations";

    private static volatile Context sContext;
    @Nullable private static volatile String sModulePackageName;
    private static volatile TranslationCache sInstance;

    /** 安装翻译 Hook 前，使用目标应用 Context 初始化数据库存储位置。 */
    public static void initialize(@Nullable Context context, @Nullable String modulePackageName) {
        if (context == null) return;
        sContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        sModulePackageName = modulePackageName;
    }

    /**
     * Forward an enabled Discord-process diagnostic event to the module app's private log store.
     * The existing single worker serializes IPC writes and keeps provider calls off UI/render threads.
     */
    public static void publishDiagnosticLog(int priority, String tag, String message) {
        publishDiagnosticLog(priority, tag, message, ProjectLog.currentRequestId());
    }

    public static void publishDiagnosticLog(int priority, String tag, String message,
                                             @Nullable String requestId) {
        TranslationCache cache = sInstance;
        Context context = sContext;
        String modulePackageName = sModulePackageName;
        if (cache == null || context == null || modulePackageName == null
                || modulePackageName.isEmpty()) return;
        try {
            cache.countPublisher.execute(() -> {
                String processName = "unknown";
                try {
                    String current = android.app.Application.getProcessName();
                    if (current != null && !current.isEmpty()) processName = current;
                } catch (Throwable ignored) { }
                try {
                    CacheCountProvider.publishLog(context, modulePackageName,
                            System.currentTimeMillis(), processName, priority, tag,
                            message == null ? "" : message,
                            requestId == null ? "" : requestId);
                } catch (Throwable failure) {
                    // Avoid ProjectLog here: reporting a failed log write through the same
                    // transport would create a recursive IPC failure loop.
                    android.util.Log.w("BoundlessChatDiscord",
                            "diagnostic log IPC failed errorClass="
                                    + failure.getClass().getSimpleName());
                }
            });
        } catch (Throwable failure) {
            android.util.Log.w("BoundlessChatDiscord",
                    "diagnostic log IPC scheduling failed errorClass="
                            + failure.getClass().getSimpleName());
        }
    }

    /**
     * Publish only the persistent entry count to the module app. The ContentProvider call is
     * dispatched on a dedicated worker so a cache write never blocks Discord's main thread.
     */
    public void publishEntryCountToModule(int count) {
        if (count < 0) return;
        try {
            countPublisher.execute(() -> {
                Context context = sContext;
                String modulePackageName = sModulePackageName;
                if (context == null || modulePackageName == null || modulePackageName.isEmpty()) {
                    ProjectLog.warnOnce("cache.count.publish.context_missing",
                            "local translation cache count publish skipped reason=module_endpoint_unavailable");
                    return;
                }
                try {
                    boolean published = CacheCountProvider.publish(context, modulePackageName, count);
                    if (published) {
                        ProjectLog.info("local translation cache count published count=" + count);
                    } else {
                        ProjectLog.warnOnce("cache.count.publish.rejected",
                                "local translation cache count publish failed reason=provider_rejected");
                    }
                } catch (Throwable failure) {
                    String message = failure.getMessage();
                    if (message == null || message.isEmpty()) message = "none";
                    message = message.replace('\n', ' ').replace('\r', ' ');
                    if (message.length() > 180) message = message.substring(0, 180);
                    ProjectLog.warnOnce("cache.count.publish.failed",
                            "local translation cache count publish failed errorClass="
                                    + failure.getClass().getSimpleName()
                                    + " errorMessage=" + message);
                }
            });
        } catch (Throwable failure) {
            ProjectLog.warnOnce("cache.count.publish.schedule_failed",
                    "local translation cache count publish scheduling failed errorClass="
                            + failure.getClass().getSimpleName());
        }
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
     * LSPosed 热重载时只释放进程内对象，不删除 SQLite 数据库，
     * 让下一代运行时继续复用已经保存的译文。
     */
    public static void reset() {
        synchronized (TranslationCache.class) {
            TranslationCache old = sInstance;
            sInstance = null;
            if (old != null) {
                old.closeDatabase();
                old.closeCountPublisher();
            }
        }
    }

    private final int maxMemoryEntries;
    private final ExecutorService countPublisher;
    private volatile int maxDiskEntries = DEFAULT_DISK_ENTRIES;
    private int writesSincePrune;
    /** -1 means not loaded yet; after initialization this tracks SQLite rows incrementally. */
    private int diskEntryCount = -1;
    @Nullable private volatile java.util.function.IntConsumer countListener;
    private final LinkedHashMap<String, String> memory;
    private DatabaseHelper helper;
    private SQLiteDatabase database;

    TranslationCache(int maxEntries) {
        maxMemoryEntries = Math.max(1, maxEntries);
        countPublisher = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BoundlessChat-CacheCount");
            thread.setDaemon(true);
            return thread;
        });
        memory = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > TranslationCache.this.maxMemoryEntries;
            }
        };
    }

    /** 先查询内存 LRU；未命中时再查询 SQLite 持久化缓存。 */
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
        } catch (Throwable failure) {
            ProjectLog.warnOnce("cache.read.failed",
                    "local translation cache read failed errorClass="
                            + failure.getClass().getSimpleName());
            // A damaged/unavailable cache must never prevent a normal API translation.
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** 修改持久化 LRU 容量；仅淘汰超出新上限的最久未使用条目。 */
    public synchronized void setMaxDiskEntries(int maxEntries) {
        int bounded = Math.max(1, Math.min(maxEntries, 1000000));
        maxDiskEntries = bounded;
        SQLiteDatabase db = database();
        if (db != null) {
            prune(db);
            refreshEntryCount(db);
            publishEntryCount();
        }
    }

    /** 设置跨进程缓存数量监听；仅发布计数，不暴露原文或译文。 */
    public synchronized void setCountListener(@Nullable java.util.function.IntConsumer listener) {
        countListener = listener;
        if (listener != null) {
            try {
                listener.accept(getEntryCount());
                ProjectLog.info("local translation cache count listener initialized");
            } catch (Throwable failure) {
                ProjectLog.warn("local translation cache count listener failed errorClass="
                        + failure.getClass().getSimpleName());
            }
        }
    }

    /** 返回持久化译文条目数，供设置页展示。 */
    public synchronized int getEntryCount() {
        SQLiteDatabase db = database();
        if (db == null) return 0;
        if (diskEntryCount < 0) {
            refreshEntryCount(db);
        }
        return Math.max(0, diskEntryCount);
    }

    /** Only run the aggregate query when the count is first loaded or after an LRU prune. */
    private void refreshEntryCount(SQLiteDatabase db) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (cursor.moveToFirst()) {
                diskEntryCount = Math.max(0, cursor.getInt(0));
            }
        } catch (Throwable ignored) {
            // Keep the last known value; a later refresh can recover from a transient failure.
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
        } catch (Throwable failure) {
            ProjectLog.warnOnce("cache.prune.failed",
                    "local translation cache prune failed errorClass="
                            + failure.getClass().getSimpleName());
            // Capacity maintenance must not interrupt translation.
        }
    }

    /** 只保存成功且非空的译文。 */
    public synchronized void put(String key, String translated) {
        if (key == null || key.isEmpty() || translated == null || translated.isEmpty()) return;
        memory.put(key, translated);

        SQLiteDatabase db = database();
        if (db == null) {
            ProjectLog.warnOnce("cache.write.database_unavailable",
                    "local translation cache write skipped reason=database_unavailable");
            return;
        }
        try {
            // REPLACE also updates existing rows. Distinguish inserts so the visible count
            // advances only for a genuinely new persisted translation.
            Cursor existing = null;
            boolean alreadyStored;
            try {
                existing = db.query(TABLE, new String[]{"cache_key"},
                        "cache_key=?", new String[]{key}, null, null, null, "1");
                alreadyStored = existing.moveToFirst();
            } finally {
                if (existing != null) existing.close();
            }

            ContentValues values = new ContentValues();
            values.put("cache_key", key);
            values.put("translated", translated);
            values.put("last_access", System.currentTimeMillis());
            long rowId = db.insertWithOnConflict(
                    TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            if (rowId == -1L) {
                ProjectLog.warn("local translation cache write failed reason=sqlite_insert_rejected");
                return;
            }

            if (!alreadyStored && diskEntryCount >= 0) {
                diskEntryCount++;
            }

            // Batch LRU maintenance to avoid a full-table prune on every insert at 1M entries.
            boolean pruned = ++writesSincePrune >= 32;
            if (pruned) {
                writesSincePrune = 0;
                prune(db);
                refreshEntryCount(db);
            }

            // New rows change the count; a prune can also change it even when this key existed.
            if (!alreadyStored || pruned) {
                publishEntryCount();
            }
            ProjectLog.info("local translation cache write status=STORED newEntry="
                    + !alreadyStored + " entryCount=" + getEntryCount());
        } catch (Throwable failure) {
            ProjectLog.warn("local translation cache write failed errorClass="
                    + failure.getClass().getSimpleName());
            // Memory cache remains useful even when persistent storage is unavailable.
        }
    }

    public synchronized void remove(String key) {
        if (key == null) return;
        memory.remove(key);
        SQLiteDatabase db = database();
        if (db != null) {
            try {
                int deleted = db.delete(TABLE, "cache_key=?", new String[]{key});
                if (deleted > 0) {
                    if (diskEntryCount >= 0) {
                        diskEntryCount = Math.max(0, diskEntryCount - deleted);
                    }
                    publishEntryCount();
                }
            } catch (Throwable ignored) {
                // Cache invalidation failure must not break message rendering.
            }
        }
    }

    /** 用户明确请求时才完整清空；普通配置变化不会调用此方法。 */
    public synchronized void clear() {
        memory.clear();
        writesSincePrune = 0;
        SQLiteDatabase db = database();
        if (db != null) {
            try {
                db.delete(TABLE, null, null);
                diskEntryCount = 0;
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
        if (context == null) {
            ProjectLog.warnOnce("cache.database.context_missing",
                    "local translation cache database unavailable reason=target_context_missing");
            return null;
        }
        try {
            if (helper == null) helper = new DatabaseHelper(context);
            database = helper.getWritableDatabase();
            return database;
        } catch (Throwable failure) {
            database = null;
            ProjectLog.warnOnce("cache.database.open.failed",
                    "local translation cache database open failed errorClass="
                            + failure.getClass().getSimpleName());
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

    /** Stop IPC work owned by this cache generation during LSPosed hot reload. */
    private void closeCountPublisher() {
        countPublisher.shutdownNow();
        try {
            if (!countPublisher.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                ProjectLog.warnOnce("cache.count.publisher.shutdown_timeout",
                        "local translation cache count publisher shutdown timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
