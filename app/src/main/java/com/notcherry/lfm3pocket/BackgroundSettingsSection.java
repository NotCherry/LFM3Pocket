package com.notcherry.lfm3pocket;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Settings scroll section for keep-loaded, idle unload, lock preview, and loopback. */
final class BackgroundSettingsSection {
    static final class Deps {
        final Supplier<RuntimePreferences> preferences;
        final BackgroundApply apply;
        final Supplier<String> loopbackToken;
        final IntSupplier loopbackPort;
        final Consumer<CharSequence> chatStatus;

        Deps(Supplier<RuntimePreferences> preferences, BackgroundApply apply,
                Supplier<String> loopbackToken, IntSupplier loopbackPort, Consumer<CharSequence> chatStatus) {
            this.preferences = preferences;
            this.apply = apply;
            this.loopbackToken = loopbackToken;
            this.loopbackPort = loopbackPort;
            this.chatStatus = chatStatus;
        }
    }

    interface BackgroundApply {
        void apply(boolean keepLoaded, int idleMinutes, boolean lockPreview, boolean loopback);
    }

    private final MainActivitySettingsHost host;
    private final Deps deps;
    private boolean bindingBackground;
    private CheckBox keepLoadedBox;
    private Button idleOffButton;
    private Button idle15Button;
    private Button idle60Button;
    private CheckBox lockPreviewBox;
    private CheckBox loopbackBox;

    BackgroundSettingsSection(MainActivitySettingsHost host, Deps deps) {
        this.host = host;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        TextView backgroundHeading = host.label(host.getString(R.string.background_heading), 11, inkMuted);
        backgroundHeading.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        backgroundHeading.setLetterSpacing(0.12f);
        backgroundHeading.setPadding(0, host.dp(18), 0, host.dp(4));
        configuration.addView(backgroundHeading);

        keepLoadedBox = new CheckBox(host.settingsActivity());
        keepLoadedBox.setId(R.id.settings_keep_loaded);
        keepLoadedBox.setText(R.string.keep_model_loaded);
        StillwaterUi.styleCheck(keepLoadedBox);
        keepLoadedBox.setOnCheckedChangeListener((button, checked) -> saveBackgroundSettings());
        configuration.addView(keepLoadedBox);

        TextView idleTitle = host.label(host.getString(R.string.unload_after_idle), 15, ink);
        idleTitle.setTypeface(StillwaterUi.sansMedium(host.settingsActivity()));
        idleTitle.setPadding(0, host.dp(8), 0, host.dp(2));
        configuration.addView(idleTitle);

        idleOffButton = idleChoice(host.getString(R.string.idle_off), 0, R.id.settings_idle_off);
        idle15Button = idleChoice(host.getString(R.string.idle_15), 15, R.id.settings_idle_15);
        idle60Button = idleChoice(host.getString(R.string.idle_60), 60, R.id.settings_idle_60);
        LinearLayout idleRow = StillwaterReadable.addFlexibleButtonRow(
                configuration, host.settingsActivity(), 44, idleOffButton, idle15Button, idle60Button);
        idleRow.setId(R.id.settings_idle_unload);

        lockPreviewBox = new CheckBox(host.settingsActivity());
        lockPreviewBox.setId(R.id.settings_lock_preview);
        lockPreviewBox.setText(R.string.lock_screen_preview);
        StillwaterUi.styleCheck(lockPreviewBox);
        lockPreviewBox.setOnCheckedChangeListener((button, checked) -> saveBackgroundSettings());
        LinearLayout.LayoutParams lockParams = new LinearLayout.LayoutParams(-1, -2);
        lockParams.topMargin = host.dp(6);
        configuration.addView(lockPreviewBox, lockParams);

        loopbackBox = new CheckBox(host.settingsActivity());
        loopbackBox.setId(R.id.settings_loopback);
        loopbackBox.setText(R.string.loopback_prompts);
        StillwaterUi.styleCheck(loopbackBox);
        loopbackBox.setOnCheckedChangeListener((button, checked) -> saveBackgroundSettings());
        configuration.addView(loopbackBox);

        Button loopbackTokenButton = host.button(host.getString(R.string.loopback_show_token));
        loopbackTokenButton.setId(R.id.settings_loopback_token);
        StillwaterUi.outlined(loopbackTokenButton);
        loopbackTokenButton.setOnClickListener(v -> showLoopbackToken());
        StillwaterReadable.addFlexibleFullWidth(configuration, loopbackTokenButton, 44);
    }

    void syncControls() {
        if (keepLoadedBox == null) return;
        RuntimePreferences prefs = deps.preferences.get();
        bindingBackground = true;
        keepLoadedBox.setChecked(prefs.keepLoaded());
        lockPreviewBox.setChecked(prefs.lockPreview());
        loopbackBox.setChecked(prefs.loopbackEnabled());
        paintIdle(prefs.idleMinutes());
        bindingBackground = false;
    }

    private void saveBackgroundSettings() {
        if (bindingBackground || keepLoadedBox == null) return;
        int idle = idle15Button.isSelected() ? 15 : idle60Button.isSelected() ? 60 : 0;
        boolean loopback = loopbackBox.isChecked();
        deps.apply.apply(keepLoadedBox.isChecked(), idle, lockPreviewBox.isChecked(), loopback);
        paintIdle(idle);
    }

    private void showLoopbackToken() {
        String token = deps.loopbackToken.get();
        int port = deps.loopbackPort.getAsInt();
        String message = host.getString(R.string.loopback_token_message, port, token);
        Context context = host.settingsActivity();
        LinearLayout dialogBody = new LinearLayout(context);
        dialogBody.setOrientation(LinearLayout.VERTICAL);
        dialogBody.setPadding(host.dp(20), host.dp(12), host.dp(20), host.dp(4));
        TextView codeView = host.label(message, 12, StillwaterUi.INK);
        codeView.setTypeface(StillwaterUi.mono(context));
        codeView.setTextIsSelectable(true);
        GradientDrawable codeBg = new GradientDrawable();
        codeBg.setColor(StillwaterUi.CODE_BG);
        codeBg.setCornerRadius(2f * context.getResources().getDisplayMetrics().density);
        codeBg.setStroke(Math.max(1, host.dp(1)), StillwaterUi.LINE);
        codeView.setBackground(codeBg);
        codeView.setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10));
        dialogBody.addView(codeView);

        StillwaterUi.showDialog(new AlertDialog.Builder(context)
                .setTitle(R.string.loopback_token_title)
                .setView(dialogBody)
                .setPositiveButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Hanami loopback token", token));
                        deps.chatStatus.accept(host.getString(R.string.loopback_token_copied));
                    }
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private Button idleChoice(String label, int minutes, int id) {
        Button choice = host.button(label);
        choice.setId(id);
        StillwaterUi.outlined(choice);
        choice.setOnClickListener(v -> {
            idleOffButton.setSelected(minutes == 0);
            idle15Button.setSelected(minutes == 15);
            idle60Button.setSelected(minutes == 60);
            saveBackgroundSettings();
        });
        return choice;
    }

    private void paintIdle(int minutes) {
        StillwaterUi.segmented(idleOffButton, minutes == 0);
        StillwaterUi.segmented(idle15Button, minutes == 15);
        StillwaterUi.segmented(idle60Button, minutes == 60);
    }
}
