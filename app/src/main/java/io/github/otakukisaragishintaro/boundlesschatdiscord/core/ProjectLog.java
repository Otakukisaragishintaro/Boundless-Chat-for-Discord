package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import android.util.Log;
import android.os.Process;

import androidx.annotation.Nullable;

import java.util.HashSet;
import java.util.UUID;
import java.util.Set;

/**
 * Project logging to Logcat, the LSPosed framework log, and the module-owned
 * diagnostic database through the UID-checked cache-count ContentProvider.
 */
public final class ProjectLog {
    private static final String TAG = "BoundlessChatDiscord";
    private static final Set<String> REPORTED = new HashSet<>();
    private static boolean enabled;
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
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
        write(Log.WARN, withProcessContext(message));
    }

    public static synchronized void bootstrapInfo(String message) {
        write(Log.INFO, withProcessContext(message));
    }

    private static String withProcessContext(String message) {
        String processName = "unknown";
        try {
            String current = android.app.Application.getProcessName();
            if (current != null && !current.isEmpty()) processName = current;
        } catch (Throwable ignored) { }
        return "pid=" + Process.myPid() + " process=" + processName + " "
                + (message == null ? "" : message);
    }

    private static synchronized void emit(int priority, @Nullable String key, String message) {
        if (!enabled || (key != null && !REPORTED.add(key))) return;
        // Apply the same credential redaction and size bound to Logcat, LSPosed and IPC.
        String safeMessage = DiagnosticLogStore.sanitizeMessage(message);
        write(priority, safeMessage);
        TranslationCache.publishDiagnosticLog(priority, TAG, safeMessage, currentRequestId());
    }

    /** Attach one correlation ID to every ProjectLog entry emitted during a translation request. */
    public static String beginRequest() {
        String previous = REQUEST_ID.get();
        REQUEST_ID.set(UUID.randomUUID().toString());
        return previous;
    }

    public static void endRequest(@Nullable String previous) {
        if (previous == null) REQUEST_ID.remove();
        else REQUEST_ID.set(previous);
    }

    @Nullable
    static String currentRequestId() {
        return REQUEST_ID.get();
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
