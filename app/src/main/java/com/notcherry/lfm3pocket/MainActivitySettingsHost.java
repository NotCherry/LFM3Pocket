package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.os.Handler;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings-scroll hooks the section controllers need from {@link MainActivity},
 * without taking a hard reference to the activity class. Extends {@link SettingsUiFactory}
 * for shared label/button/dp helpers; chat status is not on this contract (see
 * {@link ChatShellSection} and ADR 0024 amended in #292).
 */
interface MainActivitySettingsHost extends SettingsUiFactory {
    boolean isHostFinishing();

    Handler settingsMainHandler();

    void prepareSettingsRecreateForInterfaceSize();

    EditText addSettingInput(LinearLayout form, String title, String hint, int value, int id,
            int step, int min, int max);
}
