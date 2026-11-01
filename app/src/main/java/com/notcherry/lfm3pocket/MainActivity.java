package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_MODEL = 42;
    private static final int MAX_GENERATED_TOKENS = 512;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Message> messages = new ArrayList<>();
    private volatile long modelHandle = 0L;
    private boolean busy = false;
    private boolean generating = false;
    private boolean destroyed = false;
    private final StringBuilder partial = new StringBuilder();

    private TextView status;
    private TextView transcript;
    private ScrollView scroller;
    private EditText promptInput;
    private Button modelButton, sendButton, stopButton, clearButton;

    private static final class Message {
        final String role;
        final String text;
        Message(String role, String text) { this.role = role; this.text = text; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        updateControls();
        String previous = getPreferences(MODE_PRIVATE).getString("model", "");
        if (!previous.isEmpty() && new File(previous).isFile()) {
            loadModelInBackground(previous);
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
        status.setPadding(0, dp(5), 0, dp(6));
        root.addView(status);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        modelButton = button("Import GGUF");
        clearButton = button("Clear");
        stopButton = button("Stop");
        controls.addView(modelButton, new LinearLayout.LayoutParams(0, dp(48), 1.6f));
        controls.addView(clearButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        controls.addView(stopButton, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(controls);

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        transcript = label("Choose an LFM3-1.7B GGUF model to start. All inference runs on this device.\n", 16, Color.rgb(28, 32, 38));
        transcript.setTextIsSelectable(true);
        transcript.setPadding(dp(9), dp(12), dp(9), dp(12));
        scroller.addView(transcript);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(scroller, scrollParams);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.BOTTOM);
        promptInput = new EditText(this);
        promptInput.setHint("Write a message...");
        promptInput.setMinLines(1);
        promptInput.setMaxLines(4);
        promptInput.setTextSize(16);
        promptInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        sendButton = button("Send");
        inputRow.addView(promptInput, new LinearLayout.LayoutParams(0, -2, 1f));
        inputRow.addView(sendButton, new LinearLayout.LayoutParams(dp(90), dp(56)));
        root.addView(inputRow);
        setContentView(root);

        modelButton.setOnClickListener(v -> pickModel());
        clearButton.setOnClickListener(v -> {
            if (generating) { NativeBridge.stop(); }
            messages.clear();
            partial.setLength(0);
            renderTranscript();
        });
        stopButton.setOnClickListener(v -> {
            NativeBridge.stop();
            status.setText("Stopping generation...");
        });
        sendButton.setOnClickListener(v -> send());
    }

    private void updateControls() {
        modelButton.setEnabled(!busy);
        sendButton.setEnabled(!busy && modelHandle != 0L);
        clearButton.setEnabled(!busy || generating);
        stopButton.setEnabled(generating);
    }

    private void pickModel() {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        startActivityForResult(pick, PICK_MODEL);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != PICK_MODEL || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        busy = true;
        updateControls();
        status.setText("Importing GGUF into private storage...");
        worker.execute(() -> {
            File dir = new File(getFilesDir(), "models");
            dir.mkdirs();
            File temporary = new File(dir, "import.gguf.partial");
            File destination = new File(dir, "selected.gguf");
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new Exception("Cannot read the selected document");
                byte[] buffer = new byte[1024 * 1024];
                long bytes = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                    bytes += n;
                }
                output.getFD().sync();
                if (bytes < 1024 * 1024) throw new Exception("File is too small to be an LFM3 model");
                try (FileInputStream check = new FileInputStream(temporary)) {
                    byte[] magic = new byte[4];
                    if (check.read(magic) != 4 || magic[0] != 'G' || magic[1] != 'G' ||
                        magic[2] != 'U' || magic[3] != 'F') {
                        throw new Exception("Selected file is not a GGUF model");
                    }
                }
                if (destination.exists() && !destination.delete()) throw new Exception("Cannot replace old GGUF");
                if (!temporary.renameTo(destination)) throw new Exception("Cannot save imported GGUF");
                runOnUiThread(() -> status.setText("Loading model; this may take a while..."));
                loadModelOnWorker(destination.getAbsolutePath());
            } catch (Exception e) {
                temporary.delete();
                runOnUiThread(() -> {
                    busy = false;
                    status.setText("Import failed: " + e.getMessage());
                    updateControls();
                });
            }
        });
    }

    private void loadModelInBackground(String path) {
        busy = true;
        updateControls();
        status.setText("Loading model from private storage...");
        worker.execute(() -> loadModelOnWorker(path));
    }

    private void loadModelOnWorker(String path) {
        long next = 0;
        String error = null;
        try {
            next = NativeBridge.loadModel(path);
            if (next == 0L) error = "Native engine could not load model (check RAM and GGUF compatibility)";
        } catch (Throwable e) {
            error = e.toString();
        }
        final long loaded = next;
        final String failure = error;
        if (loaded != 0L) {
            if (modelHandle != 0L) NativeBridge.unloadModel(modelHandle);
            modelHandle = loaded;
            getPreferences(MODE_PRIVATE).edit().putString("model", path).apply();
        }
        runOnUiThread(() -> {
            busy = false;
            status.setText(failure == null ? "Model ready • offline • ARM64 CPU" : "Model error: " + failure);
            updateControls();
        });
    }

    private void send() {
        if (busy || modelHandle == 0L) return;
        final String userText = promptInput.getText().toString().trim();
        if (userText.isEmpty()) return;
        promptInput.setText("");
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(promptInput.getWindowToken(), 0);
        messages.add(new Message("user", userText));
        partial.setLength(0);
        final String fullPrompt = makePrompt();
        NativeBridge.resetStop();
        busy = true;
        generating = true;
        updateControls();
        status.setText("Generating locally...");
        renderTranscript();
        worker.execute(() -> {
            String error;
            try {
                error = NativeBridge.generate(modelHandle, fullPrompt, MAX_GENERATED_TOKENS, piece ->
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        partial.append(piece);
                        renderTranscript();
                    })
                );
            } catch (Throwable e) {
                error = e.toString();
            }
            final String doneError = error;
            runOnUiThread(() -> {
                if (destroyed) return;
                messages.add(new Message("assistant", partial.toString()));
                partial.setLength(0);
                generating = false;
                busy = false;
                status.setText(doneError == null ? "Model ready • offline" : "Generation: " + doneError);
                renderTranscript();
                updateControls();
            });
        });
    }

    private String makePrompt() {
        StringBuilder b = new StringBuilder("<|startoftext|><|im_start|>system\nYou are a concise helpful assistant. Reply in the language of the user.<|im_end|>\n");
        // Keep recent history to stay within the 4096-token context on typical phones.
        int start = Math.max(0, messages.size() - 8);
        for (int i = start; i < messages.size(); i++) {
            Message m = messages.get(i);
            b.append("<|im_start|>").append(m.role).append("\n")
             .append(m.text).append("<|im_end|>\n");
        }
        b.append("<|im_start|>assistant\n");
        return b.toString();
    }

    private void renderTranscript() {
        StringBuilder b = new StringBuilder();
        if (messages.isEmpty() && !generating) b.append("No conversation yet.\n");
        for (Message m : messages) {
            b.append(m.role.equals("user") ? "YOU\n" : "LFM3\n")
             .append(m.text).append("\n\n");
        }
        if (generating) b.append("LFM3\n").append(partial).append("▌");
        transcript.setText(b.toString());
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    @Override protected void onDestroy() {
        destroyed = true;
        NativeBridge.stop();
        worker.execute(() -> {
            if (modelHandle != 0L) {
                NativeBridge.unloadModel(modelHandle);
                modelHandle = 0L;
            }
        });
        worker.shutdown();
        super.onDestroy();
    }
}
