package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Module-app-owned diagnostics; Discord writes only through the UID-checked Provider. */
public final class DiagnosticLogStore {
    private static final String DATABASE_NAME = "boundless_diagnostics.db";
    private static final String TABLE = "diagnostic_logs";
    public static final int MAX_ENTRIES = 5000;
    public static final int DEFAULT_READ_LIMIT = MAX_ENTRIES;
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int PRUNE_INTERVAL = 32;
    @Nullable private static Helper sHelper;
    private static int writesSincePrune;

    private DiagnosticLogStore() { }

    /** Reuse one application-scoped helper instead of opening and closing SQLite per log. */
    private static synchronized Helper helper(Context context) {
        if (sHelper == null) {
            Context appContext = context.getApplicationContext();
            sHelper = new Helper(appContext == null ? context : appContext);
        }
        return sHelper;
    }

    public static final class Entry {
        public final long id, timestamp;
        public final String source, processName, tag, message, requestId;
        public final int priority;
        Entry(long id, long timestamp, String source, String processName, int priority,
              String tag, String message, String requestId) {
            this.id=id; this.timestamp=timestamp; this.source=source; this.processName=processName;
            this.priority=priority; this.tag=tag; this.message=message; this.requestId=requestId;
        }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, DATABASE_NAME, null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "timestamp INTEGER NOT NULL,source TEXT NOT NULL,process_name TEXT NOT NULL,"
                    + "priority INTEGER NOT NULL,tag TEXT NOT NULL,message TEXT NOT NULL,"
                    + "request_id TEXT NOT NULL)");
            db.execSQL("CREATE INDEX diagnostic_logs_timestamp ON " + TABLE + "(timestamp)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported diagnostic log database upgrade");
        }
    }

    public static synchronized boolean append(Context context, long timestamp, String source,
                                              String processName, int priority, String tag,
                                              String message, String requestId) {
        if (context == null) return false;
        try {
            SQLiteDatabase db = helper(context).getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("timestamp", Math.max(0L, timestamp));
            values.put("source", bounded(source, 32, "unknown"));
            values.put("process_name", bounded(processName, 160, "unknown"));
            values.put("priority", priority);
            values.put("tag", bounded(tag, 120, "BoundlessChatDiscord"));
            values.put("message", sanitizeMessage(message));
            values.put("request_id", bounded(requestId, 80, ""));
            if (db.insert(TABLE, null, values) < 0) return false;

            // Pruning once per batch avoids a full retained-table delete query for every event.
            if (++writesSincePrune >= PRUNE_INTERVAL) {
                db.execSQL("DELETE FROM " + TABLE + " WHERE _id NOT IN (SELECT _id FROM "
                        + TABLE + " ORDER BY _id DESC LIMIT " + MAX_ENTRIES + ")");
                writesSincePrune = 0;
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    public static synchronized List<Entry> read(Context context, int requestedLimit) {
        if (context == null) return Collections.emptyList();
        int limit = Math.max(1, Math.min(requestedLimit, MAX_ENTRIES));
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = helper(context).getReadableDatabase().rawQuery(
                "SELECT _id,timestamp,source,process_name,priority,tag,message,request_id FROM "
                        + TABLE + " ORDER BY _id DESC LIMIT ?", new String[]{Integer.toString(limit)})) {
            while (cursor.moveToNext()) entries.add(new Entry(cursor.getLong(0), cursor.getLong(1),
                    cursor.getString(2), cursor.getString(3), cursor.getInt(4),
                    cursor.getString(5), cursor.getString(6), cursor.getString(7)));
        } catch (Throwable ignored) { entries.clear(); }
        Collections.reverse(entries);
        return entries;
    }

    public static synchronized boolean clear(Context context) {
        if (context == null) return false;
        try {
            helper(context).getWritableDatabase().delete(TABLE, null, null);
            writesSincePrune = 0;
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /** Redact common credential formats before persisting or copying diagnostics. */
    public static String sanitizeMessage(String message) {
        if (message == null) return "";
        String value = message
                .replaceAll("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+", "$1[REDACTED]")
                .replaceAll("(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b", "[REDACTED_API_KEY]")
                .replaceAll("(?i)\\bBearer\\s+[^\\s,;]+", "Bearer [REDACTED]");
        return value.length() <= MAX_MESSAGE_LENGTH ? value
                : value.substring(0, MAX_MESSAGE_LENGTH) + "…[truncated]";
    }

    private static String bounded(String value, int max, String fallback) {
        String safe = value == null || value.trim().isEmpty() ? fallback : value.trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
