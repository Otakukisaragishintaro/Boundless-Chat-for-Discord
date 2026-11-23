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
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.OutboundButtonConfig;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.TranslationTaskId;
import io.github.wxmyyds.discordautotranslate.translate.JsonApiEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adds an outbound-translation action in the Activity decor overlay.
 *
 * Discord's native send action is identified by its accessibility label and used only
 * for submission. The injected button is positioned independently of the composer
 * ViewGroup hierarchy so Discord's own layout parameters are never modified.
 */
public final class ChatInputTranslateButton {
    private static final int BUTTON_WIDTH_DP = 82;
    private static final int BUTTON_HEIGHT_DP = 38;
    private static final int MAX_SEND_CANDIDATE_RETRIES = 40;
    private static final AtomicLong NEXT_REQUEST = new AtomicLong();
    private static final Map<Activity, Registration> REGISTRATIONS = new WeakHashMap<>();

    private ChatInputTranslateButton() {
    }

    private static final class Registration {
        final View decor;
        final ViewTreeObserver observer;
        final ViewTreeObserver.OnGlobalFocusChangeListener focusListener;
        final ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        final List<ButtonBinding> bindings = new ArrayList<>();
        EditText pendingEditor;
        TextWatcher pendingTextWatcher;
        EditText retryEditor;
        Runnable retryRunnable;
        int retryCount;

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
        final FrameLayout overlay;
        View sendView;
        final TextView button;
        final OutboundButtonConfig outboundConfig;
        final int buttonIndex;
        final View.OnLayoutChangeListener positionListener;
        final AtomicBoolean requestValid = new AtomicBoolean(true);
        boolean positioned;
        boolean inFlight;
        String requestId;

