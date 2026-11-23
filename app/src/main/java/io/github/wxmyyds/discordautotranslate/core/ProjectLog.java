package io.github.wxmyyds.discordautotranslate.core;

import android.util.Log;
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

    /** Invalid configuration cannot enable logging. Toggling never changes translation state. */
    public static synchronized void configure(Config.Snapshot snapshot) {
        boolean next = snapshot.configError == null && snapshot.loggingEnabled;
        if (enabled != next) REPORTED.clear();
        enabled = next;
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
                storage.edit().putString(STORAGE_KEY, updated).apply();
            } catch (Throwable ignored) {
                // Persistent diagnostics are optional and must never affect Discord.
            }
        }
        try {
            Log.println(priority, TAG, message);
        } catch (Throwable ignored) {
            // Log output must not alter the host operation.
        }
        if (framework != null) {
            try {
                framework.write(priority, TAG, message);
            } catch (Throwable ignored) {
                // Each destination is diagnostic only; failure never changes business state.
            }
        }
    }
}
