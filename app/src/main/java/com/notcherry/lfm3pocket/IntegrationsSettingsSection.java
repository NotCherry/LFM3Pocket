package com.notcherry.lfm3pocket;

import android.content.Intent;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Settings scroll section for network tools, notifications, calendar, and speech integrations. */
final class IntegrationsSettingsSection {
    static final class Deps {
        final Supplier<RuntimePreferences> preferences;
        final Runnable updateControls;
        final Runnable pickCalendarTarget;
        final Runnable pickWhistleModel;
        final Runnable downloadCactusWhistleModel;
        final Runnable downloadTinyVocaPack;
        final Consumer<Runnable> runOnUiThread;
        final Consumer<CharSequence> chatStatus;

        Deps(Supplier<RuntimePreferences> preferences, Runnable updateControls, Runnable pickCalendarTarget,
                Runnable pickWhistleModel, Runnable downloadCactusWhistleModel, Runnable downloadTinyVocaPack,
                Consumer<Runnable> runOnUiThread, Consumer<CharSequence> chatStatus) {
            this.preferences = preferences;
            this.updateControls = updateControls;
            this.pickCalendarTarget = pickCalendarTarget;
            this.pickWhistleModel = pickWhistleModel;
            this.downloadCactusWhistleModel = downloadCactusWhistleModel;
            this.downloadTinyVocaPack = downloadTinyVocaPack;
            this.runOnUiThread = runOnUiThread;
            this.chatStatus = chatStatus;
        }
    }

    private final MainActivitySettingsHost host;
    private final Deps deps;
    private boolean bindingFields;
    private CheckBox networkToolsBox;
    private CheckBox notificationAccessBox;
    private EditText notificationAllowlistField;
    private TextView calendarStatusText;
    private CheckBox tinyVocaTtsBox;
    private TextView tinyVocaStatus;
    private Button tinyVocaDownloadButton;
    private Button tinyVocaCancelButton;
    private Button tinyVocaRemoveButton;
    private Button speechInputOmniButton;
    private Button speechInputWhistleButton;
    private TextView speechInputCactusStatus;
    private Button speechInputCactusDownloadButton;
    private Button speechInputCactusCancelButton;
    private Button speechInputCactusPickButton;
    private Button speechInputCactusClearButton;
    private final CactusWhistleModelDownloader.UiCallbacks cactusWhistleDownloadUi =
            new CactusWhistleModelDownloader.UiCallbacks() {
                @Override public void onProgress(String name, long written, long total) {
                    deps.runOnUiThread.accept(() -> {
                        if (speechInputCactusStatus != null) {
                            String label = CactusWhistleModelDownloader.formatProgressLabel(name, written, total);
                            speechInputCactusStatus.setText(
                                    host.getString(R.string.speech_input_cactus_downloading, label));
                        }
                    });
                }
                @Override public void onDone(File modelFile) {
                    deps.runOnUiThread.accept(() -> {
                        deps.preferences.get().setCactusWhistleModelPath(modelFile.getAbsolutePath());
                        paintSpeechInputControls();
                        deps.updateControls.run();
                        deps.chatStatus.accept(host.getString(R.string.speech_input_cactus_status_ready));
                    });
                }
                @Override public void onError(String message) {
                    deps.runOnUiThread.accept(() -> {
                        paintSpeechInputControls();
                        deps.chatStatus.accept(message);
                    });
                }
                @Override public void onCancelled() {
                    deps.runOnUiThread.accept(() -> {
                        paintSpeechInputControls();
                        deps.chatStatus.accept(host.getString(R.string.model_download_cancelled));
                    });
                }
            };
    private final TinyVocaPackDownloader.UiCallbacks tinyVocaDownloadUi =
            new TinyVocaPackDownloader.UiCallbacks() {
                @Override public void onProgress(String name, long written, long total) {
                    deps.runOnUiThread.accept(() -> {
                        if (tinyVocaStatus != null) {
                            String label = CactusWhistleModelDownloader.formatProgressLabel(name, written, total);
                            tinyVocaStatus.setText(host.getString(R.string.tiny_voca_downloading, label));
                        }
                    });
                }
                @Override public void onDone() {
                    deps.runOnUiThread.accept(() -> {
                        paintTinyVocaPackStatus();
                        deps.updateControls.run();
                        deps.chatStatus.accept(host.getString(R.string.tiny_voca_status_ready));
                    });
                }
                @Override public void onError(String message) {
                    deps.runOnUiThread.accept(() -> {
                        paintTinyVocaPackStatus();
                        deps.chatStatus.accept(message);
                    });
                }
                @Override public void onCancelled() {
                    deps.runOnUiThread.accept(() -> {
                        paintTinyVocaPackStatus();
                        deps.chatStatus.accept(host.getString(R.string.model_download_cancelled));
                    });
                }
            };

