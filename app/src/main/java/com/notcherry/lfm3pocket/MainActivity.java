package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
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
    private static final int PICK_MODEL = 42;
    private static final int PICK_MMPROJ = 43;
    private static final int PICK_PHOTO = 44;
    private static final int TAKE_PHOTO = 45;
    private static final int REQUEST_AUDIO = 46;


    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ChatSession session;
    private ChatPanel chatPanel;
    private InferenceEngine inferenceEngine;
    private volatile long modelHandle = 0L;
    private boolean busy = false;
    private boolean generating = false;
    private volatile boolean destroyed = false;
    private volatile AtomicBoolean importCancellation;
    private final ExecutorService audioWorker = Executors.newSingleThreadExecutor();
    private RecordingController recordingController;
    private boolean foreground;
    private boolean microphoneRequested;
    private String pendingMediaPath = "";
    private String pendingMediaKind = "";

    private TextView status;
    private ModelManagerPanel modelManager;
    private InferenceSettings inferenceSettings=InferenceSettings.DEFAULT;
    private InferenceSettingsStore settingsStore;
    private boolean settingsLoaded;
    private Button modelButton, mmprojButton;
    private Button searchButton, photoButton, cameraButton, recordButton;
    private boolean searchEnabled = false;
    private String searchEndpoint = "";
    private String mmprojPath = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        inferenceEngine = ((Lfm3PocketApplication) getApplication()).newInferenceSession();
        recordingController = new RecordingController(new File(getCacheDir(), "recordings"),
                ((Lfm3PocketApplication)getApplication())::newRecordingSource,
                () -> ((Lfm3PocketApplication)getApplication()).hasMicrophonePermission(),
                audioWorker, task -> mainHandler.post(task), (captureState, message, completed) -> {
                    if (destroyed) { if (completed != null) completed.delete(); return; }
                    busy = recordingActive();
                    status.setText(message);
                    if (completed != null) {
                        pendingMediaPath = completed.getAbsolutePath();
                        pendingMediaKind = "voice recording";
                    }
                    updateControls();
                });
        session = new ChatSession(inferenceEngine, worker,
                (task, delay) -> mainHandler.postDelayed(task, delay), () -> modelHandle,
                WebSearch::search, (text, message, running) -> {
                    if (destroyed) return;
                    generating = running;
                    busy = running;
                    chatPanel.render(text);
                    status.setText(message);
                    updateControls();
                });
        settingsStore=new InferenceSettingsStore(this);
        buildUi();
        updateControls();
        settingsStore.load(settings -> {
            if (destroyed) return;
            inferenceSettings=settings;inferenceEngine.configure(settings);settingsLoaded=true;updateControls();
        }, error -> {
            if (destroyed) return;
            inferenceSettings=InferenceSettings.DEFAULT;inferenceEngine.configure(inferenceSettings);
            settingsLoaded=true;status.setText("Settings unavailable; using defaults: "+error);updateControls();
        });
        String previous = getPreferences(MODE_PRIVATE).getString("model", "");
        mmprojPath = getPreferences(MODE_PRIVATE).getString("mmproj", "");
        searchEndpoint = getPreferences(MODE_PRIVATE).getString("search_endpoint", "");
        searchEnabled = getPreferences(MODE_PRIVATE).getBoolean("search_enabled", false);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(12));
        root.setBackgroundColor(Color.rgb(250, 250, 252));

        TextView heading = label("LFM3 Pocket", 23, Color.rgb(24, 30, 44));
        heading.setTypeface(null, 1);
        root.addView(heading);

        status = label("Starting...", 13, Color.DKGRAY);
        status.setId(R.id.chat_status);
        status.setPadding(0, dp(5), 0, dp(6));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout modelControls = new LinearLayout(this);
        modelControls.setOrientation(LinearLayout.HORIZONTAL);
        modelButton = button("Import model");
        modelButton.setId(R.id.import_model);
        mmprojButton = button("Import projector");
        mmprojButton.setId(R.id.import_projector);
        modelControls.addView(modelButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        modelControls.addView(mmprojButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(status);
        controls.addView(modelControls);
        modelManager = new ModelManagerPanel(this,
                this::reloadSelectedModel, this::unloadSelectedModel, this::showInferenceSettings);
        root.addView(modelManager);
        LinearLayout actionControls = new LinearLayout(this);
        actionControls.setOrientation(LinearLayout.HORIZONTAL);
        searchButton = button("Web search: off");
        actionControls.addView(searchButton, new LinearLayout.LayoutParams(-1, dp(48)));
        controls.addView(actionControls);
        // Keep a readable transcript on small screens. Configuration can scroll independently.
        root.removeView(modelManager);
        LinearLayout configuration = new LinearLayout(this);
        configuration.setOrientation(LinearLayout.VERTICAL);
        configuration.addView(modelManager);
        configuration.addView(controls);
        ScrollView configurationScroll = new ScrollView(this);
        configurationScroll.setId(R.id.model_configuration_scroll);
        configurationScroll.addView(configuration);
        root.addView(configurationScroll, new LinearLayout.LayoutParams(-1,
                dp(Math.min(220, getResources().getConfiguration().screenHeightDp / 3))));

        chatPanel = new ChatPanel(this, this::send, this::stopActiveWork, () -> {
            pendingMediaPath = "";
            pendingMediaKind = "";
            session.clear();
        });
        root.addView(chatPanel, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout mediaControls = new LinearLayout(this);
        mediaControls.setOrientation(LinearLayout.HORIZONTAL);
        photoButton = button("Photo");
        cameraButton = button("Camera");
        recordButton = button("Voice input");
        recordButton.setId(R.id.voice_record);
        mediaControls.addView(photoButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        mediaControls.addView(cameraButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        mediaControls.addView(recordButton, new LinearLayout.LayoutParams(0, dp(46), 1.3f));
        root.addView(mediaControls);

        setContentView(root);

        modelButton.setOnClickListener(v -> pickModel());
        mmprojButton.setOnClickListener(v -> pickMmproj());
        searchButton.setOnClickListener(v -> showSearchSettings());
        photoButton.setOnClickListener(v -> pickPhoto());
        cameraButton.setOnClickListener(v -> takePhoto());
        recordButton.setOnClickListener(v -> toggleRecording());
    }

    private void updateControls() {
        searchButton.setText(searchEnabled ? "Web search: on" : "Web search: off");
        searchButton.setEnabled(!busy);
        modelButton.setEnabled(!busy);
        mmprojButton.setEnabled(!busy);
        photoButton.setEnabled(!busy);
        cameraButton.setEnabled(!busy);
        RecordingController.State capture = recordingController.state();
        recordButton.setText(capture == RecordingController.State.STOPPING ? "Finishing..."
                : recordingActive() ? "Stop recording" : "Voice input");
        recordButton.setEnabled(capture != RecordingController.State.STOPPING && (!busy || recordingActive()));
        chatPanel.updateControls(!busy && modelHandle != 0L && settingsLoaded,
                generating || recordingActive() || importCancellation != null, !busy || generating);

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
        TextView label=new TextView(this);label.setText(labelText);form.addView(label);
        EditText field=new EditText(this);field.setId(id);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);field.setText(Integer.toString(value));
        form.addView(field);return field;
    }

    private void showInferenceSettings() {
        if (busy || !settingsLoaded) return;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20),dp(8),dp(20),0);
        EditText context=settingInput(form,"Context tokens (512–8192, steps of 256)",
                inferenceSettings.contextTokens,R.id.settings_context);
        EditText threads=settingInput(form,"Decode threads (1–8, capped to available CPUs)",
                inferenceSettings.threads,R.id.settings_threads);
        EditText tokens=settingInput(form,"Max response tokens (1–2048, less than context)",
                inferenceSettings.maxTokens,R.id.settings_tokens);
        TextView error=new TextView(this);error.setTextColor(Color.RED);form.addView(error);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Inference settings")
                .setMessage("Higher context uses more memory. Changes apply to the next response.")
                .setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                InferenceSettings next=new InferenceSettings(Integer.parseInt(context.getText().toString()),
                        Integer.parseInt(threads.getText().toString()),Integer.parseInt(tokens.getText().toString()));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                settingsStore.save(next, () -> {
                    if (destroyed) return;
                    inferenceSettings=next;inferenceEngine.configure(next);updateControls();dialog.dismiss();
                }, failure -> {
                    if (destroyed) return;
                    error.setText("Settings were not saved: "+failure);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                });
            } catch(NumberFormatException invalid) { error.setText("Enter whole numbers for all settings."); }
            catch(IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        }));
        dialog.show();
    }

    private void showSearchSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText endpoint = new EditText(this);
        endpoint.setSingleLine(true);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setHint("https://your-searxng.example.org/search");
        endpoint.setText(searchEndpoint);
        form.addView(endpoint);
        CheckBox enabled = new CheckBox(this);
        enabled.setText("Use web search for new questions");
        enabled.setChecked(searchEnabled);
        form.addView(enabled);
        new AlertDialog.Builder(this)
                .setTitle("Live web search")
                .setMessage("Searches are sent to this SearXNG instance. Local model inference stays on this device.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String value = endpoint.getText().toString().trim();
                    if (!value.isEmpty() && !value.startsWith("https://")) {
                        status.setText("Use an HTTPS SearXNG search endpoint.");
                        return;
                    }
                    searchEndpoint = value;
                    searchEnabled = enabled.isChecked() && !value.isEmpty();
                    getPreferences(MODE_PRIVATE).edit()
                            .putString("search_endpoint", searchEndpoint)
                            .putBoolean("search_enabled", searchEnabled).apply();
                    updateControls();
                }).show();
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
        Intent capture = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        if (capture.resolveActivity(getPackageManager()) == null) {
            status.setText("No camera app is available.");
            return;
        }
        startActivityForResult(capture, TAKE_PHOTO);
    }

    private void copySelectedPhoto(Uri uri) {
        busy = true;
        updateControls();
        status.setText("Importing photo...");
        worker.execute(() -> {
            File photo = new File(getCacheDir(), "photo-" + System.currentTimeMillis() + ".img");
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(photo)) {
                if (input == null) throw new IOException("Could not read the selected photo");
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
                setPendingMedia(photo.getAbsolutePath(), "photo");
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    status.setText("Photo import failed: " + e.getMessage());
                    updateControls();
                });
            }
        });
    }

    private void saveCameraPhoto(Bitmap bitmap) {
        File photo = new File(getCacheDir(), "camera-" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream output = new FileOutputStream(photo)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw new IOException("Could not encode camera photo");
            output.getFD().sync();
            setPendingMedia(photo.getAbsolutePath(), "photo");
        } catch (Exception e) {
            status.setText("Camera photo failed: " + e.getMessage());
        }
    }

    private void setPendingMedia(String path, String kind) {
        runOnUiThread(() -> {
            pendingMediaPath = path;
            pendingMediaKind = kind;
            busy = false;
            status.setText(kind + " attached. Add a question and press Send.");
            updateControls();
        });
    }

    private boolean recordingActive() {
        RecordingController.State state = recordingController.state();
        return state == RecordingController.State.STARTING || state == RecordingController.State.RECORDING
                || state == RecordingController.State.STOPPING;
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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
        if (code == TAKE_PHOTO && result == RESULT_OK && data != null && data.getExtras() != null) {
            Object image = data.getExtras().get("data");
            if (image instanceof Bitmap) saveCameraPhoto((Bitmap) image);
            else status.setText("The camera did not return a photo.");
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
        try {
            GgufSelection.validate(new File(path), projector == null || projector.isEmpty() ? null : new File(projector));
            next = inferenceEngine.loadModel(path, projector == null || projector.isEmpty() ? null : projector);
            if (next == 0L) error = "Native engine could not load model (check RAM and GGUF compatibility)";
        } catch (Throwable e) {
            error = e.toString();
        }
        final long loaded = next;
        final String failure = error;
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
        else if (importCancellation != null) {
            importCancellation.set(true);
            status.setText("Cancelling import...");
        } else session.stop();
    }

    private void send(String text) {
        if (busy || modelHandle == 0L || !settingsLoaded) return;
        if (session.submit(text, pendingMediaPath, pendingMediaKind,
                searchEnabled, searchEndpoint, inferenceSettings.maxTokens)) {
            pendingMediaPath = "";
            pendingMediaKind = "";
        }
    }

    @Override protected void onDestroy() {
        destroyed = true;
        session.close();
        settingsStore.close();
        mainHandler.removeCallbacksAndMessages(null);
        if (importCancellation != null) importCancellation.set(true);
        recordingController.close();
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

