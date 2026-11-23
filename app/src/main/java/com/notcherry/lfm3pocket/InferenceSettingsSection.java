package com.notcherry.lfm3pocket;

import android.content.Context;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Settings scroll section for inference limits, prompt lookup, and inline benchmark. */
final class InferenceSettingsSection {
    /** Bundled image settings saved together with inference (quiet persist on inference save). */
    interface BundledImageSettings {
        ImageSettings readForBundledSave();

        void persistBundledQuiet(ImageSettings next);
    }

    private final MainActivitySettingsHost host;
    private final InferenceSettingsStore store;
    private final Supplier<InferenceSettings> currentSettings;
    private final Consumer<InferenceSettings> onSettingsSaved;
    private final Consumer<InferenceSettings> configureEngine;
    private final BooleanSupplier settingsLoaded;
    private final BooleanSupplier activityBusy;
    private final BooleanSupplier generating;
    private final Supplier<Long> modelHandle;
    private final ExecutorService worker;
    private final Runnable updateControls;
    private final BundledImageSettings bundledImages;
    private final Consumer<CharSequence> chatStatus;
    private boolean benchmarkRunning;
    private EditText settingsContextField;
    private EditText settingsThreadsField;
    private EditText settingsTokensField;
    private CheckBox promptLookupBox;
    private TextView settingsInlineError;
    private TextView settingsBenchResult;

    InferenceSettingsSection(MainActivitySettingsHost host, InferenceSettingsStore store,
            Supplier<InferenceSettings> currentSettings, Consumer<InferenceSettings> onSettingsSaved,
            Consumer<InferenceSettings> configureEngine, BooleanSupplier settingsLoaded,
            BooleanSupplier activityBusy, BooleanSupplier generating, Supplier<Long> modelHandle,
            ExecutorService worker, Runnable updateControls, BundledImageSettings bundledImages,
            Consumer<CharSequence> chatStatus) {
        this.host = host;
        this.store = store;
        this.currentSettings = currentSettings;
        this.onSettingsSaved = onSettingsSaved;
        this.configureEngine = configureEngine;
        this.settingsLoaded = settingsLoaded;
        this.activityBusy = activityBusy;
        this.generating = generating;
        this.modelHandle = modelHandle;
        this.worker = worker;
        this.updateControls = updateControls;
        this.bundledImages = bundledImages;
        this.chatStatus = chatStatus;
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        InferenceSettings initial = currentSettings.get();
        TextView inferenceHeading = host.label(host.getString(R.string.inference_heading), 11, inkMuted);
        inferenceHeading.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        inferenceHeading.setLetterSpacing(0.12f);
        inferenceHeading.setPadding(0, host.dp(16), 0, host.dp(4));
        configuration.addView(inferenceHeading);
        settingsContextField = host.addSettingInput(configuration, host.getString(R.string.context_label),
                host.getString(R.string.context_hint),
                initial.contextTokens, R.id.settings_context, 256, 512, 8192);
        settingsThreadsField = host.addSettingInput(configuration, host.getString(R.string.decode_threads_label),
                host.getString(R.string.decode_threads_hint),
                initial.threads, R.id.settings_threads, 1, 1, 8);
        settingsTokensField = host.addSettingInput(configuration, host.getString(R.string.response_tokens_label),
                host.getString(R.string.response_tokens_hint),
                initial.maxTokens, R.id.settings_tokens, 1, 1, 2048);
        promptLookupBox = new CheckBox(host.settingsActivity());
        promptLookupBox.setId(R.id.settings_prompt_lookup);
        promptLookupBox.setText(R.string.prompt_lookup_label);
        StillwaterUi.styleCheck(promptLookupBox);
        promptLookupBox.setChecked(initial.promptLookup);
        configuration.addView(promptLookupBox);
        configuration.addView(host.label(host.getString(R.string.prompt_lookup_hint), 12, inkMuted));
        settingsInlineError = host.label("", 13, StillwaterUi.DANGER);
        StillwaterReadable.prepareWrappingLabel(settingsInlineError);
        configuration.addView(settingsInlineError);

        TextView benchHeading = host.label("BENCHMARK", 11, inkMuted);
        benchHeading.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        benchHeading.setLetterSpacing(0.12f);
        benchHeading.setPadding(0, host.dp(16), 0, host.dp(4));
        configuration.addView(benchHeading);
        configuration.addView(host.label(
                "Measures prompt-processing (pp) and decode (tg) tok/s on the loaded model, like llama-bench.",
                12, inkMuted));
        Button runBench = host.button("Run speed test");
        runBench.setId(R.id.settings_bench_run);
        StillwaterUi.quiet(runBench);
        runBench.setOnClickListener(v -> runInlineBenchmark());
        configuration.addView(runBench, new LinearLayout.LayoutParams(-1, host.dp(48)));
        settingsBenchResult = host.label(
                "Load a model, then run the speed test. Results wrap and can be copied.", 13, ink);
        settingsBenchResult.setId(R.id.settings_bench_result);
        settingsBenchResult.setSingleLine(false);
        settingsBenchResult.setMaxLines(24);
        settingsBenchResult.setHorizontallyScrolling(false);
        settingsBenchResult.setPadding(0, host.dp(8), 0, host.dp(4));
        configuration.addView(settingsBenchResult);
        Button copyBench = host.button("Copy results");
        copyBench.setId(R.id.settings_bench_copy);
        StillwaterUi.quiet(copyBench);
        copyBench.setOnClickListener(v -> copyBenchmarkResults());
        configuration.addView(copyBench, new LinearLayout.LayoutParams(-1, host.dp(44)));
        TextView settingsBuildInfo = host.label(safeNativeBuildInfo(), 12, inkMuted);
        settingsBuildInfo.setId(R.id.settings_build_info);
        settingsBuildInfo.setSingleLine(false);
        settingsBuildInfo.setMaxLines(8);
        settingsBuildInfo.setHorizontallyScrolling(false);
        settingsBuildInfo.setPadding(0, host.dp(4), 0, host.dp(8));
        configuration.addView(settingsBuildInfo);
    }