    IntegrationsSettingsSection(MainActivitySettingsHost host, Deps deps) {
        this.host = host;
        this.deps = deps;
    }

    CactusWhistleModelDownloader.UiCallbacks cactusWhistleDownloadUi() {
        return cactusWhistleDownloadUi;
    }

    TinyVocaPackDownloader.UiCallbacks tinyVocaDownloadUi() {
        return tinyVocaDownloadUi;
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        LinearLayout toolsCard = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.tools_heading));
        networkToolsBox = new CheckBox(host.settingsActivity());
        networkToolsBox.setId(R.id.settings_network_tools);
        networkToolsBox.setText(R.string.network_tools_label);
        StillwaterUi.styleCheck(networkToolsBox);
        networkToolsBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingFields) return;
            deps.preferences.get().setNetworkToolsEnabled(checked);
        });
        toolsCard.addView(networkToolsBox);
        StillwaterUi.settingsHelp(toolsCard, host.getString(R.string.network_tools_help));

        LinearLayout notifCard = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.notification_access_heading));
        notificationAccessBox = new CheckBox(host.settingsActivity());
        notificationAccessBox.setId(R.id.settings_notification_access);
        notificationAccessBox.setText(R.string.notification_access_enabled);
        StillwaterUi.styleCheck(notificationAccessBox);
        notificationAccessBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingFields) return;
            deps.preferences.get().setNotificationAccessEnabled(checked);
        });
        notifCard.addView(notificationAccessBox);
        StillwaterUi.settingsHelp(notifCard, host.getString(R.string.notification_access_help));

        Button openNotificationAccessSettings = host.button(host.getString(R.string.notification_access_open_settings));
        openNotificationAccessSettings.setId(R.id.settings_notification_open_system);
        StillwaterUi.outlined(openNotificationAccessSettings);
        openNotificationAccessSettings.setOnClickListener(v -> {
            try {
                host.settingsActivity().startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                deps.chatStatus.accept("Could not open notification access settings.");
            }
        });
        StillwaterReadable.addFlexibleFullWidth(notifCard, openNotificationAccessSettings, 48);

        StillwaterUi.settingsDivider(notifCard);

        TextView notificationAllowlistLabel = host.label(host.getString(R.string.notification_access_allowlist_label), 15, ink);
        notificationAllowlistLabel.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        notificationAllowlistLabel.setPadding(0, 0, 0, host.dp(8));
        StillwaterReadable.prepareWrappingLabel(notificationAllowlistLabel);
        notifCard.addView(notificationAllowlistLabel);

        notificationAllowlistField = new EditText(host.settingsActivity());
        notificationAllowlistField.setId(R.id.settings_notification_allowlist);
        notificationAllowlistField.setMinLines(3);
        notificationAllowlistField.setMaxLines(8);
        notificationAllowlistField.setGravity(Gravity.TOP | Gravity.START);
        notificationAllowlistField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        notificationAllowlistField.setHint(host.getString(R.string.notification_access_allowlist_hint));
        StillwaterReadable.setTextSizeSp(notificationAllowlistField, 14f);
        notificationAllowlistField.setTypeface(StillwaterUi.mono(host.settingsActivity()));
        StillwaterUi.styleBoxedInput(notificationAllowlistField);
        notificationAllowlistField.setHintTextColor(StillwaterUi.DISABLED);
        notifCard.addView(notificationAllowlistField, new LinearLayout.LayoutParams(-1, -2));

        Button saveNotificationAllowlist = host.button(host.getString(R.string.notification_access_save_allowlist));
        saveNotificationAllowlist.setId(R.id.settings_notification_save_allowlist);
        StillwaterUi.outlined(saveNotificationAllowlist);
        saveNotificationAllowlist.setOnClickListener(v -> saveNotificationAllowlist());
        StillwaterReadable.addFlexibleFullWidth(notifCard, saveNotificationAllowlist, 48);
        StillwaterUi.settingsHelp(notifCard, host.getString(R.string.notification_access_empty_allowlist));

        LinearLayout calendarCard = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.calendar_heading));
        StillwaterUi.settingsHelp(calendarCard, host.getString(R.string.calendar_help));
        calendarStatusText = host.label(host.getString(R.string.calendar_none_picked), 14, ink);
        calendarStatusText.setId(R.id.settings_calendar_status);
        calendarStatusText.setPadding(0, host.dp(2), 0, host.dp(8));
        StillwaterReadable.prepareWrappingLabel(calendarStatusText);
        calendarCard.addView(calendarStatusText);

        Button calendarPickButton = host.button(host.getString(R.string.calendar_pick_button));
        calendarPickButton.setId(R.id.settings_calendar_pick);
        StillwaterUi.outlined(calendarPickButton);
        calendarPickButton.setOnClickListener(v -> deps.pickCalendarTarget.run());

        Button calendarClearButton = host.button(host.getString(R.string.calendar_clear_button));
        calendarClearButton.setId(R.id.settings_calendar_clear);
        StillwaterUi.dangerOutlined(calendarClearButton);
        calendarClearButton.setOnClickListener(v -> {
            deps.preferences.get().setCalendarWriteTarget("", "");
            refreshCalendarPickerStatus();
        });
        StillwaterReadable.addFlexibleButtonRow(calendarCard, host.settingsActivity(), 48,
                calendarPickButton, calendarClearButton);

        LinearLayout speechCard = StillwaterUi.settingsCard(configuration,
                host.getString(R.string.speech_heading));

        TextView speechInputLabel = host.label(host.getString(R.string.speech_input_mode_label), 16, ink);
        speechInputLabel.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        StillwaterReadable.prepareWrappingLabel(speechInputLabel);
        speechCard.addView(speechInputLabel);
        StillwaterUi.settingsHelp(speechCard, host.getString(R.string.speech_input_mode_help));

        speechInputOmniButton = host.button(host.getString(R.string.speech_input_omni));
        speechInputWhistleButton = host.button(host.getString(R.string.speech_input_cactus_whistle));
        speechInputOmniButton.setOnClickListener(v -> {
            deps.preferences.get().setSpeechInputMode(SpeechInputMode.OMNI_AUDIO);
            paintSpeechInputControls();
            deps.updateControls.run();
        });
        speechInputWhistleButton.setOnClickListener(v -> {
            deps.preferences.get().setSpeechInputMode(SpeechInputMode.CACTUS_WHISTLE_STT);
            paintSpeechInputControls();
            deps.updateControls.run();
        });
        StillwaterReadable.addFlexibleButtonRow(
                speechCard, host.settingsActivity(), 48, speechInputOmniButton, speechInputWhistleButton);

        speechInputCactusStatus = host.label(host.getString(R.string.speech_input_cactus_status_missing), 13, ink);
        speechInputCactusStatus.setId(R.id.settings_cactus_whistle_status);
        StillwaterUi.styleCodeBlock(speechInputCactusStatus);
        StillwaterReadable.prepareWrappingLabel(speechInputCactusStatus);
        LinearLayout.LayoutParams whistleStatusParams = new LinearLayout.LayoutParams(-1, -2);
        whistleStatusParams.topMargin = host.dp(12);
        speechCard.addView(speechInputCactusStatus, whistleStatusParams);

        speechInputCactusDownloadButton = host.button(host.getString(R.string.speech_input_cactus_download));
        speechInputCactusDownloadButton.setId(R.id.settings_cactus_whistle_download);
        StillwaterUi.outlined(speechInputCactusDownloadButton);
        speechInputCactusDownloadButton.setOnClickListener(v -> deps.downloadCactusWhistleModel.run());
        StillwaterReadable.addFlexibleFullWidth(speechCard, speechInputCactusDownloadButton, 48);

        speechInputCactusCancelButton = host.button(host.getString(R.string.model_download_cancel));
        speechInputCactusCancelButton.setId(R.id.settings_cactus_whistle_cancel);
        StillwaterUi.dangerOutlined(speechInputCactusCancelButton);
        speechInputCactusCancelButton.setOnClickListener(v -> {
            ModelDownloadManager.getInstance(host.settingsActivity()).cancel(ModelDownloadKind.CACTUS_WHISTLE);
            paintSpeechInputControls();
        });
        StillwaterReadable.addFlexibleFullWidth(speechCard, speechInputCactusCancelButton, 48);

        speechInputCactusPickButton = host.button(host.getString(R.string.speech_input_cactus_pick));
        speechInputCactusPickButton.setId(R.id.settings_cactus_whistle_pick);
        StillwaterUi.outlined(speechInputCactusPickButton);
        speechInputCactusPickButton.setOnClickListener(v -> deps.pickWhistleModel.run());

        speechInputCactusClearButton = host.button(host.getString(R.string.speech_input_cactus_clear));
        speechInputCactusClearButton.setId(R.id.settings_cactus_whistle_clear);
        StillwaterUi.dangerOutlined(speechInputCactusClearButton);
        speechInputCactusClearButton.setOnClickListener(v -> {
            deps.preferences.get().setCactusWhistleModelPath("");
            new CactusWhistleModelStore(host.settingsActivity()).clear();
            CactusWhistleBridge.resetLoadedModel();
            paintSpeechInputControls();
            deps.updateControls.run();
        });
        StillwaterReadable.addFlexibleButtonRow(speechCard, host.settingsActivity(), 48,
                speechInputCactusPickButton, speechInputCactusClearButton);
        StillwaterUi.settingsHelp(speechCard, host.getString(R.string.speech_input_cactus_help));

        StillwaterUi.settingsDivider(speechCard);

        tinyVocaTtsBox = new CheckBox(host.settingsActivity());
        tinyVocaTtsBox.setId(R.id.settings_tiny_voca_tts);
        tinyVocaTtsBox.setText(R.string.tiny_voca_tts_label);
        StillwaterUi.styleCheck(tinyVocaTtsBox);
        tinyVocaTtsBox.setOnCheckedChangeListener((button, checked) -> {
            if (bindingFields) return;
            deps.preferences.get().setTinyVocaEnglishTtsEnabled(checked);
            paintTinyVocaPackStatus();
            deps.updateControls.run();
        });
        speechCard.addView(tinyVocaTtsBox);
        StillwaterUi.settingsHelp(speechCard, host.getString(R.string.tiny_voca_tts_help));

        tinyVocaStatus = host.label(host.getString(R.string.tiny_voca_status_missing), 13, ink);
        tinyVocaStatus.setId(R.id.settings_tiny_voca_status);
        StillwaterUi.styleCodeBlock(tinyVocaStatus);
        StillwaterReadable.prepareWrappingLabel(tinyVocaStatus);
        LinearLayout.LayoutParams vocaStatusParams = new LinearLayout.LayoutParams(-1, -2);
        vocaStatusParams.topMargin = host.dp(6);
        speechCard.addView(tinyVocaStatus, vocaStatusParams);

        tinyVocaDownloadButton = host.button(host.getString(R.string.tiny_voca_download));
        tinyVocaDownloadButton.setId(R.id.settings_tiny_voca_download);
        StillwaterUi.outlined(tinyVocaDownloadButton);
        tinyVocaDownloadButton.setOnClickListener(v -> deps.downloadTinyVocaPack.run());

        tinyVocaRemoveButton = host.button(host.getString(R.string.tiny_voca_remove));
        tinyVocaRemoveButton.setId(R.id.settings_tiny_voca_remove);
        StillwaterUi.dangerOutlined(tinyVocaRemoveButton);
        tinyVocaRemoveButton.setOnClickListener(v -> {
            new TinyVocaPackStore(host.settingsActivity()).clear();
            TinyVocaPhonemizerBridge.resetLoadedState();
            paintTinyVocaPackStatus();
            deps.updateControls.run();
        });
        StillwaterReadable.addFlexibleButtonRow(speechCard, host.settingsActivity(), 48,
                tinyVocaDownloadButton, tinyVocaRemoveButton);

        tinyVocaCancelButton = host.button(host.getString(R.string.model_download_cancel));
        tinyVocaCancelButton.setId(R.id.settings_tiny_voca_cancel);
        StillwaterUi.dangerOutlined(tinyVocaCancelButton);
        tinyVocaCancelButton.setOnClickListener(v -> {
            ModelDownloadManager.getInstance(host.settingsActivity()).cancel(ModelDownloadKind.TINY_VOCA);
            paintTinyVocaPackStatus();
        });
        StillwaterReadable.addFlexibleFullWidth(speechCard, tinyVocaCancelButton, 48);
    }

    void syncControls() {
        if (networkToolsBox == null) return;
        RuntimePreferences prefs = deps.preferences.get();
        bindingFields = true;
        networkToolsBox.setChecked(prefs.networkToolsEnabled());
        if (notificationAccessBox != null) notificationAccessBox.setChecked(prefs.notificationAccessEnabled());
        if (notificationAllowlistField != null) {
            StringBuilder allowlistText = new StringBuilder();
            for (String pkg : prefs.notificationAllowlist()) {
                if (allowlistText.length() > 0) allowlistText.append('\n');
                allowlistText.append(pkg);
            }
            notificationAllowlistField.setText(allowlistText.toString());
        }
        if (tinyVocaTtsBox != null) tinyVocaTtsBox.setChecked(prefs.tinyVocaEnglishTtsEnabled());
        paintTinyVocaPackStatus();
        paintSpeechInputControls();
        refreshCalendarPickerStatus();
        bindingFields = false;
    }

    void syncFields() {
        if (networkToolsBox == null) return;
        RuntimePreferences prefs = deps.preferences.get();
        bindingFields = true;
        networkToolsBox.setChecked(prefs.networkToolsEnabled());
        if (tinyVocaTtsBox != null) tinyVocaTtsBox.setChecked(prefs.tinyVocaEnglishTtsEnabled());
        paintTinyVocaPackStatus();
        paintSpeechInputControls();
        bindingFields = false;
        refreshCalendarPickerStatus();
    }

    void refreshCalendarPickerStatus() {
        if (calendarStatusText == null) return;
        RuntimePreferences prefs = deps.preferences.get();
        String id = prefs.calendarWriteId();
        if (id == null || id.trim().isEmpty()) {
            calendarStatusText.setText(host.getString(R.string.calendar_none_picked));
            return;
        }
        String label = prefs.calendarWriteLabel();
        if (label == null || label.trim().isEmpty()) label = id.trim();
        else label = label.trim() + " (id " + id.trim() + ")";
        calendarStatusText.setText(host.getString(R.string.calendar_picked, label));
    }

    void paintSpeechInputControls() {
        RuntimePreferences prefs = deps.preferences.get();
        SpeechInputMode mode = prefs.speechInputMode();
        StillwaterUi.segmented(speechInputOmniButton, mode == SpeechInputMode.OMNI_AUDIO);
        StillwaterUi.segmented(speechInputWhistleButton, mode == SpeechInputMode.CACTUS_WHISTLE_STT);
        CactusWhistleModelStore store = new CactusWhistleModelStore(host.settingsActivity());
        String path = prefs.cactusWhistleModelPath();
        boolean hasPath = path != null && !path.trim().isEmpty();
        if (hasPath && !new File(path).isFile()) {
            prefs.setCactusWhistleModelPath("");
            hasPath = false;
        }
        if (!hasPath && store.isReady()) {
            path = store.modelFile().getAbsolutePath();
            prefs.setCactusWhistleModelPath(path);
            hasPath = true;
        }
        boolean downloading = CactusWhistleModelDownloader.isDownloading();
        if (speechInputCactusStatus != null) {
            if (downloading) {
                CharSequence current = speechInputCactusStatus.getText();
                String currentStr = current == null ? "" : current.toString();
                if (!currentStr.contains("%")) {
                    speechInputCactusStatus.setText(host.getString(R.string.model_download_starting));
                }
            } else {
                SttAvailability availability = CactusWhistleBridge.availability(path);
                String label;
                if (!hasPath) {
                    label = host.getString(R.string.speech_input_cactus_model_none);
                } else if (availability.available) {
                    String readyMsg = mode == SpeechInputMode.CACTUS_WHISTLE_STT
                            ? host.getString(R.string.speech_input_cactus_status_ready)
                            : host.getString(R.string.speech_input_cactus_ready_select_mode);
                    label = new File(path).getName() + " · " + readyMsg;
                } else {
                    label = new File(path).getName() + " · " + availability.message;
                }
                speechInputCactusStatus.setText(label);
            }
        }
        if (speechInputCactusDownloadButton != null) {
            speechInputCactusDownloadButton.setEnabled(!downloading && !store.isReady());
        }
        if (speechInputCactusCancelButton != null) {
            speechInputCactusCancelButton.setEnabled(downloading);
            speechInputCactusCancelButton.setVisibility(downloading ? View.VISIBLE : View.GONE);
        }
        if (speechInputCactusPickButton != null) {
            speechInputCactusPickButton.setEnabled(!downloading);
        }
        if (speechInputCactusClearButton != null) {
            speechInputCactusClearButton.setEnabled(!downloading && (hasPath || store.isReady()));
        }
    }

    void paintTinyVocaPackStatus() {
        if (tinyVocaStatus == null) return;
        boolean ready = new TinyVocaPackStore(host.settingsActivity()).isReady();
        boolean downloading = TinyVocaPackDownloader.isDownloading();
        if (downloading) {
            CharSequence current = tinyVocaStatus.getText();
            String currentStr = current == null ? "" : current.toString();
            if (!currentStr.contains("%")) {
                tinyVocaStatus.setText(host.getString(R.string.model_download_starting));
            }
        } else if (!ready) {
            tinyVocaStatus.setText(host.getString(R.string.tiny_voca_status_missing));
        } else if (!TinyVocaPhonemizerBridge.isNativeLibraryAvailable()) {
            tinyVocaStatus.setText(host.getString(R.string.tiny_voca_status_no_phonemizer));
        } else if (!deps.preferences.get().tinyVocaEnglishTtsEnabled()) {
            tinyVocaStatus.setText(host.getString(R.string.tiny_voca_status_ready_enable));
        } else {
            tinyVocaStatus.setText(host.getString(R.string.tiny_voca_status_ready));
        }
        if (tinyVocaDownloadButton != null) tinyVocaDownloadButton.setEnabled(!downloading && !ready);
        if (tinyVocaCancelButton != null) {
            tinyVocaCancelButton.setEnabled(downloading);
            tinyVocaCancelButton.setVisibility(downloading ? View.VISIBLE : View.GONE);
        }
        if (tinyVocaRemoveButton != null) tinyVocaRemoveButton.setEnabled(!downloading && ready);
    }

    private void saveNotificationAllowlist() {
        if (notificationAllowlistField == null) return;
        String raw = notificationAllowlistField.getText() == null
                ? "" : notificationAllowlistField.getText().toString();
        LinkedHashSet<String> packages = new LinkedHashSet<>();
        for (String line : raw.split("[\n,]+")) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) packages.add(trimmed);
        }
        deps.preferences.get().setNotificationAllowlist(packages);
        deps.chatStatus.accept(host.getString(R.string.notification_access_allowlist_saved));
    }
}