        ButtonBinding(Activity activity, EditText editor, FrameLayout overlay,
                      View sendView, TextView button, OutboundButtonConfig outboundConfig,
                      int buttonIndex) {
            this.activity = activity;
            this.editor = editor;
            this.overlay = overlay;
            this.sendView = sendView;
            this.button = button;
            this.outboundConfig = outboundConfig;
            this.buttonIndex = buttonIndex;
            this.positionListener = (view, left, top, right, bottom,
                                     oldLeft, oldTop, oldRight, oldBottom) ->
                    positionButton(this);
            this.editor.addOnLayoutChangeListener(positionListener);
            this.overlay.addOnLayoutChangeListener(positionListener);
            if (this.sendView != null) {
            this.sendView.addOnLayoutChangeListener(positionListener);
        }
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
                            && !isComposerVisible(current.binding.editor)) {
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
        clearPendingEditorWatcher(registration);
        cancelSendCandidateRetry(registration);
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
                clearPendingEditorWatcher(registration);
                cancelSendCandidateRetry(registration);
                removeBinding(registration);
            }
            REGISTRATIONS.clear();
        }
    }

    private static void updateFocusedEditor(Activity activity, Registration registration, View focused) {
        if (!(focused instanceof EditText)) {
            clearPendingEditorWatcher(registration);
            cancelSendCandidateRetry(registration);
            if (focused != null) {
                ProjectLog.infoOnce("chat-input-non-edittext-focus",
                        "chat input focus target is not EditText class="
                                + focused.getClass().getName());
            }
            // A route change can leave Discord's composer View attached and technically
            // visible underneath the next screen. Focus leaving the editor is the reliable
            // navigation boundary; the button must not survive on another screen.
            if (!registration.bindings.isEmpty()) {
                removeBinding(registration);
            }
            return;
        }
        EditText editor = (EditText) focused;
        ensurePendingEditorWatcher(activity, registration, editor);
        ProjectLog.infoOnce("diagnostic.chatInputEditorFocused",
                "Discord chat input editor focused");
        if (!registration.bindings.isEmpty()
                && registration.bindings.get(0).editor == editor
                && registration.bindings.get(0).button.getParent()
                        == registration.bindings.get(0).overlay) {
            cancelSendCandidateRetry(registration);
            Candidate refreshed = findSendCandidate(editor);
            View refreshedSendView = refreshed != null && refreshed.sendView.isAttachedToWindow()
                    ? refreshed.sendView : null;
            for (ButtonBinding binding : registration.bindings) {
                if (binding.sendView != refreshedSendView) {
                    if (binding.sendView != null) {
                        binding.sendView.removeOnLayoutChangeListener(binding.positionListener);
                    }
                    binding.sendView = refreshedSendView;
                    if (refreshedSendView != null) {
                        refreshedSendView.addOnLayoutChangeListener(binding.positionListener);
                    }
                }
                positionButton(binding);
            }
            if (refreshedSendView == null) {
                ProjectLog.infoOnce("diagnostic.chatInputSendControlTemporarilyMissing",
                        "Discord native send control temporarily unavailable; anchoring translation buttons to editor");
            }
            reportHierarchyIfEnabled(editor);
            return;
        }
        removeBinding(registration);

        Candidate candidate = findSendCandidate(editor);
        boolean hasInputText = editor.getText() != null
                && !editor.getText().toString().trim().isEmpty();
        if (candidate == null && !hasInputText) {
            scheduleSendCandidateRetry(activity, registration, editor);
            ProjectLog.infoOnce("diagnostic.chatInputSendControlMissing",
                    "Discord chat input send control not found");
            ProjectLog.info("chat input send control not found near focused editor");
            return;
        }
        if (candidate != null) {
            ProjectLog.infoOnce("diagnostic.chatInputSendControlFound",
                    "Discord chat input send control found");
        } else {
            ProjectLog.infoOnce("diagnostic.chatInputFallbackAnchor",
                    "Discord translation button attached using editor fallback anchor");
        }

        if (!(registration.decor instanceof FrameLayout)) {
            ProjectLog.warn("chat input decor is not a FrameLayout; overlay injection skipped class="
                    + registration.decor.getClass().getName());
            return;
        }
        FrameLayout overlay = (FrameLayout) registration.decor;

        Config.Snapshot currentConfig = HookBridge.get().configSnapshot();
        List<OutboundButtonConfig> outboundButtons = currentConfig == null
                ? OutboundButtonConfig.defaults(Config.DEF_SEND_TARGET_LANG,
                        Config.DEF_SEND_SYSTEM_PROMPT) : currentConfig.outboundButtons;
        if (outboundButtons.isEmpty()) return;
        try {
            for (int buttonIndex = 0; buttonIndex < outboundButtons.size(); buttonIndex++) {
                OutboundButtonConfig outboundConfig = outboundButtons.get(buttonIndex);
                TextView button = new TextView(activity);
                button.setText(outboundConfig.label);
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
        // Do not render at FrameLayout's temporary origin while the IME/composer is settling.
        button.setVisibility(View.INVISIBLE);
        button.setElevation(dp(activity, 8));
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        dp(activity, BUTTON_WIDTH_DP), dp(activity, BUTTON_HEIGHT_DP),
                        Gravity.TOP | Gravity.LEFT);
                overlay.addView(button, params);
                ButtonBinding binding = new ButtonBinding(activity, editor, overlay,
                        candidate == null ? null : candidate.sendView, button,
                        outboundConfig, buttonIndex);
                registration.bindings.add(binding);
                button.setOnClickListener(view -> startTranslation(binding));
                positionButton(binding);
                final TextView attachedButton = button;
                button.post(() -> {
                    if (attachedButton.getParent() != overlay || !attachedButton.isAttachedToWindow()
                            || attachedButton.getWidth() <= 0 || attachedButton.getHeight() <= 0) {
                        ProjectLog.warn("chat input translate button layout verification failed");
                    } else {
                        ProjectLog.info("chat input translate button layout verified bounds="
                                + boundsOnScreen(attachedButton).toShortString());
                    }
                });
            }
            cancelSendCandidateRetry(registration);
            clearPendingEditorWatcher(registration);
            ProjectLog.infoOnce("diagnostic.translationButtonAttached",
                    "Discord outbound translation buttons attached count=" + outboundButtons.size());
            reportHierarchyIfEnabled(editor);
        } catch (Throwable failure) {
            removeBinding(registration);
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
        if (registration.bindings.isEmpty()) return;
        List<ButtonBinding> bindings = new ArrayList<>(registration.bindings);
        registration.bindings.clear();
        for (ButtonBinding binding : bindings) {
            binding.requestValid.set(false);
            binding.editor.removeOnLayoutChangeListener(binding.positionListener);
            if (binding.sendView != null) {
                binding.sendView.removeOnLayoutChangeListener(binding.positionListener);
            }
            binding.overlay.removeOnLayoutChangeListener(binding.positionListener);
            if (binding.button.getParent() instanceof ViewGroup) {
                ((ViewGroup) binding.button.getParent()).removeView(binding.button);
            }
        }
    }

    /** Position the action in the window overlay, independently of Discord's composer layout. */
    private static void positionButton(ButtonBinding binding) {
        if (binding == null || !isComposerUsable(binding.editor)
                || binding.overlay.getWidth() <= 0 || binding.overlay.getHeight() <= 0) return;
        Rect editorBounds = boundsOnScreen(binding.editor);
        Rect editorVisibleBounds = new Rect();
        if (!binding.editor.getGlobalVisibleRect(editorVisibleBounds)
                || editorVisibleBounds.width() <= 0 || editorVisibleBounds.height() <= 0
                || !editorVisibleBounds.contains(editorBounds)) {
            // During IME open/close Android can briefly report transient coordinates.
            // Keep the last confirmed location; never animate through the screen center.
            return;
        }
        Rect overlayBounds = boundsOnScreen(binding.overlay);
        if (!overlayBounds.contains(editorBounds)) return;
        boolean useEditorAnchor = binding.sendView == null
                || !binding.sendView.isAttachedToWindow();
        Rect anchor = useEditorAnchor ? editorBounds : boundsOnScreen(binding.sendView);
        if (useEditorAnchor) {
            // The composer may receive IME composing text before Discord exposes its
            // native send control. Keep the action visible above the editor meanwhile.
            if (editorBounds.width() <= 0 || editorBounds.height() <= 0) return;
        } else {
            int verticalOverlap = Math.max(0, Math.min(anchor.bottom, editorBounds.bottom)
                    - Math.max(anchor.top, editorBounds.top));
            if (anchor.width() <= 0 || anchor.height() <= 0
                    || anchor.centerX() <= editorBounds.centerX()
                    || verticalOverlap * 3 < Math.max(1,
                            Math.min(anchor.height(), editorBounds.height()))) {
                return;
            }
        }
        int[] overlayLocation = new int[2];
        binding.overlay.getLocationOnScreen(overlayLocation);
        int width = dp(binding.activity, BUTTON_WIDTH_DP);
        int height = dp(binding.activity, BUTTON_HEIGHT_DP);
        int margin = dp(binding.activity, 8);
        int left = anchor.right - width;
        int top = anchor.top - height - margin
                - binding.buttonIndex * (height + dp(binding.activity, 4));
        left -= overlayLocation[0];
        top -= overlayLocation[1];
        left = Math.max(0, Math.min(left, binding.overlay.getWidth() - width));
        top = Math.max(0, Math.min(top, binding.overlay.getHeight() - height));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                binding.button.getLayoutParams();
        boolean changed = params.width != width || params.height != height
                || params.gravity != (Gravity.TOP | Gravity.LEFT)
                || params.leftMargin != left || params.topMargin != top;
        if (changed) {
            params.width = width;
            params.height = height;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.leftMargin = left;
            params.topMargin = top;
            binding.button.setLayoutParams(params);
        }
        // Avoid requesting another layout pass when the overlay is already topmost.
        int childCount = binding.overlay.getChildCount();
        if (childCount > 0 && binding.overlay.getChildAt(childCount - 1) != binding.button) {
            binding.button.bringToFront();
        }
        binding.positioned = true;
        if (binding.button.getVisibility() != View.VISIBLE) {
            binding.button.setVisibility(View.VISIBLE);
        }
    }

    private static void startTranslation(ButtonBinding binding) {
        if (binding == null || binding.inFlight || !binding.requestValid.get()
                || !isComposerUsable(binding.editor)) return;

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
        Config.Snapshot outbound = base.forSendTranslation(binding.outboundConfig);
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
                        && base.version().equals(io.github.wxmyyds.discordautotranslate.hooks
                                .HookBridge.get().configSnapshot().version()),
                (identity, accepted, translated, error) -> {
                    try (HookRuntime.Lease lease = HookRuntime.enter()) {
                        if (lease == null || !requestId.equals(binding.requestId)) return;
                        binding.inFlight = false;
                        binding.button.setEnabled(true);
                        binding.button.setText(binding.outboundConfig.label);

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
                                    || !isComposerUsable(binding.editor)
                                    || !result.contentEquals(binding.editor.getText())) {
                                return;
                            }
                            // Discord may recreate or detach its native send view while
                            // the IME is visible. Resolve the current control at send time.
                            Candidate currentCandidate = findSendCandidate(binding.editor);
                            if (currentCandidate == null
                                    || !currentCandidate.sendView.isAttachedToWindow()) {
                                binding.editor.setText(source);
                                binding.editor.setSelection(source.length());
                                Toast.makeText(binding.activity,
                                        "已翻译，但未找到当前发送按钮；已恢复原文",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (binding.sendView != currentCandidate.sendView) {
                                if (binding.sendView != null) {
                                    binding.sendView.removeOnLayoutChangeListener(
                                            binding.positionListener);
                                }
                                binding.sendView = currentCandidate.sendView;
                                binding.sendView.addOnLayoutChangeListener(
                                        binding.positionListener);
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
     * Keep disabled send controls eligible: Discord disables the native send action
     * while the editor is empty, but the translation action must still be available.
     */
    private static View findSendAction(View view, Rect inputBounds, int depth) {
        if (view == null || depth > 7 || view.getVisibility() != View.VISIBLE) return null;

        if (isSendActionLabel(sendActionLabel(view))) {
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

    /** A usable editor may be temporarily clipped while Android is resizing for the IME. */
    private static boolean isComposerUsable(EditText editor) {
        return editor != null && editor.isAttachedToWindow()
                && editor.getVisibility() == View.VISIBLE && editor.isShown()
                && editor.getWidth() > 0 && editor.getHeight() > 0;
    }

    /** Strict visibility check is reserved for deciding whether navigation left the composer. */
    private static boolean isComposerVisible(EditText editor) {
        if (!isComposerUsable(editor)) return false;
        Rect visible = new Rect();
        return editor.getGlobalVisibleRect(visible)
                && visible.width() > 0 && visible.height() > 0;
    }

    private static void scheduleSendCandidateRetry(Activity activity,
                                                   Registration registration,
                                                   EditText editor) {
        if (registration.retryEditor != editor) {
            cancelSendCandidateRetry(registration);
            registration.retryEditor = editor;
        }
        if (registration.retryRunnable != null
                || registration.retryCount >= MAX_SEND_CANDIDATE_RETRIES) return;
        registration.retryCount++;
        Runnable retry = new Runnable() {
            @Override public void run() {
                if (registration.retryEditor != editor) return;
                registration.retryRunnable = null;
                if (!registration.bindings.isEmpty() || !editor.hasFocus()
                        || !isComposerUsable(editor)) {
                    cancelSendCandidateRetry(registration);
                    return;
                }
                updateFocusedEditor(activity, registration, editor);
            }
        };
        registration.retryRunnable = retry;
        // Discord's React composer may publish its native send control well after the
        // IME's first composing-text update. Keep checking for a short bounded window
        // instead of exhausting six animation frames before the control exists.
        registration.decor.postDelayed(retry, 100L);
    }

    private static void cancelSendCandidateRetry(Registration registration) {
        if (registration == null) return;
        if (registration.retryRunnable != null) {
            registration.decor.removeCallbacks(registration.retryRunnable);
        }
        registration.retryEditor = null;
        registration.retryRunnable = null;
        registration.retryCount = 0;
    }

    private static void ensurePendingEditorWatcher(Activity activity,
                                                   Registration registration,
                                                   EditText editor) {
        if (registration.pendingEditor == editor
                && registration.pendingTextWatcher != null) return;
        clearPendingEditorWatcher(registration);
        registration.pendingEditor = editor;
        registration.pendingTextWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable editable) {
                if (registration.bindings.isEmpty() && editor.hasFocus()
                        && isComposerUsable(editor)) {
                    cancelSendCandidateRetry(registration);
                    updateFocusedEditor(activity, registration, editor);
                }
            }
        };
        editor.addTextChangedListener(registration.pendingTextWatcher);
    }

    private static void clearPendingEditorWatcher(Registration registration) {
        if (registration == null || registration.pendingEditor == null
                || registration.pendingTextWatcher == null) return;
        registration.pendingEditor.removeTextChangedListener(registration.pendingTextWatcher);
        registration.pendingEditor = null;
        registration.pendingTextWatcher = null;
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
