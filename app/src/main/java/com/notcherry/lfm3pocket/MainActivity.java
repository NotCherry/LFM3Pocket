package com.notcherry.lfm3pocket;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.StringRes;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends androidx.activity.ComponentActivity implements MainActivitySettingsHost {
    public static final String EXTRA_HISTORY_CONVERSATION_ID = "history_conversation_id";
    public static final String EXTRA_HISTORY_TITLE = "history_title";
    public static final String EXTRA_OPEN_SETTINGS = "open_settings";
    public static final String EXTRA_DRAFT_TEXT = "draft_text";
    public static final String EXTRA_FRESH_CHAT = "fresh_chat";
    /** Home and other in-app shortcuts that must share {@link #requestNewChat(boolean)}. */
    public static final String EXTRA_REQUEST_NEW_CHAT = "request_new_chat";
    /** System assistant gesture: open the single entry path and start a Hanami voice session. */
    public static final String EXTRA_ASSISTANT_VOICE_SESSION = "assistant_voice_session";
    public static final String EXTRA_SHARED_IMAGE = "shared_image_uri";
    public static final String EXTRA_SHARED_DOCUMENTS = "shared_document_uris";
    private static final int PICK_MODEL = 42;
    private static final int PICK_MMPROJ = 43;
    private static final int PICK_PHOTO = 44;
    private static final int TAKE_PHOTO = 45;
    private static final int REQUEST_AUDIO = 46;
    private static final int REQUEST_CAMERA = 47;
    private static final int OPEN_MODEL_URI = 48;
    private static final int OPEN_MMPROJ_URI = 49;
    private static final int PICK_WHISTLE_MODEL = 50;
    private static final int REQUEST_READ_CALENDAR = 51;
    private static final int REQUEST_POST_NOTIFICATIONS_WHISTLE = 52;
    private static final int PICK_DOCUMENT = 53;
    private static final int REQUEST_ASSISTANT_ROLE = 54;
    private static final int PICK_WORKSPACE_TREE = 55;
    private static final int REQUEST_READ_CONTACTS = 56;

    private ExecutorService worker;
    /** Dedicated non-inference I/O pool for attachments and Settings probes so hanami-infer never stalls. */
    private final ExecutorService ioWorker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "hanami-io");
        t.setDaemon(true);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ModelRuntime runtime;
    private ChatSession session;
    private ChatPanel chatPanel;
    private HistoryRepository historyRepository;
    private String historyConversationId = "default";
    private String historyTitle = "Hanami";
    private InferenceEngine inferenceEngine;
    private boolean modelBusy = false;
    private boolean generating = false;
    private boolean speaking = false;
    private volatile boolean destroyed = false;
    private PlaybackController playbackController;
    private float attachedInterfaceScale = InterfaceSize.DEFAULT;

    private AppearanceSettingsSection appearanceSettings;
    private ImageSettingsSection imageSettingsSection;
    private InferenceSettingsSection inferenceSettingsSection;
    private SearchSettingsSection searchSettingsSection;
    private BackgroundSettingsSection backgroundSettingsSection;
    private IntegrationsSettingsSection integrationsSettingsSection;
    private WorkspaceSettingsSection workspaceSettingsSection;
    private ExternalToolsSettingsSection externalToolsSection;
    private ChatShellSection chatShellSection;
    private ModelImportSettingsSection modelImportSection;
    private CrashLogSettingsSection crashLogSettingsSection;

    private ModelImportCoordinator modelImportCoordinator;
    private AttachmentImportCoordinator attachmentCoordinator;
    private VoiceInputCoordinator voiceCoordinator;
    private ModelDownloadDialogs modelDownloadDialogs;
    private ToolOutcomePresenter toolOutcomePresenter;
    private CalendarPickerHelper calendarPickerHelper;

    private boolean foreground;
    private boolean cameraRequested;
    private String lastAssistantReply = "";
    boolean assistantVoiceSessionLaunchRequested;
    boolean freshChatRequestedFromAssist;

    private View settingsHost;
    private InferenceSettings inferenceSettings = InferenceSettings.DEFAULT;
    private InferenceSettingsStore settingsStore;
    private ImageSettings imageSettings = ImageSettings.DEFAULT;
    private ImageSettingsStore imageSettingsStore;
    private boolean settingsLoaded;
    private boolean imageSettingsLoaded;
    private boolean settingsOpen;
    private Button photoButton, cameraButton, documentButton, recordButton, speakButton;
    private SearchConfig searchConfig = new SearchConfig(SearchProvider.SEARXNG, "", "", false);
    private boolean searchEnabled = false;
    private String searchEndpoint = "";
    private String mmprojPath = "";
    private String voiceUiStatus = "";

    private final List<Runnable> pendingResumeRestores = new ArrayList<>();
    private String pendingSwitchId;
    private String pendingSwitchTitle;

    private boolean isBusy() {
        return generating || speaking || modelBusy
                || (voiceCoordinator != null && (voiceCoordinator.recordingActive()
                        || voiceCoordinator.playbackActive() || voiceCoordinator.isTranscribing()))
                || (attachmentCoordinator != null && attachmentCoordinator.isImporting())
                || (modelImportCoordinator != null && modelImportCoordinator.isBusy());
    }

    private final ModelRuntime.Ui viewer = new ModelRuntime.Ui() {
        @Override public void onChat(String transcript, String message, boolean running) {
            if (destroyed || chatPanel == null || chatShellSection == null) return;
            generating = running;
            chatPanel.render(transcript, message);
            chatShellSection.applyChatStatusDuringTurn(message, running);
            updateControls();
        }
        @Override public void onSnapshot(ChatSnapshot snapshot) {
            if (destroyed || chatPanel == null) return;
            chatPanel.renderSnapshot(snapshot);
        }
        @Override public void onAssistantReply(String text) {
            if (text != null && !text.isBlank()) {
                lastAssistantReply = text;
                updateControls();
            }
        }
        @Override public void onHistoryTitle(String title) {
            if (title == null || title.isBlank()) return;
            historyTitle = title;
            if (chatShellSection != null) chatShellSection.setBrandTitle(historyTitle);
        }
        @Override public void onPlayback(String message) {
            if (destroyed || chatShellSection == null) return;
            setChatStatus(message);
            updateControls();
        }
        @Override public void onSpeech(boolean active, String message) {
            if (destroyed || chatShellSection == null) return;
            speaking = active;
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
        @Override public void onModelStateChanged(boolean loaded, String message) {
            if (destroyed || chatShellSection == null) return;
            if (runtime != null && !runtime.isLoading() && !runtime.isUnloading()) {
                modelBusy = false;
            }
            if (!loaded) {
                generating = runtime != null && runtime.isGenerating();
                speaking = false;
            }
            if (message != null && !message.isEmpty()) {
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
        if (appearanceSettings != null) appearanceSettings.syncControls();
        if (integrationsSettingsSection != null) {
            CactusWhistleModelDownloader.bindUi(integrationsSettingsSection.cactusWhistleDownloadUi());
            TinyVocaPackDownloader.bindUi(integrationsSettingsSection.tinyVocaDownloadUi());
            if (CactusWhistleModelDownloader.isDownloading()) integrationsSettingsSection.paintSpeechInputControls();
            if (TinyVocaPackDownloader.isDownloading()) integrationsSettingsSection.paintTinyVocaPackStatus();
        }
        String pendingWhistleError = CactusWhistleModelDownloader.consumePendingBackgroundError();
        if (pendingWhistleError != null) setChatStatus(pendingWhistleError);
        String pendingTinyVocaError = TinyVocaPackDownloader.consumePendingBackgroundError();
        if (pendingTinyVocaError != null) setChatStatus(pendingTinyVocaError);
    }

    @Override protected void onPause() {
        if (integrationsSettingsSection != null) {
            CactusWhistleModelDownloader.unbindUi(integrationsSettingsSection.cactusWhistleDownloadUi());
            TinyVocaPackDownloader.unbindUi(integrationsSettingsSection.tinyVocaDownloadUi());
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
        initCoordinators();
        settingsStore = new InferenceSettingsStore(this);
        imageSettingsStore = new ImageSettingsStore(this);
        buildUi(state);
        session = runtime.session();
        runtime.attach(viewer);
        updateControls();
        loadStoredSettings();
        restoreModelAndStartupFlow(getIntent());
    }

    private void initCoordinators() {
        Lfm3PocketApplication app = (Lfm3PocketApplication) getApplication();
        attachmentCoordinator = new AttachmentImportCoordinator(new AttachmentImportCoordinator.Deps(
                this, ioWorker, () -> chatPanel, () -> imageSettings, () -> destroyed,
                () -> {}, this::setChatStatus, this::updateControls, app.processingTelemetry()));
        voiceCoordinator = new VoiceInputCoordinator(new VoiceInputCoordinator.Deps(
                this, runtime, playbackController, mainHandler, this::pocketPreferences,
                () -> chatPanel, () -> destroyed, () -> foreground, this::isBusy,
                () -> generating, () -> speaking, () -> settingsLoaded, () -> lastAssistantReply,
                value -> {}, value -> speaking = value, () -> {},
                this::setChatStatus, attachmentCoordinator::onCompletedRecordingAudio,
                this::send, () -> requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_AUDIO),
                this::updateControls));
        modelImportCoordinator = new ModelImportCoordinator(new ModelImportCoordinator.Deps(
                this, runtime, worker, this::pocketPreferences, () -> destroyed,
                this::isBusy, value -> {}, this::setChatStatus, this::updateControls,
                voiceCoordinator::maybeStartPendingAssistantVoiceSession,
                () -> {
                    if (integrationsSettingsSection != null) integrationsSettingsSection.paintSpeechInputControls();
                }));
        modelDownloadDialogs = new ModelDownloadDialogs(new ModelDownloadDialogs.Deps(
                this, this::requestNotificationPermissionForModelDownloadIfNeeded,
                () -> chatShellSection != null, this::setChatStatus,
                () -> {
                    if (integrationsSettingsSection != null) integrationsSettingsSection.paintTinyVocaPackStatus();
                },
                () -> {
                    if (integrationsSettingsSection != null) integrationsSettingsSection.paintSpeechInputControls();
                }));
        toolOutcomePresenter = new ToolOutcomePresenter(new ToolOutcomePresenter.Deps(
                this, runtime, historyRepository, worker, mainHandler, () -> chatPanel,
                () -> historyConversationId, () -> historyTitle, () -> destroyed));
        calendarPickerHelper = new CalendarPickerHelper(new CalendarPickerHelper.Deps(
                this, () -> requestPermissions(new String[] {
                        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR },
                        REQUEST_READ_CALENDAR),
                this::setChatStatus,
                () -> {
                    if (integrationsSettingsSection != null) integrationsSettingsSection.refreshCalendarPickerStatus();
                }));
    }

    private void loadStoredSettings() {
        settingsStore.load(settings -> {
            if (destroyed) return;
            inferenceSettings = settings;
            inferenceEngine.configure(settings);
            settingsLoaded = true;
            syncInlineSettingsFields();
            updateControls();
        }, error -> {
            if (destroyed) return;
            inferenceSettings = InferenceSettings.DEFAULT;
            inferenceEngine.configure(inferenceSettings);
            settingsLoaded = true;
            setChatStatus("Settings unavailable; using defaults: " + error);
            syncInlineSettingsFields();
            updateControls();
        });
        imageSettingsStore.load(settings -> {
            if (destroyed) return;
            imageSettings = settings;
            imageSettingsLoaded = true;
            if (imageSettingsSection != null) imageSettingsSection.syncFields();
        }, error -> {
            if (destroyed) return;
            imageSettings = ImageSettings.DEFAULT;
            imageSettingsLoaded = true;
            if (imageSettingsSection != null) imageSettingsSection.syncFields();
            setChatStatus("Image settings unavailable; using defaults: " + error);
        });
    }

    private void restoreModelAndStartupFlow(Intent intent) {
        String previous = pocketPreferences().getString("model", "");
        mmprojPath = pocketPreferences().getString("mmproj", "");
        searchConfig = SearchConfig.fromPreferences(pocketPreferences());
        searchEndpoint = searchConfig.endpoint;
        searchEnabled = searchConfig.isReady();
        chatPanel.setIdleMode(searchEnabled);
        syncInlineSettingsFields();
        syncBackgroundControls();
        final boolean launchNewChat = shouldDeferNewChat(intent);
        consumeAssistantVoiceSessionIntent(intent);
        final boolean modelInMemory = runtime.handle() != 0L && !runtime.isUnloading()
                && (previous.equals(runtime.loadedPath()) || ModelImportCoordinator.isContentUri(previous));
        if (modelInMemory) {
            if (!launchNewChat && !runtime.isGenerating()) {
                setChatStatus(mmprojPath == null || mmprojPath.isEmpty()
                        ? getString(R.string.status_text_model_ready)
                        : getString(R.string.status_omni_model_ready));
            }
        } else if (runtime.isLoading()) {
            modelBusy = true;
            setChatStatus(R.string.loading_model);
            updateControls();
        } else if (!previous.isEmpty()
                && (ModelImportCoordinator.isContentUri(previous) || new File(previous).isFile())) {
            modelImportCoordinator.loadSelectedModel(previous, mmprojPath);
        } else if (runtime.handle() == 0L) {
            setChatStatus(R.string.status_no_model_loaded);
        }
        if (launchNewChat) {
            mainHandler.post(() -> handlePendingNewChatRequests(intent));
        } else {
            // Resume path: re-apply persisted history if async restore raced with startup/model load.
            scheduleResumeRestores(historyConversationId);
        }
        if (pendingSwitchId != null) confirmSwitchConversation();
        voiceCoordinator.maybeStartPendingAssistantVoiceSession();
    }

    private void scheduleResumeRestores(String resumeId) {
        cancelPendingResumeRestores();
        if (resumeId == null || resumeId.isEmpty()) return;
        long[] delays = { 1200L, 3500L };
        for (long delay : delays) {
            Runnable task = () -> {
                if (destroyed || runtime == null) return;
                if (!resumeId.equals(historyConversationId)) return;
                if (!resumeId.equals(runtime.conversationId())) return;
                if (runtime.isGenerating()) return;
                runtime.restoreOpenConversation();
            };
            pendingResumeRestores.add(task);
            mainHandler.postDelayed(task, delay);
        }
    }

    private void cancelPendingResumeRestores() {
        for (Runnable task : pendingResumeRestores) {
            mainHandler.removeCallbacks(task);
        }
        pendingResumeRestores.clear();
    }

    private void adoptConversation(Intent intent) {
        cancelPendingResumeRestores();
        String requested = intent == null ? null : intent.getStringExtra(EXTRA_HISTORY_CONVERSATION_ID);
        String requestedTitle = intent == null ? null : intent.getStringExtra(EXTRA_HISTORY_TITLE);
        boolean explicit = requested != null && !requested.isBlank();
        SharedPreferences prefs = pocketPreferences();
        OpenConversation choice = OpenConversation.choose(
                explicit ? requested : null,
                prefs.getString("history_conversation_id", ""),
                runtime.conversationId(),
                runtime.isGenerating(),
                UUID.randomUUID().toString());
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
        if (!newChatIntent) {
            runtime.restoreOpenConversation();
        }
    }

    private void confirmSwitchConversation() {
        String id = pendingSwitchId;
        String title = pendingSwitchTitle;
        pendingSwitchId = null;
        pendingSwitchTitle = null;
        StillwaterUi.showDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.reply_still_running_title)
                .setMessage(R.string.reply_still_running_message)
                .setPositiveButton(R.string.stop_and_start_new, (dialog, which) -> {
                    runtime.stopTurn();
                    runtime.clearConversation();
                    lastAssistantReply = "";
                    historyConversationId = id;
                    historyTitle = title == null || title.isBlank()
                            ? getString(R.string.new_conversation_title) : title;
                    runtime.setConversation(historyConversationId, historyTitle);
                    if (chatShellSection != null) chatShellSection.setBrandTitle(historyTitle);
                })
                .setNegativeButton(R.string.keep_this_reply, (dialog, which) -> {
                    historyConversationId = runtime.conversationId();
                    historyTitle = runtime.historyTitle();
                }));
    }

    private long modelHandle() { return runtime == null ? 0L : runtime.handle(); }

    @Override public Activity settingsActivity() { return this; }

    @Override public int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public boolean isHostFinishing() { return destroyed; }

    @Override public Handler settingsMainHandler() { return mainHandler; }

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
        return SettingStepperHelper.addSettingInput(this, form, title, hint, value, id, step, min, max);
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
        cancelPendingResumeRestores();
        voiceCoordinator.cancelAllOnClear();
        speaking = false;
        attachmentCoordinator.cancelPendingImportSilently();
        attachmentCoordinator.clearAndDeletePending(true);
        lastAssistantReply = "";
        if (voiceCoordinator.playbackActive()) playbackController.stop();
        chatPanel.clearToolCard();
        runtime.clearConversation();
        generating = runtime.isGenerating();
        lastAssistantReply = "";
        updateControls();
    }

    private void onChatPanelRetryTurn(String turnId) {
        if (isBusy() || modelHandle() == 0L || runtime.isUnloading() || !settingsLoaded) return;
        runtime.noteSubmission("retry", inferenceSettings.maxTokens);
        if (runtime.retryInterrupted(turnId)) {
            session = runtime.session();
            attachmentCoordinator.clearPendingForRetry();
            generating = true;
            updateControls();
        }
    }

    private void buildUi(Bundle state) {
        int surface = StillwaterUi.SURFACE;
        int ink = StillwaterUi.INK;
        int inkMuted = StillwaterUi.MUTED;
        int accent = StillwaterUi.CANDLE;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        StillwaterUi.applySystemAndImeInsets(this, root, dp(16), dp(6), dp(16), dp(8));

        chatShellSection = new ChatShellSection(this, new ChatShellSection.Deps(
                () -> historyTitle, () -> searchEnabled, () -> requestNewChat(false),
                () -> setSettingsOpen(true), this::showSearchSettings,
                new ChatShellSection.ChatPanelDeps(this::send, this::stopActiveWork, this::onChatPanelNewChat,
                        this::onChatPanelRetryTurn, this::requestCompact,
                        ref -> {
                            String err = AttachmentViewer.open(this, ref);
                            if (err != null && !err.isEmpty()) setChatStatus(err);
                        },
                        () -> {
                            if (session != null) session.clearActiveDocuments();
                        },
                        () -> {
                            if (session != null) session.clearActiveImages();
                        })));
        chatShellSection.mountChrome(root, ink, inkMuted, accent);

        settingsHost = buildSettingsHost(surface, ink, inkMuted, accent);
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
        voiceCoordinator.bindRecordButton(recordButton);
        speakButton.setOnClickListener(v -> voiceCoordinator.toggleSpokenReply());
        // Keep Settings open across interface-size recreate via EXTRA_OPEN_SETTINGS only.
        // Do not persist settings_open in savedInstanceState — InferenceSettingsE2eTest and
        // other recreate paths expect to return to chat after scenario.recreate().
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            setSettingsOpen(true);
        }
        applyDraft(getIntent());
    }

    private LinearLayout buildSettingsHost(int surface, int ink, int inkMuted, int accent) {
        LinearLayout settingsRoot = new LinearLayout(this);
        settingsRoot.setOrientation(LinearLayout.VERTICAL);
        settingsRoot.setBackgroundColor(surface);
        LinearLayout settingsHeader = new LinearLayout(this);
        settingsHeader.setOrientation(LinearLayout.HORIZONTAL);
        settingsHeader.setGravity(Gravity.CENTER_VERTICAL);
        settingsHeader.setPadding(0, dp(4), 0, dp(10));
        TextView settingsHeading = label(getString(R.string.settings_title), 26, ink);
        settingsHeading.setTypeface(StillwaterUi.serif(this));
        StillwaterReadable.prepareWrappingLabel(settingsHeading);
        settingsHeader.addView(settingsHeading, new LinearLayout.LayoutParams(0, -2, 1f));
        Button settingsBack = button(getString(R.string.back_to_chat));
        settingsBack.setId(R.id.settings_back);
        StillwaterUi.accentOutlined(settingsBack);
        StillwaterReadable.setTextSizeSp(settingsBack, 14f);
        settingsBack.setPadding(dp(14), dp(8), dp(14), dp(8));
        int backFloor = Math.max(dp(48), InterfaceSize.minTouchPx(this));
        settingsBack.setMinimumHeight(backFloor);
        settingsBack.setMinWidth(backFloor);
        settingsBack.setOnClickListener(v -> setSettingsOpen(false));
        StillwaterReadable.prepareWrappingControl(settingsBack, backFloor);
        settingsHeader.addView(settingsBack, new LinearLayout.LayoutParams(-2, -2));
        settingsRoot.addView(settingsHeader, new LinearLayout.LayoutParams(-1, -2));
        View settingsHeaderDivider = new View(this);
        settingsHeaderDivider.setBackgroundColor(StillwaterUi.LINE);
        settingsRoot.addView(settingsHeaderDivider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));

        LinearLayout configuration = new LinearLayout(this);
        configuration.setOrientation(LinearLayout.VERTICAL);
        configuration.setPadding(0, 0, 0, dp(8));
        modelImportSection = new ModelImportSettingsSection(this, new ModelImportSettingsSection.Deps(
                this::pickModel, this::pickMmproj,
                () -> pickExternalModel(OPEN_MODEL_URI),
                () -> pickExternalModel(OPEN_MMPROJ_URI),
                modelImportCoordinator::reloadSelectedModel,
                modelImportCoordinator::unloadSelectedModel,
                this::showInferenceSettings));
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
                inferenceEngine::configure, () -> settingsLoaded, this::isBusy, () -> generating,
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
                () -> searchConfig,
                provider -> SearchConfig.readApiKey(pocketPreferences(), provider),
                nextConfig -> {
                    searchConfig = nextConfig;
                    searchEndpoint = nextConfig.endpoint;
                    searchEnabled = nextConfig.enabled;
                    SearchConfig.saveToPreferences(pocketPreferences(), nextConfig);
                },
                enabled -> chatPanel.setIdleMode(enabled), this::updateControls, this::isBusy,
                this::showSearchSettings, this::setChatStatus, ioWorker, this::runOnUiThread));
        searchSettingsSection.mount(configuration, ink, inkMuted);

        imageSettingsSection.mount(configuration, ink, inkMuted);
        backgroundSettingsSection = new BackgroundSettingsSection(this, new BackgroundSettingsSection.Deps(
                () -> new RuntimePreferences(this),
                (keep, idle, lock, loopback) -> runtime.updateBackgroundSettings(keep, idle, lock, loopback),
                () -> runtime.loopbackToken(), () -> runtime.loopbackPort(), this::setChatStatus));
        backgroundSettingsSection.mount(configuration, ink, inkMuted);
        integrationsSettingsSection = new IntegrationsSettingsSection(this, new IntegrationsSettingsSection.Deps(
                () -> new RuntimePreferences(this), () -> {
                    if (runtime != null) runtime.refreshVoiceAvailability();
                    updateControls();
                }, calendarPickerHelper::pickCalendarTarget,
                this::pickWhistleModel, modelDownloadDialogs::downloadCactusWhistleModel,
                modelDownloadDialogs::downloadTinyVocaPack,
                this::runOnUiThread, this::setChatStatus));
        integrationsSettingsSection.mount(configuration, ink, inkMuted);
        workspaceSettingsSection = new WorkspaceSettingsSection(this, new WorkspaceSettingsSection.Deps(
                () -> new RuntimePreferences(this), this::pickWorkspaceFolder, this::setChatStatus));
        workspaceSettingsSection.mount(configuration, ink);
        Lfm3PocketApplication app = (Lfm3PocketApplication) getApplication();
        externalToolsSection = new ExternalToolsSettingsSection(this, new ExternalToolsSettingsSection.Deps(
                app::customShortcutStore, app::mcpToolProvider,
                () -> requestPermissions(new String[] { Manifest.permission.READ_CONTACTS }, REQUEST_READ_CONTACTS),
                ioWorker, this::runOnUiThread, this::setChatStatus));
        externalToolsSection.mount(configuration, ink);

        new DefaultAssistantSettingsSection(this, new DefaultAssistantSettingsSection.Deps(
                this::startActivity, intent -> startActivityForResult(intent, REQUEST_ASSISTANT_ROLE),
                this::setChatStatus)).mount(configuration, inkMuted);
        crashLogSettingsSection = new CrashLogSettingsSection(this, new CrashLogSettingsSection.Deps(
                this::setChatStatus,
                () -> CrashLogSettingsSection.collectDeviceInfo(this, runtime.loadedPath(),
                        !mmprojPath.isEmpty(), inferenceSettings),
                app::processingTelemetry, app::diagnostics, ioWorker, this::runOnUiThread));
        crashLogSettingsSection.mount(configuration, ink, inkMuted);

        ScrollView configurationScroll = new ScrollView(this);
        configurationScroll.setId(R.id.model_configuration_scroll);
        configurationScroll.setBackgroundColor(surface);
        configurationScroll.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        configurationScroll.setClipToPadding(false);
        configurationScroll.setPadding(0, dp(16), 0, dp(24));
        configurationScroll.addView(configuration);
        settingsRoot.addView(configurationScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Sticky Save controls stay on-screen (small emulator + IME) for UiAutomator Stage 1 IDs.
        View footerDivider = new View(this);
        footerDivider.setBackgroundColor(StillwaterUi.LINE);
        settingsRoot.addView(footerDivider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        LinearLayout settingsActions = new LinearLayout(this);
        settingsActions.setOrientation(LinearLayout.VERTICAL);
        settingsActions.setBackgroundColor(surface);
        settingsActions.setPadding(0, dp(8), 0, dp(6));
        Button saveInference = button(getString(R.string.save));
        saveInference.setId(R.id.settings_save);
        StillwaterUi.primary(saveInference);
        StillwaterReadable.setTextSizeSp(saveInference, 15f);
        saveInference.setOnClickListener(v -> {
            if (inferenceSettingsSection != null) inferenceSettingsSection.saveInline();
        });
        if (searchSettingsSection != null) {
            Button saveSearch = searchSettingsSection.createSaveButton();
            StillwaterReadable.setTextSizeSp(saveSearch, 15f);
            StillwaterReadable.addFlexibleButtonRow(settingsActions, this, 50,
                    new float[] { 1.15f, 1f }, saveInference, saveSearch);
        } else {
            StillwaterReadable.addFlexibleFullWidth(settingsActions, saveInference, 50);
        }
        settingsRoot.addView(settingsActions, new LinearLayout.LayoutParams(-1, -2));
        return settingsRoot;
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
        boolean busyNow = isBusy();
        boolean modelReady = modelHandle() != 0L && (runtime == null || !runtime.isUnloading());
        if (searchSettingsSection != null) searchSettingsSection.paintWebSearchControl(busyNow);
        if (chatShellSection != null) chatShellSection.paintModePill(searchEnabled);
        if (modelImportSection != null) modelImportSection.paintImportButtons(busyNow);
        photoButton.setEnabled(!busyNow);
        cameraButton.setEnabled(!busyNow);
        if (documentButton != null) documentButton.setEnabled(!busyNow);
        voiceCoordinator.paintVoiceControls(recordButton, speakButton, voiceUiStatus);

        boolean recording = voiceCoordinator.recordingActive();
        boolean playback = voiceCoordinator.playbackActive();
        boolean transcribing = voiceCoordinator.isTranscribing();
        boolean importing = modelImportCoordinator.isImporting();
        boolean importingAttachment = attachmentCoordinator != null && attachmentCoordinator.isImporting();
        boolean canCompact = !busyNow && !speaking && !recording && !playback
                && !importing && !importingAttachment && modelReady && settingsLoaded;
        chatPanel.setModelReady(modelReady);
        chatPanel.updateControls(!busyNow && modelReady && settingsLoaded,
                generating || speaking || recording || playback || transcribing || importing || importingAttachment,
                !busyNow || generating || transcribing || importingAttachment,
                canCompact);

        if (modelImportSection != null) {
            String model = pocketPreferences().getString("model", "");
            String projector = pocketPreferences().getString("mmproj", "");
            String description = currentChatStatus().toString();
            modelImportSection.paintModelManager(
                    settingsLoaded ? inferenceSettings.summary() : "Loading inference settings...",
                    model.isEmpty() ? "" : pocketPreferences().getString("model_name", new File(model).getName()),
                    projector.isEmpty() ? "" : pocketPreferences().getString("projector_name", new File(projector).getName()),
                    busyNow, modelReady,
                    description.startsWith("Model error") || description.startsWith("Import failed"));
        }
    }

    private void requestCompact() {
        if (isBusy() || generating || speaking || voiceCoordinator.recordingActive()
                || voiceCoordinator.playbackActive() || modelHandle() == 0L || runtime.isUnloading() || !settingsLoaded) {
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
            generating = true;
            updateControls();
        }
    }
    private void syncInlineSettingsFields() {
        if (inferenceSettingsSection != null) inferenceSettingsSection.syncFields();
        if (searchSettingsSection != null) searchSettingsSection.syncFields();
        if (integrationsSettingsSection != null) integrationsSettingsSection.syncFields();
        if (workspaceSettingsSection != null) workspaceSettingsSection.refreshStatus();
        if (externalToolsSection != null) externalToolsSection.refreshStatus();
    }

    private void syncBackgroundControls() {
        if (backgroundSettingsSection != null) backgroundSettingsSection.syncControls();
        if (integrationsSettingsSection != null) integrationsSettingsSection.syncControls();
        if (workspaceSettingsSection != null) workspaceSettingsSection.refreshStatus();
        if (externalToolsSection != null) externalToolsSection.refreshStatus();
    }

    private void showInferenceSettings() {
        if (isBusy() || !settingsLoaded) return;
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
        if (isBusy()) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        startActivityForResult(pick, PICK_PHOTO);
    }

    private void takePhoto() {
        if (isBusy()) return;
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
        if (isBusy()) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "text/plain", "text/markdown", "text/csv", "text/html", "text/xml",
                "application/json", "application/xml", "application/pdf"
        });
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(pick, PICK_DOCUMENT);
    }

    private void pickModel() { pickModel(PICK_MODEL); }

    private void pickMmproj() { pickModel(PICK_MMPROJ); }

    private void pickModel(int requestCode) {
        if (isBusy()) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, requestCode);
    }

    private void pickExternalModel(int requestCode) {
        if (isBusy()) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick, requestCode);
    }

    private void pickWhistleModel() {
        if (isBusy() || CactusWhistleModelDownloader.isDownloading()) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, PICK_WHISTLE_MODEL);
    }

    private void pickWorkspaceFolder() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(pick, PICK_WORKSPACE_TREE);
        } catch (Exception e) {
            setChatStatus("Could not open folder picker.");
        }
    }

    String pendingMediaPathForTest() { return attachmentCoordinator.pendingMediaPathForTest(); }

    String[] pendingMediaPathsForTest() { return attachmentCoordinator.pendingMediaPathsForTest(); }

    /** Instrumentation seam: submit through the same path as the Send button without IME/focus races. */
    void sendForTest(String text) { send(text == null ? "" : text); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQUEST_CAMERA && cameraRequested) {
            cameraRequested = false;
            if (granted && !destroyed && !isBusy()) {
                launchCameraCapture();
            } else {
                setChatStatus("Camera access is needed to take a photo.");
            }
            updateControls();
            return;
        }
        if (requestCode == REQUEST_AUDIO) {
            voiceCoordinator.onAudioPermissionResult(granted);
            return;
        }
        if (requestCode == REQUEST_READ_CALENDAR) {
            boolean allGranted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            calendarPickerHelper.onPermissionResult(allGranted);
            return;
        }
        if (requestCode == REQUEST_READ_CONTACTS) {
            if (externalToolsSection != null) externalToolsSection.refreshStatus();
            setChatStatus(granted ? R.string.contacts_status_ready : R.string.contacts_status_off);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
    }

    @Override protected void onStop() {
        foreground = false;
        if (voiceCoordinator != null) voiceCoordinator.onStop();
        super.onStop();
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_WORKSPACE_TREE && result == RESULT_OK && data != null && data.getData() != null) {
            if (workspaceSettingsSection != null) {
                workspaceSettingsSection.onFolderPicked(data.getData(), data.getFlags());
            }
            return;
        }
        if (code == PICK_PHOTO && result == RESULT_OK && data != null && data.getData() != null) {
            attachmentCoordinator.copySelectedPhoto(data.getData());
            return;
        }
        if (code == TAKE_PHOTO && result == RESULT_OK && data != null) {
            String path = data.getStringExtra(CameraCaptureActivity.EXTRA_PHOTO_PATH);
            if (path != null && new File(path).isFile()) attachmentCoordinator.prepareCapturedPhoto(path);
            else setChatStatus("The camera did not return a photo.");
            return;
        }
        if (code == TAKE_PHOTO && result != RESULT_OK) {
            setChatStatus("Camera closed without taking a photo.");
            return;
        }
        if (code == PICK_DOCUMENT && result == RESULT_OK && data != null) {
            attachmentCoordinator.importSelectedDocuments(data);
            return;
        }
        if ((code == OPEN_MODEL_URI || code == OPEN_MMPROJ_URI)
                && result == RESULT_OK && data != null && data.getData() != null) {
            modelImportCoordinator.openExternalDocument(data.getData(), code == OPEN_MMPROJ_URI);
            return;
        }
        if (code == PICK_WHISTLE_MODEL && result == RESULT_OK && data != null && data.getData() != null) {
            modelImportCoordinator.importWhistleModel(data.getData());
            return;
        }
        if ((code == PICK_MODEL || code == PICK_MMPROJ)
                && result == RESULT_OK && data != null && data.getData() != null) {
            modelImportCoordinator.importPickedGguf(data.getData(), code == PICK_MMPROJ);
        }
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
        if (voiceCoordinator.consumeAssistantVoiceSessionIntent(intent)) {
            assistantVoiceSessionLaunchRequested = true;
        }
    }

    private void requestNewChat(boolean fromSystemAssist) {
        if (runtime.isGenerating()) {
            StillwaterUi.showDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.reply_still_running_title)
                    .setMessage(R.string.reply_still_running_message)
                    .setPositiveButton(R.string.stop_and_start_new, (dialog, which) -> {
                        runtime.stopTurn();
                        startFreshChat(fromSystemAssist);
                    })
                    .setNegativeButton(R.string.keep_this_reply, null));
            return;
        }
        startFreshChat(fromSystemAssist);
    }

    private void startFreshChat(boolean fromSystemAssist) {
        cancelPendingResumeRestores();
        voiceCoordinator.cancelAllOnClear();
        speaking = false;
        attachmentCoordinator.cancelPendingImportSilently();
        attachmentCoordinator.clearAndDeletePending(true);
        lastAssistantReply = "";
        if (voiceCoordinator.playbackActive()) playbackController.stop();
        if (chatPanel != null) chatPanel.clearToolCard();
        String id = UUID.randomUUID().toString();
        historyConversationId = id;
        historyTitle = getString(R.string.new_conversation_title);
        if (chatShellSection != null) chatShellSection.setBrandTitle(historyTitle);
        runtime.setConversation(id, historyTitle);
        runtime.clearConversation();
        generating = runtime.isGenerating();
        setSettingsOpen(false);
        String freshStatus = fromSystemAssist
                ? getString(R.string.fresh_chat_from_assist)
                : getString(R.string.new_chat_ready);
        setChatStatus(freshStatus);
        if (chatPanel != null) {
            chatPanel.renderSnapshot(new ChatSnapshot(Collections.emptyList(), freshStatus, false));
            chatPanel.render(FlatTranscriptFormatter.EMPTY_TRANSCRIPT, freshStatus);
        }
        updateControls();
    }

    private void stopActiveWork() {
        if (voiceCoordinator.stopActiveWorkIfHandling()) return;
        if (attachmentCoordinator.cancelImportIfActive()) return;
        if (voiceCoordinator.playbackActive()) {
            playbackController.stop();
        } else if (speaking) {
            runtime.stopSpeech();
        } else if (!modelImportCoordinator.cancelImportIfActive()) {
            runtime.stopTurn();
        }
    }

    /** Presents a validated coordinator result in the conversation and keeps authorization in the coordinator. */
    public void presentToolOutcome(ToolCoordinator.Outcome outcome) {
        toolOutcomePresenter.present(outcome);
    }

    /** Includes the request because immediate safe actions have no confirmation proposal to carry it. */
    public void presentToolOutcome(ToolRegistry.Request request, ToolCoordinator.Outcome outcome) {
        toolOutcomePresenter.present(request, outcome);
    }

    private void requestNotificationPermissionForModelDownloadIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS },
                REQUEST_POST_NOTIFICATIONS_WHISTLE);
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
                attachmentCoordinator.copySelectedPhoto(Uri.parse(shared));
            } catch (Exception failed) {
                setChatStatus("Photo import failed: " + failed.getMessage());
            }
        }
        ArrayList<String> sharedDocs = intent.getStringArrayListExtra(EXTRA_SHARED_DOCUMENTS);
        if (sharedDocs != null && !sharedDocs.isEmpty()) {
            intent.removeExtra(EXTRA_SHARED_DOCUMENTS);
            ArrayList<Uri> uris = new ArrayList<>(sharedDocs.size());
            for (String s : sharedDocs) {
                if (s != null && !s.isBlank()) uris.add(Uri.parse(s));
            }
            attachmentCoordinator.importDocumentUris(uris);
        }
    }

    private void send(String text) {
        if (isBusy() || modelHandle() == 0L || runtime.isUnloading() || !settingsLoaded) return;
        if (!runtime.acceptsTurn()) {
            setChatStatus("Phone is too warm for a new reply.");
            return;
        }
        runtime.noteSubmission(text, inferenceSettings.maxTokens);
        session = runtime.session();
        List<ChatSession.Attachment> attachments = attachmentCoordinator.buildPendingAttachments();
        if (session.submit(text, attachments, searchEnabled, searchEndpoint, inferenceSettings.maxTokens)) {
            attachmentCoordinator.clearPendingAfterSubmit();
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
        voiceCoordinator.maybeStartPendingAssistantVoiceSession();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (runtime != null) runtime.detach(viewer);
        if (attachmentCoordinator != null) {
            attachmentCoordinator.cancelPendingImportSilently();
            attachmentCoordinator.clearAndDeletePending(false);
        }
        if (settingsStore != null) settingsStore.close();
        if (imageSettingsStore != null) imageSettingsStore.close();
        if (appearanceSettings != null) appearanceSettings.dispose();
        if (crashLogSettingsSection != null) crashLogSettingsSection.dispose();
        mainHandler.removeCallbacksAndMessages(null);
        if (modelImportCoordinator != null) modelImportCoordinator.cancelOnDestroy();
        if (voiceCoordinator != null) voiceCoordinator.onDestroy();
        ioWorker.shutdownNow();
        if (runtime != null && isFinishing() && !isChangingConfigurations()) runtime.onChatFinished();
        super.onDestroy();
    }

    /**
     * The shared model and conversation file. {@code getPreferences} would name
     * that file from the install id and miss the seeded {@code MainActivity} file.
     */
    private SharedPreferences pocketPreferences() {
        return getSharedPreferences(RuntimePreferences.FILE, MODE_PRIVATE);
    }
}
