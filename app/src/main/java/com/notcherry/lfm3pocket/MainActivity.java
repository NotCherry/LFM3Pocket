package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.util.Linkify;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.StringRes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends androidx.activity.ComponentActivity implements MainActivitySettingsHost {
    public static final String EXTRA_HISTORY_CONVERSATION_ID="history_conversation_id";
    public static final String EXTRA_HISTORY_TITLE="history_title";
    public static final String EXTRA_OPEN_SETTINGS = "open_settings";
    public static final String EXTRA_DRAFT_TEXT = "draft_text";
    public static final String EXTRA_FRESH_CHAT = "fresh_chat";
    /** Home and other in-app shortcuts that must share {@link #requestNewChat(boolean)}. */
    public static final String EXTRA_REQUEST_NEW_CHAT = "request_new_chat";
    /** System assistant gesture: open the single entry path and start a Hanami voice session. */
    public static final String EXTRA_ASSISTANT_VOICE_SESSION = "assistant_voice_session";
    public static final String EXTRA_SHARED_IMAGE = "shared_image_uri";
    private static final int PICK_MODEL = 42;
    private static final int PICK_MMPROJ = 43;
    private static final int PICK_PHOTO = 44;
    private static final int TAKE_PHOTO = 45;
    private static final int REQUEST_AUDIO = 46;
    private static final int REQUEST_CAMERA = 47;
    private static final int OPEN_MODEL_URI = 48;
    private static final int PICK_DOCUMENT = 49;
    private static final int OPEN_MMPROJ_URI = 49;
    private static final int PICK_WHISTLE_MODEL = 50;
    private static final int REQUEST_READ_CALENDAR = 51;
    private static final int REQUEST_POST_NOTIFICATIONS_WHISTLE = 52;


    private ExecutorService worker;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ModelRuntime runtime;
    private ChatSession session;
    private ChatPanel chatPanel;
    private ToolCoordinator toolCoordinator;
    private HistoryRepository historyRepository;
    private String historyConversationId="default";
    private String historyTitle="Hanami";
    private InferenceEngine inferenceEngine;
    private boolean busy = false;
    private boolean generating = false;
    private boolean speaking = false;
    private volatile boolean destroyed = false;
    private volatile AtomicBoolean importCancellation;
    private final ExecutorService audioWorker = Executors.newSingleThreadExecutor();
    private RecordingController recordingController;
    private VoicePttCoordinator voicePtt;
    private final java.util.concurrent.atomic.AtomicBoolean voiceSttCancelled = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean transcribingVoiceInput;
    private PlaybackController playbackController;
    private float attachedInterfaceScale = InterfaceSize.DEFAULT;
    private AppearanceSettingsSection appearanceSettings;
    private ImageSettingsSection imageSettingsSection;
    private InferenceSettingsSection inferenceSettingsSection;
    private SearchSettingsSection searchSettingsSection;
    private BackgroundSettingsSection backgroundSettingsSection;
    private IntegrationsSettingsSection integrationsSettingsSection;
    private Button defaultAssistantButton;
    private boolean foreground;
    private boolean microphoneRequested;
    private boolean cameraRequested;
    private String pendingImagePath = "";
    private String pendingAudioPath = "";
    private final java.util.List<ChatSession.Attachment> pendingDocuments = new java.util.ArrayList<>();
    private String lastAssistantReply = "";
    private boolean pendingAssistantVoiceSession;
    boolean assistantVoiceSessionLaunchRequested;
    boolean freshChatRequestedFromAssist;

    private ChatShellSection chatShellSection;
    private ModelImportSettingsSection modelImportSection;
    private View settingsHost;
    private InferenceSettings inferenceSettings=InferenceSettings.DEFAULT;
    private InferenceSettingsStore settingsStore;
    private ImageSettings imageSettings=ImageSettings.DEFAULT;
    private ImageSettingsStore imageSettingsStore;
    private boolean settingsLoaded;
    private boolean imageSettingsLoaded;
    private boolean settingsOpen;
    private Button photoButton, cameraButton, documentButton, recordButton, speakButton;
    private boolean searchEnabled = false;
    private String searchEndpoint = "";
    private String mmprojPath = "";

    private String voiceUiStatus = "";

    private final ModelRuntime.Ui viewer = new ModelRuntime.Ui() {
        @Override public void onChat(String transcript, String message, boolean running) {
            if (destroyed || chatPanel == null || chatShellSection == null) return;
            generating = running;
            busy = running || recordingActive() || playbackActive() || speaking;
            chatPanel.render(transcript, message);
            chatShellSection.applyChatStatusDuringTurn(message, running);
            updateControls();
        }
        @Override public void onSnapshot(ChatSnapshot snapshot) {
            if (destroyed || chatPanel == null) return;
            chatPanel.renderSnapshot(snapshot);
        }
        @Override public void onAssistantReply(String text) {
            if (text != null && !text.isBlank()) lastAssistantReply = text;
        }
        @Override public void onHistoryTitle(String title) {
            if (title == null || title.isBlank()) return;
            historyTitle = title;
            if (chatShellSection != null) chatShellSection.setBrandTitle(historyTitle);
        }
        @Override public void onPlayback(String message) {
            if (destroyed || chatShellSection == null) return;
            busy = recordingActive() || playbackActive() || generating || speaking;
            setChatStatus(message);
            updateControls();
        }
        @Override public void onSpeech(boolean active, String message) {
            if (destroyed || chatShellSection == null) return;
            speaking = active;
            busy = active || recordingActive() || playbackActive() || generating;
            setChatStatus(message);
            updateControls();
        }
        @Override public void onToolOutcome(ToolRegistry.Request request, ToolCoordinator.Outcome outcome) {
            if (destroyed) return;
            presentToolOutcome(request, outcome);
        }
        @Override public void onVoiceStatus(String message) {
            if (destroyed || chatShellSection == null) return;
            if (message != null) {
                voiceUiStatus = message;
                setChatStatus(message);
            }
            updateControls();
        }
        @Override public boolean isUsable() { return !destroyed && chatPanel != null; }
    };

    @Override protected void attachBaseContext(Context newBase) {
        attachedInterfaceScale = InterfaceSize.read(newBase);
        super.attachBaseContext(InterfaceSize.wrap(newBase));
    }

    @Override protected void onResume() {
        super.onResume();
        InterfaceSize.recreateIfChanged(this, attachedInterfaceScale);
        if (integrationsSettingsSection != null) {
            CactusWhistleModelDownloader.bindUi(integrationsSettingsSection.cactusWhistleDownloadUi());
            if (CactusWhistleModelDownloader.isDownloading()) integrationsSettingsSection.paintSpeechInputControls();
        }
        String pendingWhistleError = CactusWhistleModelDownloader.consumePendingBackgroundError();
        if (pendingWhistleError != null) setChatStatus(pendingWhistleError);
    }

    @Override protected void onPause() {
        if (integrationsSettingsSection != null) {
            CactusWhistleModelDownloader.unbindUi(integrationsSettingsSection.cactusWhistleDownloadUi());
        }
        super.onPause();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        runtime = ((Lfm3PocketApplication) getApplication()).modelRuntime();
        worker = runtime.worker();
        inferenceEngine = runtime.engine();
        playbackController = runtime.playback();
        historyRepository = new HistoryRepository(HistoryDatabase.get(this));
        adoptConversation(getIntent());
        recordingController = new RecordingController(new File(getCacheDir(), "recordings"),
                ((Lfm3PocketApplication)getApplication())::newRecordingSource,
                () -> ((Lfm3PocketApplication)getApplication()).hasMicrophonePermission(),
                audioWorker, task -> mainHandler.post(task), (captureState, message, completed) -> {
                    if (destroyed) { if (completed != null) completed.delete(); return; }
                    if (completed != null && SpeechInputProviders.usesSttTextPipeline(this)) {
                        handleCompletedRecordingAsStt(completed);
                        return;
                    }
                    busy = recordingActive() || playbackActive() || speaking || generating;
                    setChatStatus(message);
                    if (completed != null) {
                        if (!pendingAudioPath.isEmpty()) HistoryMediaCleanup.deleteAttachment(this,pendingAudioPath);
                        pendingAudioPath = completed.getAbsolutePath();
                    }
                    updateControls();
                });
        settingsStore=new InferenceSettingsStore(this);
        imageSettingsStore=new ImageSettingsStore(this);
        buildUi(state);
        session = runtime.session();
        runtime.attach(viewer);
        updateControls();
        settingsStore.load(settings -> {
            if (destroyed) return;
            inferenceSettings=settings;inferenceEngine.configure(settings);settingsLoaded=true;
            syncInlineSettingsFields();updateControls();
        }, error -> {
            if (destroyed) return;
            inferenceSettings=InferenceSettings.DEFAULT;inferenceEngine.configure(inferenceSettings);
            settingsLoaded=true;setChatStatus("Settings unavailable; using defaults: "+error);
            syncInlineSettingsFields();updateControls();
        });
        imageSettingsStore.load(settings -> {
            if (destroyed) return;
            imageSettings=settings;imageSettingsLoaded=true;
            if (imageSettingsSection != null) imageSettingsSection.syncFields();
        }, error -> {
            if (destroyed) return;
            imageSettings=ImageSettings.DEFAULT;imageSettingsLoaded=true;
            if (imageSettingsSection != null) imageSettingsSection.syncFields();
            setChatStatus("Image settings unavailable; using defaults: "+error);
        });
        String previous = pocketPreferences().getString("model", "");
        mmprojPath = pocketPreferences().getString("mmproj", "");
        searchEndpoint = pocketPreferences().getString("search_endpoint", "");
        searchEnabled = pocketPreferences().getBoolean("search_enabled", false);
        chatPanel.setIdleMode(searchEnabled);
        syncInlineSettingsFields();
        syncBackgroundControls();
        final boolean launchNewChat = shouldDeferNewChat(getIntent());
        consumeAssistantVoiceSessionIntent(getIntent());
        final boolean modelInMemory = runtime.handle() != 0L
                && (previous.equals(runtime.loadedPath()) || isContentUri(previous));
        if (modelInMemory) {
            if (!launchNewChat && !runtime.isGenerating()) setChatStatus(mmprojPath == null || mmprojPath.isEmpty()
                    ? "Text model ready · offline" : "Model and projector loaded · offline");
        } else if (!previous.isEmpty() && (isContentUri(previous) || new File(previous).isFile())) {
            loadSelectedModel(previous, mmprojPath);
        } else if (runtime.handle() == 0L) {
            setChatStatus("No model loaded. Select an LFM3 GGUF file.");
        }
        if (launchNewChat) {
            mainHandler.post(() -> handlePendingNewChatRequests(getIntent()));
        } else {
            // Resume path: re-apply persisted history if async restore raced with startup/model load.
            final String resumeId = historyConversationId;
            mainHandler.postDelayed(() -> {
                if (destroyed || runtime == null) return;
                if (resumeId == null || resumeId.isEmpty()) return;
                if (!resumeId.equals(runtime.conversationId())) return;
                if (runtime.isGenerating()) return;
                runtime.restoreOpenConversation();
            }, 1200);
            mainHandler.postDelayed(() -> {
                if (destroyed || runtime == null) return;
                if (resumeId == null || resumeId.isEmpty()) return;
                if (!resumeId.equals(runtime.conversationId())) return;
                if (runtime.isGenerating()) return;
                runtime.restoreOpenConversation();
            }, 3500);
        }
        if (pendingSwitchId != null) confirmSwitchConversation();
        maybeStartPendingAssistantVoiceSession();
    }

    private String pendingSwitchId;
    private String pendingSwitchTitle;

    private void adoptConversation(Intent intent) {
        String requested = intent == null ? null : intent.getStringExtra(EXTRA_HISTORY_CONVERSATION_ID);
        String requestedTitle = intent == null ? null : intent.getStringExtra(EXTRA_HISTORY_TITLE);
        boolean explicit = requested != null && !requested.isBlank();
        android.content.SharedPreferences prefs = pocketPreferences();
        OpenConversation choice = OpenConversation.choose(
                explicit ? requested : null,
                prefs.getString("history_conversation_id", ""),
                runtime.conversationId(),
                runtime.isGenerating(),
                java.util.UUID.randomUUID().toString());
        String id = choice.id;
        String title = requestedTitle != null && !requestedTitle.isBlank() ? requestedTitle
                : prefs.getString("history_title", runtime.historyTitle());
        if (choice.confirmSwitch) {
            pendingSwitchId = id;
            pendingSwitchTitle = title;
            historyConversationId = runtime.conversationId();
            historyTitle = runtime.historyTitle();
            return;
        }
        boolean newChatIntent = intent != null && (intent.getBooleanExtra(EXTRA_REQUEST_NEW_CHAT, false)
                || intent.getBooleanExtra(EXTRA_FRESH_CHAT, false));
        boolean resumeFromStored = !newChatIntent && (explicit
                || !prefs.getString("history_conversation_id", "").isBlank());
        if (!runtime.isGenerating() && !runtime.conversationId().isBlank()
                && (!id.equals(runtime.conversationId()) || resumeFromStored)) {
            runtime.clearConversation();
            lastAssistantReply = "";
        }
        historyConversationId = id;
        historyTitle = title == null || title.isBlank() ? getString(R.string.new_conversation_title) : title;
        runtime.setConversation(historyConversationId, historyTitle);
        runtime.restoreOpenConversation();
    }

    private void confirmSwitchConversation() {
        String id = pendingSwitchId;
        String title = pendingSwitchTitle;
        pendingSwitchId = null;
        pendingSwitchTitle = null;
        new AlertDialog.Builder(this)
                .setTitle(R.string.reply_still_running_title)
                .setMessage(R.string.reply_still_running_message)
                .setPositiveButton(R.string.stop_and_start_new, (dialog, which) -> {
                    runtime.stopTurn();
                    runtime.clearConversation();
                    lastAssistantReply = "";
                    historyConversationId = id;
                    historyTitle = title == null || title.isBlank() ? getString(R.string.new_conversation_title) : title;
                    runtime.setConversation(historyConversationId, historyTitle);
                    if (chatShellSection != null) chatShellSection.setBrandTitle(historyTitle);
                })
                .setNegativeButton(R.string.keep_this_reply, (dialog, which) -> {
                    historyConversationId = runtime.conversationId();
                    historyTitle = runtime.historyTitle();
                })
                .show();
    }

    private long modelHandle() { return runtime == null ? 0L : runtime.handle(); }

    @Override public Activity settingsActivity() {
        return this;
    }

    @Override public int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public boolean isHostFinishing() {
        return destroyed;
    }

    @Override public Handler settingsMainHandler() {
        return mainHandler;
    }

    @Override public void prepareSettingsRecreateForInterfaceSize() {
        if (getIntent() != null) getIntent().putExtra(EXTRA_OPEN_SETTINGS, true);
        settingsOpen = true;
        recreate();
    }

    void setChatStatus(CharSequence message) {
        if (chatShellSection != null) chatShellSection.setChatStatus(message);
    }

    void setChatStatus(@StringRes int resId) {
        setChatStatus(getString(resId));
    }

    void setChatStatus(@StringRes int resId, Object... formatArgs) {
        setChatStatus(getString(resId, formatArgs));
    }

    private CharSequence currentChatStatus() {
        return chatShellSection == null ? "" : chatShellSection.chatStatusText();
    }

    @Override public EditText addSettingInput(LinearLayout form, String title, String hint, int value, int id,
            int step, int min, int max) {
        return settingInput(form, title, hint, value, id, step, min, max);
    }

    @Override public TextView label(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        // Flexible wrapping: large font scales must never clip settings copy.
        v.setSingleLine(false);
        v.setMaxLines(Integer.MAX_VALUE);
        v.setHorizontallyScrolling(false);
        return v;
    }

    @Override public Button button(String title) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        return b;
    }

    private void onChatPanelNewChat() {
        voiceSttCancelled.set(true);
        HistoryMediaCleanup.deleteAttachment(this, pendingImagePath);
        HistoryMediaCleanup.deleteAttachment(this, pendingAudioPath);
        pendingImagePath = "";
        pendingAudioPath = "";
        clearPendingDocuments();
        lastAssistantReply = "";
        if (playbackActive()) playbackController.stop();
        chatPanel.clearImageAttachment();
        runtime.clearConversation();
        lastAssistantReply = "";
    }

    private void onChatPanelRetryTurn(String turnId) {
        if (busy || modelHandle() == 0L || !settingsLoaded) return;
        runtime.noteSubmission("retry", inferenceSettings.maxTokens);
        if (runtime.retryInterrupted(turnId)) {
            session = runtime.session();
            pendingImagePath = "";
            pendingAudioPath = "";
            clearPendingDocuments();
            chatPanel.clearImageAttachment();
            busy = true;
            updateControls();
        }
    }

    private void buildUi(Bundle state) {
        int surface = StillwaterUi.SURFACE;
        int ink = StillwaterUi.INK;
        int inkMuted = StillwaterUi.MUTED;
        int accent = StillwaterUi.CANDLE;
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        root.setPadding(dp(12), dp(6), dp(12), dp(6));

        chatShellSection = new ChatShellSection(this, new ChatShellSection.Deps(
                () -> historyTitle, () -> searchEnabled, () -> requestNewChat(false),
                () -> setSettingsOpen(true), this::showSearchSettings,
                new ChatShellSection.ChatPanelDeps(this::send, this::stopActiveWork, this::onChatPanelNewChat,
                        this::onChatPanelRetryTurn, this::requestCompact)));
        chatShellSection.mountChrome(root, ink, inkMuted, accent);

        // --- Settings host (hidden by default) ---
        LinearLayout settingsRoot = new LinearLayout(this);
        settingsRoot.setOrientation(LinearLayout.VERTICAL);
        settingsRoot.setBackgroundColor(surface);
        Button settingsBack = button(getString(R.string.back_to_chat));
        settingsBack.setId(R.id.settings_back);
        StillwaterUi.quiet(settingsBack);
        settingsBack.setTextColor(accent);
        settingsBack.setOnClickListener(v -> setSettingsOpen(false));
        StillwaterReadable.prepareWrappingControl(settingsBack, dp(44));
        settingsRoot.addView(settingsBack, new LinearLayout.LayoutParams(-2, -2));
        TextView settingsHeading = label(getString(R.string.settings_title), 28, ink);
        settingsHeading.setTypeface(StillwaterUi.serif(this));
        settingsHeading.setPadding(0, dp(2), 0, dp(6));
        StillwaterReadable.prepareWrappingLabel(settingsHeading);
        settingsRoot.addView(settingsHeading);

        LinearLayout configuration = new LinearLayout(this);
        configuration.setOrientation(LinearLayout.VERTICAL);
        configuration.setPadding(0, 0, 0, dp(16));
        modelImportSection = new ModelImportSettingsSection(this, new ModelImportSettingsSection.Deps(
                this::pickModel, this::pickMmproj,
                () -> pickExternalModel(OPEN_MODEL_URI),
                () -> pickExternalModel(OPEN_MMPROJ_URI),
                this::reloadSelectedModel, this::unloadSelectedModel, this::showInferenceSettings));
        modelImportSection.mount(configuration, accent);

        appearanceSettings = new AppearanceSettingsSection(this);
        appearanceSettings.mount(configuration, ink, inkMuted);
        imageSettingsSection = new ImageSettingsSection(this, imageSettingsStore,
                () -> imageSettings, validated -> {
            imageSettings = validated;
            imageSettingsLoaded = true;
        }, this::setChatStatus);
        inferenceSettingsSection = new InferenceSettingsSection(this, settingsStore,
                () -> inferenceSettings, validated -> inferenceSettings = validated,
                inferenceEngine::configure, () -> settingsLoaded, () -> busy, () -> generating,
                this::modelHandle, worker, this::updateControls,
                new InferenceSettingsSection.BundledImageSettings() {
                    @Override public ImageSettings readForBundledSave() {
                        return imageSettingsSection == null
                                ? imageSettings
                                : imageSettingsSection.readForInlineInferenceSave();
                    }
                    @Override public void persistBundledQuiet(ImageSettings next) {
                        if (imageSettingsSection != null) imageSettingsSection.persist(next, false);
                    }
                }, this::setChatStatus);
        inferenceSettingsSection.mount(configuration, ink, inkMuted);
        searchSettingsSection = new SearchSettingsSection(this, new SearchSettingsSection.Deps(
                () -> searchEndpoint, () -> searchEnabled,
                (endpoint, enabled) -> {
                    searchEndpoint = endpoint;
                    searchEnabled = enabled;
                    pocketPreferences().edit()
                            .putString("search_endpoint", searchEndpoint)
                            .putBoolean("search_enabled", searchEnabled).apply();
                },
                enabled -> chatPanel.setIdleMode(enabled), this::updateControls, () -> busy,
                this::showSearchSettings, this::setChatStatus));
        searchSettingsSection.mount(configuration, ink, inkMuted);

        imageSettingsSection.mount(configuration, ink, inkMuted);
        backgroundSettingsSection = new BackgroundSettingsSection(this, new BackgroundSettingsSection.Deps(
                () -> new RuntimePreferences(this),
                (keep, idle, lock, loopback) -> runtime.updateBackgroundSettings(keep, idle, lock, loopback),
                () -> runtime.loopbackToken(), () -> runtime.loopbackPort(), this::setChatStatus));
        backgroundSettingsSection.mount(configuration, ink, inkMuted);
        integrationsSettingsSection = new IntegrationsSettingsSection(this, new IntegrationsSettingsSection.Deps(
                () -> new RuntimePreferences(this), this::updateControls, this::pickCalendarTarget,
                this::pickWhistleModel, this::downloadCactusWhistleModel, this::downloadTinyVocaPack,
                this::runOnUiThread, this::setChatStatus));
        integrationsSettingsSection.mount(configuration, ink, inkMuted);

        defaultAssistantButton = button(getString(R.string.default_assistant_button));
        defaultAssistantButton.setId(R.id.settings_default_assistant);
        StillwaterUi.quiet(defaultAssistantButton);
        defaultAssistantButton.setOnClickListener(v -> openDefaultAssistantPicker());
        StillwaterReadable.addFlexibleFullWidth(configuration, defaultAssistantButton, 48);
        TextView defaultAssistantHelp = label(getString(R.string.default_assistant_help), 12, inkMuted);
        StillwaterReadable.prepareWrappingLabel(defaultAssistantHelp);
        configuration.addView(defaultAssistantHelp);

        TextView crashHeading = label(getString(R.string.settings_crash_log_heading), 11, inkMuted);
        crashHeading.setTypeface(StillwaterUi.sansMedium(this));
        crashHeading.setLetterSpacing(0.12f);
        crashHeading.setPadding(0, dp(16), 0, dp(4));
        configuration.addView(crashHeading);
        configuration.addView(label(getString(R.string.settings_crash_log_body), 14, inkMuted));
        TextView crashLogText = label(getString(R.string.settings_crash_log_empty), 13, ink);
        crashLogText.setId(R.id.settings_crash_log_text);
        crashLogText.setPadding(0, dp(8), 0, dp(8));
        configuration.addView(crashLogText);
        Button viewCrashLog = button(getString(R.string.settings_crash_log_view));
        viewCrashLog.setId(R.id.settings_crash_log_view);
        StillwaterUi.quiet(viewCrashLog);
        viewCrashLog.setOnClickListener(v -> {
            String body = CrashLog.read(this);
            crashLogText.setText(body.isEmpty() ? getString(R.string.settings_crash_log_empty) : body);
        });
        configuration.addView(viewCrashLog, new LinearLayout.LayoutParams(-1, dp(44)));
        Button exportCrashLog = button(getString(R.string.settings_crash_log_export));
        exportCrashLog.setId(R.id.settings_crash_log_export);
        StillwaterUi.quiet(exportCrashLog);
        exportCrashLog.setOnClickListener(v -> {
            String body = CrashLog.read(this);
            if (body.isEmpty()) body = getString(R.string.settings_crash_log_empty);
            android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(android.content.Intent.EXTRA_SUBJECT, "Hanami crash log");
            share.putExtra(android.content.Intent.EXTRA_TEXT, body);
            startActivity(android.content.Intent.createChooser(share, getString(R.string.settings_crash_log_export)));
            setChatStatus(getString(R.string.settings_crash_log_exported));
        });
        configuration.addView(exportCrashLog, new LinearLayout.LayoutParams(-1, dp(44)));
        Button clearCrashLog = button(getString(R.string.settings_crash_log_clear));
        clearCrashLog.setId(R.id.settings_crash_log_clear);
        StillwaterUi.quiet(clearCrashLog);
        clearCrashLog.setOnClickListener(v -> {
            CrashLog.clear(this);
            crashLogText.setText(getString(R.string.settings_crash_log_empty));
        });
        configuration.addView(clearCrashLog, new LinearLayout.LayoutParams(-1, dp(44)));

        ScrollView configurationScroll = new ScrollView(this);
        configurationScroll.setId(R.id.model_configuration_scroll);
        configurationScroll.setBackgroundColor(surface);
        configurationScroll.setPadding(0, dp(4), 0, dp(8));
        configurationScroll.addView(configuration);
        settingsRoot.addView(configurationScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Sticky Save controls stay on-screen (small emulator + IME) for UiAutomator Stage 1 IDs.
        LinearLayout settingsActions = new LinearLayout(this);
        settingsActions.setOrientation(LinearLayout.VERTICAL);
        settingsActions.setBackgroundColor(surface);
        settingsActions.setPadding(0, dp(6), 0, dp(6));
        Button saveInference = button(getString(R.string.save));
        saveInference.setId(R.id.settings_save);
        StillwaterUi.primary(saveInference);
        saveInference.setOnClickListener(v -> {
            if (inferenceSettingsSection != null) inferenceSettingsSection.saveInline();
        });
        StillwaterReadable.addFlexibleFullWidth(settingsActions, saveInference, 52);
        if (searchSettingsSection != null) searchSettingsSection.mountSaveButton(settingsActions);
        settingsRoot.addView(settingsActions, new LinearLayout.LayoutParams(-1, -2));

        settingsHost = settingsRoot;
        settingsHost.setVisibility(View.GONE);
        root.addView(settingsHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        // --- Chat shell (primary viewport) ---
        chatShellSection.mountViewport(root, surface);
        chatPanel = chatShellSection.chatPanel();
        photoButton = chatShellSection.photoButton();
        cameraButton = chatShellSection.cameraButton();
        documentButton = chatShellSection.documentButton();
        recordButton = chatShellSection.recordButton();
        speakButton = chatShellSection.speakButton();

        setContentView(root);

        photoButton.setOnClickListener(v -> pickPhoto());
        cameraButton.setOnClickListener(v -> takePhoto());
        documentButton.setOnClickListener(v -> pickDocument());
        voicePtt = new VoicePttCoordinator(runtime, () -> {
            if (!((Lfm3PocketApplication) getApplication()).hasMicrophonePermission()) {
                microphoneRequested = true;
                requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_AUDIO);
            }
        });
        recordButton.setOnClickListener(v -> {
            if (runtime.voice().isEnabled() && voicePtt.onClick(v)) return;
            if (runtime.voice().isEnabled()) return;
            toggleRecording();
        });
        recordButton.setOnTouchListener((v, event) -> runtime.voice().isEnabled() && voicePtt.onTouch(v, event));
        speakButton.setOnClickListener(v -> toggleSpokenReply());
        // Keep Settings open across interface-size recreate via EXTRA_OPEN_SETTINGS only.
        // Do not persist settings_open in savedInstanceState — InferenceSettingsE2eTest and
        // other recreate paths expect to return to chat after scenario.recreate().
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            setSettingsOpen(true);
        }
        applyDraft(getIntent());
    }

    private void setSettingsOpen(boolean open) {
        settingsOpen = open;
        if (!open && getIntent() != null) {
            // Interface-size recreate sets EXTRA_OPEN_SETTINGS; clear it when the user leaves
            // Settings so a later scenario.recreate() returns to chat.
            getIntent().removeExtra(EXTRA_OPEN_SETTINGS);
        }
        if (settingsHost != null) settingsHost.setVisibility(open ? View.VISIBLE : View.GONE);
        if (chatShellSection != null) chatShellSection.hideChatUiWhenSettingsOpen(open);
        if (open) syncInlineSettingsFields();
    }

    private void updateControls() {
        if (searchSettingsSection != null) searchSettingsSection.paintWebSearchControl(busy);
        if (chatShellSection != null) chatShellSection.paintModePill(searchEnabled);
        if (modelImportSection != null) modelImportSection.paintImportButtons(busy);
        photoButton.setEnabled(!busy);
        cameraButton.setEnabled(!busy);
        if (documentButton != null) documentButton.setEnabled(!busy);
        RecordingController.State capture = recordingController.state();
        boolean voiceMic = runtime.voice().isEnabled();
        boolean voiceListening = voiceMic && runtime.voice().isListening();
        recordButton.setText(capture == RecordingController.State.STOPPING ? getString(R.string.finishing)
                : voiceListening ? getString(R.string.voice_listening)
                : recordingActive() ? getString(R.string.stop_recording) : getString(R.string.voice_input));
        if (voiceMic) {
            String description = (voiceUiStatus != null && !voiceUiStatus.isEmpty())
                    ? voiceUiStatus
                    : (voiceListening ? getString(R.string.voice_listening)
                            : getString(R.string.voice_record_talkback));
            if (voiceListening && !description.toLowerCase(java.util.Locale.ROOT).contains("listen")) {
                description = getString(R.string.voice_listening);
            }
            recordButton.setContentDescription(description);
            if (!description.equals(recordButton.getTag(R.id.voice_record))) {
                recordButton.setTag(R.id.voice_record, description);
                if (voiceListening || (voiceUiStatus != null && !voiceUiStatus.isEmpty())) {
                    recordButton.announceForAccessibility(description);
                }
            }
        } else {
            recordButton.setContentDescription(null);
            recordButton.setTag(R.id.voice_record, null);
        }
        recordButton.setEnabled(capture != RecordingController.State.STOPPING && (!busy || recordingActive() || voiceMic));
        PlaybackController.State playback = playbackController.state();
        speakButton.setText(playback == PlaybackController.State.STOPPING
                ? getString(R.string.stopping_speech)
                : (playbackActive() || speaking) ? getString(R.string.stop_speech) : getString(R.string.speak_reply));
        speakButton.setEnabled((!busy || playbackActive() || speaking) && (playbackActive() || speaking
                || (modelHandle() != 0L && settingsLoaded && !lastAssistantReply.isBlank())));
        boolean canCompact = !busy && !speaking && !recordingActive() && !playbackActive()
                && importCancellation == null && modelHandle() != 0L && settingsLoaded;
        chatPanel.updateControls(!busy && modelHandle() != 0L && settingsLoaded,
                generating || speaking || recordingActive() || playbackActive() || transcribingVoiceInput || importCancellation != null,
                !busy || generating || transcribingVoiceInput,
                canCompact);

        if (modelImportSection != null) {
            String model = pocketPreferences().getString("model", "");
            String projector = pocketPreferences().getString("mmproj", "");
            String description = currentChatStatus().toString();
            modelImportSection.paintModelManager(
                    settingsLoaded ? inferenceSettings.summary() : "Loading inference settings...",
                    model.isEmpty() ? "" : pocketPreferences().getString("model_name", new File(model).getName()),
                    projector.isEmpty() ? "" : pocketPreferences().getString("projector_name", new File(projector).getName()),
                    busy, modelHandle() != 0,
                    description.startsWith("Model error") || description.startsWith("Import failed"));
        }
    }

    private void requestCompact() {
        if (busy || generating || speaking || recordingActive() || playbackActive() || modelHandle() == 0L || !settingsLoaded) {
            if (modelHandle() == 0L) setChatStatus("No model loaded.");
            return;
        }
        session = runtime.session();
        boolean started = runtime.requestCompact();
        if (!started && session != null) {
            // Session emits a specific status when it cannot compact yet.
            ChatSnapshot compactSnapshot = session.snapshot();
            setChatStatus(compactSnapshot == null ? currentChatStatus() : compactSnapshot.status);
        } else if (started) {
            busy = true;
            updateControls();
        }
    }

    private void reloadSelectedModel() {
        if (busy) return;
        String model = pocketPreferences().getString("model", "");
        if (!model.isEmpty()) loadSelectedModel(model, pocketPreferences().getString("mmproj", ""));
    }

    private void unloadSelectedModel() {
        if (busy || modelHandle() == 0) return;
        busy = true;
        setChatStatus("Unloading model...");
        updateControls();
        runtime.unload("user", () -> {
            if (destroyed) return;
            busy = false;
            setChatStatus("Model unloaded. Selected files remain available for reload.");
            updateControls();
        });
    }

    private EditText settingInput(LinearLayout form, String title, String hint, int value, int id,
                                  int step, int min, int max) {
        TextView name = label(title, 15, StillwaterUi.INK);
        name.setTypeface(StillwaterUi.sansMedium(this));
        name.setPadding(0, dp(10), 0, 0);
        StillwaterReadable.prepareWrappingLabel(name);
        form.addView(name);
        TextView hintView = label(hint, 12, StillwaterUi.MUTED);
        hintView.setTypeface(StillwaterUi.sans(this));
        StillwaterReadable.prepareWrappingLabel(hintView);
        form.addView(hintView);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = button("–");
        StillwaterUi.quiet(minus);
        minus.setTextColor(StillwaterUi.SOFT);
        StillwaterReadable.prepareWrappingControl(minus, dp(40));
        EditText field = new EditText(this);
        field.setId(id);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(Integer.toString(value));
        StillwaterReadable.setTextSizeSp(field, 18);
        field.setGravity(Gravity.CENTER);
        field.setBackgroundColor(Color.TRANSPARENT);
        field.setTypeface(StillwaterUi.sansMedium(this));
        StillwaterUi.styleInput(field);
        Button plus = button("+");
        StillwaterUi.quiet(plus);
        plus.setTextColor(StillwaterUi.SOFT);
        StillwaterReadable.prepareWrappingControl(plus, dp(40));
        minus.setOnClickListener(v -> nudge(field, -step, min, max));
        plus.setOnClickListener(v -> nudge(field, step, min, max));
        row.addView(minus, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(plus, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));
        form.addView(row);
        return field;
    }

    private void nudge(EditText field, int delta, int min, int max) {
        int current;
        try {
            current = Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException invalid) {
            current = min;
        }
        int next = Math.max(min, Math.min(max, current + delta));
        field.setText(Integer.toString(next));
    }

    private void syncInlineSettingsFields() {
        if (inferenceSettingsSection != null) inferenceSettingsSection.syncFields();
        if (searchSettingsSection != null) searchSettingsSection.syncFields();
        if (integrationsSettingsSection != null) integrationsSettingsSection.syncFields();
    }

    private void showInferenceSettings() {
        if (busy || !settingsLoaded) return;
        setSettingsOpen(true);
        syncInlineSettingsFields();
        if (inferenceSettingsSection != null) inferenceSettingsSection.focusContextField();
    }

    private void showSearchSettings() {
        setSettingsOpen(true);
        syncInlineSettingsFields();
        if (searchSettingsSection != null) searchSettingsSection.focusEndpointField();
    }

    private void pickPhoto() {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        startActivityForResult(pick, PICK_PHOTO);
    }

    private void takePhoto() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraRequested = true;
            requestPermissions(new String[] { Manifest.permission.CAMERA }, REQUEST_CAMERA);
            return;
        }
        launchCameraCapture();
    }

    private void launchCameraCapture() {
        startActivityForResult(new Intent(this, CameraCaptureActivity.class), TAKE_PHOTO);
    }


    private void pickDocument() {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "text/plain", "text/markdown", "text/csv", "text/html", "text/xml",
                "application/json", "application/xml", "application/pdf"
        });
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(pick, PICK_DOCUMENT);
    }

    private void importSelectedDocuments(Intent data) {
        if (data == null) return;
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        if (pendingDocuments.size() + uris.size() > DocumentAttachmentReader.MAX_FILES) {
            setChatStatus("You can attach up to " + DocumentAttachmentReader.MAX_FILES + " documents.");
            return;
        }
        busy = true;
        updateControls();
        setChatStatus(getString(R.string.importing_document));
        worker.execute(() -> {
            java.util.ArrayList<ChatSession.Attachment> imported = new java.util.ArrayList<>();
            String error = null;
            for (Uri uri : uris) {
                if (destroyed) return;
                File temp = null;
                try {
                    String displayName = displayNameForUri(uri);
                    String mime = getContentResolver().getType(uri);
                    long size = selectedUriSize(uri);
                    long maxBytes = DocumentAttachmentReader.isPdfMimeOrName(mime, displayName)
                            ? DocumentAttachmentReader.MAX_PDF_BYTES : DocumentAttachmentReader.MAX_TEXT_BYTES;
                    if (size >= 0 && size > maxBytes) {
                        error = DocumentAttachmentReader.isPdfMimeOrName(mime, displayName)
                                ? displayName + ": This PDF is too large to attach."
                                : displayName + ": This file is too large to attach.";
                        break;
                    }
                    temp = copyUriToCache(uri, "doc-" + System.currentTimeMillis() + "-" + imported.size() + ".tmp", maxBytes);
                    DocumentAttachmentReader.Result result = DocumentAttachmentReader.read(temp, displayName, mime);
                    if (!result.isOk()) {
                        error = result.displayName + ": " + result.error;
                        break;
                    }
                    imported.add(ChatSession.Attachment.document(result.displayName, result.text));
                } catch (Exception e) {
                    String name = displayNameForUri(uri);
                    String message = e.getMessage() == null ? "Could not read this file." : e.getMessage();
                    error = name + ": " + message;
                    break;
                } finally {
                    if (temp != null && temp.exists()) temp.delete();
                }
            }
            final String importError = error;
            final java.util.ArrayList<ChatSession.Attachment> ok = imported;
            runOnUiThread(() -> {
                if (destroyed) return;
                busy = false;
                if (importError != null) {
                    setChatStatus(importError);
                } else if (!ok.isEmpty()) {
                    pendingDocuments.addAll(ok);
                    setChatStatus(getString(R.string.documents_attached_status, pendingDocuments.size()));
                }
                syncPendingDocumentsUi();
                updateControls();
            });
        });
    }

    private boolean isPdfUri(Uri uri, String displayName) {
        String mime = getContentResolver().getType(uri);
        return DocumentAttachmentReader.isPdfMimeOrName(mime, displayName);
    }

    private long selectedUriSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.SIZE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column);
            }
        } catch (Exception ignored) {
        }
        return -1L;
    }

    private String displayNameForUri(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri == null ? null : uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "document" : last.trim();
    }

    private File copyUriToCache(Uri uri, String name, long maxBytes) throws IOException {
        File out = new File(getCacheDir(), name);
        try (InputStream input = getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(out)) {
            if (input == null) throw new IOException("Could not read the selected file.");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new IOException("This file is too large to attach.");
                }
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
            return out;
        } catch (IOException e) {
            out.delete();
            throw e;
        }
    }

    private void removePendingDocument(int index) {
        if (index < 0 || index >= pendingDocuments.size()) return;
        pendingDocuments.remove(index);
        syncPendingDocumentsUi();
        if (pendingDocuments.isEmpty()) {
            setChatStatus("Document removed.");
        } else {
            setChatStatus(getString(R.string.documents_attached_status, pendingDocuments.size()));
        }
        updateControls();
    }

    private void syncPendingDocumentsUi() {
        if (chatPanel == null) return;
        if (pendingDocuments.isEmpty()) {
            chatPanel.clearDocumentAttachments();
            return;
        }
        java.util.ArrayList<String> names = new java.util.ArrayList<>(pendingDocuments.size());
        for (ChatSession.Attachment doc : pendingDocuments) names.add(doc.displayName);
        chatPanel.setDocumentAttachments(names, this::removePendingDocument);
    }

    private void clearPendingDocuments() {
        pendingDocuments.clear();
        if (chatPanel != null) chatPanel.clearDocumentAttachments();
    }

    private void copySelectedPhoto(Uri uri) {
        busy = true;
        updateControls();
        setChatStatus(getString(R.string.importing_photo));
        ImageSettings prepareSettings = imageSettings;
        worker.execute(() -> {
            File photo = new File(getCacheDir(), "photo-" + System.currentTimeMillis() + ".img");
            File preparedCopy = null;
            try {
                Long reportedSize = selectedPhotoSize(uri);
                if (reportedSize != null && reportedSize > ImageAttachmentFiles.MAX_IMAGE_BYTES)
                    throw new IOException("Selected image exceeds the 32 MiB limit.");
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(photo)) {
                    if (input == null) throw new IOException("Could not read the selected photo");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    long totalBytes = 0;
                    while ((count = input.read(buffer)) != -1) {
                        totalBytes += count;
                        if (totalBytes > ImageAttachmentFiles.MAX_IMAGE_BYTES)
                            throw new IOException("Selected image exceeds the 32 MiB limit.");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                String validationError = ImageAttachmentFiles.validationError(photo);
                if (validationError != null) throw new IOException(validationError);
                runOnUiThread(() -> {
                    if (!destroyed) setChatStatus(getString(R.string.preparing_photo));
                });
                ImagePreprocessor.Result prepared = ImagePreprocessor.prepare(this, photo, prepareSettings);
                preparedCopy = prepared.file;
                if (prepared.prepared && !prepared.file.equals(photo)) photo.delete();
                setPendingMedia(prepared.file.getAbsolutePath(), "photo", prepared.caption);
            } catch (Exception e) {
                photo.delete();
                if (preparedCopy != null && !preparedCopy.equals(photo)) preparedCopy.delete();
                runOnUiThread(() -> {
                    busy = false;
                    setChatStatus("Photo import failed: " + e.getMessage());
                    updateControls();
                });
            }
        });
    }

    private void prepareCapturedPhoto(String path) {
        busy = true;
        updateControls();
        setChatStatus(getString(R.string.preparing_photo));
        ImageSettings prepareSettings = imageSettings;
        worker.execute(() -> {
            File photo = new File(path);
            File preparedCopy = null;
            try {
                String validationError = ImageAttachmentFiles.validationError(photo);
                if (validationError != null) throw new IOException(validationError);
                ImagePreprocessor.Result prepared = ImagePreprocessor.prepare(this, photo, prepareSettings);
                preparedCopy = prepared.file;
                if (prepared.prepared && !prepared.file.equals(photo)) photo.delete();
                setPendingMedia(prepared.file.getAbsolutePath(), "photo", prepared.caption);
            } catch (Exception e) {
                if (preparedCopy != null && !preparedCopy.equals(photo)) preparedCopy.delete();
                HistoryMediaCleanup.deleteAttachment(this, path);
                runOnUiThread(() -> {
                    busy = false;
                    setChatStatus("Photo import failed: " + e.getMessage());
                    updateControls();
                });
            }
        });
    }

    private Long selectedPhotoSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.SIZE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column);
            }
        } catch (Exception ignored) {
            // Some document providers omit size metadata. The bounded stream copy remains authoritative.
        }
        return null;
    }

    private void setPendingMedia(String path, String kind) {
        setPendingMedia(path, kind, "");
    }

    private void setPendingMedia(String path, String kind, String caption) {
        runOnUiThread(() -> {
            if ("photo".equals(kind)) {
                if (!pendingImagePath.isEmpty() && !pendingImagePath.equals(path))
                    HistoryMediaCleanup.deleteAttachment(this,pendingImagePath);
                pendingImagePath=path;
                chatPanel.showImageAttachment(path, caption, () -> removePendingImage(path));
            } else {
                if (!pendingAudioPath.isEmpty() && !pendingAudioPath.equals(path))
                    HistoryMediaCleanup.deleteAttachment(this,pendingAudioPath);
                pendingAudioPath=path;
            }
            busy = false;
            setChatStatus("photo".equals(kind)
                    ? getString(R.string.photo_attached_status)
                    : (kind + " attached. Add a question and press Send."));
            updateControls();
        });
    }

    private void removePendingImage(String expectedPath) {
        if (!expectedPath.equals(pendingImagePath)) return;
        HistoryMediaCleanup.deleteAttachment(this, pendingImagePath);
        pendingImagePath = "";
        chatPanel.clearImageAttachment();
        setChatStatus("Photo removed. Add a question and press Send.");
        updateControls();
    }

    String pendingMediaPathForTest() { return !pendingAudioPath.isEmpty()?pendingAudioPath:pendingImagePath; }
    String[] pendingMediaPathsForTest() {
        if(pendingImagePath.isEmpty())return pendingAudioPath.isEmpty()?new String[0]:new String[]{pendingAudioPath};
        if(pendingAudioPath.isEmpty())return new String[]{pendingImagePath};
        return new String[]{pendingImagePath,pendingAudioPath};
    }
    /** Instrumentation seam: submit through the same path as the Send button without IME/focus races. */
    void sendForTest(String text) { send(text == null ? "" : text); }

    private boolean recordingActive() {
        RecordingController.State state = recordingController.state();
        return state == RecordingController.State.STARTING || state == RecordingController.State.RECORDING
                || state == RecordingController.State.STOPPING;
    }

    private boolean playbackActive() {
        PlaybackController.State state = playbackController.state();
        return state == PlaybackController.State.STARTING || state == PlaybackController.State.PLAYING
                || state == PlaybackController.State.STOPPING;
    }

    private void toggleRecording() {
        if (recordingActive()) { recordingController.stop(); return; }
        if (busy || !foreground) return;
        if (!((Lfm3PocketApplication)getApplication()).hasMicrophonePermission()) {
            microphoneRequested = true;
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_AUDIO);
            return;
        }
        voiceSttCancelled.set(false);
        recordingController.start();
    }

    private void handleCompletedRecordingAsStt(File wav) {
        if (wav == null || !wav.isFile()) return;
        final File recording = wav;
        voiceSttCancelled.set(false);
        transcribingVoiceInput = true;
        busy = true;
        setChatStatus(getString(R.string.speech_input_transcribing));
        updateControls();
        audioWorker.execute(() -> {
            SttResult result;
            try {
                result = SpeechInputProviders.current(this).transcribe(this, recording, voiceSttCancelled);
            } catch (Throwable t) {
                result = SttResult.error(t.getMessage() == null ? t.toString() : t.getMessage());
            } finally {
                recording.delete();
            }
            final SttResult stt = result;
            mainHandler.post(() -> {
                transcribingVoiceInput = false;
                if (destroyed) return;
                busy = recordingActive() || playbackActive() || speaking || generating;
                if (stt == null || stt.status == SttResult.Status.CANCELLED || voiceSttCancelled.get()) {
                    setChatStatus("Speech transcription cancelled.");
                    updateControls();
                    return;
                }
                if (!stt.isOk()) {
                    if (stt.status == SttResult.Status.EMPTY) {
                        String msg = stt.message == null || stt.message.isEmpty()
                                ? getString(R.string.speech_input_transcript_empty)
                                : stt.message;
                        setChatStatus(msg);
                    } else {
                        String detail = stt.message == null || stt.message.isEmpty() ? "unknown error" : stt.message;
                        setChatStatus(getString(R.string.speech_input_transcript_failed, detail));
                    }
                    updateControls();
                    return;
                }
                updateControls();
                // Route recognized text into the existing text/inference pipeline (same as typed text).
                send(stt.text);
            });
        });
    }

    private void toggleSpokenReply() {
        if (playbackActive() || speaking) {
            runtime.speak(lastAssistantReply);
            return;
        }
        if (busy || !foreground || modelHandle() == 0L || lastAssistantReply.isBlank()) return;
        busy = true;
        speaking = true;
        updateControls();
        runtime.speak(lastAssistantReply);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && cameraRequested) {
            cameraRequested = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && !destroyed && !busy) {
                launchCameraCapture();
            } else {
                setChatStatus("Camera access is needed to take a photo.");
            }
            updateControls();
            return;
        }
        if (requestCode == REQUEST_AUDIO && microphoneRequested) {
            microphoneRequested = false;
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingAssistantVoiceSession) {
                maybeStartPendingAssistantVoiceSession();
                return;
            }
            if (granted
                    && foreground && !destroyed && !busy) recordingController.start();
            else setChatStatus("Microphone access is needed for voice input.");
            if (!granted) pendingAssistantVoiceSession = false;
            return;
        }
        if (requestCode == REQUEST_READ_CALENDAR) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showCalendarPickerDialog();
            } else {
                setChatStatus(getString(R.string.calendar_permission_needed));
            }
            if (integrationsSettingsSection != null) integrationsSettingsSection.refreshCalendarPickerStatus();
        }
    }

    @Override protected void onStart() { super.onStart(); foreground = true; }

    @Override protected void onStop() {
        foreground = false;
        voiceSttCancelled.set(true);
        if (recordingController != null && recordingActive()) recordingController.cancel();
        super.onStop();
    }

    private void pickModel() {
        pickModel(PICK_MODEL);
    }

    private void pickMmproj() {
        pickModel(PICK_MMPROJ);
    }

    private void pickModel(int requestCode) {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, requestCode);
    }

    private void pickExternalModel(int requestCode) {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick, requestCode);
    }

    private void pickWhistleModel() {
        if (busy || CactusWhistleModelDownloader.isDownloading()) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, PICK_WHISTLE_MODEL);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_PHOTO && result == RESULT_OK && data != null && data.getData() != null) {
            copySelectedPhoto(data.getData());
            return;
        }
        if (code == TAKE_PHOTO && result == RESULT_OK && data != null) {
            String path = data.getStringExtra(CameraCaptureActivity.EXTRA_PHOTO_PATH);
            if (path != null && new File(path).isFile()) prepareCapturedPhoto(path);
            else setChatStatus("The camera did not return a photo.");
            return;
        }
        if (code == TAKE_PHOTO && result != RESULT_OK) {
            setChatStatus("Camera closed without taking a photo.");
            return;
        }
        if ((code == OPEN_MODEL_URI || code == OPEN_MMPROJ_URI)
                && result == RESULT_OK && data != null && data.getData() != null) {
            openExternalDocument(data.getData(), code == OPEN_MMPROJ_URI);
            return;
        }
        if (code == PICK_WHISTLE_MODEL && result == RESULT_OK && data != null && data.getData() != null) {
            importWhistleModel(data.getData());
            return;
        }
        if ((code != PICK_MODEL && code != PICK_MMPROJ) || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        busy = true;
        updateControls();
        setChatStatus(code == PICK_MODEL ? getString(R.string.importing_model) : "Importing projector into private storage...");
        AtomicBoolean cancellation = new AtomicBoolean();
        importCancellation = cancellation;
        updateControls();
        worker.execute(() -> {
            File imported = null;
            boolean isProjector = code == PICK_MMPROJ;
            try {
                String previousModel = pocketPreferences().getString("model", "");
                String previousProjector = pocketPreferences().getString("mmproj", "");
                if (isProjector && previousModel.isEmpty()) throw new IOException("Import a model before its multimodal projector");
                long expected = -1;
                String importedName = "Imported GGUF";
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[] { OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        if (!cursor.isNull(0)) expected = cursor.getLong(0);
                        if (!cursor.isNull(1)) importedName = cursor.getString(1);
                    }
                }
                imported = new ModelImporter(new File(getFilesDir(), "models")).importFile(
                        () -> getContentResolver().openInputStream(uri), expected, cancellation,
                        bytes -> runOnUiThread(() -> {
                            if (!destroyed && !cancellation.get()) setChatStatus("Importing GGUF: " + (bytes / 1024 / 1024) + " MiB. Stop to cancel.");
                        }));
                if (cancellation.get()) throw new IOException("Import cancelled");
                String model = isProjector ? previousModel : imported.getAbsolutePath();
                String projector = isProjector ? imported.getAbsolutePath() : "";
                if (runtime.loadBlocking(model, projector, statusText -> runOnUiThread(() -> {
                    if (!destroyed) setChatStatus(statusText);
                }))) {
                    pocketPreferences().edit().putString("model", model)
                            .putString("mmproj", projector)
                            .putString(isProjector ? "projector_name" : "model_name", importedName).apply();
                    if (!isProjector) pocketPreferences().edit().remove("projector_name").apply();
                    // Only delete owned obsolete imports after the new selection loads.
                    deleteImportedModel(isProjector ? previousProjector : previousModel);
                    if (!isProjector) deleteImportedModel(previousProjector);
                    imported = null;
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!destroyed) setChatStatus("Import failed: " + e.getMessage());
                });
            } finally {
                if (imported != null) imported.delete();
                importCancellation = null;
                runOnUiThread(() -> {
                    if (!destroyed) {
                        busy = false;
                        updateControls();
                        maybeStartPendingAssistantVoiceSession();
                    }
                });
            }
        });
    }

    private void openExternalDocument(Uri uri, boolean projector) {
        busy = true;
        updateControls();
        setChatStatus(getString(R.string.opening_external_model));
        worker.execute(() -> {
            ExternalModelStore store = ((Lfm3PocketApplication) getApplication()).externalModels();
            try {
                String previousModel = pocketPreferences().getString("model", "");
                String previousProjector = pocketPreferences().getString("mmproj", "");
                if (projector && previousModel.isEmpty()) {
                    throw new IOException("Open or import a model before its multimodal projector");
                }
                String displayName = "GGUF";
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                        displayName = cursor.getString(0);
                    }
                }
                java.util.HashSet<String> protectedUris = new java.util.HashSet<>();
                if (isContentUri(previousModel)) protectedUris.add(previousModel);
                if (isContentUri(previousProjector)) protectedUris.add(previousProjector);
                store.remember(uri, displayName, projector, protectedUris);
                ExternalModelStore.Opened opened = store.open(uri);
                String modelPath;
                String projectorPath;
                String nextModelUri = projector ? previousModel : uri.toString();
                String nextProjectorUri = projector ? uri.toString() : "";
                if (projector) {
                    modelPath = resolveLoadPath(previousModel, store);
                    projectorPath = opened.path;
                } else {
                    modelPath = opened.path;
                    projectorPath = "";
                }
                GgufSelection.validate(new File(modelPath),
                        projectorPath.isEmpty() ? null : new File(projectorPath));
                boolean loaded = runtime.loadBlocking(modelPath, projectorPath, statusText -> runOnUiThread(() -> {
                    if (!destroyed) setChatStatus(statusText);
                }));
                if (loaded) {
                    pocketPreferences().edit()
                            .putString("model", nextModelUri)
                            .putString("mmproj", nextProjectorUri)
                            .putString(projector ? "projector_name" : "model_name", displayName)
                            .apply();
                    if (!projector) pocketPreferences().edit().remove("projector_name").apply();
                    java.util.HashSet<String> active = new java.util.HashSet<>();
                    if (isContentUri(nextModelUri)) active.add(nextModelUri);
                    if (isContentUri(nextProjectorUri)) active.add(nextProjectorUri);
                    store.releaseExcept(active);
                } else {
                    java.util.HashSet<String> active = new java.util.HashSet<>();
                    if (isContentUri(previousModel)) active.add(previousModel);
                    if (isContentUri(previousProjector)) active.add(previousProjector);
                    store.releaseExcept(active);
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!destroyed) setChatStatus("Could not open storage model: " + e.getMessage());
                });
            } finally {
                runOnUiThread(() -> {
                    if (!destroyed) {
                        busy = false;
                        updateControls();
                        maybeStartPendingAssistantVoiceSession();
                    }
                });
            }
        });
    }

    private String resolveLoadPath(String stored, ExternalModelStore store) throws IOException {
        if (isContentUri(stored)) {
            return store.open(Uri.parse(stored)).path;
        }
        if (stored == null || stored.isEmpty() || !new File(stored).isFile()) {
            throw new IOException("Selected model is missing");
        }
        return stored;
    }

    private void deleteImportedModel(String path) {
        if (path == null || isContentUri(path)) return;
        File file = new File(path);
        if (new File(getFilesDir(), "models").equals(file.getParentFile())) file.delete();
    }

    private void importWhistleModel(Uri uri) {
        busy = true;
        updateControls();
        setChatStatus(getString(R.string.opening_external_model));
        worker.execute(() -> {
            File dest = null;
            try {
                String name = "whistle.cact";
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                        String display = cursor.getString(0);
                        if (display != null && !display.trim().isEmpty()) name = new File(display.trim()).getName();
                    }
                } catch (Exception ignored) { }
                if (!name.toLowerCase().endsWith(".cact")) {
                    // Keep original name but accept; provider will validate availability/load.
                }
                File dir = new File(getFilesDir(), "cactus");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create Cactus model directory");
                dest = new File(dir, name);
                File temp = new File(dir, name + ".partial");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new IOException("Cannot read the selected document");
                    byte[] buffer = new byte[1024 * 64];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    out.getFD().sync();
                }
                java.nio.file.Files.move(temp.toPath(), dest.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                File saved = dest;
                runOnUiThread(() -> {
                    if (destroyed) return;
                    new RuntimePreferences(this).setCactusWhistleModelPath(saved.getAbsolutePath());
                    if (integrationsSettingsSection != null) integrationsSettingsSection.paintSpeechInputControls();
                    busy = false;
                    setChatStatus(saved.getName() + " selected for Cactus Whistle STT.");
                    updateControls();
                });
                return;
            } catch (Exception e) {
                if (dest != null) dest.delete();
                final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> {
                    if (destroyed) return;
                    busy = false;
                    setChatStatus("Could not import Whistle model: " + message);
                    updateControls();
                });
            }
        });
    }


    private void loadSelectedModel(String model, String projector) {
        if (isContentUri(model) || isContentUri(projector)) {
            final String targetModel = model;
            final String targetProjector = projector == null ? "" : projector;
            busy = true;
            updateControls();
            setChatStatus(getString(R.string.opening_external_model));
            worker.execute(() -> {
                try {
                    ExternalModelStore store = ((Lfm3PocketApplication) getApplication()).externalModels();
                    String modelPath = resolveLoadPath(targetModel, store);
                    String projectorPath = targetProjector.isEmpty()
                            ? "" : resolveLoadPath(targetProjector, store);
                    boolean ok = runtime.loadBlocking(modelPath, projectorPath, statusText -> runOnUiThread(() -> {
                        if (!destroyed) setChatStatus(statusText);
                    }));
                    if (ok) {
                        java.util.HashSet<String> active = new java.util.HashSet<>();
                        if (isContentUri(targetModel)) active.add(targetModel);
                        if (isContentUri(targetProjector)) active.add(targetProjector);
                        store.releaseExcept(active);
                    } else {
                        String currentModel = pocketPreferences().getString("model", "");
                        String currentProjector = pocketPreferences().getString("mmproj", "");
                        java.util.HashSet<String> active = new java.util.HashSet<>();
                        if (isContentUri(currentModel)) active.add(currentModel);
                        if (isContentUri(currentProjector)) active.add(currentProjector);
                        // Keep current model usable; release only candidate descriptors not needed by it.
                        // If the failed target differs from current, drop its descriptors.
                        if (isContentUri(targetModel) && !active.contains(targetModel)) store.release(targetModel);
                        if (isContentUri(targetProjector) && !active.contains(targetProjector)) store.release(targetProjector);
                        // Defensive: do not retain unrelated stale leases beyond current selection.
                        store.releaseExcept(active);
                        if (!destroyed) {
                            runOnUiThread(() -> setChatStatus("Could not load the selected storage model."));
                        }
                    }
                } catch (Exception e) {
                    ExternalModelStore store = ((Lfm3PocketApplication) getApplication()).externalModels();
                    String currentModel = pocketPreferences().getString("model", "");
                    String currentProjector = pocketPreferences().getString("mmproj", "");
                    java.util.HashSet<String> active = new java.util.HashSet<>();
                    if (isContentUri(currentModel)) active.add(currentModel);
                    if (isContentUri(currentProjector)) active.add(currentProjector);
                    if (isContentUri(targetModel) && !active.contains(targetModel)) store.release(targetModel);
                    if (isContentUri(targetProjector) && !active.contains(targetProjector)) store.release(targetProjector);
                    store.releaseExcept(active);
                    runOnUiThread(() -> {
                        if (!destroyed) setChatStatus("Could not open storage model: " + e.getMessage());
                    });
                } finally {
                    runOnUiThread(() -> {
                        if (!destroyed) {
                            busy = false;
                            updateControls();
                            maybeStartPendingAssistantVoiceSession();
                        }
                    });
                }
            });
            return;
        }
        loadModelInBackground(model, projector);
    }

    private void loadModelInBackground(String path, String projector) {
        busy = true;
        updateControls();
        setChatStatus(getString(R.string.loading_model));
        runtime.load(path, projector, statusText -> {
            if (destroyed) return;
            busy = false;
            setChatStatus(statusText);
            updateControls();
            maybeStartPendingAssistantVoiceSession();
        });
    }

    private static boolean isContentUri(String value) {
        return value != null && value.startsWith("content:");
    }

    private static boolean shouldDeferNewChat(Intent intent) {
        return intent != null && (intent.getBooleanExtra(EXTRA_REQUEST_NEW_CHAT, false)
                || intent.getBooleanExtra(EXTRA_FRESH_CHAT, false));
    }

    private void handlePendingNewChatRequests(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_REQUEST_NEW_CHAT, false)) {
            requestNewChat(false);
            intent.removeExtra(EXTRA_REQUEST_NEW_CHAT);
            return;
        }
        if (intent.getBooleanExtra(EXTRA_FRESH_CHAT, false)) {
            freshChatRequestedFromAssist = true;
            requestNewChat(true);
            intent.removeExtra(EXTRA_FRESH_CHAT);
        }
    }

    private void consumeAssistantVoiceSessionIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_ASSISTANT_VOICE_SESSION, false)) {
            assistantVoiceSessionLaunchRequested = true;
            pendingAssistantVoiceSession = true;
            intent.removeExtra(EXTRA_ASSISTANT_VOICE_SESSION);
        }
    }

    private void maybeStartPendingAssistantVoiceSession() {
        if (!pendingAssistantVoiceSession || destroyed) return;
        if (runtime == null || runtime.isGenerating()) return;
        if (runtime.handle() == 0L) {
            String model = pocketPreferences().getString("model", "");
            if (!busy && (model == null || model.isEmpty())) {
                pendingAssistantVoiceSession = false;
                setChatStatus(getString(R.string.assistant_voice_needs_model));
                updateControls();
            }
            return;
        }
        if (!runtime.voice().isEnabled()) {
            pendingAssistantVoiceSession = false;
            setChatStatus(runtime.voice().disabledReason());
            updateControls();
            return;
        }
        if (!((Lfm3PocketApplication) getApplication()).hasMicrophonePermission()) {
            microphoneRequested = true;
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_AUDIO);
            return;
        }
        pendingAssistantVoiceSession = false;
        runtime.voice().beginShadeTalk();
        updateControls();
    }

    private void requestNewChat(boolean fromSystemAssist) {
        if (runtime.isGenerating()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.reply_still_running_title)
                    .setMessage(R.string.reply_still_running_message)
                    .setPositiveButton(R.string.stop_and_start_new, (dialog, which) -> {
                        runtime.stopTurn();
                        startFreshChat(fromSystemAssist);
                    })
                    .setNegativeButton(R.string.keep_this_reply, null)
                    .show();
            return;
        }
        startFreshChat(fromSystemAssist);
    }

    private void startFreshChat(boolean fromSystemAssist) {
        HistoryMediaCleanup.deleteAttachment(this, pendingImagePath);
        HistoryMediaCleanup.deleteAttachment(this, pendingAudioPath);
        pendingImagePath = "";
        pendingAudioPath = "";
        clearPendingDocuments();
        lastAssistantReply = "";
        if (playbackActive()) playbackController.stop();
        if (chatPanel != null) chatPanel.clearImageAttachment();
        String id = java.util.UUID.randomUUID().toString();
        historyConversationId = id;
        historyTitle = getString(R.string.new_conversation_title);
        if (chatShellSection != null) chatShellSection.setBrandTitle(historyTitle);
        runtime.setConversation(id, historyTitle);
        runtime.clearConversation();
        setSettingsOpen(false);
        String freshStatus = fromSystemAssist
                ? getString(R.string.fresh_chat_from_assist)
                : getString(R.string.new_chat_ready);
        setChatStatus(freshStatus);
        if (chatPanel != null) {
            chatPanel.render(FlatTranscriptFormatter.EMPTY_TRANSCRIPT, freshStatus);
        }
        updateControls();
    }

    private void openDefaultAssistantPicker() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.app.role.RoleManager roles = getSystemService(android.app.role.RoleManager.class);
                if (roles != null && roles.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                    startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT));
                    return;
                }
            }
            startActivity(new Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
            } catch (Exception nested) {
                setChatStatus("Could not open the default-assistant settings.");
            }
        }
    }

    private void stopActiveWork() {
        if (transcribingVoiceInput) {
            voiceSttCancelled.set(true);
            transcribingVoiceInput = false;
            busy = false;
            setChatStatus("Speech transcription cancelled.");
            updateControls();
        } else if (recordingActive()) {
            recordingController.stop();
        } else if (playbackActive()) {
            playbackController.stop();
        } else if (speaking) {
            runtime.speak(lastAssistantReply);
        } else if (importCancellation != null) {
            importCancellation.set(true);
            setChatStatus("Cancelling import...");
        } else {
            runtime.stopTurn();
        }
    }

    /** Presents a validated coordinator result in the conversation and keeps authorization in the coordinator. */
    public void presentToolOutcome(ToolCoordinator.Outcome outcome) {
        if (outcome == null) return;
        ToolRegistry.Request request = outcome.proposal == null ? null : outcome.proposal.request;
        presentToolOutcome(request, outcome);
    }

    /** Includes the request because immediate safe actions have no confirmation proposal to carry it. */
    public void presentToolOutcome(ToolRegistry.Request request, ToolCoordinator.Outcome outcome) {
        if (outcome == null || chatPanel == null) return;
        toolCoordinator = ((Lfm3PocketApplication)getApplication()).toolCoordinator();
        renderToolOutcome(outcome.proposal, request, outcome);
    }

    private void renderToolOutcome(ToolCoordinator.Proposal proposal, ToolRegistry.Request request,
                                  ToolCoordinator.Outcome outcome) {
        boolean pending = proposal != null && "PENDING_CONFIRMATION".equals(outcome.status);
        if (proposal != null) request = proposal.request;
        String details = request == null ? "Android action" : ToolCoordinator.describe(request);
        ToolCoordinator.Risk actionRisk = proposal == null ? ToolCoordinator.risk(request) : proposal.risk;
        String risk = request == null ? "Risk unavailable"
                : actionRisk == ToolCoordinator.Risk.CONFIRM ? "Risk: confirmation required" : "Risk: safe action";
        String result = outcome.result == null ? "" : outcome.result.message;
        ToolRegistry.Request actionRequest = request;
        Runnable approve = pending ? () -> resolveToolOutcome(proposal, actionRequest, true) : null;
        Runnable reject = pending ? () -> resolveToolOutcome(proposal, actionRequest, false) : null;
        chatPanel.renderToolCard(details, risk, toolOutcomeLabel(outcome), result, pending, approve, reject);
        if (pending && proposal != null) {
            runtime.showConsent(proposal.id, ToolCoordinator.describe(proposal.request),
                    proposal.risk == ToolCoordinator.Risk.SAFE);
        } else {
            runtime.clearConsent();
        }
        if(!pending && request!=null && outcome.result!=null) persistToolOutcome(proposal,request,outcome);
    }

    private void syncBackgroundControls() {
        if (backgroundSettingsSection != null) backgroundSettingsSection.syncControls();
        if (integrationsSettingsSection != null) integrationsSettingsSection.syncControls();
    }

    private void downloadTinyVocaPack() {
        if (integrationsSettingsSection == null) return;
        integrationsSettingsSection.beginTinyVocaDownload();
        new Thread(() -> new TinyVocaPackDownloader(new TinyVocaPackStore(this)).download(
                new TinyVocaPackDownloader.Progress() {
                    @Override public void onFile(String path, long written, long total) {
                        runOnUiThread(() -> {
                            if (integrationsSettingsSection != null) {
                                integrationsSettingsSection.onTinyVocaDownloadProgress(path);
                            }
                        });
                    }
                    @Override public void onDone() {
                        TinyVocaPackStore pack = new TinyVocaPackStore(MainActivity.this);
                        new Thread(() -> TinyVocaPhonemizerBridge.ensureLoaded(pack.phonemizerDir()),
                                "tiny-voca-phonemizer-warmup").start();
                        runOnUiThread(() -> {
                            if (integrationsSettingsSection != null) {
                                integrationsSettingsSection.paintTinyVocaPackStatus();
                            }
                            setChatStatus(getString(R.string.tiny_voca_status_ready));
                        });
                    }
                    @Override public void onError(String message) {
                        runOnUiThread(() -> {
                            if (integrationsSettingsSection != null) {
                                integrationsSettingsSection.paintTinyVocaPackStatus();
                            }
                            setChatStatus(message);
                        });
                    }
                }), "tiny-voca-download").start();
    }

    private void requestNotificationPermissionForModelDownloadIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS },
                REQUEST_POST_NOTIFICATIONS_WHISTLE);
    }

    private void downloadCactusWhistleModel() {
        requestNotificationPermissionForModelDownloadIfNeeded();
        ModelDownloadManager manager = ModelDownloadManager.getInstance(this);
        ModelDownloadManager.StartResult outcome = manager.startWhistle(false);
        if (outcome == ModelDownloadManager.StartResult.METERED_NEEDS_CONFIRMATION) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.model_download_metered_title)
                    .setMessage(R.string.model_download_metered_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        ModelDownloadManager.StartResult retry = manager.startWhistle(true);
                        handleWhistleDownloadStart(retry, manager);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        handleWhistleDownloadStart(outcome, manager);
    }

    private void handleWhistleDownloadStart(ModelDownloadManager.StartResult outcome, ModelDownloadManager manager) {
        if (chatShellSection == null) return;
        if (outcome == ModelDownloadManager.StartResult.INSUFFICIENT_SPACE) {
            setChatStatus(R.string.model_download_insufficient_space,
                    formatMb(manager.insufficientSpaceRequiredBytes(ModelDownloadKind.CACTUS_WHISTLE)),
                    formatMb(manager.insufficientSpaceUsableBytes()));
        } else if (outcome == ModelDownloadManager.StartResult.ALREADY_RUNNING) {
            setChatStatus(R.string.model_download_already_running);
        } else if (outcome == ModelDownloadManager.StartResult.SCHEDULE_FAILED) {
            setChatStatus(R.string.model_download_schedule_failed);
        }
        if (outcome == ModelDownloadManager.StartResult.STARTED
                || outcome == ModelDownloadManager.StartResult.ALREADY_RUNNING) {
            if (integrationsSettingsSection != null) integrationsSettingsSection.paintSpeechInputControls();
        }
    }

    private static String formatMb(long bytes) {
        return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void applyDraft(Intent intent) {
        if (intent == null || chatPanel == null) return;
        String draft = intent.getStringExtra(EXTRA_DRAFT_TEXT);
        if (draft != null && !draft.isBlank()) {
            chatPanel.setDraft(draft);
            intent.removeExtra(EXTRA_DRAFT_TEXT);
        }
        String shared = intent.getStringExtra(EXTRA_SHARED_IMAGE);
        if (shared != null && !shared.isBlank()) {
            intent.removeExtra(EXTRA_SHARED_IMAGE);
            try {
                copySelectedPhoto(Uri.parse(shared));
            } catch (Exception failed) {
                setChatStatus("Photo import failed: " + failed.getMessage());
            }
        }
    }

    private void persistToolOutcome(ToolCoordinator.Proposal proposal,ToolRegistry.Request request,
                                   ToolCoordinator.Outcome outcome) {
        String requestId=proposal==null?java.util.UUID.randomUUID().toString():proposal.id;
        String arguments=new org.json.JSONObject(request.arguments).toString();
        HistoryRepository.ToolExecution execution=new HistoryRepository.ToolExecution(requestId,
                request.tool.wireName,arguments,outcome.result.succeeded,outcome.result.status,
                outcome.result.message,System.currentTimeMillis());
        try {
            worker.execute(() -> historyRepository.appendToolOutcome(historyConversationId,historyTitle,execution));
        } catch(java.util.concurrent.RejectedExecutionException ignored) {
            // Activity teardown owns no UI work; the in-flight generation path remains unaffected.
        }
    }

    private static String toolOutcomeLabel(ToolCoordinator.Outcome outcome) {
        switch (outcome.status) {
            case "PENDING_CONFIRMATION": return "Needs your approval";
            case "DECLINED": return "Declined";
            case "EXPIRED": return "Approval expired";
            case "PERMISSION_DENIED": return "Permission denied";
            case "UNTRUSTED_SOURCE": return "Request blocked";
            case "ALREADY_RESOLVED": return "Action already resolved";
            case "UNKNOWN_PROPOSAL": return "Action no longer available";
            default:
                if (outcome.result == null) return "Action unavailable";
                return outcome.result.succeeded ? "Action complete" : "Action failed";
        }
    }

    private void resolveToolOutcome(ToolCoordinator.Proposal proposal, ToolRegistry.Request request, boolean approve) {
        if (destroyed) return;
        chatPanel.renderToolCard(ToolCoordinator.describe(request),
                proposal.risk == ToolCoordinator.Risk.CONFIRM ? "Risk: confirmation required" : "Risk: safe action",
                approve ? "Executing Android action…" : "Declining Android action…", "", false, null, null);
        worker.execute(() -> {
            ToolCoordinator.Outcome outcome = approve
                    ? toolCoordinator.approve(proposal.id) : toolCoordinator.reject(proposal.id);
            mainHandler.post(() -> { if (!destroyed) renderToolOutcome(proposal, request, outcome); });
        });
    }

    private void send(String text) {
        if (busy || modelHandle() == 0L || !settingsLoaded) return;
        if (!runtime.acceptsTurn()) {
            setChatStatus("Phone is too warm for a new reply.");
            return;
        }
        runtime.noteSubmission(text, inferenceSettings.maxTokens);
        session = runtime.session();
        List<ChatSession.Attachment> attachments=new ArrayList<>();
        if(!pendingImagePath.isEmpty())attachments.add(new ChatSession.Attachment(
                pendingImagePath,ChatSession.AttachmentKind.PHOTO));
        if(!pendingAudioPath.isEmpty())attachments.add(new ChatSession.Attachment(
                pendingAudioPath,ChatSession.AttachmentKind.VOICE_RECORDING));
        if(!pendingDocuments.isEmpty()) attachments.addAll(pendingDocuments);
        if (session.submit(text, attachments, searchEnabled, searchEndpoint, inferenceSettings.maxTokens)) {
            pendingImagePath = "";
            pendingAudioPath = "";
            pendingDocuments.clear();
            chatPanel.clearImageAttachment();
            chatPanel.clearDocumentAttachments();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        adoptConversation(intent);
        if (pendingSwitchId != null) confirmSwitchConversation();
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) setSettingsOpen(true);
        consumeAssistantVoiceSessionIntent(intent);
        handlePendingNewChatRequests(intent);
        applyDraft(intent);
        maybeStartPendingAssistantVoiceSession();
    }

    private void pickCalendarTarget() {
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.READ_CALENDAR }, REQUEST_READ_CALENDAR);
            return;
        }
        showCalendarPickerDialog();
    }

    private void showCalendarPickerDialog() {
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            setChatStatus(getString(R.string.calendar_permission_needed));
            return;
        }
        final java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        final java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        String[] projection = {
                android.provider.CalendarContract.Calendars._ID,
                android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                android.provider.CalendarContract.Calendars.ACCOUNT_NAME
        };
        try (android.database.Cursor cursor = getContentResolver().query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                projection, null, null,
                android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC")) {
            if (cursor != null) {
                int idIdx = cursor.getColumnIndex(android.provider.CalendarContract.Calendars._ID);
                int nameIdx = cursor.getColumnIndex(android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME);
                int accountIdx = cursor.getColumnIndex(android.provider.CalendarContract.Calendars.ACCOUNT_NAME);
                while (cursor.moveToNext()) {
                    String id = idIdx >= 0 ? cursor.getString(idIdx) : null;
                    if (id == null || id.trim().isEmpty()) continue;
                    String name = nameIdx >= 0 ? cursor.getString(nameIdx) : null;
                    String account = accountIdx >= 0 ? cursor.getString(accountIdx) : null;
                    String label = (name == null || name.trim().isEmpty()) ? id : name.trim();
                    if (account != null && !account.trim().isEmpty() && !account.equals(name)) {
                        label = label + " (" + account.trim() + ")";
                    }
                    ids.add(id.trim());
                    labels.add(label);
                }
            }
        } catch (SecurityException e) {
            setChatStatus(getString(R.string.calendar_permission_needed));
            if (integrationsSettingsSection != null) integrationsSettingsSection.refreshCalendarPickerStatus();
            return;
        } catch (Exception ignored) {
            // Fall through to empty-state dialog.
        }
        if (ids.isEmpty()) {
            setChatStatus(getString(R.string.calendar_no_calendars));
            return;
        }
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.calendar_pick_title)
                .setItems(items, (dialog, which) -> {
                    if (which < 0 || which >= ids.size()) return;
                    new RuntimePreferences(this).setCalendarWriteTarget(ids.get(which), labels.get(which));
                    if (integrationsSettingsSection != null) {
                        integrationsSettingsSection.refreshCalendarPickerStatus();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (runtime != null) runtime.detach(viewer);
        HistoryMediaCleanup.deleteAttachment(this,pendingImagePath);
        HistoryMediaCleanup.deleteAttachment(this,pendingAudioPath);
        pendingImagePath = "";
        pendingAudioPath = "";
        pendingDocuments.clear();
        if (settingsStore != null) settingsStore.close();
        if (imageSettingsStore != null) imageSettingsStore.close();
        if (appearanceSettings != null) appearanceSettings.dispose();
        mainHandler.removeCallbacksAndMessages(null);
        if (importCancellation != null) importCancellation.set(true);
        if (recordingController != null) recordingController.close();
        audioWorker.shutdown();
        if (runtime != null && isFinishing() && !isChangingConfigurations()) runtime.onChatFinished();
        super.onDestroy();
    }

    /**
     * The shared model and conversation file. {@code getPreferences} would name
     * that file from the install id and miss the seeded {@code MainActivity} file.
     */
    private android.content.SharedPreferences pocketPreferences() {
        return getSharedPreferences(RuntimePreferences.FILE, MODE_PRIVATE);
    }
}

