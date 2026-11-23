package io.github.wxmyyds.discordautotranslate.hooks;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.TranslationTaskId;
import io.github.wxmyyds.discordautotranslate.translate.JsonApiEngine;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adds an outbound-translation action beside Discord's native send control.
 *
 * The original send control is discovered from the focused editor's nearby clickable siblings.
 * Translation is completed before the editor is updated and the original send control is clicked.
 */
public final class ChatInputTranslateButton {
    private static final int BUTTON_WIDTH_DP = 68;
    private static final int BUTTON_HEIGHT_DP = 38;
    private static final AtomicLong NEXT_REQUEST = new AtomicLong();
    private static final Map<Activity, Registration> REGISTRATIONS = new WeakHashMap<>();

    private ChatInputTranslateButton() {
    }

    private static final class Registration {
        final View decor;
        final ViewTreeObserver observer;
        final ViewTreeObserver.OnGlobalFocusChangeListener focusListener;
        final ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        ButtonBinding binding;

        Registration(View decor, ViewTreeObserver observer,
                     ViewTreeObserver.OnGlobalFocusChangeListener focusListener,
                     ViewTreeObserver.OnGlobalLayoutListener layoutListener) {
            this.decor = decor;
            this.observer = observer;
            this.focusListener = focusListener;
            this.layoutListener = layoutListener;
        }
    }

    private static final class Candidate {
        final ViewGroup parent;
        final View branch;
        final View sendView;
        final int branchIndex;

        Candidate(ViewGroup parent, View branch, View sendView, int branchIndex) {
            this.parent = parent;
            this.branch = branch;
            this.sendView = sendView;
            this.branchIndex = branchIndex;
        }
    }

    private static final class ButtonBinding {
        final Activity activity;
        final EditText editor;
        final ViewGroup parent;
        final View sendView;
        final TextView button;
        final AtomicBoolean requestValid = new AtomicBoolean(true);
        boolean inFlight;
        String requestId;

        ButtonBinding(Activity activity, EditText editor, Candidate candidate, TextView button) {
            this.activity = activity;
            this.editor = editor;
            this.parent = candidate.parent;
            this.sendView = candidate.sendView;
            this.button = button;
        }
    }

    /** Called from Activity.onResume on the main thread. */
    public static void attach(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        ProjectLog.infoOnce("diagnostic.chatInputActivityAttach",
                "Discord chat input Activity attach entered");
        // Diagnostics must never prevent the actual button hook from attaching.
        try {
            ChatInputViewDiagnostics.attach(activity);
        } catch (Throwable failure) {
            ProjectLog.warn("chat input hierarchy diagnostics failed errorClass="
                    + failure.getClass().getSimpleName());
        }
        View decor = activity.getWindow().getDecorView();
        ViewTreeObserver observer = decor.getViewTreeObserver();
        if (!observer.isAlive()) return;

        synchronized (REGISTRATIONS) {
            Registration previous = REGISTRATIONS.get(activity);
            if (previous != null && previous.decor == decor
                    && previous.observer == observer && observer.isAlive()) {
                updateFocusedEditor(activity, previous, decor.findFocus());
                return;
            }
            detachLocked(activity);

            ViewTreeObserver.OnGlobalFocusChangeListener focusListener =
                    (oldFocus, newFocus) -> {
                        Registration current;
                        synchronized (REGISTRATIONS) {
                            current = REGISTRATIONS.get(activity);
                        }
                        if (current != null) {
                            updateFocusedEditor(activity, current, newFocus);
                        }
                    };
            ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> {
                Registration current;
                synchronized (REGISTRATIONS) {
                    current = REGISTRATIONS.get(activity);
                }
                if (current != null) {
                    View focused = current.decor.findFocus();
                    if (focused instanceof EditText) {
                        updateFocusedEditor(activity, current, focused);
                    } else if (current.binding != null
                            && !current.binding.editor.isAttachedToWindow()) {
                        removeBinding(current);
                    }
                }
            };

            Registration registration = new Registration(
                    decor, observer, focusListener, layoutListener);
            REGISTRATIONS.put(activity, registration);
            observer.addOnGlobalFocusChangeListener(focusListener);
            observer.addOnGlobalLayoutListener(layoutListener);
            updateFocusedEditor(activity, registration, decor.findFocus());
        }
    }

