package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.os.Handler;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * UI and lifecycle hooks the settings scroll sections need from {@link MainActivity},
 * without taking a hard reference to the activity class.
 */
interface MainActivitySettingsHost {
    Activity settingsActivity();

    int dp(int n);

    TextView label(String s, int sp, int color);

    Button button(String title);

    String getString(int resId);

    String getString(int resId, Object... formatArgs);

    boolean isHostFinishing();

    Handler settingsMainHandler();

    void prepareSettingsRecreateForInterfaceSize();

    void setChatStatus(CharSequence message);

    EditText addSettingInput(LinearLayout form, String title, String hint, int value, int id,
            int step, int min, int max);
}