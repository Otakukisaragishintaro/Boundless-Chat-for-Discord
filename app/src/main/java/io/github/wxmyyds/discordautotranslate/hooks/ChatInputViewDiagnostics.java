package io.github.wxmyyds.discordautotranslate.hooks;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.EditText;

import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Opt-in, content-free diagnostics for locating Discord's native chat input controls.
 *
 * <p>Only view class names, resource entry names, hierarchy positions and interaction flags
 * are recorded. Text, hints, content descriptions, message contents and credentials are never read.</p>
 */
public final class ChatInputViewDiagnostics {
    private static final Map<Activity, Registration> REGISTRATIONS = new WeakHashMap<>();

    private ChatInputViewDiagnostics() {
    }

    private static final class Registration {
        final View decor;
        final ViewTreeObserver observer;
        final ViewTreeObserver.OnGlobalFocusChangeListener listener;
        String lastSignature;

        Registration(View decor, ViewTreeObserver observer,
                     ViewTreeObserver.OnGlobalFocusChangeListener listener) {
            this.decor = decor;
            this.observer = observer;
            this.listener = listener;
        }
    }

    /** Called from Activity.onResume on the main thread. */
    public static void attach(Activity activity) {
        if (activity == null || activity.getWindow() == null || !ProjectLog.isEnabled()) {
            return;
        }
        View decor = activity.getWindow().getDecorView();
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (!observer.isAlive()) {
            return;
        }
        synchronized (REGISTRATIONS) {
            Registration previous = REGISTRATIONS.get(activity);
            if (previous != null && previous.decor == decor
                    && previous.observer == observer && observer.isAlive()) {
                reportIfFocused(activity, previous, decor.findFocus());
                return;
            }
            detachLocked(activity);
            ViewTreeObserver.OnGlobalFocusChangeListener listener =
                    (oldFocus, newFocus) -> {
                        Registration current;
                        synchronized (REGISTRATIONS) {
                            current = REGISTRATIONS.get(activity);
                        }
                        if (current != null) {
                            reportIfFocused(activity, current, newFocus);
                        }
                    };
            Registration registration = new Registration(decor, observer, listener);
            REGISTRATIONS.put(activity, registration);
            observer.addOnGlobalFocusChangeListener(listener);
            reportIfFocused(activity, registration, decor.findFocus());
        }
    }

    /** Called from Activity.onDestroy on the main thread. */
    public static void detach(Activity activity) {
        if (activity == null) {
            return;
        }
        synchronized (REGISTRATIONS) {
            detachLocked(activity);
        }
    }

    private static void detachLocked(Activity activity) {
        Registration registration = REGISTRATIONS.remove(activity);
        if (registration != null && registration.observer.isAlive()) {
            registration.observer.removeOnGlobalFocusChangeListener(registration.listener);
        }
    }

    /** Removes every listener before the runtime's main-thread dispatcher is shut down. */
    public static void clear() {
        synchronized (REGISTRATIONS) {
            if (REGISTRATIONS.isEmpty()) {
                return;
            }
        }
        if (!Scheduler.MainThread.runAndAwait(ChatInputViewDiagnostics::clearOnMainThread, 2000L)) {
            throw new IllegalStateException("Chat input diagnostic listeners did not detach");
        }
    }

    private static void clearOnMainThread() {
        synchronized (REGISTRATIONS) {
            for (Map.Entry<Activity, Registration> entry : REGISTRATIONS.entrySet()) {
                Registration registration = entry.getValue();
                if (registration != null && registration.observer.isAlive()) {
                    registration.observer.removeOnGlobalFocusChangeListener(registration.listener);
                }
            }
            REGISTRATIONS.clear();
        }
    }

    private static void reportIfFocused(Activity activity, Registration registration, View focused) {
        if (!(focused instanceof EditText)) {
            return;
        }
        StringBuilder report = new StringBuilder(2048);
        report.append("chat-input-view-tree activity=")
                .append(activity.getClass().getName())
                .append(" focus=");
        View current = focused;
        int depth = 0;
        while (current != null && depth < 7) {
            report.append("\n  ").append(depth).append(": ")
                    .append(describe(current));
            ViewParent parent = current.getParent();
            if (parent instanceof ViewGroup && depth < 3) {
                ViewGroup group = (ViewGroup) parent;
                int index = indexOf(group, current);
                report.append(" parentChildIndex=").append(index)
                        .append(" siblings=[");
                int limit = Math.min(group.getChildCount(), 12);
                for (int i = 0; i < limit; i++) {
                    if (i != 0) report.append(" | ");
                    report.append(i).append(':').append(describe(group.getChildAt(i)));
                }
                if (group.getChildCount() > limit) {
                    report.append(" | … total=").append(group.getChildCount());
                }
                report.append(']');
            }
            current = parent instanceof View ? (View) parent : null;
            depth++;
        }
        String value = report.toString();
        synchronized (registration) {
            if (value.equals(registration.lastSignature)) {
                return;
            }
            registration.lastSignature = value;
        }
        ProjectLog.info(value);
    }

    private static int indexOf(ViewGroup parent, View child) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) == child) {
                return i;
            }
        }
        return -1;
    }

    private static String describe(View view) {
        String resource = "none";
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                resource = view.getResources().getResourceEntryName(id);
            } catch (Throwable ignored) {
                resource = "id-" + id;
            }
        }
        String type = view.getClass().getName();
        int children = view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : 0;
        return type + "{id=" + resource
                + ",size=" + view.getWidth() + "x" + view.getHeight()
                + ",visibility=" + view.getVisibility()
                + ",clickable=" + view.isClickable()
                + ",focusable=" + view.isFocusable()
                + ",focused=" + view.hasFocus()
                + ",enabled=" + view.isEnabled()
                + ",children=" + children + "}";
    }
}