    void syncFields() {
        if (settingsContextField == null || !settingsLoaded.getAsBoolean()) return;
        InferenceSettings settings = currentSettings.get();
        settingsContextField.setText(Integer.toString(settings.contextTokens));
        settingsThreadsField.setText(Integer.toString(settings.threads));
        settingsTokensField.setText(Integer.toString(settings.maxTokens));
        if (promptLookupBox != null) promptLookupBox.setChecked(settings.promptLookup);
    }

    void focusContextField() {
        if (settingsContextField != null) settingsContextField.requestFocus();
    }

    void saveInline() {
        if (activityBusy.getAsBoolean() || !settingsLoaded.getAsBoolean() || settingsContextField == null) return;
        if (settingsInlineError != null) settingsInlineError.setText("");
        try {
            InferenceSettings next = readFieldsFromUi();
            ImageSettings nextImages = bundledImages.readForBundledSave();
            store.save(next, () -> {
                if (host.isHostFinishing()) return;
                onSettingsSaved.accept(next);
                configureEngine.accept(next);
                // Quiet image persist so it cannot race and overwrite the inference summary
                // that InferenceSettingsE2eTest matches exactly on chat_status.
                bundledImages.persistBundledQuiet(nextImages);
                updateControls.run();
                chatStatus.accept(next.summary());
            }, failure -> {
                if (host.isHostFinishing()) return;
                if (settingsInlineError != null)
                    settingsInlineError.setText("Settings were not saved: " + failure);
            });
        } catch (NumberFormatException invalid) {
            if (settingsInlineError != null)
                settingsInlineError.setText("Enter whole numbers for all settings.");
        } catch (IllegalArgumentException invalid) {
            if (settingsInlineError != null) settingsInlineError.setText(invalid.getMessage());
            chatStatus.accept(invalid.getMessage());
        }
    }

    private InferenceSettings readFieldsFromUi() {
        return new InferenceSettings(
                Integer.parseInt(settingsContextField.getText().toString()),
                Integer.parseInt(settingsThreadsField.getText().toString()),
                Integer.parseInt(settingsTokensField.getText().toString()),
                promptLookupBox == null || promptLookupBox.isChecked());
    }

    private void runInlineBenchmark() {
        if (host.isHostFinishing() || benchmarkRunning || activityBusy.getAsBoolean() || generating.getAsBoolean()) {
            return;
        }
        long handle = modelHandle.get();
        if (handle == 0L) {
            if (settingsBenchResult != null)
                settingsBenchResult.setText("Load a GGUF model first, then run the speed test.");
            return;
        }
        InferenceSettings limits = currentSettings.get();
        try {
            if (settingsLoaded.getAsBoolean() && settingsContextField != null) {
                limits = readFieldsFromUi();
            }
        } catch (RuntimeException ignored) {
            limits = currentSettings.get();
        }
        final InferenceSettings benchLimits = limits;
        benchmarkRunning = true;
        if (settingsBenchResult != null)
            settingsBenchResult.setText("Running prompt-processing and decode benchmark…");
        updateControls.run();
        worker.execute(() -> {
            String report;
            try {
                report = NativeBridge.runBenchmark(handle, benchLimits);
            } catch (UnsatisfiedLinkError | RuntimeException e) {
                report = "Benchmark failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final String text = report == null ? "Benchmark returned no result." : report;
            host.settingsMainHandler().post(() -> {
                benchmarkRunning = false;
                if (host.isHostFinishing()) return;
                if (settingsBenchResult != null) settingsBenchResult.setText(text);
                updateControls.run();
            });
        });
    }

    private void copyBenchmarkResults() {
        if (settingsBenchResult == null) return;
        CharSequence text = settingsBenchResult.getText();
        if (text == null || text.length() == 0) return;
        Context context = host.settingsActivity();
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Hanami benchmark", text));
        chatStatus.accept("Benchmark results copied.");
    }

    private static String safeNativeBuildInfo() {
        try {
            return NativeBridge.nativeBuildInfo();
        } catch (UnsatisfiedLinkError | Exception ignored) {
            return "Native build info unavailable until the library loads.";
        }
    }
}
