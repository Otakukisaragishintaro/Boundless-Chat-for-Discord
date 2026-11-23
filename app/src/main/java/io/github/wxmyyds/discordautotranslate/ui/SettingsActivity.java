package io.github.wxmyyds.discordautotranslate.ui;

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
import android.widget.AdapterView;
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

import io.github.wxmyyds.discordautotranslate.R;
import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.OutboundButtonConfig;
import io.github.wxmyyds.discordautotranslate.core.TranslationScope;
import io.github.wxmyyds.discordautotranslate.core.ConfigurationSync;
import io.github.wxmyyds.discordautotranslate.core.Protocols;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.PreferenceCommit;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.DiagnosticProvider;
import io.github.wxmyyds.discordautotranslate.translate.ApiTester;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

    private static final String[] TARGET_LANGUAGE_VALUES = {
            "简体中文", "繁體中文", "English", "日本語", "한국어", "Русский",
            "Українська", "Español", "Français", "Deutsch", "Português", "Italiano",
            "العربية", "فارسی", "हिन्दी", "বাংলা", "ไทย", "עברית", "Ελληνικά",
            "Tiếng Việt", "Türkçe", "Bahasa Indonesia"
    };
    private static final String[] TARGET_LANGUAGE_LABELS_EN = {
            "Simplified Chinese", "Traditional Chinese", "English", "Japanese", "Korean",
            "Russian", "Ukrainian", "Spanish", "French", "German", "Portuguese", "Italian",
            "Arabic", "Persian", "Hindi", "Bengali", "Thai", "Hebrew", "Greek",
            "Vietnamese", "Turkish", "Indonesian"
    };
    private static final String[] TARGET_LANGUAGE_LABELS_ZH = {
            "简体中文", "繁體中文", "英语", "日语", "韩语", "俄语", "乌克兰语", "西班牙语",
            "法语", "德语", "葡萄牙语", "意大利语", "阿拉伯语", "波斯语", "印地语",
            "孟加拉语", "泰语", "希伯来语", "希腊语", "越南语", "土耳其语", "印尼语"
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
    private boolean chineseUi;
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
    private Switch enabledInput;
    private Switch loggingInput;
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
        chineseUi = isChineseSystemLanguage();
        setTitle(R.string.module_name);
        setContentView(buildContentView());
        prefs = getSharedPreferences(LOCAL_PREFS_NAME, MODE_PRIVATE);
        installDirtyTracking();
        restoreDraft(savedInstanceState);
        pendingScopeEditor = savedInstanceState == null ? null : savedInstanceState.getBundle(STATE_SCOPE_EDITOR);
        loadIntoUi();
        updateSaveButtonState();
        if (!persistedConfigInvalid) {
            updateStatus(getString(R.string.status_local_ready));
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
        draft.putBoolean("streaming", streamingInput.isChecked());
        draft.putBoolean("check_wrapper", checkWrapperInput.isChecked());
        draft.putBoolean("strip_outer_wrapper", stripOuterWrapperInput.isChecked());
        draft.putBoolean("check_segments", checkSegmentMarkersInput.isChecked());
        draft.putBoolean("check_placeholders", checkPlaceholdersInput.isChecked());
        draft.putBoolean("check_retained", checkRetainedMarkersInput.isChecked());
        draft.putBoolean("check_length", checkLengthInput.isChecked());
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

        Button viewLogsButton = new Button(this);
        viewLogsButton.setText(chineseUi ? "查看诊断日志" : "View diagnostic logs");
        viewLogsButton.setOnClickListener(view -> showProjectLogs());
        root.addView(viewLogsButton, matchWrap());

        Button exportDiagnosticsButton = new Button(this);
        exportDiagnosticsButton.setText(chineseUi ? "分享完整诊断包" : "Share diagnostic package");
        exportDiagnosticsButton.setOnClickListener(view -> exportProjectDiagnostics());
        root.addView(exportDiagnosticsButton, matchWrap());

        // 供应商协议：请求体与译文解析按所选协议内置实现，
        // 不再让用户手写 JSON 请求模板与译文提取路径。
        TextView protocolLabel = new TextView(this);
        protocolLabel.setText(R.string.label_protocol);
        protocolLabel.setPadding(0, dp(8), 0, 0);
        root.addView(protocolLabel, matchWrap());
        protocolSpinner = new Spinner(this);
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PROTOCOL_ENTRIES);
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocolSpinner.setAdapter(protocolAdapter);
        protocolSpinner.setSelection(positionOf(Config.DEF_PROTOCOL));
        root.addView(protocolSpinner, matchWrap());
        protocolSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateStreamingAvailability();
                markSpinnerDirty();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateStreamingAvailability();
                markSpinnerDirty();
            }
        });
        TextView protocolHint = new TextView(this);
        protocolHint.setTextSize(12f);
        protocolHint.setText(R.string.hint_protocol);
        root.addView(protocolHint, matchWrap());

        streamingInput = new Switch(this);
        streamingHint = addToggle(root, streamingInput, SettingsToggleText.Setting.STREAMING);
        TextView toggleDraftHint = new TextView(this);
        toggleDraftHint.setTextSize(12f);
        toggleDraftHint.setText(R.string.hint_switch_draft);
        root.addView(toggleDraftHint, matchWrap());

        // 注意：TYPE_TEXT_VARIATION_* 必须与 TYPE_CLASS_TEXT 组合使用。
        urlInput = addField(root, getString(R.string.label_api_url),
                getString(R.string.hint_api_url),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        keyInput = addField(root, getString(R.string.label_api_key), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        modelInput = addField(root, getString(R.string.label_model), Config.DEF_MODEL,
                InputType.TYPE_CLASS_TEXT);
        TextView targetLanguageLabel = new TextView(this);
        targetLanguageLabel.setText(R.string.label_target_language);
        targetLanguageLabel.setPadding(0, dp(8), 0, 0);
        root.addView(targetLanguageLabel, matchWrap());
        targetLanguageSpinner = new Spinner(this);
        targetLanguageValues.clear();
        targetLanguageValues.addAll(Arrays.asList(TARGET_LANGUAGE_VALUES));
        String[] labels = chineseUi ? TARGET_LANGUAGE_LABELS_ZH : TARGET_LANGUAGE_LABELS_EN;
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
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                markSpinnerDirty();
            }
        });
        root.addView(targetLanguageSpinner, matchWrap());
        // Legacy single-language spinner is retained only as a migration bridge; it is not shown.
        sendTargetLanguageSpinner = new Spinner(this);
        sendTargetLanguageSpinner.setAdapter(targetLanguageAdapter);
        sendTargetLanguageSpinner.setSelection(targetLanguagePosition(Config.DEF_SEND_TARGET_LANG));
        headersInput = addField(root, getString(R.string.label_headers), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        TextView outboundTitle = new TextView(this);
        outboundTitle.setText(chineseUi ? "发送语言按钮" : "Outbound language buttons");
        outboundTitle.setPadding(0, dp(10), 0, 0);
        root.addView(outboundTitle, matchWrap());
        outboundButtonsSummary = new TextView(this);
        outboundButtonsSummary.setTextSize(12f);
        root.addView(outboundButtonsSummary, matchWrap());
        manageOutboundButtonsButton = new Button(this);
        manageOutboundButtonsButton.setText(chineseUi ? "管理发送按钮" : "Manage send buttons");
        manageOutboundButtonsButton.setOnClickListener(v -> openOutboundButtonsEditor());
        root.addView(manageOutboundButtonsButton, matchWrap());
        advancedSettingsButton = new Button(this);
        advancedSettingsButton.setText(chineseUi ? "提示词与译文过滤设置…" : "Prompts and translation filters…");
        advancedSettingsButton.setOnClickListener(v -> openAdvancedSettings());
        root.addView(advancedSettingsButton, matchWrap());

        // Advanced controls stay in a hidden backing container so existing save/import
        // transactions continue to use the same validated fields.
        LinearLayout advancedBacking = new LinearLayout(this);
        advancedBacking.setOrientation(LinearLayout.VERTICAL);
        advancedBacking.setVisibility(View.GONE);
        systemPromptInput = addField(advancedBacking,
                chineseUi ? "接收翻译系统提示词" : "Incoming translation system prompt",
                getString(R.string.hint_system_prompt),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        resetSystemPromptButton = addPromptResetButton(advancedBacking, systemPromptInput,
                Config.DEF_SYSTEM_PROMPT);
        sendSystemPromptInput = addField(advancedBacking,
                chineseUi ? "新建发送按钮的默认提示词" : "Default prompt for new outbound buttons",
                chineseUi ? "新建按钮时作为初始提示词，可使用 {{target_language}}。" :
                        "Used when creating a button; supports {{target_language}}.",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        resetSendSystemPromptButton = addPromptResetButton(advancedBacking, sendSystemPromptInput,
                Config.DEF_SEND_SYSTEM_PROMPT);
        checkWrapperInput = addValidationSwitch(advancedBacking,
                chineseUi ? "检查包装标签" : "Check wrapper tags", Config.DEF_CHECK_WRAPPER);
        stripOuterWrapperInput = addValidationSwitch(advancedBacking,
                chineseUi ? "自动移除完整的外层包装标签" : "Strip one exact outer wrapper pair",
                Config.DEF_STRIP_OUTER_WRAPPER);
        checkSegmentMarkersInput = addValidationSwitch(advancedBacking,
                chineseUi ? "检查残留分段标记" : "Check leftover segment markers",
                Config.DEF_CHECK_SEGMENT_MARKERS);
        checkPlaceholdersInput = addValidationSwitch(advancedBacking,
                chineseUi ? "检查模板占位符" : "Check template placeholders",
                Config.DEF_CHECK_PLACEHOLDERS);
        checkRetainedMarkersInput = addValidationSwitch(advancedBacking,
                chineseUi ? "检查保留节点定位标记" : "Check retained-node markers",
                Config.DEF_CHECK_RETAINED_MARKERS);
        checkLengthInput = addValidationSwitch(advancedBacking,
                chineseUi ? "检查译文长度比例" : "Check translation length ratio",
                Config.DEF_CHECK_LENGTH);
        minLengthPercentInput = addField(advancedBacking,
                chineseUi ? "译文最小长度（原文百分比）" : "Minimum translation length (% of source)",
                String.valueOf(Config.DEF_MIN_LENGTH_PERCENT), InputType.TYPE_CLASS_NUMBER);
        maxLengthPercentInput = addField(advancedBacking,
                chineseUi ? "译文最大长度（原文百分比）" : "Maximum translation length (% of source)",
                String.valueOf(Config.DEF_MAX_LENGTH_PERCENT), InputType.TYPE_CLASS_NUMBER);
        root.addView(advancedBacking, matchWrap());

        timeoutInput = addField(root, getString(R.string.label_timeout),
                String.valueOf(Config.DEF_TIMEOUT_MS), InputType.TYPE_CLASS_NUMBER);
        concurrencyInput = addField(root, getString(R.string.label_concurrency),
                String.valueOf(Config.DEF_MAX_CONCURRENCY), InputType.TYPE_CLASS_NUMBER);

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

        saveButton = new Button(this);
        saveButton.setText(R.string.button_save);
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton, matchWrap());

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
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            // These content insets include the native ActionBar, unlike raw system-bar bounds.
            // Pad the viewport so its first/last controls remain reachable at the scroll limits.
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void updateOutboundButtonsSummary() {
        if (outboundButtonsSummary == null) return;
        if (outboundButtonsDraft.isEmpty()) {
            outboundButtonsSummary.setText(chineseUi ? "未启用任何发送按钮" : "No outbound buttons enabled");
            return;
        }
        StringBuilder summary = new StringBuilder();
        for (OutboundButtonConfig button : outboundButtonsDraft) {
            if (summary.length() > 0) summary.append("、");
            summary.append(button.label).append("（").append(button.targetLanguage).append("）");
        }
        outboundButtonsSummary.setText(summary.toString());
    }

    private void openOutboundButtonsEditor() {
        if (!started || destroyed || !configurationLoaded || persistedConfigInvalid
                || persistence.isLoading() || saveInProgress || syncInProgress
                || outboundButtonsDialog != null) return;
        String[] labels = chineseUi ? TARGET_LANGUAGE_LABELS_ZH : TARGET_LANGUAGE_LABELS_EN;
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
                chineseUi ? "接收翻译系统提示词" : "Incoming translation system prompt",
                getString(R.string.hint_system_prompt),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        incomingPrompt.setMinLines(7);
        incomingPrompt.setText(promptOrDefault(systemPromptInput, Config.DEF_SYSTEM_PROMPT));
        Button resetIncoming = new Button(this);
        resetIncoming.setText(chineseUi ? "恢复接收提示词默认值" : "Restore incoming prompt");
        resetIncoming.setOnClickListener(v -> incomingPrompt.setText(Config.DEF_SYSTEM_PROMPT));
        form.addView(resetIncoming, matchWrap());

        EditText newButtonPrompt = addField(form,
                chineseUi ? "新建发送按钮的默认提示词" : "Default prompt for new send buttons",
                chineseUi ? "支持 {{target_language}}。" : "Supports {{target_language}}.",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        newButtonPrompt.setMinLines(7);
        newButtonPrompt.setText(promptOrDefault(sendSystemPromptInput,
                Config.DEF_SEND_SYSTEM_PROMPT));
        Button resetOutbound = new Button(this);
        resetOutbound.setText(chineseUi ? "恢复新建按钮提示词默认值" : "Restore new-button prompt");
        resetOutbound.setOnClickListener(v ->
                newButtonPrompt.setText(Config.DEF_SEND_SYSTEM_PROMPT));
        form.addView(resetOutbound, matchWrap());

        TextView filterTitle = new TextView(this);
        filterTitle.setText(chineseUi ? "译文防损坏校验" : "Translation integrity checks");
        filterTitle.setTextSize(16f);
        filterTitle.setPadding(0, dp(12), 0, dp(4));
        form.addView(filterTitle, matchWrap());
        TextView filterHint = new TextView(this);
        filterHint.setTextSize(12f);
        filterHint.setText(chineseUi
                ? "每项可以独立关闭。关闭检查可能放行格式损坏、漏译或重复内容；空响应始终无效。"
                : "Checks can be disabled independently. Disabling them may allow malformed, incomplete, or repeated output; empty responses remain invalid.");
        form.addView(filterHint, matchWrap());

        Switch wrapper = addDialogSwitch(form, chineseUi ? "检查包装标签" : "Check wrapper tags",
                checkWrapperInput.isChecked());
        Switch strip = addDialogSwitch(form,
                chineseUi ? "自动移除完整的外层包装标签" : "Strip one exact outer wrapper pair",
                stripOuterWrapperInput.isChecked());
        Switch segments = addDialogSwitch(form,
                chineseUi ? "检查残留分段标记" : "Check leftover segment markers",
                checkSegmentMarkersInput.isChecked());
        Switch placeholders = addDialogSwitch(form,
                chineseUi ? "检查模板占位符" : "Check template placeholders",
                checkPlaceholdersInput.isChecked());
        Switch retained = addDialogSwitch(form,
                chineseUi ? "检查保留节点定位标记" : "Check retained-node markers",
                checkRetainedMarkersInput.isChecked());
        Switch length = addDialogSwitch(form,
                chineseUi ? "检查译文长度比例" : "Check translation length ratio",
                checkLengthInput.isChecked());
        EditText minPercent = addField(form,
                chineseUi ? "译文最小长度（原文百分比）" : "Minimum translation length (% of source)",
                String.valueOf(Config.DEF_MIN_LENGTH_PERCENT), InputType.TYPE_CLASS_NUMBER);
        minPercent.setText(minLengthPercentInput.getText().toString());
        EditText maxPercent = addField(form,
                chineseUi ? "译文最大长度（原文百分比）" : "Maximum translation length (% of source)",
                String.valueOf(Config.DEF_MAX_LENGTH_PERCENT), InputType.TYPE_CLASS_NUMBER);
        maxPercent.setText(maxLengthPercentInput.getText().toString());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(chineseUi ? "提示词与译文过滤设置" : "Prompts and translation filters")
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(chineseUi ? "应用到待保存设置" : "Apply to draft", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int min;
                    int max;
                    try {
                        min = Integer.parseInt(minPercent.getText().toString().trim());
                        max = Integer.parseInt(maxPercent.getText().toString().trim());
                    } catch (NumberFormatException invalid) {
                        Toast.makeText(this, "长度比例必须填写整数", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (min < 0 || max > 1000 || min > max) {
                        Toast.makeText(this, "长度比例需在 0～1000 之间，且最小值不能大于最大值",
                                Toast.LENGTH_LONG).show();
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
                    minLengthPercentInput.setText(String.valueOf(min));
                    maxLengthPercentInput.setText(String.valueOf(max));
                    markDirty();
                    dialog.dismiss();
                }));
        advancedSettingsDialog = dialog;
        dialog.setOnDismissListener(ignored -> advancedSettingsDialog = null);
        dialog.show();
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
        button.setText(chineseUi ? "恢复默认提示词" : "Restore default prompt");
        button.setOnClickListener(view -> input.setText(defaultPrompt));
        parent.addView(button, matchWrap());
        return button;
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

    private boolean isChineseSystemLanguage() {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        return "zh".equalsIgnoreCase(locale.getLanguage());
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
            setTextOrPlaceholder(minLengthPercentInput, String.valueOf(snapshot.minLengthPercent),
                    String.valueOf(Config.DEF_MIN_LENGTH_PERCENT));
            setTextOrPlaceholder(maxLengthPercentInput, String.valueOf(snapshot.maxLengthPercent),
                    String.valueOf(Config.DEF_MAX_LENGTH_PERCENT));
            baselineProtocol = snapshot.protocol;
            baselineTargetLanguage = snapshot.targetLang;
            baselineSendTargetLanguage = firstSendLanguage;
            hasUnsavedChanges = false;
        } finally {
            loadingUi = false;
            updateSaveButtonState();
        }
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
                            detail += "\n" + (chineseUi ? "回滚：" : "Rollback: ")
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
        boolean formAvailable = configurationLoaded && !persistedConfigInvalid;
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
        checkWrapperInput.setEnabled(formAvailable);
        stripOuterWrapperInput.setEnabled(formAvailable);
        checkSegmentMarkersInput.setEnabled(formAvailable);
        checkPlaceholdersInput.setEnabled(formAvailable);
        checkRetainedMarkersInput.setEnabled(formAvailable);
        checkLengthInput.setEnabled(formAvailable);
        updateToggleText(enabledInput, enabledHint, SettingsToggleText.Setting.TRANSLATION);
        updateToggleText(loggingInput, loggingHint, SettingsToggleText.Setting.LOGGING);
        protocolSpinner.setEnabled(formAvailable);
        protocolSpinner.setVisibility(persistedConfigInvalid ? View.INVISIBLE : View.VISIBLE);
        targetLanguageSpinner.setEnabled(formAvailable);
        targetLanguageSpinner.setVisibility(persistedConfigInvalid ? View.INVISIBLE : View.VISIBLE);
        sendTargetLanguageSpinner.setEnabled(formAvailable);
        sendTargetLanguageSpinner.setVisibility(View.GONE);
        if (manageOutboundButtonsButton != null) manageOutboundButtonsButton.setEnabled(formAvailable);
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

    /** Share the module-owned, content-free diagnostics without requiring LSPosed log access. */
    private void exportProjectDiagnostics() {
        SharedPreferences diagnostics = getSharedPreferences(
                DiagnosticProvider.PREFS_NAME, MODE_PRIVATE);
        StringBuilder report = new StringBuilder();
        report.append("DiscordAutoTranslate diagnostic package\n")
                .append("Generated: ").append(new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(new java.util.Date()))
                .append("\nModule package: ").append(getPackageName())
                .append("\nAndroid SDK: ").append(android.os.Build.VERSION.SDK_INT)
                .append("\nSettings logging: ")
                .append(loggingInput != null && loggingInput.isChecked() ? "ON" : "OFF")
                .append("\nRemote logging setting: ")
                .append(remotePrefs == null || !remotePrefs.contains(Config.KEY_LOGGING_ENABLED)
                        ? "UNKNOWN" : remotePrefs.getBoolean(Config.KEY_LOGGING_ENABLED, false) ? "ON" : "OFF")
                .append("\nDiscord logging acknowledged: ")
                .append(diagnostics.getBoolean(DiagnosticProvider.KEY_MODULE_ENABLED, false) ? "ON" : "OFF")
                .append("\nDiscord configuration valid: ")
                .append(diagnostics.getBoolean(DiagnosticProvider.KEY_CONFIG_VALID, false))
                .append("\nConfiguration acknowledgement time: ")
                .append(diagnostics.getLong(DiagnosticProvider.KEY_CONFIGURED_AT, 0L))
                .append("\nProvider ping: ").append(diagnostics.getLong("transport_last_ping_at", 0L))
                .append("\nLast append: ").append(diagnostics.getLong("transport_last_append_at", 0L))
                .append("\nLast configure: ").append(diagnostics.getLong("transport_last_configure_at", 0L))
                .append("\nLast rejected call: ").append(diagnostics.getLong("transport_last_reject_at", 0L))
                .append("\nCalling UID: ").append(diagnostics.getInt("transport_last_calling_uid", -1))
                .append("\nProcess UID: ").append(diagnostics.getInt("transport_last_process_uid", -1))
                .append("\nProcess PID: ").append(diagnostics.getInt("transport_last_process_pid", -1))
                .append("\nProcess name: ").append(diagnostics.getString("transport_last_process_name", "unknown"))
                .append("\nContext package: ").append(diagnostics.getString("transport_last_context_package", "unknown"))
                .append("\nAndroid SDK reported by Discord process: ")
                .append(diagnostics.getInt("transport_last_android_sdk", -1))
                .append("\nDiagnostic schema: ").append(diagnostics.getInt("transport_last_schema_version", 0))
                .append("\nLast provider method: ").append(diagnostics.getString("transport_last_method", "none"))
                .append("\nRejected method: ").append(diagnostics.getString("transport_last_reject_method", "none"))
                .append("\nRejected UID: ").append(diagnostics.getInt("transport_last_reject_uid", -1))
                .append("\n\n--- Content-free lifecycle log ---\n");
        String logs = diagnostics.getString(DiagnosticProvider.KEY_BUFFER, "");
        report.append(logs == null || logs.isEmpty() ? "No lifecycle events received.\n" : logs);

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "DiscordAutoTranslate diagnostic package");
        share.putExtra(Intent.EXTRA_TEXT, report.toString());
        try {
            startActivity(Intent.createChooser(share,
                    chineseUi ? "分享诊断包" : "Share diagnostic package"));
        } catch (Throwable failure) {
            Toast.makeText(this, chineseUi ? "无法打开分享界面：" + failure.getClass().getSimpleName()
                    : "Unable to open share sheet: " + failure.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showProjectLogs() {
        String connectionState;
        SharedPreferences remote = remotePrefs;
        if (service != null) {
            try {
                remote = service.getRemotePreferences(Config.GROUP);
                remotePrefs = remote;
                connectionState = chineseUi ? "已重新连接远程设置" : "Remote preferences refreshed";
            } catch (Throwable failure) {
                connectionState = (chineseUi ? "远程设置读取失败：" : "Remote preferences unavailable: ")
                        + failure.getClass().getSimpleName();
                remote = null;
            }
        } else {
            connectionState = chineseUi ? "设置服务尚未连接" : "Settings service is not connected";
        }

        // Discord writes diagnostics to this module-owned store through the restricted
        // DiagnosticProvider. Never read process logs from Xposed remote preferences.
        SharedPreferences diagnostics = getSharedPreferences(
                DiagnosticProvider.PREFS_NAME, MODE_PRIVATE);
        boolean uiLoggingEnabled = loggingInput != null && loggingInput.isChecked();
        boolean remoteConfigHasLoggingSetting = remote != null
                && remote.contains(Config.KEY_LOGGING_ENABLED);
        boolean remoteConfigLoggingEnabled = remote != null
                && remote.getBoolean(Config.KEY_LOGGING_ENABLED, false);
        long configuredAt = diagnostics.getLong(DiagnosticProvider.KEY_CONFIGURED_AT, 0L);
        long transportPingAt = diagnostics.getLong("transport_last_ping_at", 0L);
        long appendAt = diagnostics.getLong("transport_last_append_at", 0L);
        long configureAt = diagnostics.getLong("transport_last_configure_at", 0L);
        long rejectAt = diagnostics.getLong("transport_last_reject_at", 0L);
        int lastCallingUid = diagnostics.getInt("transport_last_calling_uid", -1);
        int rejectedUid = diagnostics.getInt("transport_last_reject_uid", -1);
        String lastMethod = diagnostics.getString("transport_last_method", "");
        String rejectedMethod = diagnostics.getString("transport_last_reject_method", "");
        boolean moduleLoggingEnabled = diagnostics.getBoolean(
                DiagnosticProvider.KEY_MODULE_ENABLED, false);
        boolean configValid = diagnostics.getBoolean(DiagnosticProvider.KEY_CONFIG_VALID, false);
        String logs = diagnostics.getString(DiagnosticProvider.KEY_BUFFER, "");
        if (logs == null) logs = "";
        java.text.SimpleDateFormat diagnosticTime = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());

        StringBuilder display = new StringBuilder();
        if (!logs.trim().isEmpty()) {
            display.append(logs);
        } else if (transportPingAt > 0L || appendAt > 0L || configureAt > 0L) {
            display.append(chineseUi
                    ? "Provider 已收到 Discord 进程请求，但目前没有可显示的白名单诊断日志。请检查下面的调用状态。\n\n"
                    : "The provider received a Discord-process request, but no allow-listed log is available. Check the call status below.\n\n");
        } else if (rejectAt > 0L) {
            display.append(chineseUi
                    ? "Provider 收到过请求，但调用方身份校验未通过。请检查下面的拒绝记录。\n\n"
                    : "The provider received a request but rejected caller authentication. Check the rejection record below.\n\n");
        } else {
            display.append(chineseUi
                    ? "尚未收到 Discord 进程诊断回执。模块现在会在 Application.attach 之前主动尝试建立诊断连接；此状态仍不能单独判断 Hook 是否执行。\n\n"
                    : "No Discord-process diagnostic acknowledgement has arrived. The module now attempts to establish diagnostics before Application.attach; this alone still cannot determine whether hooks ran.\n\n");
        }
        display.append("\n--- ").append(chineseUi ? "诊断状态" : "Diagnostic status").append(" ---\n")
                .append(chineseUi ? "设置页日志开关：" : "Settings toggle: ")
                .append(uiLoggingEnabled ? "ON" : "OFF").append('\n')
                .append(chineseUi ? "远程配置日志开关：" : "Remote config logging: ")
                .append(!remoteConfigHasLoggingSetting ? "MISSING"
                        : remoteConfigLoggingEnabled ? "ON" : "OFF").append('\n')
                .append(chineseUi ? "Discord 进程日志开关：" : "Discord process logging: ")
                .append(configuredAt == 0L ? "UNKNOWN"
                        : !configValid ? "INVALID" : moduleLoggingEnabled ? "ON" : "OFF").append('\n')
                .append(chineseUi ? "Discord 进程回执时间：" : "Discord process acknowledgement: ")
                .append(configuredAt == 0L ? (chineseUi ? "尚未收到" : "not received")
                        : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                .format(new java.util.Date(configuredAt))).append('\n')
                .append(chineseUi ? "Provider Ping：" : "Provider ping: ")
                .append(transportPingAt == 0L ? (chineseUi ? "未记录" : "not recorded")
                        : diagnosticTime.format(new java.util.Date(transportPingAt))).append('\n')
                .append(chineseUi ? "最近成功写入：" : "Last accepted append: ")
                .append(appendAt == 0L ? (chineseUi ? "未记录" : "not recorded")
                        : diagnosticTime.format(new java.util.Date(appendAt))).append('\n')
                .append(chineseUi ? "最近配置回执：" : "Last configuration acknowledgement: ")
                .append(configureAt == 0L ? (chineseUi ? "未记录" : "not recorded")
                        : diagnosticTime.format(new java.util.Date(configureAt))).append('\n')
                .append(chineseUi ? "最近 Provider 调用：" : "Last provider method: ")
                .append(lastMethod == null || lastMethod.isEmpty() ? "—" : lastMethod)
                .append(lastCallingUid < 0 ? "" : " (UID " + lastCallingUid + ")").append('\n')
                .append(chineseUi ? "最近拒绝记录：" : "Last rejected call: ")
                .append(rejectAt == 0L ? (chineseUi ? "无记录" : "none")
                        : diagnosticTime.format(new java.util.Date(rejectAt))
                                + " / " + (rejectedMethod == null ? "?" : rejectedMethod)
                                + " / UID " + rejectedUid).append('\n')
                .append(chineseUi ? "IPC 通道握手：" : "IPC transport handshake: ")
                .append(transportPingAt == 0L ? (chineseUi ? "尚未成功" : "not confirmed")
                        : (chineseUi ? "已收到 Provider 回执" : "provider acknowledgement received"))
                .append('\n')
                .append(chineseUi ? "独立诊断通道：" : "Independent diagnostics: ")
                .append(chineseUi
                        ? "ContentProvider IPC（验证 Discord UID；仅保存不含聊天内容的生命周期事件）"
                        : "ContentProvider IPC (Discord UID verified; content-free lifecycle events only)")
                .append('\n')
                .append(chineseUi ? "Discord 进程 PID/UID：" : "Discord process PID/UID: ")
                .append(diagnostics.getInt("transport_last_process_pid", -1)).append("/")
                .append(diagnostics.getInt("transport_last_process_uid", -1)).append('\n')
                .append(chineseUi ? "Discord 进程名称：" : "Discord process name: ")
                .append(diagnostics.getString("transport_last_process_name", "unknown")).append('\n')
                .append(chineseUi ? "Provider 调用上下文：" : "Provider caller context: ")
                .append(diagnostics.getString("transport_last_context_package", "unknown")).append('\n')
                .append(chineseUi ? "Android SDK：" : "Android SDK: ")
                .append(diagnostics.getInt("transport_last_android_sdk", -1)).append('\n')
                .append(chineseUi ? "Xposed 设置服务：" : "Xposed settings service: ")
                .append(connectionState)
                .append("\n\n--- ").append(chineseUi ? "Hook 分阶段确认" : "Hook stage checkpoints")
                .append(" ---\n");
        boolean providerConnected = transportPingAt > 0L;
        appendDiagnosticStage(display, chineseUi ? "模块进入进程" : "Module entry loaded",
                "module lifecycle entered", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "Discord 包生命周期" : "Discord package lifecycle",
                "Discord package lifecycle entered", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "Application.attach Hook 安装" : "Application.attach Hook installed",
                "Application.attach bootstrap hook installed", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "Application.attach Hook 触发" : "Application.attach Hook triggered",
                "Discord Application.attach hook triggered", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "Discord 版本配置识别" : "Discord version profile selected",
                "Discord hook profile selected", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "业务 Hook 安装并启用" : "Required Hooks installed and runtime activated",
                "Discord required hooks installed and runtime activated", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "Activity.onResume Hook 触发" : "Activity.onResume Hook triggered",
                "Discord Activity.onResume hook triggered", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "聊天输入 Activity 进入" : "Chat input Activity reached",
                "Discord chat input Activity attach entered", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "输入框获得焦点" : "Chat input editor focused",
                "Discord chat input editor focused", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "发送控件识别" : "Native send control found",
                "Discord chat input send control found", logs, providerConnected);
        appendDiagnosticStage(display, chineseUi ? "翻译按钮注入" : "Translation button injected",
                "Discord translation button attached", logs, providerConnected);
        if (!providerConnected) {
            display.append(chineseUi
                    ? "\nProvider 尚未完成握手：以上阶段暂时无法读取。可使用设置页的「分享完整诊断包」导出模块当前已收集的数据。\n"
                     : "\nProvider handshake is not confirmed, so these stages are not yet readable. Use Share diagnostic package to export the data currently collected by the module.\n");
        }

        final String displayed = display.toString();
        TextView content = new TextView(this);
        content.setText(displayed);
        content.setTextIsSelectable(true);
        content.setTextSize(12f);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(chineseUi ? "项目诊断日志" : "Project diagnostic logs")
                .setView(scroll)
                .setPositiveButton(chineseUi ? "复制全部" : "Copy all", (d, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("DiscordAutoTranslate logs", displayed));
                        Toast.makeText(this, chineseUi ? "日志已复制" : "Logs copied", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(chineseUi ? "关闭" : "Close", null)
                .setNeutralButton(chineseUi ? "刷新" : "Refresh", (d, which) -> showProjectLogs())
                .create();
        dialog.show();
    }

    private void appendDiagnosticStage(StringBuilder display, String label, String event,
                                       String logs, boolean providerConnected) {
        boolean confirmed = logs.contains("DiscordAutoTranslate: " + event);
        display.append(label).append(chineseUi ? "：" : ": ");
        if (confirmed) {
            display.append(chineseUi ? "已确认" : "CONFIRMED");
        } else {
            display.append(!providerConnected
                    ? (chineseUi ? "无法读取（IPC 未握手）" : "UNAVAILABLE (IPC not connected)")
                    : (chineseUi ? "尚未记录" : "NOT RECORDED"));
        }
        display.append('\n');
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
