package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.widget.Button;
import android.widget.TextView;

/** Narrow UI factory shared by settings sections and the chat shell (ADR 0024). */
interface SettingsUiFactory {
    Activity settingsActivity();

    int dp(int n);

    TextView label(String s, int sp, int color);

    Button button(String title);

    String getString(int resId);

    String getString(int resId, Object... formatArgs);
}
