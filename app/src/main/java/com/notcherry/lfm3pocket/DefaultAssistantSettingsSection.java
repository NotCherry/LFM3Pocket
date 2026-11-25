package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/** Settings scroll row for choosing the device default assistant. */
final class DefaultAssistantSettingsSection {
    static final class Deps {
        final Consumer<Intent> launchActivity;
        final Consumer<Intent> launchActivityForResult;
        final Consumer<CharSequence> chatStatus;

        Deps(Consumer<Intent> launchActivity, Consumer<CharSequence> chatStatus) {
            this(launchActivity, launchActivity, chatStatus);
        }

        Deps(Consumer<Intent> launchActivity, Consumer<Intent> launchActivityForResult,
                Consumer<CharSequence> chatStatus) {
            this.launchActivity = launchActivity;
            this.launchActivityForResult = launchActivityForResult;
            this.chatStatus = chatStatus;
        }
    }

    private final SettingsUiFactory ui;
    private final Deps deps;

    DefaultAssistantSettingsSection(SettingsUiFactory ui, Deps deps) {
        this.ui = ui;
        this.deps = deps;
    }

    void mount(LinearLayout configuration, int inkMuted) {
        LinearLayout card = StillwaterUi.settingsCard(configuration,
                ui.getString(R.string.default_assistant_heading));
        StillwaterUi.settingsHelp(card, ui.getString(R.string.default_assistant_help));
        Button defaultAssistantButton = ui.button(ui.getString(R.string.default_assistant_button));
        defaultAssistantButton.setId(R.id.settings_default_assistant);
        StillwaterUi.outlined(defaultAssistantButton);
        defaultAssistantButton.setOnClickListener(v -> openDefaultAssistantPicker());
        StillwaterReadable.addFlexibleFullWidth(card, defaultAssistantButton, 48);
    }

    private void openDefaultAssistantPicker() {
        Activity activity = ui.settingsActivity();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roles = activity.getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                    deps.launchActivityForResult.accept(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT));
                    return;
                }
            }
            deps.launchActivity.accept(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
        } catch (Exception e) {
            try {
                deps.launchActivity.accept(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
            } catch (Exception nested) {
                deps.chatStatus.accept("Could not open the default-assistant settings.");
            }
        }
    }
}
