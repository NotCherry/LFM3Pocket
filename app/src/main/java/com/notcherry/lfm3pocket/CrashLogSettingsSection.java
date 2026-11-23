package com.notcherry.lfm3pocket;

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
        TextView crashHeading = ui.label(ui.getString(R.string.settings_crash_log_heading), 11, inkMuted);
        crashHeading.setTypeface(StillwaterUi.sansMedium(ui.settingsActivity()));
        crashHeading.setLetterSpacing(0.12f);
        crashHeading.setPadding(0, ui.dp(16), 0, ui.dp(4));
        configuration.addView(crashHeading);
        configuration.addView(ui.label(ui.getString(R.string.settings_crash_log_body), 14, inkMuted));
        crashLogText = ui.label(ui.getString(R.string.settings_crash_log_empty), 13, ink);
        crashLogText.setId(R.id.settings_crash_log_text);
        crashLogText.setPadding(0, ui.dp(8), 0, ui.dp(8));
        configuration.addView(crashLogText);
        Button viewCrashLog = ui.button(ui.getString(R.string.settings_crash_log_view));
        viewCrashLog.setId(R.id.settings_crash_log_view);
        StillwaterUi.quiet(viewCrashLog);
        viewCrashLog.setOnClickListener(v -> {
            String body = CrashLog.read(ui.settingsActivity());
            crashLogText.setText(body.isEmpty() ? ui.getString(R.string.settings_crash_log_empty) : body);
        });
        configuration.addView(viewCrashLog, new LinearLayout.LayoutParams(-1, ui.dp(44)));
        Button exportCrashLog = ui.button(ui.getString(R.string.settings_crash_log_export));
        exportCrashLog.setId(R.id.settings_crash_log_export);
        StillwaterUi.quiet(exportCrashLog);
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
        configuration.addView(exportCrashLog, new LinearLayout.LayoutParams(-1, ui.dp(44)));
        Button clearCrashLog = ui.button(ui.getString(R.string.settings_crash_log_clear));
        clearCrashLog.setId(R.id.settings_crash_log_clear);
        StillwaterUi.quiet(clearCrashLog);
        clearCrashLog.setOnClickListener(v -> {
            CrashLog.clear(ui.settingsActivity());
            crashLogText.setText(ui.getString(R.string.settings_crash_log_empty));
        });
        configuration.addView(clearCrashLog, new LinearLayout.LayoutParams(-1, ui.dp(44)));
    }
}