    /** Called from Activity.onDestroy on the main thread. */
    public static void detach(Activity activity) {
        ChatInputViewDiagnostics.detach(activity);
        if (activity == null) return;
        synchronized (REGISTRATIONS) {
            detachLocked(activity);
        }
    }

    private static void detachLocked(Activity activity) {
        Registration registration = REGISTRATIONS.remove(activity);
        if (registration == null) return;
        if (registration.observer.isAlive()) {
            registration.observer.removeOnGlobalFocusChangeListener(registration.focusListener);
            registration.observer.removeOnGlobalLayoutListener(registration.layoutListener);
        }
        removeBinding(registration);
    }

    /** Remove every listener and injected button before the runtime dispatcher is shut down. */
    public static void clear() {
        ChatInputViewDiagnostics.clear();
        synchronized (REGISTRATIONS) {
            if (REGISTRATIONS.isEmpty()) return;
        }
        if (!Scheduler.MainThread.runAndAwait(ChatInputTranslateButton::clearOnMainThread, 2000L)) {
            throw new IllegalStateException("Chat input buttons did not detach");
        }
    }

    private static void clearOnMainThread() {
        synchronized (REGISTRATIONS) {
            for (Registration registration : REGISTRATIONS.values()) {
                if (registration == null) continue;
                if (registration.observer.isAlive()) {
                    registration.observer.removeOnGlobalFocusChangeListener(registration.focusListener);
                    registration.observer.removeOnGlobalLayoutListener(registration.layoutListener);
                }
                removeBinding(registration);
            }
            REGISTRATIONS.clear();
        }
    }

    private static void updateFocusedEditor(Activity activity, Registration registration, View focused) {
        if (!(focused instanceof EditText)) {
            if (focused != null) {
                ProjectLog.infoOnce("chat-input-non-edittext-focus",
                        "chat input focus target is not EditText class="
                                + focused.getClass().getName());
            }
            if (registration.binding != null
                    && !registration.binding.editor.isAttachedToWindow()) {
                removeBinding(registration);
            }
            return;
        }
        EditText editor = (EditText) focused;
        ProjectLog.infoOnce("diagnostic.chatInputEditorFocused",
                "Discord chat input editor focused");
        if (registration.binding != null && registration.binding.editor == editor
                && registration.binding.button.getParent() == registration.binding.parent) {
            reportHierarchyIfEnabled(editor);
            return;
        }
        removeBinding(registration);

        Candidate candidate = findSendCandidate(editor);
        if (candidate == null) {
            ProjectLog.infoOnce("diagnostic.chatInputSendControlMissing",
                    "Discord chat input send control not found");
            ProjectLog.info("chat input send control not found near focused editor");
            return;
        }
        ProjectLog.infoOnce("diagnostic.chatInputSendControlFound",
                "Discord chat input send control found");

        TextView button = new TextView(activity);
        button.setText("翻译发送");
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setSingleLine(true);
        button.setAllCaps(false);
        button.setMinWidth(dp(activity, BUTTON_WIDTH_DP));
        button.setMinHeight(dp(activity, BUTTON_HEIGHT_DP));
        button.setPadding(dp(activity, 7), 0, dp(activity, 7), 0);
        button.setBackground(createButtonBackground(activity));
        button.setClickable(true);
        button.setFocusable(false);
        ViewGroup.LayoutParams params = copyLayoutParams(candidate.parent, candidate.branch);
        params.width = dp(activity, BUTTON_WIDTH_DP);
        params.height = dp(activity, BUTTON_HEIGHT_DP);
        try {
            int insertAt = Math.max(0, Math.min(candidate.branchIndex,
                    candidate.parent.getChildCount()));
            candidate.parent.addView(button, insertAt, params);
            ButtonBinding binding = new ButtonBinding(activity, editor, candidate, button);
            registration.binding = binding;
            button.setOnClickListener(view -> startTranslation(binding));
            ProjectLog.infoOnce("diagnostic.translationButtonAttached",
                    "Discord translation button attached");
            ProjectLog.info("chat input translate button attached editor="
                    + editor.getClass().getName() + " send="
                    + candidate.sendView.getClass().getName() + " label="
                    + sendActionLabel(candidate.sendView));
            button.post(() -> {
                if (button.getParent() != candidate.parent || !button.isAttachedToWindow()
                        || button.getWidth() <= 0 || button.getHeight() <= 0) {
                    ProjectLog.warn("chat input translate button layout verification failed parent="
                            + (button.getParent() == null ? "none"
                            : button.getParent().getClass().getName())
                            + " size=" + button.getWidth() + "x" + button.getHeight());
                } else {
                    ProjectLog.info("chat input translate button layout verified bounds="
                            + boundsOnScreen(button).toShortString());
                }
            });
            reportHierarchyIfEnabled(editor);
        } catch (Throwable failure) {
            ProjectLog.infoOnce("diagnostic.translationButtonAttachFailed",
                    "Discord translation button attach failed");
            ProjectLog.warn("chat input translate button attach failed errorClass="
                    + failure.getClass().getSimpleName());
        }
    }

