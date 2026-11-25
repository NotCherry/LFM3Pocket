package com.notcherry.lfm3pocket;

import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/** Settings scroll section for viewing, exporting, and clearing local crash logs. */
final class CrashLogSettingsSection {
    static final class Deps {
        final Consumer<CharSequence> chatStatus;

        Deps(Consumer<CharSequence> chatStatus) {
            this.chatStatus = chatStatus;
        }
    }

    private final SettingsUiFactory ui;
    private final Deps deps;
    private TextView crashLogText;

    CrashLogSettingsSection(SettingsUiFactory ui, Deps deps) {
        this.ui = ui;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        LinearLayout card = StillwaterUi.settingsCard(configuration,
                ui.getString(R.string.settings_crash_log_heading));
        StillwaterUi.settingsHelp(card, ui.getString(R.string.settings_crash_log_body));

        crashLogText = ui.label(ui.getString(R.string.settings_crash_log_empty), 13, ink);
        crashLogText.setId(R.id.settings_crash_log_text);
        StillwaterUi.styleCodeBlock(crashLogText);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, -2);
        logParams.topMargin = ui.dp(4);
        logParams.bottomMargin = ui.dp(6);
        card.addView(crashLogText, logParams);

        Button viewCrashLog = ui.button(ui.getString(R.string.settings_crash_log_view));
        viewCrashLog.setId(R.id.settings_crash_log_view);
        StillwaterUi.outlined(viewCrashLog);
        viewCrashLog.setOnClickListener(v -> {
            String body = CrashLog.read(ui.settingsActivity());
            crashLogText.setText(body.isEmpty() ? ui.getString(R.string.settings_crash_log_empty) : body);
        });

        Button exportCrashLog = ui.button(ui.getString(R.string.settings_crash_log_export));
        exportCrashLog.setId(R.id.settings_crash_log_export);
        StillwaterUi.outlined(exportCrashLog);
        exportCrashLog.setOnClickListener(v -> {
            String body = CrashLog.read(ui.settingsActivity());
            if (body.isEmpty()) body = ui.getString(R.string.settings_crash_log_empty);
            android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(android.content.Intent.EXTRA_SUBJECT, "Hanami crash log");
            share.putExtra(android.content.Intent.EXTRA_TEXT, body);
            ui.settingsActivity().startActivity(android.content.Intent.createChooser(share,
                    ui.getString(R.string.settings_crash_log_export)));
            deps.chatStatus.accept(ui.getString(R.string.settings_crash_log_exported));
        });
        StillwaterReadable.addFlexibleButtonRow(card, ui.settingsActivity(), 48,
                viewCrashLog, exportCrashLog);

        Button clearCrashLog = ui.button(ui.getString(R.string.settings_crash_log_clear));
        clearCrashLog.setId(R.id.settings_crash_log_clear);
        StillwaterUi.dangerOutlined(clearCrashLog);
        clearCrashLog.setOnClickListener(v -> {
            CrashLog.clear(ui.settingsActivity());
            crashLogText.setText(ui.getString(R.string.settings_crash_log_empty));
        });
        StillwaterReadable.addFlexibleFullWidth(card, clearCrashLog, 48);
    }
}
