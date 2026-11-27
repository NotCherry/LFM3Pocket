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
    private ReasoningLevel selectedReasoningLevel = ReasoningLevel.AUTO;
    private Button reasoningAutoButton;
    private Button reasoningOffButton;
    private Button reasoningLowButton;
    private Button reasoningMediumButton;
    private Button reasoningHighButton;
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
        selectedReasoningLevel = initial.reasoningLevel;
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

        StillwaterUi.settingsDivider(inferenceCard);

        TextView reasoningTitle = host.label(host.getString(R.string.reasoning_label), 16, ink);
        reasoningTitle.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        inferenceCard.addView(reasoningTitle);
        StillwaterUi.settingsHelp(inferenceCard, host.getString(R.string.reasoning_hint));

        reasoningAutoButton = reasoningChoice(ReasoningLevel.AUTO, R.string.reasoning_auto, R.id.settings_reasoning_auto);
        reasoningOffButton = reasoningChoice(ReasoningLevel.OFF, R.string.reasoning_off, R.id.settings_reasoning_off);
        reasoningLowButton = reasoningChoice(ReasoningLevel.LOW, R.string.reasoning_low, R.id.settings_reasoning_low);
        LinearLayout reasoningRow1 = StillwaterReadable.addFlexibleButtonRow(
                inferenceCard, host.settingsActivity(), 48,
                reasoningAutoButton, reasoningOffButton, reasoningLowButton);
        reasoningRow1.setId(R.id.settings_reasoning_level);

        reasoningMediumButton = reasoningChoice(ReasoningLevel.MEDIUM, R.string.reasoning_medium, R.id.settings_reasoning_medium);
        reasoningHighButton = reasoningChoice(ReasoningLevel.HIGH, R.string.reasoning_high, R.id.settings_reasoning_high);
        StillwaterReadable.addFlexibleButtonRow(
                inferenceCard, host.settingsActivity(), 48,
                reasoningMediumButton, reasoningHighButton);
        highlightReasoning(selectedReasoningLevel);

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
        selectedReasoningLevel = settings.reasoningLevel;
        highlightReasoning(selectedReasoningLevel);
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

    private Button reasoningChoice(ReasoningLevel level, int labelRes, int viewId) {
        Button choice = host.button(host.getString(labelRes));
        choice.setId(viewId);
        StillwaterUi.outlined(choice);
        choice.setOnClickListener(v -> {
            if (activityBusy.getAsBoolean() || !settingsLoaded.getAsBoolean()) return;
            selectedReasoningLevel = level;
            highlightReasoning(level);
            InferenceSettings current = currentSettings.get();
            InferenceSettings next;
            try {
                next = readFieldsFromUi();
            } catch (RuntimeException ignored) {
                next = new InferenceSettings(current.contextTokens, current.threads,
                        current.maxTokens, current.promptLookup, level);
            }
            final InferenceSettings toSave = next;
            store.save(toSave, () -> {
                if (host.isHostFinishing()) return;
                onSettingsSaved.accept(toSave);
                configureEngine.accept(toSave);
                updateControls.run();
                chatStatus.accept(toSave.summary());
            }, failure -> {
                if (host.isHostFinishing()) return;
                if (settingsInlineError != null)
                    settingsInlineError.setText("Settings were not saved: " + failure);
            });
        });
        return choice;
    }

    private void highlightReasoning(ReasoningLevel selected) {
        if (reasoningAutoButton == null) return;
        StillwaterUi.segmented(reasoningAutoButton, selected == ReasoningLevel.AUTO);
        StillwaterUi.segmented(reasoningOffButton, selected == ReasoningLevel.OFF);
        StillwaterUi.segmented(reasoningLowButton, selected == ReasoningLevel.LOW);
        StillwaterUi.segmented(reasoningMediumButton, selected == ReasoningLevel.MEDIUM);
        StillwaterUi.segmented(reasoningHighButton, selected == ReasoningLevel.HIGH);
    }

    private InferenceSettings readFieldsFromUi() {
        return new InferenceSettings(
                Integer.parseInt(settingsContextField.getText().toString()),
                Integer.parseInt(settingsThreadsField.getText().toString()),
                Integer.parseInt(settingsTokensField.getText().toString()),
                promptLookupBox == null || promptLookupBox.isChecked(),
                selectedReasoningLevel);
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
