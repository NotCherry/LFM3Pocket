package com.notcherry.lfm3pocket;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings scroll row for choosing the device default assistant. */
final class DefaultAssistantSettingsSection {
    static final class Deps {
        final Runnable openDefaultAssistantPicker;

        Deps(Runnable openDefaultAssistantPicker) {
            this.openDefaultAssistantPicker = openDefaultAssistantPicker;
        }
    }

    private final SettingsUiFactory ui;
    private final Deps deps;

    DefaultAssistantSettingsSection(SettingsUiFactory ui, Deps deps) {
        this.ui = ui;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int inkMuted) {
        Button defaultAssistantButton = ui.button(ui.getString(R.string.default_assistant_button));
        defaultAssistantButton.setId(R.id.settings_default_assistant);
        StillwaterUi.quiet(defaultAssistantButton);
        defaultAssistantButton.setOnClickListener(v -> deps.openDefaultAssistantPicker.run());
        StillwaterReadable.addFlexibleFullWidth(configuration, defaultAssistantButton, 48);
        TextView defaultAssistantHelp = ui.label(ui.getString(R.string.default_assistant_help), 12, inkMuted);
        StillwaterReadable.prepareWrappingLabel(defaultAssistantHelp);
        configuration.addView(defaultAssistantHelp);
    }
}
