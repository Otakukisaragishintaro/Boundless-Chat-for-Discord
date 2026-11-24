package io.github.otakukisaragishintaro.boundlesschatdiscord.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Binder;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Process;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One-way IPC endpoint for the Discord process to publish the persistent cache count.
 *
 * <p>LibXposed remote preferences are intentionally read-only in hooked apps, so the target
 * process reports this non-sensitive integer through ContentProvider.call(). The provider
 * accepts writes only from the official Discord package UID and stores the count in this
 * module application's private preferences for the settings UI.</p>
 */
public final class CacheCountProvider extends ContentProvider {
    public static final String PREFERENCES_NAME = "boundless_cache_count";
    public static final String KEY_ENTRY_COUNT = "entry_count";

    private static final String AUTHORITY_SUFFIX = ".cache-count";
    private static final String METHOD_PUBLISH_COUNT = "publish_count";
    private static final String EXTRA_ENTRY_COUNT = "entry_count";
    private static final String EXTRA_PUBLISHED = "published";
    private static final String ALLOWED_CALLER_PACKAGE = "com.discord";
    private static final int MAX_ENTRY_COUNT = 1_000_000;

    /**
     * Send the count from the hooked target process to the module app.
     * The caller should invoke this from a worker thread, not a UI callback.
     */
    public static boolean publish(@NonNull Context targetContext,
                                  @NonNull String modulePackageName,
                                  int entryCount) {
        if (modulePackageName.isEmpty() || entryCount < 0 || entryCount > MAX_ENTRY_COUNT) {
            return false;
        }
        String authority = modulePackageName + AUTHORITY_SUFFIX;
        Bundle extras = new Bundle();
        extras.putInt(EXTRA_ENTRY_COUNT, entryCount);
        ProjectLog.infoOnce("cache.count.publish.endpoint",
                "local translation cache count provider endpoint authority=" + authority);

        // Capture what the Discord process itself can see before making the IPC call.
        // This is diagnostic only and does not change provider visibility or permissions.
        int processUid = Process.myUid();
        int processUserId = UserHandle.getUserId(processUid);
        String contextPackage = safeContextPackage(targetContext);
        String opPackage = safeContextOpPackage(targetContext);
        PackageManager packageManager = targetContext.getPackageManager();
        try {
            ProviderInfo provider = packageManager.resolveContentProvider(authority, 0);
            if (provider == null) {
                ProjectLog.warnOnce("cache.count.provider.resolve.missing",
                        "local translation cache provider resolution result=NOT_FOUND"
                                + " authority=" + authority
                                + " contextPackage=" + contextPackage
                                + " opPackage=" + opPackage
                                + " processUid=" + processUid
                                + " processUserId=" + processUserId);
            } else {
                int providerUid = provider.applicationInfo == null
                        ? -1 : provider.applicationInfo.uid;
                ProjectLog.infoOnce("cache.count.provider.resolve.found",
                        "local translation cache provider resolution result=FOUND"
                                + " authority=" + authority
                                + " providerPackage=" + provider.packageName
                                + " providerClass=" + provider.name
                                + " exported=" + provider.exported
                                + " enabled=" + provider.enabled
                                + " providerUid=" + providerUid
                                + " contextPackage=" + contextPackage
                                + " opPackage=" + opPackage
                                + " processUid=" + processUid
                                + " processUserId=" + processUserId);
            }
        } catch (Throwable failure) {
            ProjectLog.warnOnce("cache.count.provider.resolve.error",
                    "local translation cache provider resolution threw"
                            + " errorClass=" + failure.getClass().getSimpleName()
                            + " errorMessage=" + safeMessage(failure)
                            + " authority=" + authority
                            + " contextPackage=" + contextPackage
                            + " opPackage=" + opPackage
                            + " processUid=" + processUid
                            + " processUserId=" + processUserId);
        }

        try {
            ApplicationInfo moduleInfo = packageManager.getApplicationInfo(modulePackageName, 0);
            ProjectLog.infoOnce("cache.count.module.package.visible",
                    "local translation cache module package visibility result=FOUND"
                            + " modulePackage=" + modulePackageName
                            + " moduleUid=" + moduleInfo.uid
                            + " processUid=" + processUid
                            + " processUserId=" + processUserId);
        } catch (Throwable failure) {
            ProjectLog.warnOnce("cache.count.module.package.missing",
                    "local translation cache module package visibility result=NOT_FOUND"
                            + " modulePackage=" + modulePackageName
                            + " errorClass=" + failure.getClass().getSimpleName()
                            + " errorMessage=" + safeMessage(failure)
                            + " contextPackage=" + contextPackage
                            + " opPackage=" + opPackage
                            + " processUid=" + processUid
                            + " processUserId=" + processUserId);
        }

        Bundle response = targetContext.getContentResolver().call(
                authority, METHOD_PUBLISH_COUNT, null, extras);
        boolean published = response != null
                && response.getBoolean(EXTRA_PUBLISHED, false);
        ProjectLog.infoOnce(published
                        ? "cache.count.provider.call.success"
                        : "cache.count.provider.call.empty",
                "local translation cache provider call result="
                        + (published ? "PUBLISHED" : "NO_CONFIRMATION")
                        + " authority=" + authority
                        + " responsePresent=" + (response != null));
        return published;
    }

    @NonNull
    private static String safeContextPackage(@NonNull Context context) {
        try {
            String value = context.getPackageName();
            return value == null ? "null" : value;
        } catch (Throwable failure) {
            return "error:" + failure.getClass().getSimpleName();
        }
    }

    @NonNull
    private static String safeContextOpPackage(@NonNull Context context) {
        try {
            String value = context.getOpPackageName();
            return value == null ? "null" : value;
        } catch (Throwable failure) {
            return "error:" + failure.getClass().getSimpleName();
        }
    }

    @NonNull
    private static String safeMessage(@NonNull Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isEmpty()) return "none";
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (!METHOD_PUBLISH_COUNT.equals(method)) {
            return null;
        }
        if (!isDiscordCaller()) {
            throw new SecurityException("Only the Discord package may publish cache counts");
        }
        if (extras == null) {
            return publishResult(false);
        }
        int entryCount = extras.getInt(EXTRA_ENTRY_COUNT, -1);
        if (entryCount < 0 || entryCount > MAX_ENTRY_COUNT) {
            return publishResult(false);
        }
        Context context = getContext();
        if (context == null) {
            return publishResult(false);
        }
        SharedPreferences preferences = context.getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
        boolean saved = preferences.edit().putInt(KEY_ENTRY_COUNT, entryCount).commit();
        return publishResult(saved);
    }

    private boolean isDiscordCaller() {
        // getCallingPackage() may be unavailable for some ContentProvider.call() paths.
        // Compare the Binder caller UID with the UID assigned to the installed Discord app.
        // The manifest declares <queries><package android:name="com.discord" /></queries>
        // so package visibility does not hide this package from PackageManager.
        try {
            Context context = getContext();
            if (context == null) return false;
            int callerUid = Binder.getCallingUid();
            ApplicationInfo discord = context.getPackageManager()
                    .getApplicationInfo(ALLOWED_CALLER_PACKAGE, 0);
            return callerUid == discord.uid;
        } catch (PackageManager.NameNotFoundException | SecurityException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Bundle publishResult(boolean published) {
        Bundle result = new Bundle();
        result.putBoolean(EXTRA_PUBLISHED, published);
        return result;
    }

    // This provider exposes a single call method and deliberately has no queryable data.
    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
                        @Nullable String selection, @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
                     @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values,
                      @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