    private static GradientDrawable createButtonBackground(Activity activity) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(88, 101, 242));
        drawable.setCornerRadius(dp(activity, 9));
        return drawable;
    }

    private static void removeBinding(Registration registration) {
        ButtonBinding binding = registration.binding;
        registration.binding = null;
        if (binding == null) return;
        binding.requestValid.set(false);
        if (binding.button.getParent() instanceof ViewGroup) {
            ((ViewGroup) binding.button.getParent()).removeView(binding.button);
        }
    }

    private static void startTranslation(ButtonBinding binding) {
        if (binding == null || binding.inFlight || !binding.requestValid.get()
                || !binding.editor.isAttachedToWindow()
                || !binding.sendView.isAttachedToWindow()) return;

        String source = binding.editor.getText() == null
                ? "" : binding.editor.getText().toString();
        if (source.trim().isEmpty()) {
            Toast.makeText(binding.activity, "请先输入要翻译的内容", Toast.LENGTH_SHORT).show();
            return;
        }

        Config.Snapshot base = io.github.wxmyyds.discordautotranslate.hooks.HookBridge
                .get().configSnapshot();
        if (base == null || base.configError != null || !base.isUsable()) {
            Toast.makeText(binding.activity, "翻译配置不可用，请检查 API 设置", Toast.LENGTH_SHORT).show();
            return;
        }
        Config.Snapshot outbound = base.forSendTranslation();
        String requestId = "outbound:" + NEXT_REQUEST.incrementAndGet();
        String hash = Scheduler.contentHash(source, outbound.version());
        TranslationTaskId task = TranslationTaskId.create(requestId, hash, 1);

        binding.inFlight = true;
        binding.requestId = requestId;
        binding.button.setText("翻译中…");
        binding.button.setEnabled(false);
        AtomicBoolean valid = new AtomicBoolean(true);
        binding.requestValid.set(true);

        Scheduler.get().submit(task, source, new JsonApiEngine(outbound),
                identity -> valid.get()
                        && binding.requestValid.get()
                        && HookRuntime.isActive()
                        && binding.editor.isAttachedToWindow()
                        && source.contentEquals(binding.editor.getText())
                        && base.version().equals(io.github.wxmyyds.discordautotranslate.hooks
                                .HookBridge.get().configSnapshot().version())
                        && base.sendTargetLang.equals(io.github.wxmyyds.discordautotranslate.hooks
                                .HookBridge.get().configSnapshot().sendTargetLang),
                (identity, accepted, translated, error) -> {
                    try (HookRuntime.Lease lease = HookRuntime.enter()) {
                        if (lease == null || !requestId.equals(binding.requestId)) return;
                        binding.inFlight = false;
                        binding.button.setEnabled(true);
                        binding.button.setText("翻译发送");

                        if (!accepted || error != null || translated == null
                                || translated.trim().isEmpty()) {
                            if (error != null) {
                                ProjectLog.warn("outbound translation failed errorClass="
                                        + error.getClass().getSimpleName());
                            }
                            Toast.makeText(binding.activity,
                                    accepted ? "翻译失败，原文未发送" : "输入内容或配置已变化，未发送",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }

                        String result = translated.trim();
                        binding.editor.setText(result);
                        binding.editor.setSelection(result.length());
                        // Let React Native's TextWatcher/event bridge receive the new text first.
                        binding.editor.postDelayed(() -> {
                            if (!binding.requestValid.get()
                                    || !binding.editor.isAttachedToWindow()
                                    || !binding.sendView.isAttachedToWindow()
                                    || !result.contentEquals(binding.editor.getText())) {
                                return;
                            }
                            boolean sent = false;
                            try {
                                sent = binding.sendView.performClick();
                            } catch (Throwable clickFailure) {
                                ProjectLog.warn("native send click failed errorClass="
                                        + clickFailure.getClass().getSimpleName());
                            }
                            if (!sent) {
                                binding.editor.setText(source);
                                binding.editor.setSelection(source.length());
                                Toast.makeText(binding.activity,
                                        "已翻译，但未能触发原发送按钮；已恢复原文",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }, 120L);
                    }
                });
    }

    private static Candidate findSendCandidate(EditText editor) {
        Rect inputBounds = boundsOnScreen(editor);
        View branch = editor;
        ViewParent ancestor = editor.getParent();
        for (int depth = 0; depth < 7 && ancestor instanceof ViewGroup; depth++) {
            ViewGroup parent = (ViewGroup) ancestor;
            int inputIndex = indexOf(parent, branch);
            Candidate best = null;
            int bestCenterX = Integer.MIN_VALUE;
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (i == inputIndex) continue;
                View child = parent.getChildAt(i);
                View sendAction = findSendAction(child, inputBounds, 0);
                if (sendAction == null) continue;
                Rect rect = boundsOnScreen(sendAction);
                if (rect.centerX() > bestCenterX) {
                    bestCenterX = rect.centerX();
                    best = new Candidate(parent, child, sendAction, i);
                }
            }
            if (best != null) {
                ProjectLog.info("chat input native send action found parent="
                        + parent.getClass().getName() + " branch="
                        + best.branch.getClass().getName() + " control="
                        + best.sendView.getClass().getName() + " label="
                        + sendActionLabel(best.sendView));
                return best;
            }
            branch = parent;
            ancestor = parent.getParent();
        }
        return null;
    }

    /**
     * Find Discord's actual send action by its accessibility label, not by proximity.
     * The emoji button is closer to the editor on current Discord layouts, so distance
     * alone is not a valid way to distinguish the send action.
     */
    private static View findSendAction(View view, Rect inputBounds, int depth) {
        if (view == null || depth > 7 || view.getVisibility() != View.VISIBLE
                || !view.isEnabled()) return null;

        if (view.isClickable() && isSendActionLabel(sendActionLabel(view))) {
            Rect rect = boundsOnScreen(view);
            int overlap = Math.max(0, Math.min(rect.bottom, inputBounds.bottom)
                    - Math.max(rect.top, inputBounds.top));
            int minHeight = Math.max(1, Math.min(rect.height(), inputBounds.height()));
            if (rect.centerX() > inputBounds.centerX()
                    && overlap * 3 >= minHeight
                    && rect.width() > 0 && rect.height() > 0) {
                return view;
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findSendAction(group.getChildAt(i), inputBounds, depth + 1);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static String sendActionLabel(View view) {
        if (view == null) return "";
        CharSequence description = view.getContentDescription();
        if (description != null && description.length() > 0) {
            return description.toString().trim();
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) return text.toString().trim();
        }
        return "";
    }

    private static boolean isSendActionLabel(String label) {
        if (label == null) return false;
        String normalized = label.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("发送")
                || normalized.equals("发送消息")
                || normalized.equals("send")
                || normalized.equals("send message")
                || normalized.equals("送信")
                || normalized.equals("보내기")
                || normalized.equals("enviar")
                || normalized.equals("envoyer")
                || normalized.equals("senden")
                || normalized.equals("отправить");
    }

    private static Rect boundsOnScreen(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Rect(location[0], location[1],
                location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    private static int indexOf(ViewGroup parent, View child) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) == child) return i;
        }
        return -1;
    }

    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup parent, View source) {
        ViewGroup.LayoutParams original = source.getLayoutParams();
        if (original != null) {
            for (Constructor<?> constructor : original.getClass().getConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length == 1 && types[0].isAssignableFrom(original.getClass())) {
                    try {
                        return (ViewGroup.LayoutParams) constructor.newInstance(original);
                    } catch (Throwable ignored) {
                        // Try the next compatible layout-parameter constructor.
                    }
                }
            }
        }
        int height = source.getHeight() > 0 ? source.getHeight() : BUTTON_HEIGHT_DP;
        return new ViewGroup.LayoutParams(BUTTON_WIDTH_DP, height);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void reportHierarchyIfEnabled(EditText editor) {
        if (!ProjectLog.isEnabled()) return;
        StringBuilder report = new StringBuilder("chat-input-editor class=")
                .append(editor.getClass().getName())
                .append(" parent=");
        ViewParent parent = editor.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 5) {
            report.append(((View) parent).getClass().getName()).append('/');
            parent = parent.getParent();
        }
        ProjectLog.info(report.toString());
    }
}
