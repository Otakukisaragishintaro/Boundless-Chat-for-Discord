package io.github.wxmyyds.discordautotranslate.core;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Sole project log output. Discord-process diagnostics are sent through a restricted
 * ContentProvider IPC channel; remote Xposed preferences are read-only in hooked apps.
 */
public final class ProjectLog {
    private static final String TAG = "DiscordAutoTranslate";
    private static final Set<String> REPORTED = new HashSet<>();
    private static final ArrayDeque<PendingDiagnostic> PENDING = new ArrayDeque<>();
    private static final int MAX_PENDING = 40;
    private static boolean enabled;
    private static boolean configurationValid;
    @Nullable private static Context transportContext;
    @Nullable private static Uri transportUri;
    @Nullable private static Output framework;

    public interface Output {
        void write(int priority, String tag, String message);
    }

    private static final class PendingDiagnostic {
        final int priority;
        final String message;
        PendingDiagnostic(int priority, String message) {
            this.priority = priority;
            this.message = message;
        }
    }

    private ProjectLog() {}

    public static synchronized void attach(@Nullable Output output) {
        framework = output;
    }

    /**
     * Attach the IPC transport only after Discord Application.attach supplies a real
     * target-process Context. The authority belongs to this module's own application.
     */
    public static synchronized void setTransport(
            @Nullable Context context, @Nullable String modulePackageName) {
        if (context == null || modulePackageName == null || modulePackageName.isEmpty()) {
            transportContext = null;
            transportUri = null;
            return;
        }
        transportContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        transportUri = Uri.parse("content://" + modulePackageName + ".diagnostics");
        // Verify that Android can resolve and start the module-owned provider before
        // treating the transport as available. This also leaves a timestamp in the
        // module app so the settings screen can distinguish "no attach" from "no IPC".
        sendPing();
        flushPending();
        reportConfiguration();
    }

    /** Invalid configuration cannot enable logging. Toggling never changes translation state. */
    public static synchronized void configure(Config.Snapshot snapshot) {
        boolean nextValid = snapshot.configError == null;
        boolean nextEnabled = nextValid && snapshot.loggingEnabled;
        if (enabled != nextEnabled) REPORTED.clear();
        enabled = nextEnabled;
        configurationValid = nextValid;
        reportConfiguration();
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

    private static synchronized void emit(int priority, @Nullable String key, String message) {
        if (!enabled || (key != null && !REPORTED.add(key))) return;
        write(priority, message, false);
    }

    public static synchronized void bootstrapWarn(String message) {
        write(Log.WARN, message, true);
    }

    public static synchronized void bootstrapInfo(String message) {
        write(Log.INFO, message, true);
    }

    /** Bootstrap entries are fixed lifecycle facts and safe for the provider allow-list. */
    private static void write(int priority, String message, boolean force) {
        String diagnostic = message == null ? "" : message;
        if (force) {
            sendOrQueue(priority, diagnostic);
            String processName = "unknown";
            try {
                String current = android.app.Application.getProcessName();
                if (current != null && !current.isEmpty()) processName = current;
            } catch (Throwable ignored) { }
            diagnostic = "pid=" + Process.myPid() + " process=" + processName + " " + diagnostic;
        }
        try {
            Log.println(priority, TAG, diagnostic);
        } catch (Throwable ignored) { }
        if (framework != null) {
            try {
                framework.write(priority, TAG, diagnostic);
            } catch (Throwable ignored) { }
        }
    }

    private static void sendOrQueue(int priority, String message) {
        if (transportContext != null && transportUri != null
                && sendDiagnostic(priority, message)) {
            return;
        }
        if (PENDING.size() >= MAX_PENDING) PENDING.removeFirst();
        PENDING.addLast(new PendingDiagnostic(priority, message));
    }

    private static void flushPending() {
        while (!PENDING.isEmpty()) {
            PendingDiagnostic item = PENDING.peekFirst();
            if (!sendDiagnostic(item.priority, item.message)) return;
            PENDING.removeFirst();
        }
    }

    /** Report transport failures through Logcat and the independent LSPosed log. */
    private static void reportTransportStatus(int priority, String message) {
        try {
            Log.println(priority, TAG, message);
        } catch (Throwable ignored) { }
        Output output = framework;
        if (output != null) {
            try {
                output.write(priority, TAG, message);
            } catch (Throwable ignored) { }
        }
    }

    private static boolean sendPing() {
        Context context = transportContext;
        Uri uri = transportUri;
        if (context == null || uri == null) return false;
        try {
            Bundle response = context.getContentResolver().call(uri, "ping", null, new Bundle());
            boolean accepted = response != null && response.getBoolean("accepted", false);
            reportTransportStatus(accepted ? Log.INFO : Log.WARN,
                    "diagnostic IPC ping " + (accepted ? "accepted" : "not acknowledged"));
            return accepted;
        } catch (Throwable failure) {
            reportTransportStatus(Log.WARN, "diagnostic IPC ping failed errorClass="
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean sendDiagnostic(int priority, String message) {
        Context context = transportContext;
        Uri uri = transportUri;
        if (context == null || uri == null) return false;
        try {
            Bundle data = new Bundle();
            data.putString("message", message);
            data.putInt("priority", priority);
            Bundle response = context.getContentResolver().call(uri, "append", null, data);
            return response != null && response.getBoolean("accepted", false);
        } catch (Throwable failure) {
            reportTransportStatus(Log.WARN, "diagnostic IPC append failed errorClass="
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static void reportConfiguration() {
        Context context = transportContext;
        Uri uri = transportUri;
        if (context == null || uri == null) return;
        try {
            Bundle data = new Bundle();
            data.putBoolean("enabled", enabled);
            data.putBoolean("valid", configurationValid);
            Bundle response = context.getContentResolver().call(uri, "configure", null, data);
            if (response == null || !response.getBoolean("accepted", false)) {
                reportTransportStatus(Log.WARN, "diagnostic IPC configuration acknowledgement failed");
            }
        } catch (Throwable failure) {
            reportTransportStatus(Log.WARN, "diagnostic IPC configuration failed errorClass="
                    + failure.getClass().getSimpleName());
        }
    }
}
