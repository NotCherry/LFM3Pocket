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
    private static final int PICK_MODEL = 42;
    private static final int PICK_MMPROJ = 43;
    private static final int PICK_PHOTO = 44;
    private static final int TAKE_PHOTO = 45;
    private static final int REQUEST_AUDIO = 46;
    private static final int REQUEST_CAMERA = 47;


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
    private PlaybackController playbackController;
    private CheckBox keepLoadedBox;
    private Button idleOffButton;
    private Button idle15Button;
    private Button idle60Button;
    private CheckBox lockPreviewBox;
    private CheckBox loopbackBox;
    private boolean foreground;
    private boolean microphoneRequested;
    private boolean cameraRequested;
    private String pendingImagePath = "";
    private String pendingAudioPath = "";
    private String lastAssistantReply = "";

    private TextView status;
    private TextView brandTitle;
    private TextView modePill;
    private View topBarView;
    private ModelManagerPanel modelManager;
    private View settingsHost;
    private View chatShell;
    private InferenceSettings inferenceSettings=InferenceSettings.DEFAULT;
    private InferenceSettingsStore settingsStore;
    private boolean settingsLoaded;
    private boolean settingsOpen;
    private Button modelButton, mmprojButton;
    private Button searchButton, photoButton, cameraButton, recordButton, speakButton;
    private Button settingsOpenButton;
    private EditText settingsContextField, settingsThreadsField, settingsTokensField;
    private TextView settingsInlineError;
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
        buildUi();
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
        String previous = pocketPreferences().getString("model", "");
        mmprojPath = pocketPreferences().getString("mmproj", "");
        searchEndpoint = pocketPreferences().getString("search_endpoint", "");
        searchEnabled = pocketPreferences().getBoolean("search_enabled", false);
        chatPanel.setIdleMode(searchEnabled);
        syncInlineSettingsFields();
        syncBackgroundControls();
        if (runtime.handle() != 0L && previous.equals(runtime.loadedPath())) {
            if (!runtime.isGenerating()) status.setText(mmprojPath == null || mmprojPath.isEmpty()
                    ? "Text model ready \u0007 offline" : "Model and projector loaded • offline");
        } else if (!previous.isEmpty() && new File(previous).isFile()) {
            loadModelInBackground(previous, mmprojPath);
        } else if (runtime.handle() == 0L) {
            status.setText("No model loaded. Select an LFM3 GGUF file.");
        }
        if (pendingSwitchId != null) confirmSwitchConversation();
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
        historyTitle = title == null || title.isBlank() ? "New conversation" : title;
        runtime.setConversation(historyConversationId, historyTitle);
    }

    private void confirmSwitchConversation() {
        String id = pendingSwitchId;
        String title = pendingSwitchTitle;
        pendingSwitchId = null;
        pendingSwitchTitle = null;
        new AlertDialog.Builder(this)
                .setTitle("Reply still running")
                .setMessage("Stop the current reply and start a new conversation?")
                .setPositiveButton("Stop and start new", (dialog, which) -> {
                    runtime.stopTurn();
                    runtime.clearConversation();
                    lastAssistantReply = "";
                    historyConversationId = id;
                    historyTitle = title == null || title.isBlank() ? "New conversation" : title;
                    runtime.setConversation(historyConversationId, historyTitle);
                    if (brandTitle != null) brandTitle.setText(historyTitle);
                })
                .setNegativeButton("Keep this reply", (dialog, which) -> {
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
        return v;
    }

    private Button button(String title) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        return b;
    }

    private void buildUi() {
        int surface = StillwaterUi.SURFACE;
        int ink = StillwaterUi.INK;
        int inkMuted = StillwaterUi.MUTED;
        int accent = StillwaterUi.CANDLE;
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        brandTitle = label(historyTitle, 20, ink);
        brandTitle.setTypeface(StillwaterUi.serif(this));
        titles.addView(brandTitle);
        modePill = label(searchEnabled ? "Web search + local model" : "Mode · Local / offline", 12, inkMuted);
        modePill.setTypeface(StillwaterUi.sans(this));
        titles.addView(modePill);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        settingsOpenButton = button("");
        settingsOpenButton.setId(R.id.settings_open);
        settingsOpenButton.setContentDescription("Settings");
        settingsOpenButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_still_gear, 0, 0, 0);
        StillwaterUi.quiet(settingsOpenButton);
        settingsOpenButton.setOnClickListener(v -> setSettingsOpen(true));
        topBar.addView(settingsOpenButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        Button overflow = button("⋮");
        overflow.setId(R.id.chat_overflow);
        StillwaterUi.quiet(overflow);
        overflow.setTextColor(inkMuted);
        overflow.setOnClickListener(v -> {
            android.view.ContextThemeWrapper menuContext =
                    new android.view.ContextThemeWrapper(this, R.style.Theme_Stillwater);
            android.widget.PopupMenu menu = new android.widget.PopupMenu(menuContext, v);
            menu.getMenu().add(0, 1, 0, "Settings");
            menu.getMenu().add(0, 2, 1, "Web search");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) setSettingsOpen(true);
                else if (item.getItemId() == 2) showSearchSettings();
                return true;
            });
            menu.show();
        });
        topBar.addView(overflow, new LinearLayout.LayoutParams(dp(40), dp(40)));
        topBarView = topBar;
        root.addView(topBar);

        status = label("Starting...", 13, inkMuted);
        status.setId(R.id.chat_status);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);

        // --- Settings host (hidden by default) ---
        LinearLayout settingsRoot = new LinearLayout(this);
        settingsRoot.setOrientation(LinearLayout.VERTICAL);
        settingsRoot.setBackgroundColor(surface);
        Button settingsBack = button("Back to chat");
        settingsBack.setId(R.id.settings_back);
        StillwaterUi.quiet(settingsBack);
        settingsBack.setTextColor(accent);
        settingsBack.setOnClickListener(v -> setSettingsOpen(false));
        settingsRoot.addView(settingsBack, new LinearLayout.LayoutParams(-2, dp(44)));
        TextView settingsHeading = label("Settings", 34, ink);
        settingsHeading.setTypeface(StillwaterUi.serif(this));
        settingsHeading.setPadding(0, dp(4), 0, dp(8));
        settingsRoot.addView(settingsHeading);

        LinearLayout configuration = new LinearLayout(this);
        configuration.setOrientation(LinearLayout.VERTICAL);
        configuration.setPadding(0, 0, 0, dp(16));
        LinearLayout modelControls = new LinearLayout(this);
        modelControls.setOrientation(LinearLayout.HORIZONTAL);
        modelButton = button("Import model");
        modelButton.setId(R.id.import_model);
        StillwaterUi.quiet(modelButton);
        modelButton.setTextColor(accent);
        mmprojButton = button("Import projector");
        mmprojButton.setId(R.id.import_projector);
        StillwaterUi.quiet(mmprojButton);
        mmprojButton.setTextColor(accent);
        modelControls.addView(modelButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        modelControls.addView(mmprojButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        configuration.addView(modelControls);
        // Model manager stays near the top so Unload/Load remain on-screen for Stage 1.
        modelManager = new ModelManagerPanel(this,
                this::reloadSelectedModel, this::unloadSelectedModel, this::showInferenceSettings);
        configuration.addView(modelManager);

        TextView inferenceHeading = label("INFERENCE", 11, inkMuted);
        inferenceHeading.setTypeface(StillwaterUi.sansMedium(this));
        inferenceHeading.setLetterSpacing(0.12f);
        inferenceHeading.setPadding(0, dp(16), 0, dp(4));
        configuration.addView(inferenceHeading);
        settingsContextField = settingInput(configuration, "Context", "512–8192, step 256",
                inferenceSettings.contextTokens, R.id.settings_context, 256, 512, 8192);
        settingsThreadsField = settingInput(configuration, "Decode threads", "1–8, capped to CPUs",
                inferenceSettings.threads, R.id.settings_threads, 1, 1, 8);
        settingsTokensField = settingInput(configuration, "Response tokens", "1–2048, below context",
                inferenceSettings.maxTokens, R.id.settings_tokens, 1, 1, 2048);
        settingsInlineError = label("", 13, StillwaterUi.DANGER);
        configuration.addView(settingsInlineError);

        TextView searchHeading = label("SEARXNG", 11, inkMuted);
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
        searchEnabledBox.setText("Use web search for new questions");
        StillwaterUi.styleCheck(searchEnabledBox);
        searchEnabledBox.setChecked(searchEnabled);
        configuration.addView(searchEnabledBox);
        searchButton = button("Web search: off");
        searchButton.setId(R.id.web_search);
        StillwaterUi.quiet(searchButton);
        searchButton.setOnClickListener(v -> showSearchSettings());
        configuration.addView(searchButton, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView backgroundHeading = label("BACKGROUND", 11, inkMuted);
        backgroundHeading.setTypeface(StillwaterUi.sansMedium(this));
        backgroundHeading.setLetterSpacing(0.12f);
        backgroundHeading.setPadding(0, dp(16), 0, dp(4));
        configuration.addView(backgroundHeading);
        keepLoadedBox = new CheckBox(this);
        keepLoadedBox.setId(R.id.settings_keep_loaded);
        keepLoadedBox.setText("Keep the model loaded when I leave");
        StillwaterUi.styleCheck(keepLoadedBox);
        keepLoadedBox.setOnCheckedChangeListener((button, checked) -> saveBackgroundSettings());
        configuration.addView(keepLoadedBox);
        configuration.addView(label("Unload after idle", 16, ink));
        LinearLayout idleRow = new LinearLayout(this);
        idleRow.setId(R.id.settings_idle_unload);
        idleRow.setOrientation(LinearLayout.HORIZONTAL);
        idleOffButton = idleChoice("Off", 0, R.id.settings_idle_off);
        idle15Button = idleChoice("15 min", 15, R.id.settings_idle_15);
        idle60Button = idleChoice("60 min", 60, R.id.settings_idle_60);
        idleRow.addView(idleOffButton, new LinearLayout.LayoutParams(0, dp(44), 1f));
        idleRow.addView(idle15Button, new LinearLayout.LayoutParams(0, dp(44), 1f));
        idleRow.addView(idle60Button, new LinearLayout.LayoutParams(0, dp(44), 1f));
        configuration.addView(idleRow);
        lockPreviewBox = new CheckBox(this);
        lockPreviewBox.setId(R.id.settings_lock_preview);
        lockPreviewBox.setText("Show the reply on the lock screen");
        StillwaterUi.styleCheck(lockPreviewBox);
        lockPreviewBox.setOnCheckedChangeListener((button, checked) -> saveBackgroundSettings());
        configuration.addView(lockPreviewBox);
        loopbackBox = new CheckBox(this);
        loopbackBox.setId(R.id.settings_loopback);
        loopbackBox.setText("Allow prompts from 127.0.0.1 (cannot approve tools)");
        StillwaterUi.styleCheck(loopbackBox);
        loopbackBox.setOnCheckedChangeListener((button, checked) -> saveBackgroundSettings());
        configuration.addView(loopbackBox);

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
        Button saveInference = button("Save");
        saveInference.setId(R.id.settings_save);
        StillwaterUi.primary(saveInference);
        saveInference.setOnClickListener(v -> saveInlineInferenceSettings());
        settingsActions.addView(saveInference, new LinearLayout.LayoutParams(-1, dp(52)));
        Button saveSearch = button("Save search");
        saveSearch.setId(R.id.search_save);
        StillwaterUi.quiet(saveSearch);
        saveSearch.setOnClickListener(v -> saveInlineSearchSettings());
        settingsActions.addView(saveSearch, new LinearLayout.LayoutParams(-2, dp(44)));
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
        });
        chatRoot.addView(chatPanel, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout mediaControls = new LinearLayout(this);
        mediaControls.setOrientation(LinearLayout.HORIZONTAL);
        mediaControls.setBackgroundColor(surface);
        mediaControls.setPadding(0, dp(2), 0, dp(2));
        photoButton = mediaButton("Photo", R.drawable.ic_still_photo);
        photoButton.setId(R.id.chat_photo);
        cameraButton = mediaButton("Camera", R.drawable.ic_still_camera);
        cameraButton.setId(R.id.chat_camera);
        recordButton = mediaButton("Voice input", R.drawable.ic_still_mic);
        recordButton.setId(R.id.voice_record);
        speakButton = mediaButton("Speak reply", R.drawable.ic_still_speak);
        speakButton.setId(R.id.voice_speak);
        mediaControls.addView(photoButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        mediaControls.addView(cameraButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        mediaControls.addView(recordButton, new LinearLayout.LayoutParams(0, dp(46), 1.2f));
        mediaControls.addView(speakButton, new LinearLayout.LayoutParams(0, dp(46), 1.2f));
        chatRoot.addView(mediaControls);
        chatShell = chatRoot;
        root.addView(chatShell, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);

        modelButton.setOnClickListener(v -> pickModel());
        mmprojButton.setOnClickListener(v -> pickMmproj());
        searchButton.setOnClickListener(v -> showSearchSettings());
        photoButton.setOnClickListener(v -> pickPhoto());
        cameraButton.setOnClickListener(v -> takePhoto());
        recordButton.setOnClickListener(v -> toggleRecording());
        speakButton.setOnClickListener(v -> toggleSpokenReply());

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            setSettingsOpen(true);
        }
        applyDraft(getIntent());
    }

    private void setSettingsOpen(boolean open) {
        settingsOpen = open;
        if (settingsHost != null) settingsHost.setVisibility(open ? View.VISIBLE : View.GONE);
        if (chatShell != null) chatShell.setVisibility(open ? View.GONE : View.VISIBLE);
        if (settingsOpenButton != null) settingsOpenButton.setVisibility(open ? View.GONE : View.VISIBLE);
        if (topBarView != null) topBarView.setVisibility(open ? View.GONE : View.VISIBLE);
        if (open) syncInlineSettingsFields();
    }

    private void updateControls() {
        searchButton.setText(searchEnabled ? "Web search: on" : "Web search: off");
        if (modePill != null) modePill.setText(searchEnabled ? "Web search + local model" : "Mode · Local / offline");
        searchButton.setEnabled(!busy);
        modelButton.setEnabled(!busy);
        mmprojButton.setEnabled(!busy);
        photoButton.setEnabled(!busy);
        cameraButton.setEnabled(!busy);
        RecordingController.State capture = recordingController.state();
        recordButton.setText(capture == RecordingController.State.STOPPING ? "Finishing..."
                : recordingActive() ? "Stop recording" : "Voice input");
        recordButton.setEnabled(capture != RecordingController.State.STOPPING && (!busy || recordingActive()));
        PlaybackController.State playback = playbackController.state();
        speakButton.setText(playback == PlaybackController.State.STOPPING
                ? "Stopping speech..."
                : (playbackActive() || speaking) ? "Stop speech" : "Speak reply");
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
        if (!model.isEmpty()) loadModelInBackground(model,
                pocketPreferences().getString("mmproj", ""));
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
        button.setTextSize(12);
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(4));
        StillwaterUi.quiet(button);
        return button;
    }

    private EditText settingInput(LinearLayout form, String title, String hint, int value, int id,
                                  int step, int min, int max) {
        TextView name = label(title, 15, StillwaterUi.INK);
        name.setTypeface(StillwaterUi.sansMedium(this));
        name.setPadding(0, dp(10), 0, 0);
        form.addView(name);
        TextView hintView = label(hint, 12, StillwaterUi.MUTED);
        hintView.setTypeface(StillwaterUi.sans(this));
        form.addView(hintView);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = button("–");
        StillwaterUi.quiet(minus);
        minus.setTextColor(StillwaterUi.SOFT);
        EditText field = new EditText(this);
        field.setId(id);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(Integer.toString(value));
        field.setTextSize(18);
        field.setGravity(Gravity.CENTER);
        field.setBackgroundColor(Color.TRANSPARENT);
        field.setTypeface(StillwaterUi.sansMedium(this));
        StillwaterUi.styleInput(field);
        Button plus = button("+");
        StillwaterUi.quiet(plus);
        plus.setTextColor(StillwaterUi.SOFT);
        minus.setOnClickListener(v -> nudge(field, -step, min, max));
        plus.setOnClickListener(v -> nudge(field, step, min, max));
        row.addView(minus, new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(plus, new LinearLayout.LayoutParams(dp(40), dp(40)));
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
        }
        if (searchEndpointField != null) {
            searchEndpointField.setText(searchEndpoint);
            searchEnabledBox.setChecked(searchEnabled);
        }
    }

    private void saveInlineInferenceSettings() {
        if (busy || !settingsLoaded || settingsContextField == null) return;
        if (settingsInlineError != null) settingsInlineError.setText("");
        try {
            InferenceSettings next = new InferenceSettings(
                    Integer.parseInt(settingsContextField.getText().toString()),
                    Integer.parseInt(settingsThreadsField.getText().toString()),
                    Integer.parseInt(settingsTokensField.getText().toString()));
            settingsStore.save(next, () -> {
                if (destroyed) return;
                inferenceSettings = next;
                inferenceEngine.configure(next);
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
        status.setText("Importing photo...");
        worker.execute(() -> {
            File photo = new File(getCacheDir(), "photo-" + System.currentTimeMillis() + ".img");
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
                setPendingMedia(photo.getAbsolutePath(), "photo");
            } catch (Exception e) {
                photo.delete();
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
        runOnUiThread(() -> {
            if ("photo".equals(kind)) {
                if (!pendingImagePath.isEmpty() && !pendingImagePath.equals(path))
                    HistoryMediaCleanup.deleteAttachment(this,pendingImagePath);
                pendingImagePath=path;
                chatPanel.showImageAttachment(path, () -> removePendingImage(path));
            } else {
                if (!pendingAudioPath.isEmpty() && !pendingAudioPath.equals(path))
                    HistoryMediaCleanup.deleteAttachment(this,pendingAudioPath);
                pendingAudioPath=path;
            }
            busy = false;
            status.setText(kind + " attached. Add a question and press Send.");
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && foreground && !destroyed && !busy) recordingController.start();
            else status.setText("Microphone access is needed for voice input.");
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

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_PHOTO && result == RESULT_OK && data != null && data.getData() != null) {
            copySelectedPhoto(data.getData());
            return;
        }
        if (code == TAKE_PHOTO && result == RESULT_OK && data != null) {
            String path = data.getStringExtra(CameraCaptureActivity.EXTRA_PHOTO_PATH);
            if (path != null && new File(path).isFile()) setPendingMedia(path, "photo");
            else status.setText("The camera did not return a photo.");
            return;
        }
        if (code == TAKE_PHOTO && result != RESULT_OK) {
            status.setText("Camera closed without taking a photo.");
            return;
        }
        if ((code != PICK_MODEL && code != PICK_MMPROJ) || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        busy = true;
        updateControls();
        status.setText(code == PICK_MODEL ? "Importing model into private storage..." : "Importing projector into private storage...");
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
                    if (!destroyed) { busy = false; updateControls(); }
                });
            }
        });
    }

    private void deleteImportedModel(String path) {
        File file = new File(path);
        if (new File(getFilesDir(), "models").equals(file.getParentFile())) file.delete();
    }

    private void loadModelInBackground(String path, String projector) {
        busy = true;
        updateControls();
        status.setText("Loading model from private storage...");
        runtime.load(path, projector, statusText -> {
            if (destroyed) return;
            busy = false;
            status.setText(statusText);
            updateControls();
        });
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
        paintIdle(prefs.idleMinutes());
        bindingBackground = false;
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
        if (draft == null || draft.isBlank()) return;
        chatPanel.setDraft(draft);
        intent.removeExtra(EXTRA_DRAFT_TEXT);
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
        applyDraft(intent);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (runtime != null) runtime.detach(viewer);
        HistoryMediaCleanup.deleteAttachment(this,pendingImagePath);
        HistoryMediaCleanup.deleteAttachment(this,pendingAudioPath);
        pendingImagePath = "";
        pendingAudioPath = "";
        if (settingsStore != null) settingsStore.close();
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

