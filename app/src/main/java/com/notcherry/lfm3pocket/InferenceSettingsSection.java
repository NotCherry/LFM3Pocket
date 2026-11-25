package com.notcherry.lfm3pocket;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
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
        LinearLayout inferenceCard = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.inference_heading));
        settingsContextField = host.addSettingInput(inferenceCard, host.getString(R.string.context_label),
                host.getString(R.string.context_hint),
                initial.contextTokens, R.id.settings_context, 256, 512, 8192);
        settingsThreadsField = host.addSettingInput(inferenceCard, host.getString(R.string.decode_threads_label),
                host.getString(R.string.decode_threads_hint),
                initial.threads, R.id.settings_threads, 1, 1, 8);
        settingsTokensField = host.addSettingInput(inferenceCard, host.getString(R.string.response_tokens_label),
                host.getString(R.string.response_tokens_hint),
                initial.maxTokens, R.id.settings_tokens, 1, 1, 2048);

        StillwaterUi.settingsDivider(inferenceCard);

        promptLookupBox = new CheckBox(host.settingsActivity());
        promptLookupBox.setId(R.id.settings_prompt_lookup);
        promptLookupBox.setText(R.string.prompt_lookup_label);
        StillwaterUi.styleCheck(promptLookupBox);
        promptLookupBox.setChecked(initial.promptLookup);
        inferenceCard.addView(promptLookupBox, new LinearLayout.LayoutParams(-1, -2));
        StillwaterUi.settingsHelp(inferenceCard, host.getString(R.string.prompt_lookup_hint));
        settingsInlineError = host.label("", 14, StillwaterUi.DANGER);
        StillwaterReadable.prepareWrappingLabel(settingsInlineError);
        inferenceCard.addView(settingsInlineError);

        LinearLayout benchCard = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.benchmark_heading));
        StillwaterUi.settingsHelp(benchCard, host.getString(R.string.benchmark_help));

        Button runBench = host.button(host.getString(R.string.benchmark_run));
        runBench.setId(R.id.settings_bench_run);
        StillwaterUi.outlined(runBench);
        runBench.setOnClickListener(v -> runInlineBenchmark());

        Button copyBench = host.button(host.getString(R.string.benchmark_copy));
        copyBench.setId(R.id.settings_bench_copy);
        StillwaterUi.outlined(copyBench);
        copyBench.setOnClickListener(v -> copyBenchmarkResults());
        StillwaterReadable.addFlexibleButtonRow(benchCard, host.settingsActivity(), 48,
                runBench, copyBench);

        settingsBenchResult = host.label(host.getString(R.string.benchmark_idle_result), 13, ink);
        settingsBenchResult.setId(R.id.settings_bench_result);
        settingsBenchResult.setSingleLine(false);
        settingsBenchResult.setMaxLines(24);
        settingsBenchResult.setHorizontallyScrolling(false);
        StillwaterUi.styleCodeBlock(settingsBenchResult);
        LinearLayout.LayoutParams resParams = new LinearLayout.LayoutParams(-1, -2);
        resParams.topMargin = host.dp(12);
        benchCard.addView(settingsBenchResult, resParams);

        TextView settingsBuildInfo = host.label(safeNativeBuildInfo(), 12, inkMuted);
        settingsBuildInfo.setId(R.id.settings_build_info);
        settingsBuildInfo.setTypeface(StillwaterUi.mono(host.settingsActivity()));
        settingsBuildInfo.setLineSpacing(0f, 1.3f);
        settingsBuildInfo.setSingleLine(false);
        settingsBuildInfo.setMaxLines(8);
        settingsBuildInfo.setHorizontallyScrolling(false);
        settingsBuildInfo.setPadding(0, host.dp(10), 0, 0);
        benchCard.addView(settingsBuildInfo);
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
                settingsBenchResult.setText(host.getString(R.string.benchmark_need_model));
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
            settingsBenchResult.setText(host.getString(R.string.benchmark_running));
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
        chatStatus.accept(host.getString(R.string.benchmark_copied));
    }

    private static String safeNativeBuildInfo() {
        try {
            return NativeBridge.nativeBuildInfo();
        } catch (UnsatisfiedLinkError | Exception ignored) {
            return "Native build info unavailable until the library loads.";
        }
    }
}
