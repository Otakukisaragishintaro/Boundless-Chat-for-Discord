package io.github.wxmyyds.discordautotranslate.core;

import android.util.Log;
import android.app.Application;
import android.os.Process;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Sole project log output. Fixed diagnostics; raw non-2xx upstream error bodies are the sole content exception.
 */
public final class ProjectLog {
    private static final String TAG = "DiscordAutoTranslate";
    private static final Set<String> REPORTED = new HashSet<>();
    private static boolean enabled;
    private static final String STORAGE_KEY = "__project_log_buffer_v1";
    private static final int MAX_BUFFER_CHARS = 24000;
    @Nullable private static SharedPreferences storage;
    @Nullable private static Output framework;

    /** Optional framework destination in addition to logcat; owned by ModuleEntry. */
    public interface Output {
        void write(int priority, String tag, String message);
    }

    private ProjectLog() {}

    public static synchronized void attach(@Nullable Output output) {
        framework = output;
    }

    /** Persist the bounded diagnostic buffer in the same LSPosed remote preferences read by the settings UI. */
    public static synchronized void setStorage(@Nullable SharedPreferences preferences) {
        storage = preferences;
    }

    /**
     * Emit a fixed lifecycle diagnostic even when the user log gate is disabled or
     * remote preferences could not be acquired. This path never records user content.
     */
    public static synchronized void bootstrapWarn(String message) {
        write(Log.WARN, message, true);
    }

    /** Record a lifecycle checkpoint without depending on the configurable log gate. */
    public static synchronized void bootstrapInfo(String message) {
        write(Log.INFO, message, true);
    }

    /** Invalid configuration cannot enable logging. Toggling never changes translation state. */
    public static synchronized void configure(Config.Snapshot snapshot) {
        boolean next = snapshot.configError == null && snapshot.loggingEnabled;
        if (enabled != next) REPORTED.clear();
        enabled = next;
        if (storage != null) {
            try {
                storage.edit()
                        .putBoolean("__project_log_module_enabled", enabled)
                        .putLong("__project_log_configured_at", System.currentTimeMillis())
                        .remove("__project_log_storage_error")
                        .commit();
            } catch (Throwable failure) {
                // The settings viewer exposes storage failures separately from log contents.
            }
        }
    }

    /** Whether project diagnostics are currently enabled by the saved configuration. */
    public static synchronized boolean isEnabled() {
        return enabled;
    }

    public static void info(String message) {
        emit(Log.INFO, null, message);
    }

    public static void warn(String message) {
        emit(Log.WARN, null, message);
    }

    public static void infoOnce(String key, String message) {
        emit(Log.INFO, key, message);
    }

    public static void warnOnce(String key, String message) {
        emit(Log.WARN, key, message);
    }

    /** Serialize gate and both outputs: disabling waits for an already-started write. */
    private static synchronized void emit(int priority, @Nullable String key, String message) {
        if (!enabled || (key != null && !REPORTED.add(key))) return;
        write(priority, message, false);
    }

    /** Shared output path. Bootstrap entries are fixed lifecycle facts, never message data. */
    private static void write(int priority, String message, boolean force) {
        // Bootstrap records are the independent proof that code executed inside a process.
        // Include process identity so LSPosed/logcat output can distinguish Discord from settings.
        if (force) {
            String processName = "unknown";
            try {
                String current = Application.getProcessName();
                if (current != null && !current.isEmpty()) processName = current;
            } catch (Throwable ignored) { }
            message = "pid=" + Process.myPid() + " process=" + processName + " " + message;
        }
        if (storage != null) {
            try {
                String previous = storage.getString(STORAGE_KEY, "");
                String updated = (previous == null ? "" : previous)
                        + System.currentTimeMillis() + " " + (priority >= Log.WARN ? "W" : "I")
                        + "/" + TAG + ": " + message + "\n";
                if (updated.length() > MAX_BUFFER_CHARS) {
                    updated = updated.substring(updated.length() - MAX_BUFFER_CHARS);
                    int firstBreak = updated.indexOf("\n");
                    if (firstBreak >= 0 && firstBreak + 1 < updated.length()) {
                        updated = updated.substring(firstBreak + 1);
                    }
                }
                boolean stored = storage.edit().putString(STORAGE_KEY, updated).commit();
                if (!stored) Log.w(TAG, "diagnostic buffer commit returned false");
            } catch (Throwable failure) {
                try {
                    storage.edit().putString("__project_log_storage_error",
                            failure.getClass().getSimpleName()).commit();
                } catch (Throwable ignored) { }
            }
        }
        try {
            Log.println(priority, TAG, message);
        } catch (Throwable ignored) { }
        if (framework != null) {
            try {
                framework.write(priority, TAG, message);
            } catch (Throwable ignored) { }
        }
    }
}
