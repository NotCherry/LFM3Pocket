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
    private static final int PICK_MODEL = 42;
    private static final int PICK_MMPROJ = 43;
    private static final int PICK_PHOTO = 44;
    private static final int TAKE_PHOTO = 45;
    private static final int REQUEST_AUDIO = 46;
    private static final int REQUEST_CAMERA = 47;


    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ChatSession session;
    private ChatPanel chatPanel;
    private ToolCoordinator toolCoordinator;
    private HistoryRepository historyRepository;
    private String historyConversationId="default";
    private String historyTitle="LFM3 Pocket";
    private InferenceEngine inferenceEngine;
    private volatile long modelHandle = 0L;
    private boolean busy = false;
    private boolean generating = false;
    private volatile boolean destroyed = false;
    private volatile AtomicBoolean importCancellation;
    private final ExecutorService audioWorker = Executors.newSingleThreadExecutor();
    private RecordingController recordingController;
    private PlaybackController playbackController;
    private AndroidPlaybackFocus playbackFocus;
    private boolean foreground;
    private boolean microphoneRequested;
    private boolean cameraRequested;
    private String pendingImagePath = "";
    private String pendingAudioPath = "";
    private String lastAssistantReply = "";

    private TextView status;
    private TextView brandTitle;
    private TextView modePill;
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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        inferenceEngine = ((Lfm3PocketApplication) getApplication()).newInferenceSession();
        historyRepository = new HistoryRepository(HistoryDatabase.get(this));
        android.content.SharedPreferences historyPreferences=getPreferences(MODE_PRIVATE);
        String requestedConversation=getIntent().getStringExtra(EXTRA_HISTORY_CONVERSATION_ID);
        String requestedTitle=getIntent().getStringExtra(EXTRA_HISTORY_TITLE);
        historyConversationId=requestedConversation==null || requestedConversation.isBlank()
                ? historyPreferences.getString("history_conversation_id",java.util.UUID.randomUUID().toString())
                : requestedConversation;
        historyTitle=requestedTitle==null || requestedTitle.isBlank()
                ? historyPreferences.getString("history_title","New conversation") : requestedTitle;
        historyPreferences.edit().putString("history_conversation_id",historyConversationId)
                .putString("history_title",historyTitle).apply();
        recordingController = new RecordingController(new File(getCacheDir(), "recordings"),
                ((Lfm3PocketApplication)getApplication())::newRecordingSource,
                () -> ((Lfm3PocketApplication)getApplication()).hasMicrophonePermission(),
                audioWorker, task -> mainHandler.post(task), (captureState, message, completed) -> {
                    if (destroyed) { if (completed != null) completed.delete(); return; }
                    busy = recordingActive() || playbackActive();
                    status.setText(message);
                    if (completed != null) {
                        if (!pendingAudioPath.isEmpty()) HistoryMediaCleanup.deleteAttachment(this,pendingAudioPath);
                        pendingAudioPath = completed.getAbsolutePath();
                    }
                    updateControls();
                });
        playbackFocus = new AndroidPlaybackFocus(this);
        playbackController = new PlaybackController(
                ((Lfm3PocketApplication) getApplication()).newPlaybackTrackFactory(),
                playbackFocus::request,
                playbackFocus::abandon, audioWorker, task -> mainHandler.post(task), (playState, message) -> {
                    if (destroyed) return;
                    busy = recordingActive() || playbackActive() || generating;
                    status.setText(message);
                    updateControls();
                });
        session = new ChatSession(inferenceEngine, worker,
                (task, delay) -> mainHandler.postDelayed(task, delay), () -> modelHandle,
                WebSearch::search, turn -> {
                    if("New conversation".equals(historyTitle)) historyTitle=titleForQuestion(turn.userText);
                    if(turn.assistantText!=null && !turn.assistantText.isBlank())
                        lastAssistantReply=turn.assistantText;
                    HistoryRepository.Status resultStatus=HistoryRepository.Status.valueOf(turn.status.name());
                    historyRepository.completeTurn(new HistoryRepository.Turn(turn.id,
                            historyConversationId,historyTitle,turn.userText,turn.assistantText,
                            turn.speechTranscript,resultStatus,turn.completedAt,null,turn.speechTranscriptStatus));
                }, path -> HistoryMediaCleanup.deleteAttachment(this,path), (text, message, running) -> {
                    if (destroyed) return;
                    generating = running;
                    busy = running || recordingActive() || playbackActive();
                    chatPanel.render(text,message);
                    status.setText(message);
                    status.setVisibility(running ? View.GONE : View.VISIBLE);
                    updateControls();
                }, snapshot -> {
                    if (destroyed || chatPanel == null) return;
                    chatPanel.renderSnapshot(snapshot);
                });
        settingsStore=new InferenceSettingsStore(this);
        buildUi();
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
        String previous = getPreferences(MODE_PRIVATE).getString("model", "");
        mmprojPath = getPreferences(MODE_PRIVATE).getString("mmproj", "");
        searchEndpoint = getPreferences(MODE_PRIVATE).getString("search_endpoint", "");
        searchEnabled = getPreferences(MODE_PRIVATE).getBoolean("search_enabled", false);
        chatPanel.setIdleMode(searchEnabled);
        syncInlineSettingsFields();
        if (!previous.isEmpty() && new File(previous).isFile()) {
            loadModelInBackground(previous, mmprojPath);
        } else {
            status.setText("No model loaded. Select an LFM3 GGUF file.");
        }
    }

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
        // Atlas charcoal shell — chat owns the primary viewport; model/search live in Settings (U10/U13).
        int surface = Color.parseColor("#0E1116");
        int raised = Color.parseColor("#151A21");
        int ink = Color.parseColor("#E8EAED");
        int inkMuted = Color.parseColor("#9AA3AD");
        int accent = Color.parseColor("#5B8FA8");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface);
        root.setPadding(dp(12), dp(12), dp(12), dp(10));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        brandTitle = label("LFM3", 22, ink);
        brandTitle.setTypeface(null, 1);
        topBar.addView(brandTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        modePill = label(searchEnabled ? "Search" : "Offline", 12, accent);
        modePill.setPadding(dp(10), dp(4), dp(10), dp(4));
        modePill.setBackgroundColor(raised);
        topBar.addView(modePill);
        settingsOpenButton = button("Settings");
        settingsOpenButton.setId(R.id.settings_open);
        settingsOpenButton.setTextColor(ink);
        settingsOpenButton.setBackgroundColor(Color.TRANSPARENT);
        settingsOpenButton.setOnClickListener(v -> setSettingsOpen(true));
        topBar.addView(settingsOpenButton, new LinearLayout.LayoutParams(-2, dp(40)));
        Button overflow = button("⋮");
        overflow.setId(R.id.chat_overflow);
        overflow.setTextColor(inkMuted);
        overflow.setBackgroundColor(Color.TRANSPARENT);
        overflow.setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(this, v);
            menu.getMenu().add(0, 1, 0, "Settings");
            menu.getMenu().add(0, 2, 1, "Web search");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) setSettingsOpen(true);
                else if (item.getItemId() == 2) showSearchSettings();
                return true;
            });
            menu.show();
        });
        topBar.addView(overflow, new LinearLayout.LayoutParams(-2, dp(40)));
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
        settingsBack.setOnClickListener(v -> setSettingsOpen(false));
        settingsRoot.addView(settingsBack, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView settingsHeading = label("Settings", 20, ink);
        settingsHeading.setTypeface(null, 1);
        settingsHeading.setPadding(0, dp(8), 0, dp(8));
        settingsRoot.addView(settingsHeading);

        LinearLayout configuration = new LinearLayout(this);
        configuration.setOrientation(LinearLayout.VERTICAL);
        configuration.setPadding(0, 0, 0, dp(16));
        LinearLayout modelControls = new LinearLayout(this);
        modelControls.setOrientation(LinearLayout.HORIZONTAL);
        modelButton = button("Import model");
        modelButton.setId(R.id.import_model);
        mmprojButton = button("Import projector");
        mmprojButton.setId(R.id.import_projector);
        modelControls.addView(modelButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        modelControls.addView(mmprojButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        configuration.addView(modelControls);
        modelManager = new ModelManagerPanel(this,
                this::reloadSelectedModel, this::unloadSelectedModel, this::showInferenceSettings);
        configuration.addView(modelManager);

        TextView inferenceHeading = label("Inference", 16, ink);
        inferenceHeading.setTypeface(null, 1);
        inferenceHeading.setPadding(0, dp(12), 0, dp(4));
        configuration.addView(inferenceHeading);
        settingsContextField = settingInput(configuration,
                "Context tokens (512–8192, steps of 256)",
                inferenceSettings.contextTokens, R.id.settings_context);
        settingsContextField.setTextColor(ink);
        settingsThreadsField = settingInput(configuration,
                "Decode threads (1–8, capped to available CPUs)",
                inferenceSettings.threads, R.id.settings_threads);
        settingsThreadsField.setTextColor(ink);
        settingsTokensField = settingInput(configuration,
                "Max response tokens (1–2048, less than context)",
                inferenceSettings.maxTokens, R.id.settings_tokens);
        settingsTokensField.setTextColor(ink);
        settingsInlineError = label("", 13, Color.parseColor("#C45B5B"));
        configuration.addView(settingsInlineError);

        TextView searchHeading = label("SearXNG", 16, ink);
        searchHeading.setTypeface(null, 1);
        searchHeading.setPadding(0, dp(12), 0, dp(4));
        configuration.addView(searchHeading);
        searchEndpointField = new EditText(this);
        searchEndpointField.setId(R.id.search_endpoint);
        searchEndpointField.setSingleLine(true);
        searchEndpointField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        searchEndpointField.setHint("https://your-searxng.example.org/search");
        searchEndpointField.setHintTextColor(inkMuted);
        searchEndpointField.setTextColor(ink);
        searchEndpointField.setText(searchEndpoint);
        configuration.addView(searchEndpointField);
        searchEnabledBox = new CheckBox(this);
        searchEnabledBox.setId(R.id.search_enabled);
        searchEnabledBox.setText("Use web search for new questions");
        searchEnabledBox.setTextColor(ink);
        searchEnabledBox.setChecked(searchEnabled);
        configuration.addView(searchEnabledBox);
        searchButton = button("Web search: off");
        searchButton.setId(R.id.web_search);
        searchButton.setOnClickListener(v -> showSearchSettings());
        configuration.addView(searchButton, new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView configurationScroll = new ScrollView(this);
        configurationScroll.setId(R.id.model_configuration_scroll);
        configurationScroll.setBackgroundColor(raised);
        configurationScroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        configurationScroll.addView(configuration);
        settingsRoot.addView(configurationScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Sticky Save controls stay on-screen (small emulator + IME) for UiAutomator Stage 1 IDs.
        LinearLayout settingsActions = new LinearLayout(this);
        settingsActions.setOrientation(LinearLayout.VERTICAL);
        settingsActions.setBackgroundColor(raised);
        settingsActions.setPadding(dp(8), dp(6), dp(8), dp(6));
        Button saveInference = button("Save");
        saveInference.setId(R.id.settings_save);
        saveInference.setOnClickListener(v -> saveInlineInferenceSettings());
        settingsActions.addView(saveInference, new LinearLayout.LayoutParams(-1, dp(48)));
        Button saveSearch = button("Save search");
        saveSearch.setId(R.id.search_save);
        saveSearch.setOnClickListener(v -> saveInlineSearchSettings());
        settingsActions.addView(saveSearch, new LinearLayout.LayoutParams(-1, dp(48)));
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
            session.clear();
        });
        chatRoot.addView(chatPanel, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout mediaControls = new LinearLayout(this);
        mediaControls.setOrientation(LinearLayout.HORIZONTAL);
        mediaControls.setBackgroundColor(raised);
        mediaControls.setPadding(dp(2), dp(4), dp(2), dp(2));
        photoButton = button("Photo");
        photoButton.setId(R.id.chat_photo);
        cameraButton = button("Camera");
        cameraButton.setId(R.id.chat_camera);
        recordButton = button("Voice input");
        recordButton.setId(R.id.voice_record);
        speakButton = button("Speak reply");
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
    }

    private void setSettingsOpen(boolean open) {
        settingsOpen = open;
        if (settingsHost != null) settingsHost.setVisibility(open ? View.VISIBLE : View.GONE);
        if (chatShell != null) chatShell.setVisibility(open ? View.GONE : View.VISIBLE);
        if (settingsOpenButton != null) settingsOpenButton.setVisibility(open ? View.GONE : View.VISIBLE);
        if (open) syncInlineSettingsFields();
    }

    private void updateControls() {
        searchButton.setText(searchEnabled ? "Web search: on" : "Web search: off");
        if (modePill != null) modePill.setText(searchEnabled ? "Search" : "Offline");
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
        speakButton.setText(playback == PlaybackController.State.STOPPING ? "Stopping speech..."
                : playbackActive() ? "Stop speech" : "Speak reply");
        speakButton.setEnabled((!busy || playbackActive()) && (playbackActive()
                || (modelHandle != 0L && settingsLoaded && !lastAssistantReply.isBlank())));
        chatPanel.updateControls(!busy && modelHandle != 0L && settingsLoaded,
                generating || recordingActive() || playbackActive() || importCancellation != null,
                !busy || generating);

        if (modelManager != null) {
            modelManager.showSettings(settingsLoaded ? inferenceSettings.summary() : "Loading inference settings...");
            String model = getPreferences(MODE_PRIVATE).getString("model", "");
            String projector = getPreferences(MODE_PRIVATE).getString("mmproj", "");
            String description = status.getText().toString();
            modelManager.updateSelection(
                    model.isEmpty() ? "" : getPreferences(MODE_PRIVATE).getString("model_name", new File(model).getName()),
                    projector.isEmpty() ? "" : getPreferences(MODE_PRIVATE).getString("projector_name", new File(projector).getName()),
                    busy, modelHandle != 0, description.startsWith("Model error") || description.startsWith("Import failed"));
        }
    }

    private void reloadSelectedModel() {
        if (busy) return;
        String model = getPreferences(MODE_PRIVATE).getString("model", "");
        if (!model.isEmpty()) loadModelInBackground(model,
                getPreferences(MODE_PRIVATE).getString("mmproj", ""));
    }

    private void unloadSelectedModel() {
        if (busy || modelHandle == 0) return;
        busy = true;
        status.setText("Unloading model...");
        updateControls();
        worker.execute(() -> {
            inferenceEngine.unloadModel(modelHandle);
            modelHandle = 0;
            runOnUiThread(() -> {
                if (destroyed) return;
                busy = false;
                status.setText("Model unloaded. Selected files remain available for reload.");
                updateControls();
            });
        });
    }

    private EditText settingInput(LinearLayout form,String labelText,int value,int id) {
        TextView label=new TextView(this);label.setText(labelText);
        label.setTextColor(Color.parseColor("#9AA3AD"));form.addView(label);
        EditText field=new EditText(this);field.setId(id);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);field.setText(Integer.toString(value));
        field.setTextColor(Color.parseColor("#E8EAED"));
        field.setHintTextColor(Color.parseColor("#5C6670"));
        form.addView(field);return field;
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
        getPreferences(MODE_PRIVATE).edit()
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
        if (playbackActive()) { playbackController.stop(); return; }
        if (busy || !foreground || modelHandle == 0L || lastAssistantReply.isBlank()) return;
        final String reply = lastAssistantReply;
        busy = true;
        status.setText("Generating spoken response locally…");
        updateControls();
        worker.execute(() -> {
            java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
            final int[] format = new int[] { 24000, 1, 16 };
            String error;
            try {
                inferenceEngine.resetStop();
                error = inferenceEngine.generateSpeech(modelHandle,
                        SpeechPrompt.tts(reply, "en", "female"),
                        new SpeechOptions("en", "female", Math.min(512, inferenceSettings.maxTokens)),
                        new InferenceEngine.AudioSink() {
                            @Override public void onAudioFormat(int sampleRateHz, int channelCount, int bitsPerSample) {
                                format[0] = sampleRateHz; format[1] = channelCount; format[2] = bitsPerSample;
                            }
                            @Override public void onAudioPcm(byte[] interleavedLittleEndianPcm) {
                                try { pcm.write(interleavedLittleEndianPcm); }
                                catch (java.io.IOException e) { throw new IllegalStateException(e); }
                            }
                        });
            } catch (Throwable t) {
                error = t.toString();
            }
            final String failure = error;
            final byte[] audio = pcm.toByteArray();
            mainHandler.post(() -> {
                if (destroyed) return;
                if (failure != null) {
                    busy = recordingActive() || generating;
                    status.setText("Spoken response failed: " + failure);
                    updateControls();
                    return;
                }
                if (audio.length == 0) {
                    busy = recordingActive() || generating;
                    status.setText("Spoken response produced no audio.");
                    updateControls();
                    return;
                }
                if (!playbackController.play(audio, format[0], format[1], format[2])) {
                    busy = recordingActive() || generating;
                    status.setText("Could not start spoken playback.");
                    updateControls();
                }
            });
        });
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
                String previousModel = getPreferences(MODE_PRIVATE).getString("model", "");
                String previousProjector = getPreferences(MODE_PRIVATE).getString("mmproj", "");
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
                if (destroyed || cancellation.get()) throw new IOException("Import cancelled");
                String model = isProjector ? previousModel : imported.getAbsolutePath();
                String projector = isProjector ? imported.getAbsolutePath() : "";
                if (loadModelOnWorker(model, projector)) {
                    getPreferences(MODE_PRIVATE).edit().putString("model", model)
                            .putString("mmproj", projector)
                            .putString(isProjector ? "projector_name" : "model_name", importedName).apply();
                    if (!isProjector) getPreferences(MODE_PRIVATE).edit().remove("projector_name").apply();
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
        worker.execute(() -> loadModelOnWorker(path, projector));
    }

    private boolean loadModelOnWorker(String path, String projector) {
        long next = 0;
        String error = null;
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            GgufSelection.validate(new File(path), projector == null || projector.isEmpty() ? null : new File(projector));
            next = inferenceEngine.loadModel(path, projector == null || projector.isEmpty() ? null : projector);
            if (next == 0L) error = "Native engine could not load model (check RAM and GGUF compatibility)";
        } catch (Throwable e) {
            error = e.toString();
        }
        long loadMs = android.os.SystemClock.elapsedRealtime() - started;
        final long loaded = next;
        final String failure = error;
        LocalDiagnostics diagnostics = ((Lfm3PocketApplication) getApplication()).diagnostics();
        diagnostics.record(LocalDiagnostics.Kind.MODEL_LOAD, loadMs,
                failure == null ? "ok path=" + new File(path).getName() : "error=" + failure);
        try {
            diagnostics.writeTo(new File(getFilesDir(), "diagnostics.ndjson"));
        } catch (Exception ignored) {
        }
        if (loaded != 0L && destroyed) {
            inferenceEngine.unloadModel(loaded);
            return false;
        }
        if (loaded != 0L) {
            if (modelHandle != 0L) inferenceEngine.unloadModel(modelHandle);
            modelHandle = loaded;
            getPreferences(MODE_PRIVATE).edit().putString("model", path).apply();
        }
        runOnUiThread(() -> {
            if (destroyed) return;
            busy = false;
            status.setText(failure == null
                    ? (projector == null || projector.isEmpty()
                        ? "Text model ready  offline"
                        : "Model and projector loaded • offline")
                    : "Model error: " + failure);
            updateControls();
        });
        return loaded != 0L;
    }

    private void stopActiveWork() {
        if (recordingActive()) recordingController.stop();
        else if (playbackActive()) playbackController.stop();
        else if (importCancellation != null) {
            importCancellation.set(true);
            status.setText("Cancelling import...");
        } else {
            inferenceEngine.stop();
            session.stop();
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
        if(!pending && request!=null && outcome.result!=null) persistToolOutcome(proposal,request,outcome);
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
        if (busy || modelHandle == 0L || !settingsLoaded) return;
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

    private static String titleForQuestion(String question) {
        String title=question==null?"New conversation":question.replaceAll("\\s+"," ").trim();
        if(title.length()>60) title=title.substring(0,57)+"…";
        return title.isEmpty()?"New conversation":title;
    }

    @Override protected void onDestroy() {
        destroyed = true;
        HistoryMediaCleanup.deleteAttachment(this,pendingImagePath);
        HistoryMediaCleanup.deleteAttachment(this,pendingAudioPath);
        pendingImagePath = "";
        pendingAudioPath = "";
        session.close();
        settingsStore.close();
        mainHandler.removeCallbacksAndMessages(null);
        if (importCancellation != null) importCancellation.set(true);
        recordingController.close();
        playbackController.close();
        audioWorker.shutdown();
        inferenceEngine.stop();
        worker.execute(() -> {
            if (modelHandle != 0L) {
                inferenceEngine.unloadModel(modelHandle);
                modelHandle = 0L;
            }
        });
        worker.shutdown();
        super.onDestroy();
    }
}

