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
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends androidx.activity.ComponentActivity {
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
    private static final int OPEN_MMPROJ_URI = 49;


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
    private PlaybackController playbackController;
    private CheckBox keepLoadedBox;
    private Button idleOffButton;
    private Button idle15Button;
    private Button idle60Button;
    private CheckBox lockPreviewBox;
    private CheckBox loopbackBox;
    private CheckBox networkToolsBox;
    private CheckBox tinyVocaTtsBox;
    private TextView tinyVocaStatus;
    private Button tinyVocaDownloadButton;
    private Button tinyVocaRemoveButton;
    private Button interfaceCompactButton, interfaceDefaultButton, interfaceLargeButton;
    private SeekBar interfaceSizeSlider;
    private TextView interfaceSizeValue;
    private boolean bindingInterfaceSize;
    private boolean interfaceSliderTracking;
    private float pendingInterfaceSliderScale = InterfaceSize.DEFAULT;
    private float attachedInterfaceScale = InterfaceSize.DEFAULT;
    private final Runnable applyInterfaceSlider = () -> {
        if (destroyed || bindingInterfaceSize || interfaceSliderTracking) return;
        applyInterfaceSize(pendingInterfaceSliderScale);
    };
    private Button openModelUriButton, openProjectorUriButton, defaultAssistantButton;
    private boolean foreground;
    private boolean microphoneRequested;
    private boolean cameraRequested;
    private String pendingImagePath = "";
    private String pendingAudioPath = "";
    private String lastAssistantReply = "";
    private boolean pendingAssistantVoiceSession;
    boolean assistantVoiceSessionLaunchRequested;
    boolean freshChatRequestedFromAssist;

    private TextView status;
    private TextView brandTitle;
    private TextView modePill;
    private View topBarView;
    private ModelManagerPanel modelManager;
    private View settingsHost;
    private View chatShell;
    private InferenceSettings inferenceSettings=InferenceSettings.DEFAULT;
    private InferenceSettingsStore settingsStore;
    private ImageSettings imageSettings=ImageSettings.DEFAULT;
    private ImageSettingsStore imageSettingsStore;
    private boolean settingsLoaded;
    private boolean imageSettingsLoaded;
    private boolean settingsOpen;
    private boolean bindingImageSettings;
    private CheckBox imagePrepareBox;
    private CheckBox imageCropBox;
    private EditText imageQualityField;
    private Button imageMax384Button, imageMax512Button, imageMax768Button, imageMax1024Button, imageMaxOriginalButton;
    private Button modelButton, mmprojButton;
    private Button searchButton, photoButton, cameraButton, recordButton, speakButton;
    private Button settingsOpenButton;
    private EditText settingsContextField, settingsThreadsField, settingsTokensField;
    private CheckBox promptLookupBox;
    private TextView settingsInlineError;
    private TextView settingsBenchResult;
    private TextView settingsBuildInfo;
    private boolean benchmarkRunning;
    private EditText searchEndpointField;
    private CheckBox searchEnabledBox;
    private boolean searchEnabled = false;
    private String searchEndpoint = "";
    private String mmprojPath = "";

    private final ModelRuntime.Ui viewer = new ModelRuntime.Ui() {
        @Override public void onChat(String transcript, String message, boolean running) {
            if (destroyed || chatPanel == null || status == null) return;
            generating = running;
            busy = running || recordingActive() || playbackActive() || speaking;
            chatPanel.render(transcript, message);
            status.setText(message);
            status.setVisibility(running ? View.GONE : View.VISIBLE);
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
            if (brandTitle != null) brandTitle.setText(historyTitle);
        }
        @Override public void onPlayback(String message) {
            if (destroyed || status == null) return;
            busy = recordingActive() || playbackActive() || generating || speaking;
            status.setText(message);
            updateControls();
        }
        @Override public void onSpeech(boolean active, String message) {
            if (destroyed || status == null) return;
            speaking = active;
            busy = active || recordingActive() || playbackActive() || generating;
            status.setText(message);
            updateControls();
        }
        @Override public void onToolOutcome(ToolRegistry.Request request, ToolCoordinator.Outcome outcome) {
            if (destroyed) return;
            presentToolOutcome(request, outcome);
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
                    busy = recordingActive() || playbackActive() || speaking || generating;
                    status.setText(message);
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
            settingsLoaded=true;status.setText("Settings unavailable; using defaults: "+error);
            syncInlineSettingsFields();updateControls();
        });
        imageSettingsStore.load(settings -> {
            if (destroyed) return;
            imageSettings=settings;imageSettingsLoaded=true;
            syncImageSettingsFields();
        }, error -> {
            if (destroyed) return;
            imageSettings=ImageSettings.DEFAULT;imageSettingsLoaded=true;
            syncImageSettingsFields();
            status.setText("Image settings unavailable; using defaults: "+error);
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
            if (!launchNewChat && !runtime.isGenerating()) status.setText(mmprojPath == null || mmprojPath.isEmpty()
                    ? "Text model ready · offline" : "Model and projector loaded · offline");
        } else if (!previous.isEmpty() && (isContentUri(previous) || new File(previous).isFile())) {
            loadSelectedModel(previous, mmprojPath);
        } else if (runtime.handle() == 0L) {
            status.setText("No model loaded. Select an LFM3 GGUF file.");
        }
        if (launchNewChat) {
            mainHandler.post(() -> handlePendingNewChatRequests(getIntent()));
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
        if (!runtime.conversationId().isBlank() && !id.equals(runtime.conversationId())) {
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
                    if (brandTitle != null) brandTitle.setText(historyTitle);
                })
                .setNegativeButton(R.string.keep_this_reply, (dialog, which) -> {
                    historyConversationId = runtime.conversationId();
                    historyTitle = runtime.historyTitle();
                })
                .show();
    }

    private long modelHandle() { return runtime == null ? 0L : runtime.handle(); }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView label(String s, int sp, int color) {
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

    private Button button(String title) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        return b;
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

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        brandTitle = label(historyTitle, 17, ink);
        brandTitle.setTypeface(StillwaterUi.serif(this));
        StillwaterReadable.prepareWrappingLabel(brandTitle);
        titles.addView(brandTitle);
        modePill = label(searchEnabled ? getString(R.string.mode_web_search) : getString(R.string.mode_local), 11, inkMuted);
        modePill.setTypeface(StillwaterUi.sans(this));
        StillwaterReadable.prepareWrappingLabel(modePill);
        titles.addView(modePill);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        Button newChat = button(getString(R.string.new_chat));
        newChat.setId(R.id.chat_new_chat);
        StillwaterUi.quiet(newChat);
        newChat.setTextColor(accent);
        newChat.setOnClickListener(v -> requestNewChat(false));
        newChat.setMinimumWidth(InterfaceSize.minTouchPx(this));
        newChat.setMinimumHeight(InterfaceSize.minTouchPx(this));
        StillwaterReadable.prepareWrappingControl(newChat, dp(44));
        topBar.addView(newChat, new LinearLayout.LayoutParams(-2, -2));
        settingsOpenButton = button("");
        settingsOpenButton.setId(R.id.settings_open);
        settingsOpenButton.setContentDescription(getString(R.string.settings_title));
        settingsOpenButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_still_gear, 0, 0, 0);
        StillwaterUi.quiet(settingsOpenButton);
        settingsOpenButton.setOnClickListener(v -> setSettingsOpen(true));
        LinearLayout.LayoutParams settingsIconParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        settingsOpenButton.setMinimumWidth(InterfaceSize.minTouchPx(this));
        settingsOpenButton.setMinimumHeight(InterfaceSize.minTouchPx(this));
        topBar.addView(settingsOpenButton, settingsIconParams);
        Button overflow = button("⋮");
        overflow.setId(R.id.chat_overflow);
        StillwaterUi.quiet(overflow);
        overflow.setTextColor(inkMuted);
        overflow.setOnClickListener(v -> {
            android.view.ContextThemeWrapper menuContext =
                    new android.view.ContextThemeWrapper(this, R.style.Theme_Stillwater);
            android.widget.PopupMenu menu = new android.widget.PopupMenu(menuContext, v);
            menu.getMenu().add(0, 1, 0, getString(R.string.settings_title));
            menu.getMenu().add(0, 2, 1, "Web search");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) setSettingsOpen(true);
                else if (item.getItemId() == 2) showSearchSettings();
                return true;
            });
            menu.show();
        });
        overflow.setMinimumWidth(InterfaceSize.minTouchPx(this));
        overflow.setMinimumHeight(InterfaceSize.minTouchPx(this));
        topBar.addView(overflow, new LinearLayout.LayoutParams(dp(36), dp(36)));
        topBarView = topBar;
        root.addView(topBar);

        status = label(getString(R.string.starting), 12, inkMuted);
        status.setId(R.id.chat_status);
        status.setPadding(0, dp(4), 0, dp(4));
        StillwaterReadable.prepareWrappingLabel(status);
        root.addView(status);

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
        modelButton = button(getString(R.string.import_model));
        modelButton.setId(R.id.import_model);
        StillwaterUi.quiet(modelButton);
        modelButton.setTextColor(accent);
        mmprojButton = button(getString(R.string.import_projector));
        mmprojButton.setId(R.id.import_projector);
        StillwaterUi.quiet(mmprojButton);
        mmprojButton.setTextColor(accent);
        StillwaterReadable.addFlexibleButtonRow(configuration, this, 48, modelButton, mmprojButton);
        openModelUriButton = button(getString(R.string.open_model_from_storage));
        openModelUriButton.setId(R.id.open_model_uri);
        StillwaterUi.quiet(openModelUriButton);
        openModelUriButton.setTextColor(accent);
        openProjectorUriButton = button(getString(R.string.open_projector_from_storage));
        openProjectorUriButton.setId(R.id.open_projector_uri);
        StillwaterUi.quiet(openProjectorUriButton);
        openProjectorUriButton.setTextColor(accent);
        StillwaterReadable.addFlexibleButtonRow(configuration, this, 48,
                openModelUriButton, openProjectorUriButton);
        // Model manager stays near the top so Unload/Load remain on-screen for Stage 1.
        modelManager = new ModelManagerPanel(this,
                this::reloadSelectedModel, this::unloadSelectedModel, this::showInferenceSettings);
        configuration.addView(modelManager, new LinearLayout.LayoutParams(-1, -2));

        addAppearanceSettingsSection(configuration, ink, inkMuted);

        TextView inferenceHeading = label(getString(R.string.inference_heading), 11, inkMuted);
        inferenceHeading.setTypeface(StillwaterUi.sansMedium(this));
        inferenceHeading.setLetterSpacing(0.12f);
        inferenceHeading.setPadding(0, dp(16), 0, dp(4));
        configuration.addView(inferenceHeading);
        settingsContextField = settingInput(configuration, getString(R.string.context_label),
                getString(R.string.context_hint),
                inferenceSettings.contextTokens, R.id.settings_context, 256, 512, 8192);
        settingsThreadsField = settingInput(configuration, getString(R.string.decode_threads_label),
                getString(R.string.decode_threads_hint),
                inferenceSettings.threads, R.id.settings_threads, 1, 1, 8);
        settingsTokensField = settingInput(configuration, getString(R.string.response_tokens_label),
                getString(R.string.response_tokens_hint),
                inferenceSettings.maxTokens, R.id.settings_tokens, 1, 1, 2048);
        promptLookupBox = new CheckBox(this);
        promptLookupBox.setId(R.id.settings_prompt_lookup);
        promptLookupBox.setText(R.string.prompt_lookup_label);
        StillwaterUi.styleCheck(promptLookupBox);
        promptLookupBox.setChecked(inferenceSettings.promptLookup);
        configuration.addView(promptLookupBox);
        configuration.addView(label(getString(R.string.prompt_lookup_hint), 12, inkMuted));
        settingsInlineError = label("", 13, StillwaterUi.DANGER);
        StillwaterReadable.prepareWrappingLabel(settingsInlineError);
        configuration.addView(settingsInlineError);

        TextView benchHeading = label("BENCHMARK", 11, inkMuted);
        benchHeading.setTypeface(StillwaterUi.sansMedium(this));
        benchHeading.setLetterSpacing(0.12f);
        benchHeading.setPadding(0, dp(16), 0, dp(4));
        configuration.addView(benchHeading);
        configuration.addView(label(
                "Measures prompt-processing (pp) and decode (tg) tok/s on the loaded model, like llama-bench.",
                12, inkMuted));
        Button runBench = button("Run speed test");
        runBench.setId(R.id.settings_bench_run);
        StillwaterUi.quiet(runBench);
        runBench.setOnClickListener(v -> runInlineBenchmark());
        configuration.addView(runBench, new LinearLayout.LayoutParams(-1, dp(48)));
        settingsBenchResult = label("Load a model, then run the speed test. Results wrap and can be copied.", 13, ink);
        settingsBenchResult.setId(R.id.settings_bench_result);
        settingsBenchResult.setSingleLine(false);
        settingsBenchResult.setMaxLines(24);
        settingsBenchResult.setHorizontallyScrolling(false);
        settingsBenchResult.setPadding(0, dp(8), 0, dp(4));
        configuration.addView(settingsBenchResult);
        Button copyBench = button("Copy results");
        copyBench.setId(R.id.settings_bench_copy);
        StillwaterUi.quiet(copyBench);
        copyBench.setOnClickListener(v -> copyBenchmarkResults());
        configuration.addView(copyBench, new LinearLayout.LayoutParams(-1, dp(44)));
        settingsBuildInfo = label(safeNativeBuildInfo(), 12, inkMuted);
        settingsBuildInfo.setId(R.id.settings_build_info);
        settingsBuildInfo.setSingleLine(false);
        settingsBuildInfo.setMaxLines(8);
        settingsBuildInfo.setHorizontallyScrolling(false);
        settingsBuildInfo.setPadding(0, dp(4), 0, dp(8));
        configuration.addView(settingsBuildInfo);

        TextView searchHeading = label(getString(R.string.searxng_heading), 11, inkMuted);
        searchHeading.setTypeface(StillwaterUi.sansMedium(this));
        searchHeading.setLetterSpacing(0.12f);
        searchHeading.setPadding(0, dp(16), 0, dp(4));
        configuration.addView(searchHeading);
        searchEndpointField = new EditText(this);
        searchEndpointField.setId(R.id.search_endpoint);
        searchEndpointField.setSingleLine(true);
        searchEndpointField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        searchEndpointField.setHint("https://your-searxng.example.org/search");
        searchEndpointField.setHintTextColor(inkMuted);
        searchEndpointField.setTextColor(ink);
        searchEndpointField.setTypeface(StillwaterUi.mono(this));
        searchEndpointField.setBackgroundColor(Color.TRANSPARENT);
        StillwaterUi.styleInput(searchEndpointField);
        searchEndpointField.setText(searchEndpoint);
        configuration.addView(searchEndpointField);
        searchEnabledBox = new CheckBox(this);
        searchEnabledBox.setId(R.id.search_enabled);
        searchEnabledBox.setText(R.string.use_web_search);
        StillwaterUi.styleCheck(searchEnabledBox);
        searchEnabledBox.setChecked(searchEnabled);
        configuration.addView(searchEnabledBox);
        searchButton = button(getString(R.string.web_search_off));
        searchButton.setId(R.id.web_search);
        StillwaterUi.quiet(searchButton);
        searchButton.setOnClickListener(v -> showSearchSettings());
        StillwaterReadable.addFlexibleFullWidth(configuration, searchButton, 44);

        addImagesSettingsSection(configuration, ink, inkMuted);

        TextView backgroundHeading = label(getString(R.string.background_heading), 11, inkMuted);
        backgroundHeading.setTypeface(StillwaterUi.sansMedium(this));
        backgroundHeading.setLetterSpacing(0.12f);
        backgroundHeading.setPadding(0, dp(16), 0, dp(4));
        configuration.addView(backgroundHeading);
        keepLoadedBox = new CheckBox(this);
        keepLoadedBox.setId(R.id.settings_keep_loaded);
        keepLoadedBox.setText(R.string.keep_model_loaded);
        StillwaterUi.styleCheck(keepLoadedBox);
        keepLoadedBox.setOnCheckedChangeListener((button, checked) -> saveBackgroundSettings());
        configuration.addView(keepLoadedBox);
        configuration.addView(label(getString(R.string.unload_after_idle), 16, ink));
        idleOffButton = idleChoice(getString(R.string.idle_off), 0, R.id.settings_idle_off);
        idle15Button = idleChoice(getString(R.string.idle_15), 15, R.id.settings_idle_15);
        idle60Button = idleChoice(getString(R.string.idle_60), 60, R.id.settings_idle_60);
        LinearLayout idleRow = StillwaterReadable.addFlexibleButtonRow(configuration, this, 44,
                idleOffButton, idle15Button, idle60Button);
        idleRow.setId(R.id.settings_idle_unload);
        lockPreviewBox = new CheckBox(this);
        lockPreviewBox.setId(R.id.settings_lock_preview);
        lockPreviewBox.setText(R.string.lock_screen_preview);
        StillwaterUi.styleCheck(lockPreviewBox);
        lockPreviewBox.setOnCheckedChangeListener((button, checked) -> saveBackgroundSettings());
        configuration.addView(lockPreviewBox);
        loopbackBox = new CheckBox(this);
        loopbackBox.setId(R.id.settings_loopback);
        loopbackBox.setText(R.string.loopback_prompts);
        StillwaterUi.styleCheck(loopbackBox);
        loopbackBox.setOnCheckedChangeListener((button, checked) -> saveBackgroundSettings());
        configuration.addView(loopbackBox);

        TextView toolsHeading = label(getString(R.string.tools_heading), 11, inkMuted);
        toolsHeading.setTypeface(StillwaterUi.sansMedium(this));
        toolsHeading.setLetterSpacing(0.12f);
        toolsHeading.setPadding(0, dp(16), 0, dp(4));
        configuration.addView(toolsHeading);
        networkToolsBox = new CheckBox(this);
        networkToolsBox.setId(R.id.settings_network_tools);
        networkToolsBox.setText(R.string.network_tools_label);
        StillwaterUi.styleCheck(networkToolsBox);
        networkToolsBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingBackground) return;
            new RuntimePreferences(this).setNetworkToolsEnabled(checked);
        });
        configuration.addView(networkToolsBox);
        TextView networkToolsHelp = label(getString(R.string.network_tools_help), 12, inkMuted);
        StillwaterReadable.prepareWrappingLabel(networkToolsHelp);
        configuration.addView(networkToolsHelp);

        TextView speechHeading = label(getString(R.string.speech_heading), 11, inkMuted);
        speechHeading.setTypeface(StillwaterUi.sansMedium(this));
        speechHeading.setLetterSpacing(0.12f);
        speechHeading.setPadding(0, dp(16), 0, dp(4));
        configuration.addView(speechHeading);
        tinyVocaTtsBox = new CheckBox(this);
        tinyVocaTtsBox.setId(R.id.settings_tiny_voca_tts);
        tinyVocaTtsBox.setText(R.string.tiny_voca_tts_label);
        StillwaterUi.styleCheck(tinyVocaTtsBox);
        tinyVocaTtsBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingBackground) return;
            new RuntimePreferences(this).setTinyVocaEnglishTtsEnabled(checked);
        });
        configuration.addView(tinyVocaTtsBox);
        TextView tinyVocaHelp = label(getString(R.string.tiny_voca_tts_help), 12, inkMuted);
        StillwaterReadable.prepareWrappingLabel(tinyVocaHelp);
        configuration.addView(tinyVocaHelp);
        tinyVocaStatus = label(getString(R.string.tiny_voca_status_missing), 13, ink);
        tinyVocaStatus.setId(R.id.settings_tiny_voca_status);
        configuration.addView(tinyVocaStatus);
        tinyVocaDownloadButton = button(getString(R.string.tiny_voca_download));
        tinyVocaDownloadButton.setId(R.id.settings_tiny_voca_download);
        StillwaterUi.quiet(tinyVocaDownloadButton);
        tinyVocaDownloadButton.setOnClickListener(v -> downloadTinyVocaPack());
        configuration.addView(tinyVocaDownloadButton, new LinearLayout.LayoutParams(-1, dp(44)));
        tinyVocaRemoveButton = button(getString(R.string.tiny_voca_remove));
        tinyVocaRemoveButton.setId(R.id.settings_tiny_voca_remove);
        StillwaterUi.quiet(tinyVocaRemoveButton);
        tinyVocaRemoveButton.setOnClickListener(v -> {
            new TinyVocaPackStore(this).clear();
            paintTinyVocaPackStatus();
        });
        configuration.addView(tinyVocaRemoveButton, new LinearLayout.LayoutParams(-1, dp(44)));

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
            status.setText(getString(R.string.settings_crash_log_exported));
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
        saveInference.setOnClickListener(v -> saveInlineInferenceSettings());
        StillwaterReadable.addFlexibleFullWidth(settingsActions, saveInference, 52);
        Button saveSearch = button(getString(R.string.save_search));
        saveSearch.setId(R.id.search_save);
        StillwaterUi.quiet(saveSearch);
        saveSearch.setOnClickListener(v -> saveInlineSearchSettings());
        StillwaterReadable.prepareWrappingControl(saveSearch, dp(44));
        settingsActions.addView(saveSearch, new LinearLayout.LayoutParams(-2, -2));
        settingsRoot.addView(settingsActions, new LinearLayout.LayoutParams(-1, -2));

        settingsHost = settingsRoot;
        settingsHost.setVisibility(View.GONE);
        root.addView(settingsHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        // --- Chat shell (primary viewport) ---
        LinearLayout chatRoot = new LinearLayout(this);
        chatRoot.setOrientation(LinearLayout.VERTICAL);
        chatPanel = new ChatPanel(this, this::send, this::stopActiveWork, () -> {
            HistoryMediaCleanup.deleteAttachment(this,pendingImagePath);
            HistoryMediaCleanup.deleteAttachment(this,pendingAudioPath);
            pendingImagePath = "";
            pendingAudioPath = "";
            lastAssistantReply = "";
            if (playbackActive()) playbackController.stop();
            chatPanel.clearImageAttachment();
            runtime.clearConversation();
            lastAssistantReply = "";
        }, turnId -> {
            if (busy || modelHandle() == 0L || !settingsLoaded) return;
            runtime.noteSubmission("retry", inferenceSettings.maxTokens);
            if (runtime.retryInterrupted(turnId)) {
                session = runtime.session();
                pendingImagePath = "";
                pendingAudioPath = "";
                chatPanel.clearImageAttachment();
                busy = true;
                updateControls();
            }
        });
        chatRoot.addView(chatPanel, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout mediaHost = new LinearLayout(this);
        mediaHost.setOrientation(LinearLayout.VERTICAL);
        mediaHost.setBackgroundColor(surface);
        mediaHost.setPadding(0, dp(2), 0, dp(2));
        photoButton = mediaButton(getString(R.string.photo), R.drawable.ic_still_photo);
        photoButton.setId(R.id.chat_photo);
        cameraButton = mediaButton(getString(R.string.camera), R.drawable.ic_still_camera);
        cameraButton.setId(R.id.chat_camera);
        recordButton = mediaButton(getString(R.string.voice_input), R.drawable.ic_still_mic);
        recordButton.setId(R.id.voice_record);
        speakButton = mediaButton(getString(R.string.speak_reply), R.drawable.ic_still_speak);
        speakButton.setId(R.id.voice_speak);
        // Two rows of two when stacked (narrow / large fonts); one weighted row otherwise.
        if (StillwaterReadable.shouldStack(this)) {
            StillwaterReadable.addFlexibleButtonRow(mediaHost, this, 46, photoButton, cameraButton);
            StillwaterReadable.addFlexibleButtonRow(mediaHost, this, 46,
                    new float[]{1f, 1.2f}, recordButton, speakButton);
        } else {
            StillwaterReadable.addFlexibleButtonRow(mediaHost, this, 46,
                    new float[]{1f, 1f, 1.2f, 1.2f},
                    photoButton, cameraButton, recordButton, speakButton);
        }
        chatRoot.addView(mediaHost);
        chatShell = chatRoot;
        root.addView(chatShell, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);

        modelButton.setOnClickListener(v -> pickModel());
        mmprojButton.setOnClickListener(v -> pickMmproj());
        openModelUriButton.setOnClickListener(v -> pickExternalModel(OPEN_MODEL_URI));
        openProjectorUriButton.setOnClickListener(v -> pickExternalModel(OPEN_MMPROJ_URI));
        searchButton.setOnClickListener(v -> showSearchSettings());
        photoButton.setOnClickListener(v -> pickPhoto());
        cameraButton.setOnClickListener(v -> takePhoto());
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
        if (chatShell != null) chatShell.setVisibility(open ? View.GONE : View.VISIBLE);
        if (settingsOpenButton != null) settingsOpenButton.setVisibility(open ? View.GONE : View.VISIBLE);
        if (topBarView != null) topBarView.setVisibility(open ? View.GONE : View.VISIBLE);
        if (open) syncInlineSettingsFields();
    }

    private void updateControls() {
        searchButton.setText(searchEnabled ? getString(R.string.web_search_on) : getString(R.string.web_search_off));
        if (modePill != null) {
            modePill.setText(searchEnabled
                    ? getString(R.string.mode_web_search)
                    : getString(R.string.mode_local));
        }
        searchButton.setEnabled(!busy);
        modelButton.setEnabled(!busy);
        mmprojButton.setEnabled(!busy);
        if (openModelUriButton != null) openModelUriButton.setEnabled(!busy);
        if (openProjectorUriButton != null) openProjectorUriButton.setEnabled(!busy);
        photoButton.setEnabled(!busy);
        cameraButton.setEnabled(!busy);
        RecordingController.State capture = recordingController.state();
        boolean voiceMic = runtime.voice().isEnabled();
        recordButton.setText(capture == RecordingController.State.STOPPING ? getString(R.string.finishing)
                : voiceMic && runtime.voice().isListening() ? getString(R.string.voice_listening)
                : recordingActive() ? getString(R.string.stop_recording) : getString(R.string.voice_input));
        recordButton.setContentDescription(voiceMic ? getString(R.string.voice_record_talkback) : null);
        recordButton.setEnabled(capture != RecordingController.State.STOPPING && (!busy || recordingActive() || voiceMic));
        PlaybackController.State playback = playbackController.state();
        speakButton.setText(playback == PlaybackController.State.STOPPING
                ? getString(R.string.stopping_speech)
                : (playbackActive() || speaking) ? getString(R.string.stop_speech) : getString(R.string.speak_reply));
        speakButton.setEnabled((!busy || playbackActive() || speaking) && (playbackActive() || speaking
                || (modelHandle() != 0L && settingsLoaded && !lastAssistantReply.isBlank())));
        chatPanel.updateControls(!busy && modelHandle() != 0L && settingsLoaded,
                generating || speaking || recordingActive() || playbackActive() || importCancellation != null,
                !busy || generating);

        if (modelManager != null) {
            modelManager.showSettings(settingsLoaded ? inferenceSettings.summary() : "Loading inference settings...");
            String model = pocketPreferences().getString("model", "");
            String projector = pocketPreferences().getString("mmproj", "");
            String description = status.getText().toString();
            modelManager.updateSelection(
                    model.isEmpty() ? "" : pocketPreferences().getString("model_name", new File(model).getName()),
                    projector.isEmpty() ? "" : pocketPreferences().getString("projector_name", new File(projector).getName()),
                    busy, modelHandle() != 0, description.startsWith("Model error") || description.startsWith("Import failed"));
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
        status.setText("Unloading model...");
        updateControls();
        runtime.unload("user", () -> {
            if (destroyed) return;
            busy = false;
            status.setText("Model unloaded. Selected files remain available for reload.");
            updateControls();
        });
    }

    private Button mediaButton(String title, int icon) {
        Button button = button(title);
        StillwaterReadable.setTextSizeSp(button, 12);
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(4));
        StillwaterUi.quiet(button);
        StillwaterReadable.prepareWrappingControl(button, dp(46));
        return button;
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
        if (settingsContextField != null && settingsLoaded) {
            settingsContextField.setText(Integer.toString(inferenceSettings.contextTokens));
            settingsThreadsField.setText(Integer.toString(inferenceSettings.threads));
            settingsTokensField.setText(Integer.toString(inferenceSettings.maxTokens));
            if (promptLookupBox != null) promptLookupBox.setChecked(inferenceSettings.promptLookup);
        }
        if (searchEndpointField != null) {
            searchEndpointField.setText(searchEndpoint);
            searchEnabledBox.setChecked(searchEnabled);
        }
        if (networkToolsBox != null) {
            bindingBackground = true;
            networkToolsBox.setChecked(new RuntimePreferences(this).networkToolsEnabled());
            RuntimePreferences prefs = new RuntimePreferences(this);
            if (tinyVocaTtsBox != null) tinyVocaTtsBox.setChecked(prefs.tinyVocaEnglishTtsEnabled());
            paintTinyVocaPackStatus();
            bindingBackground = false;
        }
    }

    private void saveInlineInferenceSettings() {
        if (busy || !settingsLoaded || settingsContextField == null) return;
        if (settingsInlineError != null) settingsInlineError.setText("");
        try {
            InferenceSettings next = new InferenceSettings(
                    Integer.parseInt(settingsContextField.getText().toString()),
                    Integer.parseInt(settingsThreadsField.getText().toString()),
                    Integer.parseInt(settingsTokensField.getText().toString()),
                    promptLookupBox == null || promptLookupBox.isChecked());
            ImageSettings nextImages = new ImageSettings(
                    imagePrepareBox == null || imagePrepareBox.isChecked(),
                    imageSettings.maxSide,
                    imageCropBox != null && imageCropBox.isChecked(),
                    readImageQualityField());
            settingsStore.save(next, () -> {
                if (destroyed) return;
                inferenceSettings = next;
                inferenceEngine.configure(next);
                // Quiet image persist so it cannot race and overwrite the inference summary
                // that InferenceSettingsE2eTest matches exactly on chat_status.
                persistImageSettings(nextImages, false);
                updateControls();
                status.setText(next.summary());
            }, failure -> {
                if (destroyed) return;
                if (settingsInlineError != null)
                    settingsInlineError.setText("Settings were not saved: " + failure);
            });
        } catch (NumberFormatException invalid) {
            if (settingsInlineError != null)
                settingsInlineError.setText("Enter whole numbers for all settings.");
        } catch (IllegalArgumentException invalid) {
            if (settingsInlineError != null) settingsInlineError.setText(invalid.getMessage());
            status.setText(invalid.getMessage());
        }
    }

    private static String safeNativeBuildInfo() {
        try {
            return NativeBridge.nativeBuildInfo();
        } catch (UnsatisfiedLinkError | Exception ignored) {
            return "Native build info unavailable until the library loads.";
        }
    }

    private void runInlineBenchmark() {
        if (destroyed || benchmarkRunning || busy || generating) return;
        long handle = runtime != null ? runtime.handle() : 0L;
        if (handle == 0L) {
            if (settingsBenchResult != null)
                settingsBenchResult.setText("Load a GGUF model first, then run the speed test.");
            return;
        }
        InferenceSettings limits = inferenceSettings;
        try {
            if (settingsLoaded && settingsContextField != null) {
                limits = new InferenceSettings(
                        Integer.parseInt(settingsContextField.getText().toString()),
                        Integer.parseInt(settingsThreadsField.getText().toString()),
                        Integer.parseInt(settingsTokensField.getText().toString()),
                        promptLookupBox == null || promptLookupBox.isChecked());
            }
        } catch (RuntimeException ignored) {
            limits = inferenceSettings;
        }
        final InferenceSettings benchLimits = limits;
        benchmarkRunning = true;
        if (settingsBenchResult != null)
            settingsBenchResult.setText("Running prompt-processing and decode benchmark…");
        updateControls();
        worker.execute(() -> {
            String report;
            try {
                report = NativeBridge.runBenchmark(handle, benchLimits);
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                report = "Benchmark failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String text = report == null ? "Benchmark returned no result." : report;
            mainHandler.post(() -> {
                benchmarkRunning = false;
                if (destroyed) return;
                if (settingsBenchResult != null) settingsBenchResult.setText(text);
                updateControls();
            });
        });
    }

    private void copyBenchmarkResults() {
        if (settingsBenchResult == null) return;
        CharSequence text = settingsBenchResult.getText();
        if (text == null || text.length() == 0) return;
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Hanami benchmark", text));
        if (status != null) status.setText("Benchmark results copied.");
    }

    private void saveInlineSearchSettings() {
        if (searchEndpointField == null) return;
        String value = searchEndpointField.getText().toString().trim();
        if (!SearchEndpointPolicy.accepts(value)) {
            status.setText("Use an HTTPS SearXNG search endpoint.");
            return;
        }
        searchEndpoint = value;
        searchEnabled = SearchEndpointPolicy.enabled(searchEnabledBox.isChecked(), value);
        pocketPreferences().edit()
                .putString("search_endpoint", searchEndpoint)
                .putBoolean("search_enabled", searchEnabled).apply();
        chatPanel.setIdleMode(searchEnabled);
        updateControls();
    }

    private void showInferenceSettings() {
        if (busy || !settingsLoaded) return;
        setSettingsOpen(true);
        syncInlineSettingsFields();
        if (settingsContextField != null) settingsContextField.requestFocus();
    }

    private void showSearchSettings() {
        setSettingsOpen(true);
        syncInlineSettingsFields();
        if (searchEndpointField != null) searchEndpointField.requestFocus();
    }

    private void addAppearanceSettingsSection(LinearLayout configuration, int ink, int inkMuted) {
        LinearLayout section = new LinearLayout(this);
        section.setId(R.id.settings_appearance);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(getString(R.string.appearance_heading), 11, inkMuted);
        heading.setTypeface(StillwaterUi.sansMedium(this));
        heading.setLetterSpacing(0.12f);
        heading.setPadding(0, dp(16), 0, dp(4));
        section.addView(heading);
        TextView title = label(getString(R.string.interface_size_label), 14, ink);
        StillwaterReadable.prepareWrappingLabel(title);
        section.addView(title);
        TextView help = label(getString(R.string.interface_size_help), 12, inkMuted);
        StillwaterReadable.prepareWrappingLabel(help);
        section.addView(help);

        interfaceCompactButton = interfacePreset(getString(R.string.interface_size_compact),
                InterfaceSize.COMPACT, R.id.settings_interface_compact);
        interfaceDefaultButton = interfacePreset(getString(R.string.interface_size_default),
                InterfaceSize.DEFAULT, R.id.settings_interface_default);
        interfaceLargeButton = interfacePreset(getString(R.string.interface_size_large),
                InterfaceSize.LARGE, R.id.settings_interface_large);
        LinearLayout presets = StillwaterReadable.addFlexibleButtonRow(section, this, 44,
                interfaceCompactButton, interfaceDefaultButton, interfaceLargeButton);
        presets.setId(R.id.settings_interface_size);

        interfaceSizeValue = label("", 12, inkMuted);
        interfaceSizeValue.setId(R.id.settings_interface_value);
        StillwaterReadable.prepareWrappingLabel(interfaceSizeValue);
        interfaceSizeValue.setPadding(0, dp(8), 0, dp(2));
        section.addView(interfaceSizeValue);

        interfaceSizeSlider = new SeekBar(this);
        interfaceSizeSlider.setId(R.id.settings_interface_slider);
        interfaceSizeSlider.setMax(50); // 80..130 in 1% steps
        interfaceSizeSlider.setMinimumHeight(InterfaceSize.minTouchPx(this));
        interfaceSizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = InterfaceSize.fromPercent(80 + progress);
                interfaceSizeValue.setText(getString(R.string.interface_size_percent, InterfaceSize.percent(scale)));
                if (!fromUser || bindingInterfaceSize) return;
                highlightInterfaceSize(scale);
                // TalkBack / D-pad change progress without start/stop tracking. Debounce those
                // writes so finger drags still apply only on stop.
                if (!interfaceSliderTracking) {
                    pendingInterfaceSliderScale = scale;
                    mainHandler.removeCallbacks(applyInterfaceSlider);
                    mainHandler.postDelayed(applyInterfaceSlider, 350);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                interfaceSliderTracking = true;
                mainHandler.removeCallbacks(applyInterfaceSlider);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                interfaceSliderTracking = false;
                if (bindingInterfaceSize) return;
                applyInterfaceSize(InterfaceSize.fromPercent(80 + seekBar.getProgress()));
            }
        });
        section.addView(interfaceSizeSlider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        configuration.addView(section);
        syncInterfaceSizeControls();
    }

    private Button interfacePreset(String title, float scale, int id) {
        Button button = button(title);
        button.setId(id);
        StillwaterUi.quiet(button);
        button.setOnClickListener(v -> {
            if (bindingInterfaceSize) return;
            applyInterfaceSize(scale);
        });
        return button;
    }

    private void applyInterfaceSize(float scale) {
        float next = InterfaceSize.clamp(scale);
        if (Math.abs(next - InterfaceSize.read(this)) < 0.001f) {
            syncInterfaceSizeControls();
            return;
        }
        InterfaceSize.write(this, next);
        if (getIntent() != null) getIntent().putExtra(EXTRA_OPEN_SETTINGS, true);
        settingsOpen = true;
        recreate();
    }

    private void syncInterfaceSizeControls() {
        if (interfaceSizeSlider == null) return;
        bindingInterfaceSize = true;
        float scale = InterfaceSize.read(this);
        interfaceSizeSlider.setProgress(InterfaceSize.percent(scale) - 80);
        interfaceSizeValue.setText(getString(R.string.interface_size_percent, InterfaceSize.percent(scale)));
        highlightInterfaceSize(scale);
        bindingInterfaceSize = false;
    }

    private void highlightInterfaceSize(float scale) {
        styleInterfacePreset(interfaceCompactButton, InterfaceSize.isCompact(scale));
        styleInterfacePreset(interfaceDefaultButton, InterfaceSize.isDefault(scale));
        styleInterfacePreset(interfaceLargeButton, InterfaceSize.isLarge(scale));
    }

    private void styleInterfacePreset(Button button, boolean selected) {
        if (button == null) return;
        if (selected) StillwaterUi.primary(button);
        else StillwaterUi.quiet(button);
    }

    private void addImagesSettingsSection(LinearLayout configuration, int ink, int inkMuted) {
        LinearLayout section = new LinearLayout(this);
        section.setId(R.id.settings_images);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(getString(R.string.settings_images_heading).toUpperCase(java.util.Locale.US), 11, inkMuted);
        heading.setTypeface(StillwaterUi.sansMedium(this));
        heading.setLetterSpacing(0.12f);
        heading.setPadding(0, dp(16), 0, dp(4));
        section.addView(heading);

        imagePrepareBox = new CheckBox(this);
        imagePrepareBox.setId(R.id.settings_image_prepare);
        imagePrepareBox.setText(R.string.settings_image_prepare);
        StillwaterUi.styleCheck(imagePrepareBox);
        imagePrepareBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingImageSettings) return;
            persistImageSettings(new ImageSettings(checked, imageSettings.maxSide,
                    imageSettings.cropToSquare, readImageQualityField()), true);
        });
        section.addView(imagePrepareBox);
        section.addView(label(getString(R.string.settings_image_prepare_help), 12, inkMuted));

        TextView maxTitle = label(getString(R.string.settings_image_max_size), 16, ink);
        maxTitle.setPadding(0, dp(10), 0, 0);
        section.addView(maxTitle);
        section.addView(label(getString(R.string.settings_image_max_help), 12, inkMuted));

        imageMax384Button = imageMaxChoice(ImageSettings.MaxSide.SIDE_384, R.id.settings_image_max_384);
        imageMax512Button = imageMaxChoice(ImageSettings.MaxSide.SIDE_512, R.id.settings_image_max_512);
        imageMax768Button = imageMaxChoice(ImageSettings.MaxSide.SIDE_768, R.id.settings_image_max_768);
        LinearLayout maxRow1 = StillwaterReadable.addFlexibleButtonRow(section, this, 44,
                imageMax384Button, imageMax512Button, imageMax768Button);
        maxRow1.setId(R.id.settings_image_max_size);

        imageMax1024Button = imageMaxChoice(ImageSettings.MaxSide.SIDE_1024, R.id.settings_image_max_1024);
        imageMaxOriginalButton = imageMaxChoice(ImageSettings.MaxSide.ORIGINAL, R.id.settings_image_max_original);
        StillwaterReadable.addFlexibleButtonRow(section, this, 44,
                imageMax1024Button, imageMaxOriginalButton);

        imageCropBox = new CheckBox(this);
        imageCropBox.setId(R.id.settings_image_crop);
        imageCropBox.setText(R.string.settings_image_crop);
        StillwaterUi.styleCheck(imageCropBox);
        imageCropBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingImageSettings) return;
            persistImageSettings(new ImageSettings(imageSettings.enabled, imageSettings.maxSide,
                    checked, readImageQualityField()), true);
        });
        section.addView(imageCropBox);
        section.addView(label(getString(R.string.settings_image_crop_help), 12, inkMuted));

        imageQualityField = settingInput(section, getString(R.string.settings_image_quality),
                getString(R.string.settings_image_quality_help),
                imageSettings.jpegQuality, R.id.settings_image_quality, 5, 40, 95);
        imageQualityField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus || bindingImageSettings) return;
            try {
                int quality = Integer.parseInt(imageQualityField.getText().toString().trim());
                persistImageSettings(new ImageSettings(imageSettings.enabled, imageSettings.maxSide,
                        imageSettings.cropToSquare, quality), true);
            } catch (RuntimeException invalid) {
                syncImageSettingsFields();
                if (status != null) status.setText(invalid.getMessage());
            }
        });
        configuration.addView(section);
        syncImageSettingsFields();
    }

    private Button imageMaxChoice(ImageSettings.MaxSide side, int id) {
        Button button = button(side.label);
        button.setId(id);
        StillwaterUi.quiet(button);
        button.setOnClickListener(v -> {
            if (bindingImageSettings) return;
            persistImageSettings(new ImageSettings(imageSettings.enabled, side,
                    imageSettings.cropToSquare, readImageQualityField()), true);
        });
        return button;
    }

    private int readImageQualityField() {
        if (imageQualityField == null) return imageSettings.jpegQuality;
        try {
            return Integer.parseInt(imageQualityField.getText().toString().trim());
        } catch (NumberFormatException ignored) {
            return imageSettings.jpegQuality;
        }
    }

    private void syncImageSettingsFields() {
        if (imagePrepareBox == null) return;
        bindingImageSettings = true;
        imagePrepareBox.setChecked(imageSettings.enabled);
        if (imageCropBox != null) imageCropBox.setChecked(imageSettings.cropToSquare);
        if (imageQualityField != null)
            imageQualityField.setText(Integer.toString(imageSettings.jpegQuality));
        highlightImageMax(imageSettings.maxSide);
        bindingImageSettings = false;
    }

    private void highlightImageMax(ImageSettings.MaxSide selected) {
        styleImageMax(imageMax384Button, selected == ImageSettings.MaxSide.SIDE_384);
        styleImageMax(imageMax512Button, selected == ImageSettings.MaxSide.SIDE_512);
        styleImageMax(imageMax768Button, selected == ImageSettings.MaxSide.SIDE_768);
        styleImageMax(imageMax1024Button, selected == ImageSettings.MaxSide.SIDE_1024);
        styleImageMax(imageMaxOriginalButton, selected == ImageSettings.MaxSide.ORIGINAL);
    }

    private void styleImageMax(Button button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? StillwaterUi.CANDLE : StillwaterUi.SOFT);
        button.setAlpha(selected ? 1f : 0.75f);
    }

    private void persistImageSettings(ImageSettings next, boolean announce) {
        if (imageSettingsStore == null) return;
        try {
            ImageSettings validated = new ImageSettings(next.enabled, next.maxSide,
                    next.cropToSquare, next.jpegQuality);
            imageSettingsStore.save(validated, () -> {
                if (destroyed) return;
                imageSettings = validated;
                imageSettingsLoaded = true;
                syncImageSettingsFields();
                if (announce && status != null) status.setText(validated.summary());
            }, failure -> {
                if (destroyed) return;
                if (status != null) status.setText("Image settings were not saved: " + failure);
                syncImageSettingsFields();
            });
        } catch (IllegalArgumentException invalid) {
            if (status != null) status.setText(invalid.getMessage());
            syncImageSettingsFields();
        }
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

    private void copySelectedPhoto(Uri uri) {
        busy = true;
        updateControls();
        status.setText(getString(R.string.importing_photo));
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
                    if (!destroyed) status.setText(getString(R.string.preparing_photo));
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
                    status.setText("Photo import failed: " + e.getMessage());
                    updateControls();
                });
            }
        });
    }

    private void prepareCapturedPhoto(String path) {
        busy = true;
        updateControls();
        status.setText(getString(R.string.preparing_photo));
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
                    status.setText("Photo import failed: " + e.getMessage());
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
            status.setText("photo".equals(kind)
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
        status.setText("Photo removed. Add a question and press Send.");
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
        recordingController.start();
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
                status.setText("Camera access is needed to take a photo.");
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
            else status.setText("Microphone access is needed for voice input.");
            if (!granted) pendingAssistantVoiceSession = false;
        }
    }

    @Override protected void onStart() { super.onStart(); foreground = true; }

    @Override protected void onStop() {
        foreground = false;
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

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_PHOTO && result == RESULT_OK && data != null && data.getData() != null) {
            copySelectedPhoto(data.getData());
            return;
        }
        if (code == TAKE_PHOTO && result == RESULT_OK && data != null) {
            String path = data.getStringExtra(CameraCaptureActivity.EXTRA_PHOTO_PATH);
            if (path != null && new File(path).isFile()) prepareCapturedPhoto(path);
            else status.setText("The camera did not return a photo.");
            return;
        }
        if (code == TAKE_PHOTO && result != RESULT_OK) {
            status.setText("Camera closed without taking a photo.");
            return;
        }
        if ((code == OPEN_MODEL_URI || code == OPEN_MMPROJ_URI)
                && result == RESULT_OK && data != null && data.getData() != null) {
            openExternalDocument(data.getData(), code == OPEN_MMPROJ_URI);
            return;
        }
        if ((code != PICK_MODEL && code != PICK_MMPROJ) || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        busy = true;
        updateControls();
        status.setText(code == PICK_MODEL ? getString(R.string.importing_model) : "Importing projector into private storage...");
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
                            if (!destroyed && !cancellation.get()) status.setText("Importing GGUF: " + (bytes / 1024 / 1024) + " MiB. Stop to cancel.");
                        }));
                if (cancellation.get()) throw new IOException("Import cancelled");
                String model = isProjector ? previousModel : imported.getAbsolutePath();
                String projector = isProjector ? imported.getAbsolutePath() : "";
                if (runtime.loadBlocking(model, projector, statusText -> runOnUiThread(() -> {
                    if (!destroyed) status.setText(statusText);
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
                    if (!destroyed) status.setText("Import failed: " + e.getMessage());
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
        status.setText(getString(R.string.opening_external_model));
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
                store.remember(uri, displayName, projector);
                ExternalModelStore.Opened opened = store.open(uri);
                String modelPath;
                String projectorPath;
                if (projector) {
                    modelPath = resolveLoadPath(previousModel, store);
                    projectorPath = opened.path;
                } else {
                    modelPath = opened.path;
                    projectorPath = "";
                }
                GgufSelection.validate(new File(modelPath),
                        projectorPath.isEmpty() ? null : new File(projectorPath));
                if (runtime.loadBlocking(modelPath, projectorPath, statusText -> runOnUiThread(() -> {
                    if (!destroyed) status.setText(statusText);
                }))) {
                    pocketPreferences().edit()
                            .putString("model", projector ? previousModel : uri.toString())
                            .putString("mmproj", projector ? uri.toString() : "")
                            .putString(projector ? "projector_name" : "model_name", displayName)
                            .apply();
                    if (!projector) pocketPreferences().edit().remove("projector_name").apply();
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (!destroyed) status.setText("Could not open storage model: " + e.getMessage());
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

    private void loadSelectedModel(String model, String projector) {
        if (isContentUri(model) || isContentUri(projector)) {
            busy = true;
            updateControls();
            status.setText(getString(R.string.opening_external_model));
            worker.execute(() -> {
                try {
                    ExternalModelStore store = ((Lfm3PocketApplication) getApplication()).externalModels();
                    String modelPath = resolveLoadPath(model, store);
                    String projectorPath = projector == null || projector.isEmpty()
                            ? "" : resolveLoadPath(projector, store);
                    boolean ok = runtime.loadBlocking(modelPath, projectorPath, statusText -> runOnUiThread(() -> {
                        if (!destroyed) status.setText(statusText);
                    }));
                    if (!ok && !destroyed) {
                        runOnUiThread(() -> status.setText("Could not load the selected storage model."));
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (!destroyed) status.setText("Could not open storage model: " + e.getMessage());
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
        status.setText(getString(R.string.loading_model));
        runtime.load(path, projector, statusText -> {
            if (destroyed) return;
            busy = false;
            status.setText(statusText);
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
                status.setText(getString(R.string.assistant_voice_needs_model));
                updateControls();
            }
            return;
        }
        if (!runtime.voice().isEnabled()) {
            pendingAssistantVoiceSession = false;
            status.setText(runtime.voice().disabledReason());
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
        lastAssistantReply = "";
        if (playbackActive()) playbackController.stop();
        if (chatPanel != null) chatPanel.clearImageAttachment();
        String id = java.util.UUID.randomUUID().toString();
        historyConversationId = id;
        historyTitle = getString(R.string.new_conversation_title);
        if (brandTitle != null) brandTitle.setText(historyTitle);
        runtime.setConversation(id, historyTitle);
        runtime.clearConversation();
        setSettingsOpen(false);
        status.setText(fromSystemAssist
                ? getString(R.string.fresh_chat_from_assist)
                : getString(R.string.new_chat_ready));
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
                status.setText("Could not open the default-assistant settings.");
            }
        }
    }

    private void stopActiveWork() {
        if (recordingActive()) recordingController.stop();
        else if (playbackActive()) playbackController.stop();
        else if (speaking) runtime.speak(lastAssistantReply);
        else if (importCancellation != null) {
            importCancellation.set(true);
            status.setText("Cancelling import...");
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
        if (keepLoadedBox == null || runtime == null) return;
        RuntimePreferences prefs = new RuntimePreferences(this);
        bindingBackground = true;
        keepLoadedBox.setChecked(prefs.keepLoaded());
        lockPreviewBox.setChecked(prefs.lockPreview());
        if (loopbackBox != null) loopbackBox.setChecked(prefs.loopbackEnabled());
        if (networkToolsBox != null) networkToolsBox.setChecked(prefs.networkToolsEnabled());
        if (tinyVocaTtsBox != null) tinyVocaTtsBox.setChecked(prefs.tinyVocaEnglishTtsEnabled());
        paintTinyVocaPackStatus();
        paintIdle(prefs.idleMinutes());
        bindingBackground = false;
    }

    private void paintTinyVocaPackStatus() {
        if (tinyVocaStatus == null) return;
        boolean ready = new TinyVocaPackStore(this).isReady();
        tinyVocaStatus.setText(ready ? getString(R.string.tiny_voca_status_ready)
                : getString(R.string.tiny_voca_status_missing));
        if (tinyVocaDownloadButton != null) tinyVocaDownloadButton.setEnabled(!ready);
        if (tinyVocaRemoveButton != null) tinyVocaRemoveButton.setEnabled(ready);
    }

    private void downloadTinyVocaPack() {
        if (tinyVocaDownloadButton != null) tinyVocaDownloadButton.setEnabled(false);
        new Thread(() -> new TinyVocaPackDownloader(new TinyVocaPackStore(this)).download(
                new TinyVocaPackDownloader.Progress() {
                    @Override public void onFile(String path, long written, long total) {
                        runOnUiThread(() -> {
                            if (tinyVocaStatus != null) {
                                tinyVocaStatus.setText(getString(R.string.tiny_voca_downloading, path));
                            }
                        });
                    }
                    @Override public void onDone() {
                        TinyVocaPackStore pack = new TinyVocaPackStore(MainActivity.this);
                        new Thread(() -> TinyVocaPhonemizerBridge.ensureLoaded(pack.phonemizerDir()),
                                "tiny-voca-phonemizer-warmup").start();
                        runOnUiThread(() -> {
                            paintTinyVocaPackStatus();
                            if (status != null) status.setText(getString(R.string.tiny_voca_status_ready));
                        });
                    }
                    @Override public void onError(String message) {
                        runOnUiThread(() -> {
                            paintTinyVocaPackStatus();
                            if (status != null) status.setText(message);
                        });
                    }
                }), "tiny-voca-download").start();
    }

    private boolean bindingBackground;

    private void saveBackgroundSettings() {
        if (bindingBackground || keepLoadedBox == null) return;
        int idle = idle15Button.isSelected() ? 15 : idle60Button.isSelected() ? 60 : 0;
        boolean loopback = loopbackBox != null && loopbackBox.isChecked();
        runtime.updateBackgroundSettings(keepLoadedBox.isChecked(), idle, lockPreviewBox.isChecked(), loopback);
        paintIdle(idle);
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
                status.setText("Photo import failed: " + failed.getMessage());
            }
        }
    }

    private Button idleChoice(String label, int minutes, int id) {
        Button choice = button(label);
        choice.setId(id);
        StillwaterUi.quiet(choice);
        choice.setOnClickListener(v -> {
            idleOffButton.setSelected(minutes == 0);
            idle15Button.setSelected(minutes == 15);
            idle60Button.setSelected(minutes == 60);
            saveBackgroundSettings();
        });
        return choice;
    }

    private void paintIdle(int minutes) {
        styleIdle(idleOffButton, minutes == 0);
        styleIdle(idle15Button, minutes == 15);
        styleIdle(idle60Button, minutes == 60);
    }

    private void styleIdle(Button choice, boolean selected) {
        choice.setSelected(selected);
        choice.setTextColor(selected ? StillwaterUi.CANDLE : StillwaterUi.INK);
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
            status.setText("Phone is too warm for a new reply.");
            return;
        }
        runtime.noteSubmission(text, inferenceSettings.maxTokens);
        session = runtime.session();
        List<ChatSession.Attachment> attachments=new ArrayList<>();
        if(!pendingImagePath.isEmpty())attachments.add(new ChatSession.Attachment(
                pendingImagePath,ChatSession.AttachmentKind.PHOTO));
        if(!pendingAudioPath.isEmpty())attachments.add(new ChatSession.Attachment(
                pendingAudioPath,ChatSession.AttachmentKind.VOICE_RECORDING));
        if (session.submit(text, attachments, searchEnabled, searchEndpoint, inferenceSettings.maxTokens)) {
            pendingImagePath = "";
            pendingAudioPath = "";
            chatPanel.clearImageAttachment();
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

    @Override protected void onDestroy() {
        destroyed = true;
        if (runtime != null) runtime.detach(viewer);
        HistoryMediaCleanup.deleteAttachment(this,pendingImagePath);
        HistoryMediaCleanup.deleteAttachment(this,pendingAudioPath);
        pendingImagePath = "";
        pendingAudioPath = "";
        if (settingsStore != null) settingsStore.close();
        if (imageSettingsStore != null) imageSettingsStore.close();
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

