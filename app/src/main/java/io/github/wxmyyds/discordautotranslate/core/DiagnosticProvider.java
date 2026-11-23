package io.github.wxmyyds.discordautotranslate.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Receives a tiny, allow-listed set of content-free lifecycle diagnostics from Discord.
 *
 * The provider is exported only because the hooked Discord process belongs to another
 * application UID. Every call is authenticated against the Binder UID and package list.
 * It does not expose a read/query interface to other applications.
 */
public final class DiagnosticProvider extends ContentProvider {
    public static final String PREFS_NAME = "discord_process_diagnostics";
    public static final String KEY_BUFFER = "diagnostic_buffer_v1";
    public static final String KEY_CONFIGURED_AT = "module_configured_at";
    public static final String KEY_MODULE_ENABLED = "module_logging_enabled";
    public static final String KEY_CONFIG_VALID = "module_config_valid";
    private static final String TARGET_PACKAGE = "com.discord";
    private static final String METHOD_APPEND = "append";
    private static final String METHOD_CONFIGURE = "configure";
    private static final String METHOD_PING = "ping";
    private static final int MAX_BUFFER_CHARS = 24000;
    private static final int MAX_MESSAGE_CHARS = 512;

    @Override
    public boolean onCreate() {
        return getContext() != null;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (!isDiscordCaller()) {
            throw new SecurityException("Diagnostic caller is not the Discord application UID");
        }
        if (extras == null) {
            throw new IllegalArgumentException("Missing diagnostic payload");
        }
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, 0);
        Bundle result = new Bundle();
        if (METHOD_PING.equals(method)) {
            boolean stored = prefs.edit()
                    .putLong("transport_last_ping_at", System.currentTimeMillis())
                    .putInt("transport_last_calling_uid", Binder.getCallingUid())
                    .commit();
            result.putBoolean("accepted", stored);
            return result;
        }
        if (METHOD_APPEND.equals(method)) {
            String message = extras.getString("message", "");
            if (!isAllowedDiagnostic(message) || message.length() > MAX_MESSAGE_CHARS) {
                throw new SecurityException("Diagnostic event is not allow-listed");
            }
            int priority = extras.getInt("priority", Log.INFO);
            if (priority != Log.INFO && priority != Log.WARN && priority != Log.ERROR) {
                priority = Log.INFO;
            }
            String line = System.currentTimeMillis() + " "
                    + (priority >= Log.WARN ? "W" : "I")
                    + "/DiscordAutoTranslate: " + message.replace('\n', ' ').replace('\r', ' ') + "\n";
            synchronized (DiagnosticProvider.class) {
                String previous = prefs.getString(KEY_BUFFER, "");
                String updated = (previous == null ? "" : previous) + line;
                if (updated.length() > MAX_BUFFER_CHARS) {
                    updated = updated.substring(updated.length() - MAX_BUFFER_CHARS);
                    int firstBreak = updated.indexOf('\n');
                    if (firstBreak >= 0 && firstBreak + 1 < updated.length()) {
                        updated = updated.substring(firstBreak + 1);
                    }
                }
                boolean stored = prefs.edit().putString(KEY_BUFFER, updated).commit();
                result.putBoolean("accepted", stored);
            }
            return result;
        }
        if (METHOD_CONFIGURE.equals(method)) {
            boolean enabled = extras.getBoolean("enabled", false);
            boolean valid = extras.getBoolean("valid", false);
            boolean stored = prefs.edit()
                    .putBoolean(KEY_MODULE_ENABLED, enabled && valid)
                    .putBoolean(KEY_CONFIG_VALID, valid)
                    .putLong(KEY_CONFIGURED_AT, System.currentTimeMillis())
                    .commit();
            result.putBoolean("accepted", stored);
            return result;
        }
        throw new IllegalArgumentException("Unknown diagnostic method");
    }

    private boolean isDiscordCaller() {
        int uid = Binder.getCallingUid();
        if (uid <= 0 || getContext() == null) return false;

        // ContentProvider verifies that getCallingPackage() belongs to the Binder UID.
        // Prefer it over requiring a single-package UID: Android may associate more
        // than one package with a shared UID, which must not reject Discord itself.
        try {
            String callingPackage = getCallingPackage();
            if (TARGET_PACKAGE.equals(callingPackage)) return true;
        } catch (Throwable ignored) {
            // Fall through to the UID package list for implementations that do not
            // propagate a calling package for call().
        }

        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        for (String packageName : packages) {
            if (TARGET_PACKAGE.equals(packageName)) return true;
        }
        return false;
    }

    /** Accept only fixed lifecycle checkpoints; never persist arbitrary application text. */
    private static boolean isAllowedDiagnostic(String message) {
        if (message == null || message.isEmpty()) return false;
        return message.equals("module lifecycle entered")
                || message.equals("Discord package lifecycle entered")
                || message.equals("remote preferences acquired")
                || message.equals("module logging configuration initialized")
                || message.equals("module logging configuration is invalid")
                || message.startsWith("remote preferences initialization failed errorClass=")
                || message.startsWith("Discord Application.attach reached")
                || message.startsWith("Discord Application.onCreate reached")
                || message.startsWith("Application.onCreate fallback failed errorClass=")
                || message.equals("Discord application attached; selecting version profile")
                || message.equals("no hook profile matches installed Discord version")
                || message.startsWith("Discord hook installation failed errorClass=")
                || message.startsWith("Application.attach hook registration failed errorClass=")
                || message.equals("waiting for Discord Application.attach")
                || message.equals("chat input lifecycle hooks installed")
                || message.startsWith("chat input lifecycle hook registration failed errorClass=")
                || message.startsWith("Discord hook profile installed");
    }

    @Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }
    @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
    @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) { return null; }
    @Override public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
