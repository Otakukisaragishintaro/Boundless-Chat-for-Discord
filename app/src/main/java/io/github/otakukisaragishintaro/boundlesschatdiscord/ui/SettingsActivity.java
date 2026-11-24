package io.github.otakukisaragishintaro.boundlesschatdiscord.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.otakukisaragishintaro.boundlesschatdiscord.R;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Config;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.CacheCountProvider;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.OutboundButtonConfig;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.TranslationScope;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.ConfigurationSync;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.Protocols;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.MemoryPrefs;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.PreferenceCommit;
import io.github.otakukisaragishintaro.boundlesschatdiscord.core.ProjectLog;
import io.github.otakukisaragishintaro.boundlesschatdiscord.translate.ApiTester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import io.github.libxposed.service.XposedService;

/** 设置界面（需求 §5.7）。 */
public class SettingsActivity extends Activity implements XposedServiceConnectionHolder.Listener {

    private static final String LOCAL_PREFS_NAME = "settings_before_activation";
    private static final String STATE_DRAFT = "configuration_draft";
    private static final String STATE_ACTIVE_SETTINGS_PAGE = "active_settings_page";
    private static final String PAGE_CACHE = "cache";
    private static final String PAGE_API = "api";
    private static final String PAGE_EXPERIMENTAL = "experimental";

    private static final String[] TARGET_LANGUAGE_VALUES = {
            "简体中文", "繁體中文", "English", "日本語", "한국어", "Русский",
            "Українська", "Español", "Français", "Deutsch", "Português", "Italiano",
            "العربية", "فارسی", "हिन्दी", "বাংলা", "ไทย", "עברית", "Ελληνικά",
            "Tiếng Việt", "Türkçe", "Bahasa Indonesia"
    };
    /** 下拉框显示顺序：0=OpenAI-Responses，1=OpenAI-Compatible，2=Anthropic-Messages。 */
    private static final String[] PROTOCOL_ENTRIES =
            {"OpenAI-Responses", "OpenAI-Compatible", "Anthropic-Messages"};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService testExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final SettingsPersistence persistence =
            new SettingsPersistence(saveExecutor, action -> mainHandler.post(action));

    @Nullable
    private XposedService service;
    private SharedPreferences prefs;
    @Nullable
    private SharedPreferences remotePrefs;
    @Nullable
    private SharedPreferences cacheCountPrefs;
    /** Listen to the count persisted by CacheCountProvider in this app's private preferences. */
    private final SharedPreferences.OnSharedPreferenceChangeListener cacheCountListener =
            (shared, key) -> {
                if (CacheCountProvider.KEY_ENTRY_COUNT.equals(key)) {
                    mainHandler.post(this::updateCacheEntryCount);
                }
            };
    @Nullable
    private Future<?> testFuture;
    @Nullable
    private ApiTester.Call testCall;

    private long testGeneration;
    private volatile long serviceGeneration;
    private long subscribedConnectionGeneration = -1;
    private long formGeneration;
    private long syncAttemptGeneration = -1;
    private long syncJobGeneration;
    private boolean saveInProgress;
    private boolean syncInProgress;
    private boolean started;
    private volatile boolean destroyed;
    private boolean configurationLoaded;
    /** 各文本项最近一次已提交的值；用于选项即时保存时保留尚未点击应用的文本草稿。 */
    private final Map<EditText, String> committedTextValues = new IdentityHashMap<>();

