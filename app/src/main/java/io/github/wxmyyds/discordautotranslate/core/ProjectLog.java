package io.github.wxmyyds.discordautotranslate.core;

import android.util.Log;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Project logging to Logcat and the LSPosed framework log.
 * No cross-process diagnostic provider or export buffer is maintained.
 */
public final class ProjectLog {
    private static final String TAG = "DiscordAutoTranslate";
    private static final Set<String> REPORTED = new HashSet<>();
    private static boolean enabled;
    @Nullable private static Output framework;

    public interface Output {
        void write(int priority, String tag, String message);
    }

    private ProjectLog() { }

    public static synchronized void attach(@Nullable Output output) {
        framework = output;
    }

    /** Invalid configuration cannot enable optional project logging. */
    public static synchronized void configure(Config.Snapshot snapshot) {
        boolean nextEnabled = snapshot.configError == null && snapshot.loggingEnabled;
        if (enabled != nextEnabled) REPORTED.clear();
        enabled = nextEnabled;
    }

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

    /** Startup and hook-installation failures remain visible even when optional logging is off. */
    public static synchronized void bootstrapWarn(String message) {
        write(Log.WARN, message);
    }

    public static synchronized void bootstrapInfo(String message) {
        write(Log.INFO, message);
    }

    private static synchronized void emit(int priority, @Nullable String key, String message) {
        if (!enabled || (key != null && !REPORTED.add(key))) return;
        write(priority, message);
    }

    private static void write(int priority, String message) {
        String safeMessage = message == null ? "" : message;
        try {
            Log.println(priority, TAG, safeMessage);
        } catch (Throwable ignored) { }
        Output output = framework;
        if (output != null) {
            try {
                output.write(priority, TAG, safeMessage);
            } catch (Throwable ignored) { }
        }
    }
}
