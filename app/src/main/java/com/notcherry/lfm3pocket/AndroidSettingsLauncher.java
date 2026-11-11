package com.notcherry.lfm3pocket;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Android adapter for the fixed settings-page allowlist. */
final class AndroidSettingsLauncher implements SettingsPageController.Launcher {
    private final Context context;

    AndroidSettingsLauncher(Context context) {
        Context resolved = context;
        if (context != null) {
            try {
                Context application = context.getApplicationContext();
                if (application != null) resolved = application;
            } catch (RuntimeException ignored) {
                // Keep dependency-factory contract tests and unattached previews fail-closed.
            }
        }
        this.context = resolved;
    }

    @Override public void open(SettingsPageController.Page page) {
        if (context == null) throw new IllegalStateException("Android context is unavailable");
        try {
            context.startActivity(createIntent(page));
        } catch (ActivityNotFoundException e) {
            throw new SettingsPageController.NoSettingsPageException();
        }
    }

    static Intent createIntent(SettingsPageController.Page page) {
        if (page == null) throw new IllegalArgumentException("Missing settings page");
        String action;
        switch (page) {
            case WIFI: action = Settings.ACTION_WIFI_SETTINGS; break;
            case BLUETOOTH: action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case SOUND: action = Settings.ACTION_SOUND_SETTINGS; break;
            case DISPLAY: action = Settings.ACTION_DISPLAY_SETTINGS; break;
            case APPLICATION: action = Settings.ACTION_APPLICATION_SETTINGS; break;
            default: throw new IllegalArgumentException("Unsupported settings page");
        }
        return new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