    private Spinner protocolSpinner;
    private Spinner targetLanguageSpinner;
    private Spinner sendTargetLanguageSpinner;
    private ArrayAdapter<String> targetLanguageAdapter;
    private final List<OutboundButtonConfig> outboundButtonsDraft = new ArrayList<>();
    private Button manageOutboundButtonsButton;
    private Button advancedSettingsButton;
    private TextView outboundButtonsSummary;
    @Nullable private Dialog outboundButtonsDialog;
    @Nullable private Dialog advancedSettingsDialog;
    private final List<String> targetLanguageValues = new ArrayList<>();
    private boolean loadingUi;
    private boolean hasUnsavedChanges;
    private boolean persistedConfigInvalid;
    private CharSequence[] fieldHints;
    @Nullable
    private String baselineProtocol;
    @Nullable
    private String baselineTargetLanguage;
    @Nullable
    private String baselineSendTargetLanguage;
    private EditText urlInput;
    private EditText keyInput;
    private EditText modelInput;
    private EditText headersInput;
    private EditText systemPromptInput;
    private EditText sendSystemPromptInput;
    private Button resetSystemPromptButton;
    private Button resetSendSystemPromptButton;
    private EditText timeoutInput;
    private EditText concurrencyInput;
    private EditText minLengthPercentInput;
    private EditText maxLengthPercentInput;
    private Switch checkWrapperInput;
    private Switch stripOuterWrapperInput;
    private Switch checkSegmentMarkersInput;
    private Switch checkPlaceholdersInput;
    private Switch checkRetainedMarkersInput;
    private Switch checkLengthInput;
    private Switch protectMentionsInput;
    private Switch protectChannelsEmojisInput;
    private Switch protectUrlsInput;
    private Switch enabledInput;
    private Switch loggingInput;
    private Switch cacheEnabledInput;
    private EditText cacheLimitInput;
    private TextView cacheEntryCountText;
    private Button cacheSettingsButton;
    private Button apiSettingsButton;
    private Button experimentalSettingsButton;
    private Switch experimentalChunkingInput;
    private EditText experimentalChunkThresholdInput;
    private Button experimentalChunkThresholdApplyButton;
    private Button cacheApplyButton;
    private Button apiApplyButton;
    private LinearLayout cacheBacking;
    private LinearLayout apiBacking;
    private LinearLayout experimentalBacking;
    /** Main settings page stays attached so its scroll position and draft widgets are preserved. */
    @Nullable private FrameLayout navigationHost;
    @Nullable private ScrollView mainSettingsPage;
    @Nullable private View activeSubpageView;
    @Nullable private View cacheSettingsPage;
    @Nullable private View apiSettingsPage;
    @Nullable private View experimentalSettingsPage;
    @Nullable private String activeSettingsPage;
    private Button clearCacheButton;
    private Switch streamingInput;
    private TextView enabledHint;
    private TextView loggingHint;
    private TextView streamingHint;
    private TextView statusText;
    private TextView resultText;
    private EditText testTextInput;
    private EditText sendTestTextInput;
    private Button saveButton;
    private Button resetButton;
    private Button configCheckButton;
    private Button testConnectionButton;
    private Button testTranslationButton;
    private Button sendTestTranslationButton;
    private Button scopeButton;
    private TextView scopeSummary;
    private TranslationScope scopeDraft = TranslationScope.ALL;
    private Dialog scopeDialog;
    private Bundle pendingScopeEditor;
    private static final String STATE_SCOPE_EDITOR = "scope_editor";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Grant Discord narrowly scoped URI visibility for the cache-count provider.
        CacheCountProvider.grantDiscordVisibility(this);
        setTitle(R.string.module_name);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(false);
        }
        setContentView(buildContentView());
        prefs = getSharedPreferences(LOCAL_PREFS_NAME, MODE_PRIVATE);
        cacheCountPrefs = getSharedPreferences(CacheCountProvider.PREFERENCES_NAME, MODE_PRIVATE);
        installDirtyTracking();
        restoreDraft(savedInstanceState);
        pendingScopeEditor = savedInstanceState == null ? null : savedInstanceState.getBundle(STATE_SCOPE_EDITOR);
        loadIntoUi();
        updateSaveButtonState();
        if (!persistedConfigInvalid) {
            updateStatus(getString(R.string.status_local_ready));
        }
        String restoredPage = savedInstanceState == null ? null
                : savedInstanceState.getString(STATE_ACTIVE_SETTINGS_PAGE);
        if (PAGE_CACHE.equals(restoredPage)) {
            openCacheSettings();
        } else if (PAGE_API.equals(restoredPage)) {
            openApiSettings();
        } else if (PAGE_EXPERIMENTAL.equals(restoredPage)) {
            openExperimentalSettings();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (configurationLoaded && !saveInProgress && !syncInProgress && !hasUnsavedChanges) {
            loadIntoUi();
        }
        if (service != null && (XposedServiceConnectionHolder.currentService() == null
                || subscribedConnectionGeneration
                        != XposedServiceConnectionHolder.connectionGeneration())) {
            onServiceDied(service);
        }
        if (cacheCountPrefs != null) {
            cacheCountPrefs.registerOnSharedPreferenceChangeListener(cacheCountListener);
        }
        updateCacheEntryCount();
        XposedServiceConnectionHolder.bind(this);
        persistence.start();
        if (pendingScopeEditor != null && configurationLoaded && !persistence.isLoading()) openScopeEditor();
        updateSaveButtonState();
    }

    @Override
    protected void onStop() {
        started = false;
        persistence.stop();
        if (scopeDialog != null) {
            pendingScopeEditor = TranslationScopeEditor.saveState(scopeDialog);
            scopeDialog.dismiss(); scopeDialog = null;
        }
        if (outboundButtonsDialog != null) {
            outboundButtonsDialog.dismiss();
            outboundButtonsDialog = null;
        }
        if (advancedSettingsDialog != null) {
            advancedSettingsDialog.dismiss();
            advancedSettingsDialog = null;
        }
        if (cacheCountPrefs != null) {
            cacheCountPrefs.unregisterOnSharedPreferenceChangeListener(cacheCountListener);
        }
        XposedServiceConnectionHolder.unbind(this);
        cancelPendingTest();
        updateSaveButtonState();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        persistence.destroy();
        mainHandler.removeCallbacksAndMessages(null);
        cancelPendingTest();
        testExecutor.shutdownNow();
        saveExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ACTIVE_SETTINGS_PAGE, activeSettingsPage);
        if (scopeDialog != null) outState.putBundle(STATE_SCOPE_EDITOR, TranslationScopeEditor.saveState(scopeDialog));
        else if (pendingScopeEditor != null) outState.putBundle(STATE_SCOPE_EDITOR, pendingScopeEditor);
        if (persistedConfigInvalid || !hasUnsavedChanges) {
            return;
        }
        Bundle draft = new Bundle();
        EditText[] fields = configurationFields();
        for (int i = 0; i < fields.length; i++) {
            draft.putString("field_" + i, fields[i].getText().toString());
        }
        draft.putBoolean("enabled", enabledInput.isChecked());
        draft.putString("scope", scopeDraft.toJson());
        draft.putBoolean("logging", loggingInput.isChecked());
        draft.putBoolean("cache_enabled", cacheEnabledInput.isChecked());
        draft.putString("cache_limit", cacheLimitInput.getText().toString());
        draft.putBoolean("streaming", streamingInput.isChecked());
        draft.putBoolean("check_wrapper", checkWrapperInput.isChecked());
        draft.putBoolean("strip_outer_wrapper", stripOuterWrapperInput.isChecked());
        draft.putBoolean("check_segments", checkSegmentMarkersInput.isChecked());
        draft.putBoolean("check_placeholders", checkPlaceholdersInput.isChecked());
        draft.putBoolean("check_retained", checkRetainedMarkersInput.isChecked());
        draft.putBoolean("check_length", checkLengthInput.isChecked());
        draft.putBoolean("protect_mentions", protectMentionsInput.isChecked());
        draft.putBoolean("protect_channels_emojis", protectChannelsEmojisInput.isChecked());
        draft.putBoolean("protect_urls", protectUrlsInput.isChecked());
        draft.putBoolean("experimental_long_message_chunking",
                experimentalChunkingInput.isChecked());
        draft.putString("experimental_long_message_threshold",
                experimentalChunkThresholdInput.getText().toString());
        draft.putInt("protocol", protocolSpinner.getSelectedItemPosition());
        draft.putString("language", selectedTargetLanguage());
        draft.putString("send_language", selectedSendTargetLanguage());
        draft.putString("outbound_buttons", OutboundButtonConfig.toJson(outboundButtonsDraft));
        draft.putString("baseline_protocol", baselineProtocol);
        draft.putString("baseline_language", baselineTargetLanguage);
        draft.putString("baseline_send_language", baselineSendTargetLanguage);
        draft.putBoolean("dirty", hasUnsavedChanges);
        outState.putBundle(STATE_DRAFT, draft);
    }

    private void restoreDraft(@Nullable Bundle savedState) {
        Bundle draft = savedState == null ? null : savedState.getBundle(STATE_DRAFT);
        if (draft == null || !draft.getBoolean("dirty") || persistedConfigInvalid) {
            return;
        }
        loadingUi = true;
        try {
            EditText[] fields = configurationFields();
            for (int i = 0; i < fields.length; i++) {
                fields[i].setText(draft.getString("field_" + i));
            }
            enabledInput.setChecked(draft.getBoolean("enabled"));
            scopeDraft = TranslationScope.parse(draft.getString("scope"));
            updateScopeSummary();
            loggingInput.setChecked(draft.getBoolean("logging", Config.DEF_LOGGING_ENABLED));
            cacheEnabledInput.setChecked(draft.getBoolean("cache_enabled", Config.DEF_CACHE_ENABLED));
            cacheLimitInput.setText(draft.getString("cache_limit", String.valueOf(Config.DEF_CACHE_LIMIT)));
            protocolSpinner.setSelection(draft.getInt("protocol"));
            streamingInput.setChecked(draft.getBoolean("streaming"));
            checkWrapperInput.setChecked(draft.getBoolean("check_wrapper", Config.DEF_CHECK_WRAPPER));
            stripOuterWrapperInput.setChecked(draft.getBoolean("strip_outer_wrapper",
                    Config.DEF_STRIP_OUTER_WRAPPER));
            checkSegmentMarkersInput.setChecked(draft.getBoolean("check_segments",
                    Config.DEF_CHECK_SEGMENT_MARKERS));
            checkPlaceholdersInput.setChecked(draft.getBoolean("check_placeholders",
                    Config.DEF_CHECK_PLACEHOLDERS));
            checkRetainedMarkersInput.setChecked(draft.getBoolean("check_retained",
                    Config.DEF_CHECK_RETAINED_MARKERS));
            checkLengthInput.setChecked(draft.getBoolean("check_length", Config.DEF_CHECK_LENGTH));
            protectMentionsInput.setChecked(draft.getBoolean("protect_mentions",
                    Config.DEF_PROTECT_MENTIONS));
            protectChannelsEmojisInput.setChecked(draft.getBoolean("protect_channels_emojis",
                    Config.DEF_PROTECT_CHANNELS_EMOJIS));
            protectUrlsInput.setChecked(draft.getBoolean("protect_urls", Config.DEF_PROTECT_URLS));
            experimentalChunkingInput.setChecked(draft.getBoolean("experimental_long_message_chunking",
                    Config.DEF_EXPERIMENTAL_LONG_MESSAGE_CHUNKING));
            experimentalChunkThresholdInput.setText(draft.getString(
                    "experimental_long_message_threshold",
                    String.valueOf(Config.DEF_EXPERIMENTAL_LONG_MESSAGE_THRESHOLD)));
            String language = draft.getString("language");
            ensureTargetLanguageAvailable(language);
            targetLanguageSpinner.setSelection(targetLanguagePosition(language));
            String sendLanguage = draft.getString("send_language", Config.DEF_SEND_TARGET_LANG);
            ensureTargetLanguageAvailable(sendLanguage);
            sendTargetLanguageSpinner.setSelection(targetLanguagePosition(sendLanguage));
            String buttonJson = draft.getString("outbound_buttons");
            if (buttonJson != null) {
                outboundButtonsDraft.clear();
                outboundButtonsDraft.addAll(OutboundButtonConfig.parse(buttonJson,
                        sendLanguage, sendSystemPromptInput.getText().toString()));
            }
            updateOutboundButtonsSummary();
            baselineProtocol = draft.getString("baseline_protocol");
            baselineTargetLanguage = draft.getString("baseline_language");
            baselineSendTargetLanguage = draft.getString("baseline_send_language",
                    Config.DEF_SEND_TARGET_LANG);
            hasUnsavedChanges = draft.getBoolean("dirty");
            formGeneration++;
            updateStreamingAvailability();
        } finally {
            loadingUi = false;
        }
    }

    // ------------------------------------------------------------ 服务连接

    @Override
    public void onServiceConnected(@NonNull XposedService connectedService) {
        if (!started || destroyed || prefs == null || service == connectedService) {
            return;
        }
        service = connectedService;
        subscribedConnectionGeneration = XposedServiceConnectionHolder.connectionGeneration();
        serviceGeneration++;
        syncJobGeneration++;
        syncInProgress = false;
        try {
            remotePrefs = connectedService.getRemotePreferences(Config.GROUP);
            updateCacheEntryCount();
        } catch (Throwable t) {
            remotePrefs = null;
            ProjectLog.warn("settings sync access status=FAILED exception="
                    + t.getClass().getSimpleName());
            updateStatus(getString(R.string.status_sync_pending));
            updateSaveButtonState();
            return;
        }
        startServiceSync();
    }

    private void startServiceSync() {
        if (!started || destroyed || service == null || remotePrefs == null
                || !configurationLoaded || persistence.isLoading()
                || saveInProgress || syncInProgress || scopeDialog != null || pendingScopeEditor != null
                || syncAttemptGeneration == serviceGeneration) {
            return;
        }
        // One attempt per actual connection generation, not per Activity start/replay.
        syncAttemptGeneration = serviceGeneration;
        final long generation = serviceGeneration;
        final long importFormGeneration = formGeneration;
        final long job = ++syncJobGeneration;
        final XposedService connectedService = service;
        final SharedPreferences local = prefs;
        final SharedPreferences remote = remotePrefs;
        syncInProgress = true;
        updateSaveButtonState();
        updateStatus(getString(R.string.status_syncing));
        ProjectLog.info("settings sync start");
        try {
            saveExecutor.execute(() -> {
                final ConfigurationSync.Result result;
                final ConfigurationSync.LocalState localState;
                synchronized (PreferenceCommit.class) {
                    if (destroyed || generation != serviceGeneration) {
                        ProjectLog.info("settings sync end status=CANCELLED");
                        return;
                    }
                    result = ConfigurationSync.synchronize(local, remote);
                    localState = ConfigurationSync.loadLocal(local);
                    recordConfigurationResult("sync", result, localState.snapshot);
                }
                persistence.post(() -> {
                    if (!started || destroyed || local != prefs) {
                        return;
                    }
                    // Refresh only confirmed authority; never load a failed initial import.
                    boolean localConfirmed = result.imported()
                            || result.direction == ConfigurationSync.Direction.EXPORT;
                    if (localConfirmed && !saveInProgress && !hasUnsavedChanges
                            && importFormGeneration == formGeneration) {
                        applyLocalState(localState, true);
                    }
                    if (generation != serviceGeneration || job != syncJobGeneration
                            || remote != remotePrefs) {
                        return;
                    }
                    syncInProgress = false;
                    if (localState.poisoned) {
                        showInvalidSavedConfig();
                        showPersistenceFailure(true,
                                getString(R.string.status_save_persist_failed));
                        return;
                    }
                    if (result.status == ConfigurationSync.Status.INVALID_SOURCE) {
                        // Invalid remote data cannot invalidate/erase the local draft.
                        if (result.direction != ConfigurationSync.Direction.IMPORT) {
                            showInvalidSavedConfig();
                        }
                        updateSaveButtonState();
                        updateStatus(getString(R.string.status_invalid_saved_config));
                        return;
                    }
                    updateSaveButtonState();
                    updateStatus(result.succeeded()
                            ? getString(R.string.status_connected,
                                    connectedService.getFrameworkVersion(),
                                    connectedService.getApiVersion())
                            : getString(R.string.status_sync_failed));
                });
            });
        } catch (Throwable t) {
            ProjectLog.warn("settings sync end status=FAILED exception="
                    + t.getClass().getSimpleName());
            syncInProgress = false;
            updateSaveButtonState();
            updateStatus(getString(R.string.status_sync_failed));
        }
    }

    @Override
    public void onServiceDied(@NonNull XposedService deadService) {
        if (!started || destroyed || service != deadService) {
            return;
        }
        service = null;
        remotePrefs = null;
        serviceGeneration++;
        syncJobGeneration++;
        syncInProgress = false;
        updateSaveButtonState();
        updateStatus(getString(R.string.status_local_ready));
    }

    // ------------------------------------------------------------ UI 构建

    @SuppressWarnings("deprecation") // Platform ActionBar dispatches its height via legacy content insets.
    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        statusText = new TextView(this);
        statusText.setTextSize(12f);
        root.addView(statusText, matchWrap());

        enabledInput = new Switch(this);
        enabledHint = addToggle(root, enabledInput, SettingsToggleText.Setting.TRANSLATION);

        scopeButton = new Button(this);
        scopeButton.setText(R.string.scope_title);
        scopeButton.setOnClickListener(view -> openScopeEditor());
        root.addView(scopeButton, matchWrap());
        scopeSummary = new TextView(this);
        scopeSummary.setTextSize(12f);
        root.addView(scopeSummary, matchWrap());
        updateScopeSummary();

        loggingInput = new Switch(this);
        loggingHint = addToggle(root, loggingInput, SettingsToggleText.Setting.LOGGING);
        TextView loggingPrivacy = new TextView(this);
        loggingPrivacy.setTextSize(12f);
        loggingPrivacy.setText(R.string.hint_logging_privacy);
        root.addView(loggingPrivacy, matchWrap());

        // 本地缓存配置集中放入独立二级菜单，主设置页只保留入口。
        cacheBacking = new LinearLayout(this);
        cacheBacking.setOrientation(LinearLayout.VERTICAL);
        TextView cacheTitle = new TextView(this);
        cacheTitle.setText(R.string.label_translation_cache);
        cacheBacking.addView(cacheTitle, matchWrap());
        // Use a dedicated row instead of relying on Switch's compound-text rendering.
        // This keeps the label and control measurable and visible on the first cold launch.
        LinearLayout cacheEnabledRow = new LinearLayout(this);
        cacheEnabledRow.setOrientation(LinearLayout.HORIZONTAL);
        cacheEnabledRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        cacheEnabledRow.setMinimumHeight(dp(56));
        cacheEnabledRow.setVisibility(View.VISIBLE);

        TextView cacheEnabledLabel = new TextView(this);
        cacheEnabledLabel.setText(R.string.label_cache_enabled);
        cacheEnabledLabel.setTextSize(16f);
        cacheEnabledLabel.setGravity(android.view.Gravity.CENTER_VERTICAL);
        cacheEnabledLabel.setMinimumHeight(dp(48));
        cacheEnabledLabel.setClickable(true);
        cacheEnabledLabel.setOnClickListener(v ->
                cacheEnabledInput.setChecked(!cacheEnabledInput.isChecked()));
        cacheEnabledRow.addView(cacheEnabledLabel, new LinearLayout.LayoutParams(
                0, dp(48), 1f));

        cacheEnabledInput = new Switch(this);
        cacheEnabledInput.setShowText(false);
        cacheEnabledInput.setMinimumHeight(dp(48));
        cacheEnabledInput.setMinimumWidth(dp(64));
        cacheEnabledInput.setVisibility(View.VISIBLE);
        cacheEnabledRow.addView(cacheEnabledInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        cacheBacking.addView(cacheEnabledRow, matchWrap());
        cacheEnabledInput.setOnCheckedChangeListener((button, checked) -> {
            updateCacheControls();
            markDirty();
            if (!loadingUi) saveOptionsOnly();
        });
        TextView cacheHint = new TextView(this);
        cacheHint.setTextSize(12f);
        cacheHint.setText(R.string.hint_translation_cache);
        cacheBacking.addView(cacheHint, matchWrap());
        TextView cacheLimitLabel = new TextView(this);
        cacheLimitLabel.setText(R.string.label_cache_limit);
        cacheBacking.addView(cacheLimitLabel, matchWrap());
        cacheLimitInput = addField(cacheBacking, "", getString(R.string.hint_cache_limit),
                InputType.TYPE_CLASS_NUMBER);
        cacheEntryCountText = new TextView(this);
        cacheEntryCountText.setTextSize(13f);
        cacheBacking.addView(cacheEntryCountText, matchWrap());
        TextView cacheRecommendation = new TextView(this);
        cacheRecommendation.setTextSize(12f);
        cacheRecommendation.setText(R.string.hint_cache_recommended);
        cacheBacking.addView(cacheRecommendation, matchWrap());
        cacheApplyButton = new Button(this);
        cacheApplyButton.setText(R.string.button_apply_cache_limit);
        cacheApplyButton.setOnClickListener(v -> {
            if (parseCacheLimitInput() <= 0) {
                cacheLimitInput.setError(getString(R.string.error_cache_limit));
                return;
            }
            saveSingleField(cacheLimitInput);
        });
        cacheBacking.addView(cacheApplyButton, matchWrap());
        clearCacheButton = new Button(this);
        clearCacheButton.setText(R.string.button_clear_translation_cache);
        clearCacheButton.setOnClickListener(v -> confirmClearTranslationCache());
        cacheBacking.addView(clearCacheButton, matchWrap());
        cacheSettingsButton = new Button(this);
        cacheSettingsButton.setText(R.string.menu_translation_cache);
        cacheSettingsButton.setOnClickListener(v -> openCacheSettings());
        root.addView(cacheSettingsButton, matchWrap());
        updateCacheControls();

        // API、服务协议与流式请求集中在独立二级菜单中。
        apiBacking = new LinearLayout(this);
        apiBacking.setOrientation(LinearLayout.VERTICAL);
        // 供应商协议：请求体与译文解析按所选协议内置实现，
        // 不再让用户手写 JSON 请求模板与译文提取路径。
        TextView protocolLabel = new TextView(this);
        protocolLabel.setText(R.string.label_protocol);
        protocolLabel.setPadding(0, dp(8), 0, 0);
        apiBacking.addView(protocolLabel, matchWrap());
        protocolSpinner = new Spinner(this);
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PROTOCOL_ENTRIES);
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocolSpinner.setAdapter(protocolAdapter);
        protocolSpinner.setSelection(positionOf(Config.DEF_PROTOCOL));
        apiBacking.addView(protocolSpinner, matchWrap());
        protocolSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateStreamingAvailability();
                markSpinnerDirty();
                if (!loadingUi && hasUnsavedChanges) saveOptionsOnly();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateStreamingAvailability();
                markSpinnerDirty();
                if (!loadingUi && hasUnsavedChanges) saveOptionsOnly();
            }
        });
        TextView protocolHint = new TextView(this);
        protocolHint.setTextSize(12f);
        protocolHint.setText(R.string.hint_protocol);
        apiBacking.addView(protocolHint, matchWrap());

        streamingInput = new Switch(this);
        streamingHint = addToggle(apiBacking, streamingInput, SettingsToggleText.Setting.STREAMING);
        TextView toggleDraftHint = new TextView(this);
        toggleDraftHint.setTextSize(12f);
        toggleDraftHint.setText(R.string.hint_switch_draft);
        apiBacking.addView(toggleDraftHint, matchWrap());

        // 注意：TYPE_TEXT_VARIATION_* 必须与 TYPE_CLASS_TEXT 组合使用。
        urlInput = addField(apiBacking, getString(R.string.label_api_url),
                getString(R.string.hint_api_url),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addFieldApplyButton(apiBacking, urlInput);
        keyInput = addField(apiBacking, getString(R.string.label_api_key), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addFieldApplyButton(apiBacking, keyInput);
        modelInput = addField(apiBacking, getString(R.string.label_model), Config.DEF_MODEL,
                InputType.TYPE_CLASS_TEXT);
        addFieldApplyButton(apiBacking, modelInput);
        TextView targetLanguageLabel = new TextView(this);
        targetLanguageLabel.setText(R.string.label_target_language);
        targetLanguageLabel.setPadding(0, dp(8), 0, 0);
        apiBacking.addView(targetLanguageLabel, matchWrap());
        targetLanguageSpinner = new Spinner(this);
        targetLanguageValues.clear();
        targetLanguageValues.addAll(Arrays.asList(TARGET_LANGUAGE_VALUES));
        String[] labels = getResources().getStringArray(R.array.target_language_labels);
        targetLanguageAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>(Arrays.asList(labels)));
        targetLanguageAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        targetLanguageSpinner.setAdapter(targetLanguageAdapter);
        targetLanguageSpinner.setSelection(targetLanguagePosition(Config.DEF_TARGET_LANG));
        targetLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                markSpinnerDirty();
                if (!loadingUi && hasUnsavedChanges) saveOptionsOnly();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                markSpinnerDirty();
                if (!loadingUi && hasUnsavedChanges) saveOptionsOnly();
            }
        });
        apiBacking.addView(targetLanguageSpinner, matchWrap());
        // Legacy single-language spinner is retained only as a migration bridge; it is not shown.
        sendTargetLanguageSpinner = new Spinner(this);
        sendTargetLanguageSpinner.setAdapter(targetLanguageAdapter);
        sendTargetLanguageSpinner.setSelection(targetLanguagePosition(Config.DEF_SEND_TARGET_LANG));
        headersInput = addField(apiBacking, getString(R.string.label_headers), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        addFieldApplyButton(apiBacking, headersInput);

        apiApplyButton = new Button(this);
        apiApplyButton.setText(R.string.button_apply_api_settings);
        apiApplyButton.setOnClickListener(v -> save());
        apiBacking.addView(apiApplyButton, matchWrap());
        apiSettingsButton = new Button(this);
        apiSettingsButton.setText(R.string.menu_api_settings);
        apiSettingsButton.setOnClickListener(v -> openApiSettings());
        root.addView(apiSettingsButton, matchWrap());

        // All experimental behavior is isolated behind one opt-in submenu.
        experimentalBacking = new LinearLayout(this);
        experimentalBacking.setOrientation(LinearLayout.VERTICAL);
        experimentalChunkingInput = new Switch(this);
        experimentalChunkingInput.setText(R.string.label_experimental_long_message_chunking);
        experimentalChunkingInput.setShowText(false);
        experimentalChunkingInput.setMinimumHeight(dp(48));
        experimentalBacking.addView(experimentalChunkingInput, matchWrap());
        TextView experimentalChunkingHint = new TextView(this);
        experimentalChunkingHint.setTextSize(12f);
        experimentalChunkingHint.setText(R.string.hint_experimental_long_message_chunking);
        experimentalBacking.addView(experimentalChunkingHint, matchWrap());
        experimentalChunkThresholdInput = addField(experimentalBacking,
                getString(R.string.label_experimental_long_message_threshold),
                getString(R.string.hint_experimental_long_message_threshold),
                InputType.TYPE_CLASS_NUMBER);
        experimentalChunkThresholdApplyButton = new Button(this);
        experimentalChunkThresholdApplyButton.setText(R.string.button_apply_experimental_threshold);
        experimentalChunkThresholdApplyButton.setOnClickListener(v -> {
            String thresholdText = experimentalChunkThresholdInput.getText().toString().trim();
            int value;
            if (thresholdText.isEmpty()) {
                value = Config.DEF_EXPERIMENTAL_LONG_MESSAGE_THRESHOLD;
            } else {
                try {
                    value = Integer.parseInt(thresholdText);
                } catch (NumberFormatException invalid) {
                    experimentalChunkThresholdInput.setError(getString(
                            R.string.error_experimental_long_message_threshold));
                    return;
                }
            }
            if (value < Config.MIN_EXPERIMENTAL_LONG_MESSAGE_THRESHOLD
                    || value > Config.MAX_EXPERIMENTAL_LONG_MESSAGE_THRESHOLD) {
                experimentalChunkThresholdInput.setError(getString(
                        R.string.error_experimental_long_message_threshold));
                return;
            }
            experimentalChunkThresholdInput.setError(null);
            saveSingleField(experimentalChunkThresholdInput);
        });
        experimentalBacking.addView(experimentalChunkThresholdApplyButton, matchWrap());
        experimentalChunkingInput.setOnCheckedChangeListener((button, checked) -> {
            markDirty();
            if (!loadingUi) saveOptionsOnly();
        });
        experimentalSettingsButton = new Button(this);
        experimentalSettingsButton.setText(R.string.menu_experimental_features);
        experimentalSettingsButton.setOnClickListener(v -> openExperimentalSettings());
        root.addView(experimentalSettingsButton, matchWrap());

        TextView outboundTitle = new TextView(this);
        outboundTitle.setText(getString(R.string.label_outbound_section));
        outboundTitle.setPadding(0, dp(10), 0, 0);
        root.addView(outboundTitle, matchWrap());
        outboundButtonsSummary = new TextView(this);
        outboundButtonsSummary.setTextSize(12f);
        root.addView(outboundButtonsSummary, matchWrap());
        manageOutboundButtonsButton = new Button(this);
        manageOutboundButtonsButton.setText(getString(R.string.button_manage_outbound));
        manageOutboundButtonsButton.setOnClickListener(v -> openOutboundButtonsEditor());
        root.addView(manageOutboundButtonsButton, matchWrap());
        advancedSettingsButton = new Button(this);
        advancedSettingsButton.setText(getString(R.string.button_advanced_settings));
        advancedSettingsButton.setOnClickListener(v -> openAdvancedSettings());
        root.addView(advancedSettingsButton, matchWrap());

        // Advanced controls stay in a hidden backing container so existing save/import
        // transactions continue to use the same validated fields.
        LinearLayout advancedBacking = new LinearLayout(this);
        advancedBacking.setOrientation(LinearLayout.VERTICAL);
        advancedBacking.setVisibility(View.GONE);
        systemPromptInput = addField(advancedBacking,
                getString(R.string.label_incoming_prompt),
                getString(R.string.hint_system_prompt),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        resetSystemPromptButton = addPromptResetButton(advancedBacking, systemPromptInput,
                Config.DEF_SYSTEM_PROMPT);
        sendSystemPromptInput = addField(advancedBacking,
                getString(R.string.label_new_outbound_prompt),
                getString(R.string.hint_default_outbound_prompt),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        resetSendSystemPromptButton = addPromptResetButton(advancedBacking, sendSystemPromptInput,
                Config.DEF_SEND_SYSTEM_PROMPT);
        checkWrapperInput = addValidationSwitch(advancedBacking,
                getString(R.string.validation_check_wrapper), Config.DEF_CHECK_WRAPPER);
        stripOuterWrapperInput = addValidationSwitch(advancedBacking,
                getString(R.string.validation_strip_wrapper),
                Config.DEF_STRIP_OUTER_WRAPPER);
        checkSegmentMarkersInput = addValidationSwitch(advancedBacking,
                getString(R.string.validation_check_segments),
                Config.DEF_CHECK_SEGMENT_MARKERS);
        checkPlaceholdersInput = addValidationSwitch(advancedBacking,
                getString(R.string.validation_check_placeholders),
                Config.DEF_CHECK_PLACEHOLDERS);
        checkRetainedMarkersInput = addValidationSwitch(advancedBacking,
                getString(R.string.validation_check_retained),
                Config.DEF_CHECK_RETAINED_MARKERS);
        checkLengthInput = addValidationSwitch(advancedBacking,
                getString(R.string.validation_check_length),
                Config.DEF_CHECK_LENGTH);
        protectMentionsInput = addValidationSwitch(advancedBacking,
                getString(R.string.protection_discord_mentions), Config.DEF_PROTECT_MENTIONS);
        protectChannelsEmojisInput = addValidationSwitch(advancedBacking,
                getString(R.string.protection_discord_channels_emojis),
                Config.DEF_PROTECT_CHANNELS_EMOJIS);
        protectUrlsInput = addValidationSwitch(advancedBacking,
                getString(R.string.protection_urls), Config.DEF_PROTECT_URLS);
        minLengthPercentInput = addField(advancedBacking,
                getString(R.string.label_min_translation_length),
                String.valueOf(Config.DEF_MIN_LENGTH_PERCENT), InputType.TYPE_CLASS_NUMBER);
        maxLengthPercentInput = addField(advancedBacking,
                getString(R.string.label_max_translation_length),
                String.valueOf(Config.DEF_MAX_LENGTH_PERCENT), InputType.TYPE_CLASS_NUMBER);
        root.addView(advancedBacking, matchWrap());

        timeoutInput = addField(apiBacking, getString(R.string.label_timeout),
                String.valueOf(Config.DEF_TIMEOUT_MS), InputType.TYPE_CLASS_NUMBER);
        addFieldApplyButton(apiBacking, timeoutInput);
        concurrencyInput = addField(apiBacking, getString(R.string.label_concurrency),
                String.valueOf(Config.DEF_MAX_CONCURRENCY), InputType.TYPE_CLASS_NUMBER);
        addFieldApplyButton(apiBacking, concurrencyInput);

        testTextInput = addField(root, getString(R.string.label_test_text),
                getString(R.string.hint_test_text),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        sendTestTextInput = addField(root, getString(R.string.label_send_test_text),
                getString(R.string.hint_send_test_text),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        fieldHints = new CharSequence[configurationFields().length];
        EditText[] fields = configurationFields();
        for (int i = 0; i < fields.length; i++) {
            fieldHints[i] = fields[i].getHint();
        }

        TextView privacy = new TextView(this);
        privacy.setTextSize(12f);
        privacy.setText(R.string.privacy_notice);
        root.addView(privacy, matchWrap());

        // 总保存按钮已移除；各设置分区通过各自的应用操作提交。
        saveButton = null;

        resetButton = new Button(this);
        resetButton.setText(R.string.button_reset_saved_settings);
        resetButton.setVisibility(View.GONE);
        resetButton.setOnClickListener(v -> confirmResetSavedSettings());
        root.addView(resetButton, matchWrap());

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);

        configCheckButton = new Button(this);
        configCheckButton.setText(R.string.button_config_check);
        configCheckButton.setOnClickListener(v -> runSelfCheck());
        testRow.addView(configCheckButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testConnectionButton = new Button(this);
        testConnectionButton.setText(R.string.button_test_connection);
        testConnectionButton.setOnClickListener(v -> runTestTranslation(true));
        testRow.addView(testConnectionButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testTranslationButton = new Button(this);
        testTranslationButton.setText(R.string.button_test_translation);
        testTranslationButton.setOnClickListener(v -> runTestTranslation(false));
        testRow.addView(testTranslationButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(testRow, matchWrap());

        sendTestTranslationButton = new Button(this);
        sendTestTranslationButton.setText(R.string.button_test_send_translation);
        sendTestTranslationButton.setOnClickListener(v ->
                runTestTranslation(ApiTester.TestKind.SEND_TRANSLATION));
        root.addView(sendTestTranslationButton, matchWrap());

        resultText = new TextView(this);
        resultText.setTextSize(12f);
        resultText.setPadding(0, dp(8), 0, 0);
        root.addView(resultText, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        SettingsUiStyle.apply(this, scroll, saveButton);

        // Keep the original settings hierarchy alive underneath pushed subpages. This avoids
        // rebuilding EditTexts/Spinners and therefore preserves pending drafts and scroll state.
        mainSettingsPage = scroll;
        navigationHost = new FrameLayout(this);
        navigationHost.setOnApplyWindowInsetsListener((view, insets) -> {
            // Apply the Activity content insets once at the navigation container. Applying and
            // consuming them on the first child ScrollView prevents later sibling subpages from
            // receiving the ActionBar/system-bar insets when they are attached dynamically.
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        navigationHost.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return navigationHost;
    }

    private void updateOutboundButtonsSummary() {
        if (outboundButtonsSummary == null) return;
        if (outboundButtonsDraft.isEmpty()) {
            outboundButtonsSummary.setText(getString(R.string.outbound_none));
            return;
        }
        StringBuilder summary = new StringBuilder();
        for (OutboundButtonConfig button : outboundButtonsDraft) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append(button.label).append("（").append(button.targetLanguage).append("）");
        }
        outboundButtonsSummary.setText(summary.toString());
    }

    private void openOutboundButtonsEditor() {
        if (!started || destroyed || !configurationLoaded || persistedConfigInvalid
                || persistence.isLoading() || saveInProgress || syncInProgress
                || outboundButtonsDialog != null) return;
        String[] labels = getResources().getStringArray(R.array.target_language_labels);
        outboundButtonsDialog = OutboundButtonsEditor.show(this,
                new ArrayList<>(outboundButtonsDraft), TARGET_LANGUAGE_VALUES, labels,
                promptOrDefault(sendSystemPromptInput, Config.DEF_SEND_SYSTEM_PROMPT),
                updated -> {
                    outboundButtonsDraft.clear();
                    outboundButtonsDraft.addAll(updated);
                    String firstLanguage = selectedSendTargetLanguage();
                    ensureTargetLanguageAvailable(firstLanguage);
                    sendTargetLanguageSpinner.setSelection(targetLanguagePosition(firstLanguage));
                    updateOutboundButtonsSummary();
                    markDirty();
                    saveOptionsOnly();
                });
        outboundButtonsDialog.setOnDismissListener(dialog -> outboundButtonsDialog = null);
    }

    private void openAdvancedSettings() {
        if (!started || destroyed || !configurationLoaded || persistedConfigInvalid
                || persistence.isLoading() || saveInProgress || syncInProgress
                || advancedSettingsDialog != null) return;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        EditText incomingPrompt = addField(form,
                getString(R.string.label_incoming_prompt),
                getString(R.string.hint_system_prompt),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        incomingPrompt.setMinLines(7);
        incomingPrompt.setText(promptOrDefault(systemPromptInput, Config.DEF_SYSTEM_PROMPT));
        Button resetIncoming = new Button(this);
        resetIncoming.setText(getString(R.string.button_restore_incoming_prompt));
        resetIncoming.setOnClickListener(v -> incomingPrompt.setText(Config.DEF_SYSTEM_PROMPT));
        form.addView(resetIncoming, matchWrap());

        EditText newButtonPrompt = addField(form,
                getString(R.string.label_new_outbound_prompt),
                getString(R.string.hint_default_outbound_prompt),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        newButtonPrompt.setMinLines(7);
        newButtonPrompt.setText(promptOrDefault(sendSystemPromptInput,
                Config.DEF_SEND_SYSTEM_PROMPT));
        Button resetOutbound = new Button(this);
        resetOutbound.setText(getString(R.string.button_restore_outbound_prompt));
        resetOutbound.setOnClickListener(v ->
                newButtonPrompt.setText(Config.DEF_SEND_SYSTEM_PROMPT));
        form.addView(resetOutbound, matchWrap());

        TextView filterTitle = new TextView(this);
        filterTitle.setText(getString(R.string.label_translation_integrity));
        filterTitle.setTextSize(16f);
        filterTitle.setPadding(0, dp(12), 0, dp(4));
        form.addView(filterTitle, matchWrap());
        TextView filterHint = new TextView(this);
        filterHint.setTextSize(12f);
        filterHint.setText(getString(R.string.hint_translation_integrity));
        form.addView(filterHint, matchWrap());

        Switch wrapper = addDialogSwitch(form, getString(R.string.validation_check_wrapper),
                checkWrapperInput.isChecked());
        Switch strip = addDialogSwitch(form,
                getString(R.string.validation_strip_wrapper),
                stripOuterWrapperInput.isChecked());
        Switch segments = addDialogSwitch(form,
                getString(R.string.validation_check_segments),
                checkSegmentMarkersInput.isChecked());
        Switch placeholders = addDialogSwitch(form,
                getString(R.string.validation_check_placeholders),
                checkPlaceholdersInput.isChecked());
        Switch retained = addDialogSwitch(form,
                getString(R.string.validation_check_retained),
                checkRetainedMarkersInput.isChecked());
        Switch length = addDialogSwitch(form,
                getString(R.string.validation_check_length),
                checkLengthInput.isChecked());
        Switch mentions = addDialogSwitch(form,
                getString(R.string.protection_discord_mentions), protectMentionsInput.isChecked());
        Switch channelsEmojis = addDialogSwitch(form,
                getString(R.string.protection_discord_channels_emojis),
                protectChannelsEmojisInput.isChecked());
        Switch urls = addDialogSwitch(form,
                getString(R.string.protection_urls), protectUrlsInput.isChecked());
        EditText minPercent = addField(form,
                getString(R.string.label_min_translation_length),
                String.valueOf(Config.DEF_MIN_LENGTH_PERCENT), InputType.TYPE_CLASS_NUMBER);
        minPercent.setText(minLengthPercentInput.getText().toString());
        EditText maxPercent = addField(form,
                getString(R.string.label_max_translation_length),
                String.valueOf(Config.DEF_MAX_LENGTH_PERCENT), InputType.TYPE_CLASS_NUMBER);
        maxPercent.setText(maxLengthPercentInput.getText().toString());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.button_advanced_settings))
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(getString(R.string.button_apply_to_draft), null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int min;
                    int max;
                    try {
                        min = Integer.parseInt(minPercent.getText().toString().trim());
                        max = Integer.parseInt(maxPercent.getText().toString().trim());
                    } catch (NumberFormatException invalid) {
                        Toast.makeText(this, R.string.error_length_integer, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (min < 0 || max > 1000 || min > max) {
                        Toast.makeText(this, R.string.error_length_range, Toast.LENGTH_LONG).show();
                        return;
                    }
                    systemPromptInput.setText(incomingPrompt.getText().toString());
                    sendSystemPromptInput.setText(newButtonPrompt.getText().toString());
                    checkWrapperInput.setChecked(wrapper.isChecked());
                    stripOuterWrapperInput.setChecked(strip.isChecked());
                    checkSegmentMarkersInput.setChecked(segments.isChecked());
                    checkPlaceholdersInput.setChecked(placeholders.isChecked());
                    checkRetainedMarkersInput.setChecked(retained.isChecked());
                    checkLengthInput.setChecked(length.isChecked());
                    protectMentionsInput.setChecked(mentions.isChecked());
                    protectChannelsEmojisInput.setChecked(channelsEmojis.isChecked());
                    protectUrlsInput.setChecked(urls.isChecked());
                    minLengthPercentInput.setText(String.valueOf(min));
                    maxLengthPercentInput.setText(String.valueOf(max));
                    markDirty();
                    saveSelectedTextFields(Arrays.asList(systemPromptInput, sendSystemPromptInput));
                    dialog.dismiss();
                }));
        advancedSettingsDialog = dialog;
        dialog.setOnDismissListener(ignored -> advancedSettingsDialog = null);
        dialog.show();
        SettingsUiStyle.styleDialog(this, dialog);
    }

    private Switch addDialogSwitch(LinearLayout parent, String label, boolean checked) {
        Switch input = new Switch(this);
        input.setText(label);
        input.setShowText(false);
        input.setChecked(checked);
        input.setMinimumHeight(dp(48));
        parent.addView(input, matchWrap());
        return input;
    }

    private Switch addValidationSwitch(LinearLayout parent, String label, boolean defaultValue) {
        Switch input = new Switch(this);
        input.setText(label);
        input.setShowText(false);
        input.setChecked(defaultValue);
        input.setMinimumHeight(dp(48));
        parent.addView(input, matchWrap());
        input.setOnCheckedChangeListener((button, checked) -> markDirty());
        return input;
    }

    private TextView addToggle(LinearLayout parent, Switch input, SettingsToggleText.Setting setting) {
        input.setShowText(false);
        input.setTextSize(16f);
        input.setMinimumHeight(dp(48));
        parent.addView(input, matchWrap());
        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        parent.addView(hint, matchWrap());
        input.setOnCheckedChangeListener((button, checked) -> {
            updateToggleText(input, hint, setting);
            markDirty();
            if (!loadingUi) saveOptionsOnly();
        });
        updateToggleText(input, hint, setting);
        return hint;
    }

    private void updateToggleText(Switch input, TextView hint, SettingsToggleText.Setting setting) {
        SettingsToggleText copy = SettingsToggleText.select(setting, input.isChecked(),
                configurationLoaded, persistedConfigInvalid, protocolSpinner == null ? null
                        : protocolAt(protocolSpinner.getSelectedItemPosition()));
        input.setText(getString(R.string.switch_title_state, getString(setting.title), getString(copy.state)));
        hint.setText(copy.description);
    }

    private void updateScopeSummary() {
        scopeSummary.setText(getString(R.string.scope_summary,
                getString(scopeDraft.defaultTranslate ? R.string.scope_default_translate
                        : R.string.scope_default_do_not_translate),
                scopeDraft.servers.size(), scopeDraft.channels.size()));
    }

    /** Shared by the button and entry, including lifecycle and persistence exclusion. */
    private boolean canOpenScopeEditor() {
        return started && !destroyed && configurationLoaded && !persistedConfigInvalid
                && !persistence.isLoading() && scopeDialog == null && !saveInProgress && !syncInProgress;
    }

    private void openScopeEditor() {
        if (!canOpenScopeEditor()) return;
        Bundle restore = pendingScopeEditor;
        pendingScopeEditor = null;
        TranslationScope initial = restore == null ? scopeDraft
                : TranslationScope.parse(restore.getString("scope"));
        scopeDialog = TranslationScopeEditor.show(this, initial, updated -> {
            scopeDraft = updated;
            updateScopeSummary();
            markDirty();
            saveOptionsOnly();
        });
        if (restore != null) TranslationScopeEditor.restoreState(scopeDialog, restore);
        scopeDialog.setOnDismissListener(dialog -> {
            scopeDialog = null;
            updateSaveButtonState();
            if (started && !destroyed && pendingScopeEditor == null) startServiceSync();
        });
        updateSaveButtonState();
    }

    private Button addPromptResetButton(LinearLayout parent, EditText input, String defaultPrompt) {
        Button button = new Button(this);
        button.setText(getString(R.string.button_restore_default_prompt));
        button.setOnClickListener(view -> input.setText(defaultPrompt));
        parent.addView(button, matchWrap());
        return button;
    }

    /** 每个文本配置项都有独立应用入口；输入内容在点击前只作为草稿显示。 */
    private void addFieldApplyButton(LinearLayout parent, EditText field) {
        Button apply = new Button(this);
        apply.setText(R.string.button_apply_field);
        apply.setOnClickListener(view -> saveSingleField(field));
        parent.addView(apply, matchWrap());
    }

    private EditText addField(LinearLayout parent, String label, String hint, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, dp(8), 0, 0);
        parent.addView(tv, matchWrap());

        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        parent.addView(et, matchWrap());
        return et;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 下拉框位置 → 协议存储值；无效位置返回 null 供配置校验拒绝。 */
    @Nullable
    private static String protocolAt(int position) {
        if (position == 0) {
            return Config.PROTOCOL_OPENAI_RESPONSES;
        }
        if (position == 1) {
            return Config.PROTOCOL_OPENAI_COMPATIBLE;
        }
        if (position == 2) {
            return Config.PROTOCOL_ANTHROPIC_MESSAGES;
        }
        return null;
    }

    /** 协议存储值 → 下拉框位置；未知值返回无选中位置。 */
    private static int positionOf(String protocol) {
        if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            return 0;
        }
        if (Config.PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)) {
            return 1;
        }
        if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
            return 2;
        }
        return AdapterView.INVALID_POSITION;
    }

    private void installDirtyTracking() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                markDirty();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        urlInput.addTextChangedListener(watcher);
        keyInput.addTextChangedListener(watcher);
        modelInput.addTextChangedListener(watcher);
        headersInput.addTextChangedListener(watcher);
        systemPromptInput.addTextChangedListener(watcher);
        sendSystemPromptInput.addTextChangedListener(watcher);
        timeoutInput.addTextChangedListener(watcher);
        concurrencyInput.addTextChangedListener(watcher);
        minLengthPercentInput.addTextChangedListener(watcher);
        maxLengthPercentInput.addTextChangedListener(watcher);
        testTextInput.addTextChangedListener(watcher);
        cacheLimitInput.addTextChangedListener(watcher);
        sendTestTextInput.addTextChangedListener(watcher);
    }

    private void markDirty() {
        if (!loadingUi && configurationLoaded && !persistedConfigInvalid) {
            formGeneration++;
            hasUnsavedChanges = true;
        }
    }

    private void markSpinnerDirty() {
        if (loadingUi || !configurationLoaded || persistedConfigInvalid) {
            return;
        }
        String protocol = protocolAt(protocolSpinner.getSelectedItemPosition());
        String targetLanguage = selectedTargetLanguage();
        String sendTargetLanguage = selectedSendTargetLanguage();
        if (!sameValue(protocol, baselineProtocol)
                || !sameValue(targetLanguage, baselineTargetLanguage)
                || !sameValue(sendTargetLanguage, baselineSendTargetLanguage)) {
            formGeneration++;
            hasUnsavedChanges = true;
        }
    }

    private void applyCacheEntryCountFromRemote() { updateCacheEntryCount(); }

    private static boolean sameValue(@Nullable String left, @Nullable String right) {
        return left == null ? right == null : left.equals(right);
    }

    private void updateStreamingAvailability() {
        if (streamingInput != null && protocolSpinner != null) {
            int position = protocolSpinner.getSelectedItemPosition();
            boolean hasSelection = position >= 0 && position < 3;
            streamingInput.setEnabled(configurationLoaded && !persistedConfigInvalid && hasSelection
                    && Protocols.supportsStreaming(protocolAt(position)));
            updateToggleText(streamingInput, streamingHint, SettingsToggleText.Setting.STREAMING);
        }
    }

    /** 显示旧配置中的列表外语言，避免升级后静默改写用户设置。 */
    private void ensureTargetLanguageAvailable(String language) {
        if (language == null || targetLanguageAdapter == null
                || targetLanguageValues.contains(language)) {
            return;
        }
        targetLanguageValues.add(language);
        targetLanguageAdapter.add(getString(R.string.legacy_language));
    }

    private int targetLanguagePosition(String language) {
        return targetLanguageValues.indexOf(language);
    }

    @Nullable
    private String selectedTargetLanguage() {
        int position = targetLanguageSpinner.getSelectedItemPosition();
        return position >= 0 && position < targetLanguageValues.size()
                ? targetLanguageValues.get(position) : null;
    }

    private String selectedSendTargetLanguage() {
        return outboundButtonsDraft.isEmpty() ? Config.DEF_SEND_TARGET_LANG
                : outboundButtonsDraft.get(0).targetLanguage;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 读写

    private EditText[] configurationFields() {
        return new EditText[] {urlInput, keyInput, modelInput, headersInput,
                systemPromptInput, sendSystemPromptInput, timeoutInput, concurrencyInput,
                minLengthPercentInput, maxLengthPercentInput, testTextInput};
    }

    /** 无效快照的占位值不是用户保存的设置；锁定页面直到用户明确重置。 */
    private void showInvalidSavedConfig() {
        persistedConfigInvalid = true;
        committedTextValues.clear();
        pendingScopeEditor = null;
        if (scopeDialog != null) { scopeDialog.dismiss(); scopeDialog = null; }
        boolean wasLoading = loadingUi;
        loadingUi = true;
        try {
            cancelPendingTest();
            ProjectLog.configure(Config.invalidSnapshot());
            enabledInput.setChecked(false);
            scopeDraft = TranslationScope.ALL;
            scopeSummary.setText("");
            loggingInput.setChecked(false);
            streamingInput.setChecked(false);
            checkWrapperInput.setChecked(Config.DEF_CHECK_WRAPPER);
            stripOuterWrapperInput.setChecked(Config.DEF_STRIP_OUTER_WRAPPER);
            checkSegmentMarkersInput.setChecked(Config.DEF_CHECK_SEGMENT_MARKERS);
            checkPlaceholdersInput.setChecked(Config.DEF_CHECK_PLACEHOLDERS);
            checkRetainedMarkersInput.setChecked(Config.DEF_CHECK_RETAINED_MARKERS);
            checkLengthInput.setChecked(Config.DEF_CHECK_LENGTH);
            protectMentionsInput.setChecked(Config.DEF_PROTECT_MENTIONS);
            protectChannelsEmojisInput.setChecked(Config.DEF_PROTECT_CHANNELS_EMOJIS);
            protectUrlsInput.setChecked(Config.DEF_PROTECT_URLS);
            experimentalChunkingInput.setChecked(Config.DEF_EXPERIMENTAL_LONG_MESSAGE_CHUNKING);
            experimentalChunkThresholdInput.setText("");
            protocolSpinner.setSelection(AdapterView.INVALID_POSITION);
            targetLanguageSpinner.setSelection(AdapterView.INVALID_POSITION);
            sendTargetLanguageSpinner.setSelection(AdapterView.INVALID_POSITION);
            outboundButtonsDraft.clear();
            updateOutboundButtonsSummary();
            for (EditText field : configurationFields()) {
                field.setText("");
                field.setHint(null);
            }
            baselineProtocol = null;
            baselineTargetLanguage = null;
            baselineSendTargetLanguage = null;
            hasUnsavedChanges = false;
            resultText.setText("");
        } finally {
            loadingUi = wasLoading;
        }
        updateSaveButtonState();
        updateStatus(getString(R.string.status_invalid_saved_config));
    }

    /** All persisted reads, including poison checks, run on the persistence executor. */
    private void loadIntoUi() {
        final SharedPreferences local = prefs;
        if (local == null || destroyed || persistence.isLoading()) {
            return;
        }
        try {
            persistence.load(local, () -> formGeneration, () -> hasUnsavedChanges,
                    (state, replaceForm) -> {
                        if (destroyed || local != prefs) {
                            return;
                        }
                        applyLocalState(state, replaceForm);
                        updateSaveButtonState();
                        if (pendingScopeEditor != null && started && !persistedConfigInvalid) openScopeEditor();
                        else startServiceSync();
                    });
        } catch (RuntimeException failure) {
            ProjectLog.warn("settings load end status=FAILED exception="
                    + failure.getClass().getSimpleName());
            updateStatus(getString(R.string.status_save_persist_failed));
        }
        updateSaveButtonState();
    }

    private void applyLocalState(ConfigurationSync.LocalState state, boolean replaceForm) {
        configurationLoaded = true;
        if (state.snapshot.configError != null) {
            showInvalidSavedConfig();
            if (state.poisoned) {
                showPersistenceFailure(true, getString(R.string.status_save_persist_failed));
            }
            return;
        }
        persistedConfigInvalid = false;
        ProjectLog.configure(state.snapshot);
        setCommittedTextValues(state.snapshot);
        // Draft restoration precedes the asynchronous read. Neither it nor newer edits
        // can be replaced by the completion of an older load/import.
        if (replaceForm) {
            applySnapshot(state.snapshot);
        }
    }

    private void applySnapshot(Config.Snapshot snapshot) {
        loadingUi = true;
        try {
            persistedConfigInvalid = false;
            EditText[] fields = configurationFields();
            for (int i = 0; i < fields.length; i++) {
                fields[i].setHint(fieldHints[i]);
            }
            enabledInput.setChecked(snapshot.enabled);
            scopeDraft = snapshot.translationScope;
            updateScopeSummary();
            loggingInput.setChecked(snapshot.loggingEnabled);
            cacheEnabledInput.setChecked(snapshot.cacheEnabled);
            cacheLimitInput.setText(String.valueOf(snapshot.cacheLimit));
            updateCacheControls();
            protocolSpinner.setSelection(positionOf(snapshot.protocol));
            streamingInput.setChecked(snapshot.streaming);
            updateStreamingAvailability();
            setTextOrPlaceholder(urlInput, snapshot.apiUrl, Config.DEF_API_URL);
            setTextOrPlaceholder(keyInput, snapshot.apiKey, Config.DEF_API_KEY);
            setTextOrPlaceholder(modelInput, snapshot.model, Config.DEF_MODEL);
            ensureTargetLanguageAvailable(snapshot.targetLang);
            targetLanguageSpinner.setSelection(targetLanguagePosition(snapshot.targetLang));
            outboundButtonsDraft.clear();
            outboundButtonsDraft.addAll(snapshot.outboundButtons);
            updateOutboundButtonsSummary();
            String firstSendLanguage = snapshot.outboundButtons.isEmpty()
                    ? snapshot.sendTargetLang : snapshot.outboundButtons.get(0).targetLanguage;
            ensureTargetLanguageAvailable(firstSendLanguage);
            sendTargetLanguageSpinner.setSelection(targetLanguagePosition(firstSendLanguage));
            setTextOrPlaceholder(headersInput, snapshot.extraHeaders, Config.DEF_EXTRA_HEADERS);
            systemPromptInput.setText(snapshot.systemPrompt);
            sendSystemPromptInput.setText(snapshot.sendSystemPrompt);
            setTextOrPlaceholder(timeoutInput, String.valueOf(snapshot.timeoutMs),
                    String.valueOf(Config.DEF_TIMEOUT_MS));
            setTextOrPlaceholder(concurrencyInput, String.valueOf(snapshot.maxConcurrency),
                    String.valueOf(Config.DEF_MAX_CONCURRENCY));
            checkWrapperInput.setChecked(snapshot.checkWrapper);
            stripOuterWrapperInput.setChecked(snapshot.stripOuterWrapper);
            checkSegmentMarkersInput.setChecked(snapshot.checkSegmentMarkers);
            checkPlaceholdersInput.setChecked(snapshot.checkPlaceholders);
            checkRetainedMarkersInput.setChecked(snapshot.checkRetainedMarkers);
            checkLengthInput.setChecked(snapshot.checkLength);
            protectMentionsInput.setChecked(snapshot.protectMentions);
            protectChannelsEmojisInput.setChecked(snapshot.protectChannelsEmojis);
            protectUrlsInput.setChecked(snapshot.protectUrls);
            experimentalChunkingInput.setChecked(snapshot.experimentalLongMessageChunking);
            setTextOrPlaceholder(experimentalChunkThresholdInput,
                    String.valueOf(snapshot.experimentalLongMessageThreshold),
                    String.valueOf(Config.DEF_EXPERIMENTAL_LONG_MESSAGE_THRESHOLD));
            setTextOrPlaceholder(minLengthPercentInput, String.valueOf(snapshot.minLengthPercent),
                    String.valueOf(Config.DEF_MIN_LENGTH_PERCENT));
            setTextOrPlaceholder(maxLengthPercentInput, String.valueOf(snapshot.maxLengthPercent),
                    String.valueOf(Config.DEF_MAX_LENGTH_PERCENT));
            baselineProtocol = snapshot.protocol;
            baselineTargetLanguage = snapshot.targetLang;
            baselineSendTargetLanguage = firstSendLanguage;
            hasUnsavedChanges = false;
            setCommittedTextValues(snapshot);
        } finally {
            loadingUi = false;
            updateSaveButtonState();
        }
    }

    /** 打开本地翻译缓存独立页面。 */
    private void openCacheSettings() {
        if (persistedConfigInvalid) return;
        showSettingsSubpage(PAGE_CACHE, R.string.menu_translation_cache, cacheBacking);
        updateCacheEntryCount();
    }

    /** 打开 API 与请求协议独立页面。 */
    private void openApiSettings() {
        if (persistedConfigInvalid) return;
        showSettingsSubpage(PAGE_API, R.string.menu_api_settings, apiBacking);
    }

    /** 所有实验性功能集中在此页面，且每项默认关闭。 */
    private void openExperimentalSettings() {
        if (persistedConfigInvalid) return;
        showSettingsSubpage(PAGE_EXPERIMENTAL, R.string.menu_experimental_features,
                experimentalBacking);
    }

    /**
     * 将目标设置内容推入全屏页面层，而不是放进 AlertDialog。
     * 主设置页只隐藏、不销毁；子页面退出时原样恢复。
     */
    private void showSettingsSubpage(String pageKey, int titleRes, LinearLayout content) {
        if (navigationHost == null || mainSettingsPage == null || persistedConfigInvalid) return;
        if (pageKey.equals(activeSettingsPage)) return;
        if (activeSettingsPage != null) closeSettingsSubpage();

        // Build each submenu exactly once. Reusing its view prevents style margins from
        // accumulating and preserves the submenu's own scroll position when revisited.
        View page = PAGE_CACHE.equals(pageKey) ? cacheSettingsPage
                : PAGE_API.equals(pageKey) ? apiSettingsPage : experimentalSettingsPage;
        if (page == null) {
            LinearLayout pageContent = new LinearLayout(this);
            pageContent.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(16);
            pageContent.setPadding(padding, padding, padding, padding);
            pageContent.addView(content, matchWrap());

            ScrollView pageScroll = new ScrollView(this);
            pageScroll.setFillViewport(true);
            pageScroll.setClipToPadding(false);
            pageScroll.setSmoothScrollingEnabled(true);
            pageScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            pageScroll.addView(pageContent, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Insets are owned by navigationHost so every page uses the same viewport.
            SettingsUiStyle.apply(this, pageScroll, null);
            page = pageScroll;
            if (PAGE_CACHE.equals(pageKey)) {
                cacheSettingsPage = page;
            } else if (PAGE_API.equals(pageKey)) {
                apiSettingsPage = page;
            } else {
                experimentalSettingsPage = page;
            }
        }

        mainSettingsPage.setVisibility(View.GONE);
        navigationHost.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activeSubpageView = page;
        activeSettingsPage = pageKey;
        setTitle(titleRes);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setHomeButtonEnabled(true);
        }

        // navigationHost owns window insets for both the main page and dynamically attached
        // subpages. Changing the ActionBar title/back affordance only requires a normal layout.
    }

    /** 关闭当前子页面并恢复主设置页；不重建主页面表单。 */
    private void closeSettingsSubpage() {
        if (navigationHost != null && activeSubpageView != null) {
            navigationHost.removeView(activeSubpageView);
        }
        activeSubpageView = null;
        activeSettingsPage = null;
        if (mainSettingsPage != null) {
            mainSettingsPage.setVisibility(View.VISIBLE);
        }
        setTitle(R.string.module_name);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home && activeSettingsPage != null) {
            closeSettingsSubpage();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (activeSettingsPage != null) {
            closeSettingsSubpage();
            return;
        }
        super.onBackPressed();
    }

    /** 缓存容量允许自定义输入，范围为 1 到 1,000,000 条。 */
    private int parseCacheLimitInput() {
        try {
            int value = Integer.parseInt(cacheLimitInput.getText().toString().trim());
            return value >= 1 && value <= Config.MAX_CACHE_LIMIT ? value : -1;
        } catch (RuntimeException invalid) {
            return -1;
        }
    }

    /** Read the last persistent cache count published by the Discord process. */
    private void updateCacheEntryCount() {
        if (cacheEntryCountText == null) return;
        int count = cacheCountPrefs == null
                ? 0 : cacheCountPrefs.getInt(CacheCountProvider.KEY_ENTRY_COUNT, 0);
        cacheEntryCountText.setText(getString(R.string.label_cache_entry_count, count));
    }

    private void updateCacheControls() {
        if (cacheLimitInput != null) {
            // 容量是独立设置；即使关闭缓存也允许预先修改。
            cacheLimitInput.setEnabled(configurationLoaded && !persistedConfigInvalid);
        }
    }

    private void confirmClearTranslationCache() {
        if (!started || destroyed || remotePrefs == null || persistedConfigInvalid) {
            Toast.makeText(this, R.string.status_cache_clear_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.button_clear_translation_cache)
                .setMessage(R.string.confirm_clear_translation_cache)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_clear_translation_cache, (dialog, which) -> {
                    try {
                        boolean next = !remotePrefs.getBoolean(
                                Config.KEY_CACHE_CLEAR_REQUEST, false);
                        remotePrefs.edit().putBoolean(Config.KEY_CACHE_CLEAR_REQUEST, next).apply();
                        Toast.makeText(this, R.string.status_cache_clear_requested,
                                Toast.LENGTH_LONG).show();
                    } catch (Throwable failure) {
                        Toast.makeText(this, R.string.status_cache_clear_unavailable,
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    /** 返回真正写入配置的文本项；测试输入框不属于持久化配置。 */
    private List<EditText> persistedTextFields() {
        return Arrays.asList(urlInput, keyInput, modelInput, headersInput,
                systemPromptInput, sendSystemPromptInput, timeoutInput, concurrencyInput,
                minLengthPercentInput, maxLengthPercentInput, cacheLimitInput,
                experimentalChunkThresholdInput);
    }

    /** 将已提交快照转换为输入框实际显示的基线文本（默认值继续显示为提示文字）。 */
    private void setCommittedTextValues(Config.Snapshot snapshot) {
        if (snapshot == null || snapshot.configError != null) return;
        committedTextValues.clear();
        committedTextValues.put(urlInput, snapshot.apiUrl.equals(Config.DEF_API_URL) ? "" : snapshot.apiUrl);
        committedTextValues.put(keyInput, snapshot.apiKey);
        committedTextValues.put(modelInput, snapshot.model.equals(Config.DEF_MODEL) ? "" : snapshot.model);
        committedTextValues.put(headersInput, snapshot.extraHeaders);
        committedTextValues.put(systemPromptInput, snapshot.systemPrompt);
        committedTextValues.put(sendSystemPromptInput, snapshot.sendSystemPrompt);
        committedTextValues.put(timeoutInput, snapshot.timeoutMs == Config.DEF_TIMEOUT_MS
                ? "" : String.valueOf(snapshot.timeoutMs));
        committedTextValues.put(concurrencyInput, snapshot.maxConcurrency == Config.DEF_MAX_CONCURRENCY
                ? "" : String.valueOf(snapshot.maxConcurrency));
        committedTextValues.put(minLengthPercentInput,
                snapshot.minLengthPercent == Config.DEF_MIN_LENGTH_PERCENT ? ""
                        : String.valueOf(snapshot.minLengthPercent));
        committedTextValues.put(maxLengthPercentInput,
                snapshot.maxLengthPercent == Config.DEF_MAX_LENGTH_PERCENT ? ""
                        : String.valueOf(snapshot.maxLengthPercent));
        committedTextValues.put(cacheLimitInput, String.valueOf(snapshot.cacheLimit));
        committedTextValues.put(experimentalChunkThresholdInput,
                snapshot.experimentalLongMessageThreshold == Config.DEF_EXPERIMENTAL_LONG_MESSAGE_THRESHOLD
                        ? "" : String.valueOf(snapshot.experimentalLongMessageThreshold));
    }

    private Map<EditText, String> captureTextDrafts() {
        Map<EditText, String> drafts = new IdentityHashMap<>();
        for (EditText field : persistedTextFields()) drafts.put(field, field.getText().toString());
        return drafts;
    }

    private void restoreTextDrafts(Map<EditText, String> drafts) {
        boolean previous = loadingUi;
        loadingUi = true;
        try {
            for (Map.Entry<EditText, String> entry : drafts.entrySet()) {
                entry.getKey().setText(entry.getValue());
            }
        } finally {
            loadingUi = previous;
        }
    }

    private boolean hasUnappliedTextDraftsExcept(List<EditText> included) {
        for (EditText field : persistedTextFields()) {
            if (included.contains(field)) continue;
            String committed = committedTextValues.get(field);
            if (committed != null && !committed.equals(field.getText().toString())) return true;
        }
        return false;
    }

    /**
     * 只提交指定文本项；其他未点击“应用”的输入先恢复到已提交值，保存结束后再还原草稿。
     * 这样开关/下拉选项即时保存时不会偷偷提交用户尚未应用的 API 文本。
     */
    private void saveSelectedTextFields(List<EditText> included) {
        if (loadingUi || !configurationLoaded || committedTextValues.isEmpty()) {
            save();
            return;
        }
        Map<EditText, String> drafts = captureTextDrafts();
        boolean otherDraftsRemain = hasUnappliedTextDraftsExcept(included);
        boolean previous = loadingUi;
        loadingUi = true;
        try {
            for (EditText field : persistedTextFields()) {
                if (!included.contains(field)) {
                    String committed = committedTextValues.get(field);
                    if (committed != null) field.setText(committed);
                }
            }
        } finally {
            loadingUi = previous;
        }
        save();
        restoreTextDrafts(drafts);
        if (otherDraftsRemain) markDirty();
    }

    private void saveSingleField(EditText field) {
        saveSelectedTextFields(Arrays.asList(field));
    }

    /** 开关和选择项立即保存，但保留所有尚未点击应用的文本草稿。 */
    private void saveOptionsOnly() {
        saveSelectedTextFields(new ArrayList<>());
    }

    private void save() {
        if (persistedConfigInvalid) {
            updateStatus(getString(R.string.status_invalid_saved_config));
            return;
        }
        if (!configurationLoaded || persistence.isLoading() || saveInProgress || syncInProgress) {
            return;
        }
        if (!started || destroyed || prefs == null) {
            updateStatus(getString(R.string.status_save_persist_failed));
            return;
        }
        Config.Snapshot snapshot = snapshotFromUi();
        if (snapshot.configError != null) {
            ProjectLog.warn("settings save end status=INVALID_SOURCE");
            updateStatus(getString(R.string.status_invalid_config));
            return;
        }
        commitConfiguration(snapshot);
    }

    private void confirmResetSavedSettings() {
        if (!started || destroyed || !persistedConfigInvalid || persistence.isLoading()
                || saveInProgress || syncInProgress) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.button_reset_saved_settings)
                .setMessage(R.string.confirm_reset_saved_settings)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_reset_saved_settings,
                        (dialog, which) -> commitConfiguration(null))
                .show();
    }

    /** 保存与显式重置共用本机提交和远程同步链路；null 仅表示已确认的重置。 */
    private void commitConfiguration(@Nullable Config.Snapshot snapshot) {
        final boolean resetting = snapshot == null;
        final String operation = resetting ? "reset" : "save";
        if (!configurationLoaded || persistence.isLoading() || saveInProgress || syncInProgress
                || resetting != persistedConfigInvalid) {
            return;
        }
        if (!resetting && snapshot.configError != null) {
            updateStatus(getString(R.string.status_invalid_config));
            return;
        }
        final SharedPreferences local = prefs;
        final SharedPreferences remote = remotePrefs;
        final long savedFormGeneration = formGeneration;
        final long savedServiceGeneration = serviceGeneration;
        if (!started || destroyed || local == null) {
            showPersistenceFailure(resetting, getString(R.string.status_save_persist_failed));
            return;
        }

        saveInProgress = true;
        updateSaveButtonState();
        updateStatus(getString(R.string.status_saving));
        if (resetting) {
            resultText.setText(R.string.status_resetting);
        }
        ProjectLog.info("settings " + operation + " start");
        try {
            saveExecutor.execute(() -> {
                final ConfigurationSync.Result saveResult;
                final ConfigurationSync.LocalState localState;
                synchronized (PreferenceCommit.class) {
                    saveResult = ConfigurationSync.save(local, remote, snapshot);
                    localState = ConfigurationSync.loadLocal(local);
                    recordConfigurationResult(operation, saveResult, localState.snapshot);
                }
                final boolean localResult = saveResult.localCommitted;
                final boolean remoteResult = remote != null && saveResult.succeeded();
                final Throwable saveFailure = saveResult.failure;
                final PreferenceCommit.Result commitResult = saveResult.transaction;
                persistence.post(() -> {
                    if (!started || destroyed || local != prefs) {
                        return;
                    }
                    saveInProgress = false;
                    updateSaveButtonState();
                    if (localResult && !resetting) setCommittedTextValues(snapshot);
                    if (resetting) committedTextValues.clear();
                    if (!localResult) {
                        if (localState.poisoned) {
                            showInvalidSavedConfig();
                        }
                        String detail = saveFailure == null
                                ? getString(R.string.status_save_persist_failed)
                                : getString(R.string.status_save_failed,
                                        saveFailure.getClass().getSimpleName());
                        if (commitResult != null && commitResult.rollbackFailed) {
                            // Only fixed labels and exception classes, never values/messages.
                            detail += "\n" + (getString(R.string.label_rollback))
                                    + (commitResult.rollbackFailure == null
                                            ? getString(R.string.status_save_persist_failed)
                                            : getString(R.string.status_save_failed,
                                                    commitResult.rollbackFailure.getClass()
                                                            .getSimpleName()));
                        }
                        showPersistenceFailure(resetting || persistedConfigInvalid, detail);
                    } else if (resetting) {
                        applyLocalState(localState,
                                !hasUnsavedChanges && savedFormGeneration == formGeneration);
                        String status = getString(remoteResult && remote == remotePrefs
                                && savedServiceGeneration == serviceGeneration
                                ? R.string.status_reset_saved_restart
                                : remote == null ? R.string.status_reset_sync_pending
                                        : R.string.status_reset_sync_failed);
                        resultText.setText(status);
                        updateStatus(status);
                    } else {
                        if (savedFormGeneration == formGeneration) {
                            baselineProtocol = snapshot.protocol;
                            baselineTargetLanguage = snapshot.targetLang;
                            baselineSendTargetLanguage = snapshot.sendTargetLang;
                            hasUnsavedChanges = false;
                        }
                        toast(getString(R.string.toast_saved));
                        updateStatus(getString(remoteResult && remote == remotePrefs
                                && savedServiceGeneration == serviceGeneration
                                ? R.string.status_saved_restart : R.string.status_sync_pending));
                    }
                    // A new connection blocked by this save gets its one sync attempt;
                    // this is not a retry against the already attempted remote store.
                    if (service != null && savedServiceGeneration != serviceGeneration) {
                        startServiceSync();
                    }
                });
            });
        } catch (Throwable t) {
            ProjectLog.warn("settings " + operation + " end status=FAILED exception="
                    + t.getClass().getSimpleName());
            saveInProgress = false;
            updateSaveButtonState();
            showPersistenceFailure(resetting, getString(R.string.status_save_failed,
                    t.getClass().getSimpleName()));
        }
    }

    /** Called under the persistence monitor; only confirmed local commits may change the gate. */
    private static void recordConfigurationResult(String operation, ConfigurationSync.Result result,
                                                  Config.Snapshot confirmedLocal) {
        if (result.localCommitted) {
            ProjectLog.configure(confirmedLocal);
        }
        String message = "settings " + operation + " end status=" + result.status.name()
                + " direction=" + (result.direction == null ? "NONE" : result.direction.name())
                + " localCommitted=" + result.localCommitted;
        if (result.failure != null) {
            message += " exception=" + result.failure.getClass().getSimpleName();
        }
        if (result.transaction != null && result.transaction.rollbackFailed) {
            message += " rollbackFailed=true";
            if (result.transaction.rollbackFailure != null) {
                message += " rollbackException="
                        + result.transaction.rollbackFailure.getClass().getSimpleName();
            }
        }
        if (result.succeeded()) {
            ProjectLog.info(message);
        } else {
            ProjectLog.warn(message);
        }
    }

    private void showPersistenceFailure(boolean resetting, String detail) {
        if (resetting) {
            resultText.setText(getString(R.string.status_reset_failed, detail));
        }
        updateStatus(detail);
    }

    private void updateSaveButtonState() {
        boolean available = started && !destroyed && prefs != null && configurationLoaded
                && !persistence.isLoading() && !saveInProgress && !syncInProgress;
        boolean formAvailable = configurationLoaded && !persistedConfigInvalid
                && !persistence.isLoading() && !saveInProgress && !syncInProgress;
        if (saveButton != null) {
            saveButton.setEnabled(available && !persistedConfigInvalid);
        }
        if (resetButton != null) {
            resetButton.setVisibility(persistedConfigInvalid ? View.VISIBLE : View.GONE);
            resetButton.setEnabled(available && persistedConfigInvalid);
        }
        enabledInput.setEnabled(formAvailable);
        scopeButton.setEnabled(canOpenScopeEditor());
        scopeSummary.setVisibility(persistedConfigInvalid ? View.INVISIBLE : View.VISIBLE);
        loggingInput.setEnabled(formAvailable);
        cacheEnabledInput.setEnabled(formAvailable);
        updateCacheEntryCount();
        cacheLimitInput.setEnabled(formAvailable);
        if (cacheApplyButton != null) cacheApplyButton.setEnabled(formAvailable && available);
        if (apiApplyButton != null) apiApplyButton.setEnabled(formAvailable && available);
        if (cacheSettingsButton != null) cacheSettingsButton.setEnabled(formAvailable);
        if (apiSettingsButton != null) apiSettingsButton.setEnabled(formAvailable);
        clearCacheButton.setEnabled(formAvailable && remotePrefs != null);
        checkWrapperInput.setEnabled(formAvailable);
        stripOuterWrapperInput.setEnabled(formAvailable);
        checkSegmentMarkersInput.setEnabled(formAvailable);
        checkPlaceholdersInput.setEnabled(formAvailable);
        checkRetainedMarkersInput.setEnabled(formAvailable);
        checkLengthInput.setEnabled(formAvailable);
        protectMentionsInput.setEnabled(formAvailable);
        protectChannelsEmojisInput.setEnabled(formAvailable);
        protectUrlsInput.setEnabled(formAvailable);
        updateToggleText(enabledInput, enabledHint, SettingsToggleText.Setting.TRANSLATION);
        updateToggleText(loggingInput, loggingHint, SettingsToggleText.Setting.LOGGING);
        protocolSpinner.setEnabled(formAvailable);
        protocolSpinner.setVisibility(persistedConfigInvalid ? View.INVISIBLE : View.VISIBLE);
        targetLanguageSpinner.setEnabled(formAvailable);
        targetLanguageSpinner.setVisibility(persistedConfigInvalid ? View.INVISIBLE : View.VISIBLE);
        sendTargetLanguageSpinner.setEnabled(formAvailable);
        sendTargetLanguageSpinner.setVisibility(View.GONE);
        if (manageOutboundButtonsButton != null) manageOutboundButtonsButton.setEnabled(formAvailable);
        if (experimentalSettingsButton != null) experimentalSettingsButton.setEnabled(formAvailable);
        if (experimentalChunkingInput != null) experimentalChunkingInput.setEnabled(formAvailable);
        if (experimentalChunkThresholdInput != null) experimentalChunkThresholdInput.setEnabled(formAvailable);
        if (experimentalChunkThresholdApplyButton != null) {
            experimentalChunkThresholdApplyButton.setEnabled(formAvailable && available);
        }
        if (advancedSettingsButton != null) advancedSettingsButton.setEnabled(formAvailable);
        for (EditText field : configurationFields()) {
            field.setEnabled(formAvailable);
        }
        if (resetSystemPromptButton != null) resetSystemPromptButton.setEnabled(formAvailable);
        if (resetSendSystemPromptButton != null) resetSendSystemPromptButton.setEnabled(formAvailable);
        updateStreamingAvailability();
        setTestButtonsEnabled(testFuture == null);
    }

    private static Object parseIntInput(EditText editText, int defaultValue) {
        String value = editText.getText().toString().trim();
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static String textOrDefault(EditText editText, String defaultValue) {
        String value = editText.getText().toString().trim();
        return value.isEmpty() ? defaultValue : value;
    }

    /** 值等于默认值时清空输入框，灰色占位符（hint）展示默认值；自定义值才写入。 */
    private static void setTextOrPlaceholder(EditText field, String value, String def) {
        field.setText(def.equals(value) ? "" : value);
    }

    private static String promptOrDefault(EditText editText, String defaultValue) {
        String value = editText.getText().toString();
        return value.trim().isEmpty() ? defaultValue : value;
    }


    private Config.Snapshot snapshotFromUi() {
        if (persistedConfigInvalid) {
            return Config.invalidSnapshot();
        }
        MemoryPrefs memoryPrefs = new MemoryPrefs();
        memoryPrefs.put(Config.KEY_ENABLED, enabledInput.isChecked());
        memoryPrefs.put(Config.KEY_TRANSLATION_SCOPE, scopeDraft.toJson());
        memoryPrefs.put(Config.KEY_LOGGING_ENABLED, loggingInput.isChecked());
        memoryPrefs.put(Config.KEY_CACHE_ENABLED, cacheEnabledInput.isChecked());
        memoryPrefs.put(Config.KEY_CACHE_LIMIT, parseCacheLimitInput());
        memoryPrefs.put(Config.KEY_PROTOCOL,
                protocolAt(protocolSpinner.getSelectedItemPosition()));
        memoryPrefs.put(Config.KEY_STREAMING, streamingInput.isChecked());
        memoryPrefs.put(Config.KEY_API_URL, textOrDefault(urlInput, Config.DEF_API_URL));
        memoryPrefs.put(Config.KEY_API_KEY, keyInput.getText().toString().trim());
        memoryPrefs.put(Config.KEY_MODEL, textOrDefault(modelInput, Config.DEF_MODEL));
        memoryPrefs.put(Config.KEY_TARGET_LANG, selectedTargetLanguage());
        memoryPrefs.put(Config.KEY_SEND_TARGET_LANG, selectedSendTargetLanguage());
        memoryPrefs.put(Config.KEY_OUTBOUND_BUTTONS,
                OutboundButtonConfig.toJson(outboundButtonsDraft));
        memoryPrefs.put(Config.KEY_EXTRA_HEADERS, headersInput.getText().toString());
        memoryPrefs.put(Config.KEY_SYSTEM_PROMPT,
                promptOrDefault(systemPromptInput, Config.DEF_SYSTEM_PROMPT));
        memoryPrefs.put(Config.KEY_SEND_SYSTEM_PROMPT,
                promptOrDefault(sendSystemPromptInput, Config.DEF_SEND_SYSTEM_PROMPT));
        memoryPrefs.put(Config.KEY_TIMEOUT_MS, parseIntInput(timeoutInput, Config.DEF_TIMEOUT_MS));
        memoryPrefs.put(Config.KEY_MAX_CONCURRENCY,
                parseIntInput(concurrencyInput, Config.DEF_MAX_CONCURRENCY));
        memoryPrefs.put(Config.KEY_CHECK_WRAPPER, checkWrapperInput.isChecked());
        memoryPrefs.put(Config.KEY_STRIP_OUTER_WRAPPER, stripOuterWrapperInput.isChecked());
        memoryPrefs.put(Config.KEY_CHECK_SEGMENT_MARKERS, checkSegmentMarkersInput.isChecked());
        memoryPrefs.put(Config.KEY_CHECK_PLACEHOLDERS, checkPlaceholdersInput.isChecked());
        memoryPrefs.put(Config.KEY_CHECK_RETAINED_MARKERS, checkRetainedMarkersInput.isChecked());
        memoryPrefs.put(Config.KEY_CHECK_LENGTH, checkLengthInput.isChecked());
        memoryPrefs.put(Config.KEY_PROTECT_MENTIONS, protectMentionsInput.isChecked());
        memoryPrefs.put(Config.KEY_PROTECT_CHANNELS_EMOJIS, protectChannelsEmojisInput.isChecked());
        memoryPrefs.put(Config.KEY_PROTECT_URLS, protectUrlsInput.isChecked());
        memoryPrefs.put(Config.KEY_EXPERIMENTAL_LONG_MESSAGE_CHUNKING,
                experimentalChunkingInput.isChecked());
        memoryPrefs.put(Config.KEY_EXPERIMENTAL_LONG_MESSAGE_THRESHOLD,
                parseIntInput(experimentalChunkThresholdInput,
                        Config.DEF_EXPERIMENTAL_LONG_MESSAGE_THRESHOLD));
        memoryPrefs.put(Config.KEY_MIN_LENGTH_PERCENT,
                parseIntInput(minLengthPercentInput, Config.DEF_MIN_LENGTH_PERCENT));
        memoryPrefs.put(Config.KEY_MAX_LENGTH_PERCENT,
                parseIntInput(maxLengthPercentInput, Config.DEF_MAX_LENGTH_PERCENT));
        return Config.read(memoryPrefs);
    }

    /** 离线配置检查：不发网络请求。 */
    private void runSelfCheck() {
        if (persistedConfigInvalid) {
            updateStatus(getString(R.string.status_invalid_saved_config));
            return;
        }
        if (!started || destroyed || !configurationLoaded || persistence.isLoading()
                || saveInProgress || syncInProgress || testFuture != null) {
            return;
        }
        showResult(ApiTester.selfCheck(snapshotFromUi()));
    }

    /**
     * 真实请求测试，使用本页面独立的单任务 executor。
     *
     * @param connectionTest true=由 ApiTester 选择固定探针，false=翻译测试文本输入框
     */
    private void runTestTranslation(boolean connectionTest) {
        runTestTranslation(connectionTest ? ApiTester.TestKind.CONNECTION
                : ApiTester.TestKind.TRANSLATION);
    }

    private void runTestTranslation(ApiTester.TestKind testKind) {
        if (!started || destroyed || !configurationLoaded || persistence.isLoading()
                || persistedConfigInvalid || saveInProgress || syncInProgress
                || testFuture != null) {
            return;
        }
        Config.Snapshot snapshot = snapshotFromUi();
        final String text = testKind == ApiTester.TestKind.SEND_TRANSLATION
                ? sendTestTextInput.getText().toString() : testTextInput.getText().toString();
        if (testKind != ApiTester.TestKind.CONNECTION && text.trim().isEmpty()) {
            resultText.setText(R.string.status_enter_test_text);
            return;
        }

        final long generation = ++testGeneration;
        resultText.setText(R.string.status_requesting);
        setTestButtonsEnabled(false);

        final ApiTester.Call call = ApiTester.newCall(snapshot, testKind, text);
        testCall = call;
        FutureTask<ApiTester.Result> task = new FutureTask<ApiTester.Result>(call) {
            @Override
            protected void done() {
                final ApiTester.Result result;
                final Throwable failure;
                try {
                    result = get();
                    failure = null;
                } catch (CancellationException e) {
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    recordUnexpectedTestFailure(e);
                    mainHandler.post(() -> finishTest(generation, this, null, e));
                    return;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    recordUnexpectedTestFailure(cause);
                    mainHandler.post(() -> finishTest(generation, this, null, cause));
                    return;
                }
                mainHandler.post(() -> finishTest(generation, this, result, failure));
            }
        };
        testFuture = task;
        try {
            testExecutor.execute(task);
        } catch (Throwable t) {
            ProjectLog.warn("settings test submission end status=FAILED exception="
                    + t.getClass().getSimpleName());
            call.cancel();
            task.cancel(true);
            testCall = null;
            testFuture = null;
            setTestButtonsEnabled(true);
            resultText.setText(getString(R.string.status_test_not_started,
                    t.getClass().getSimpleName()));
        }
    }

    private void finishTest(long generation, Future<?> task,
                            @Nullable ApiTester.Result result, @Nullable Throwable failure) {
        if (!started || destroyed || generation != testGeneration || testFuture != task) {
            return;
        }
        testFuture = null;
        testCall = null;
        setTestButtonsEnabled(true);
        if (result != null) {
            showResult(result);
        } else {
            resultText.setText(getString(R.string.status_test_failed_detail,
                    failure == null ? getString(R.string.unknown_error)
                            : failure.getClass().getSimpleName()));
            updateStatus(getString(R.string.status_test_failed));
        }
    }

    /** Future boundary diagnostics remain available even when UI delivery is no longer owned. */
    private static void recordUnexpectedTestFailure(Throwable failure) {
        ProjectLog.warn("settings test future end status=UNKNOWN_FAILURE exception="
                + failure.getClass().getSimpleName());
    }

    private void cancelPendingTest() {
        testGeneration++;
        Future<?> pending = testFuture;
        ApiTester.Call call = testCall;
        testFuture = null;
        testCall = null;
        try {
            if (call != null) {
                call.cancel();
            }
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
        }
        setTestButtonsEnabled(true);
    }

    private void setTestButtonsEnabled(boolean enabled) {
        enabled = enabled && started && !destroyed && configurationLoaded
                && !persistence.isLoading() && !persistedConfigInvalid
                && !saveInProgress && !syncInProgress;
        if (configCheckButton != null) {
            configCheckButton.setEnabled(enabled);
        }
        if (testConnectionButton != null) {
            testConnectionButton.setEnabled(enabled);
        }
        if (testTranslationButton != null) {
            testTranslationButton.setEnabled(enabled);
        }
        if (sendTestTranslationButton != null) {
            sendTestTranslationButton.setEnabled(enabled);
        }
    }

    private void showResult(ApiTester.Result result) {
        resultText.setText(ApiTestResultFormatter.format(result, this::getString));
        updateStatus(getString(result.ok
                ? R.string.status_test_passed : R.string.status_test_failed));
    }

    private void updateStatus(String status) {
        if (!destroyed && statusText != null) {
            statusText.setText(persistedConfigInvalid
                    ? getString(R.string.status_invalid_saved_config) : status);
        }
    }

    private void toast(String message) {
        if (!destroyed) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }
}
