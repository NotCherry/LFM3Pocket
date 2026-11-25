package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared numeric stepper row builder used by {@link MainActivitySettingsHost#addSettingInput} (#322).
 */
final class SettingStepperHelper {
    private SettingStepperHelper() { }

    static EditText addSettingInput(SettingsUiFactory ui, LinearLayout form, String title, String hint,
            int value, int id, int step, int min, int max) {
        Activity activity = ui.settingsActivity();
        int minTouch = Math.max(ui.dp(48), InterfaceSize.minTouchPx(activity));
        TextView name = ui.label(title, 16, StillwaterUi.INK);
        name.setTypeface(StillwaterUi.sansMedium(activity));
        name.setPadding(0, ui.dp(12), 0, ui.dp(2));
        StillwaterReadable.prepareWrappingLabel(name);
        form.addView(name);
        TextView hintView = ui.label(hint, 13, StillwaterUi.SOFT);
        hintView.setTypeface(StillwaterUi.sans(activity));
        hintView.setLineSpacing(0f, 1.25f);
        hintView.setPadding(0, 0, 0, ui.dp(8));
        StillwaterReadable.prepareWrappingLabel(hintView);
        form.addView(hintView);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int stepperWidth = Math.max(ui.dp(56), minTouch);
        Button minus = ui.button("–");
        StillwaterUi.outlined(minus);
        StillwaterReadable.setTextSizeSp(minus, 20);
        minus.setMinWidth(stepperWidth);
        minus.setMinimumWidth(stepperWidth);
        minus.setMinimumHeight(minTouch);
        StillwaterReadable.prepareWrappingControl(minus, minTouch);
        EditText field = new EditText(activity);
        field.setId(id);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(Integer.toString(value));
        StillwaterReadable.setTextSizeSp(field, 16);
        field.setGravity(Gravity.CENTER);
        field.setTypeface(StillwaterUi.mono(activity));
        StillwaterUi.styleBoxedInput(field);
        field.setMinimumHeight(minTouch);
        Button plus = ui.button("+");
        StillwaterUi.outlined(plus);
        StillwaterReadable.setTextSizeSp(plus, 20);
        plus.setMinWidth(stepperWidth);
        plus.setMinimumWidth(stepperWidth);
        plus.setMinimumHeight(minTouch);
        StillwaterReadable.prepareWrappingControl(plus, minTouch);
        minus.setOnClickListener(v -> nudge(field, -step, min, max));
        plus.setOnClickListener(v -> nudge(field, step, min, max));
        row.addView(minus, new LinearLayout.LayoutParams(stepperWidth, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        fieldParams.leftMargin = ui.dp(8);
        fieldParams.rightMargin = ui.dp(8);
        row.addView(field, fieldParams);
        row.addView(plus, new LinearLayout.LayoutParams(stepperWidth, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = ui.dp(4);
        form.addView(row, rowParams);
        return field;
    }

    static int clampNudge(String raw, int delta, int min, int max) {
        int current;
        try {
            current = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException invalid) {
            current = min;
        }
        return Math.max(min, Math.min(max, current + delta));
    }

    private static void nudge(EditText field, int delta, int min, int max) {
        int next = clampNudge(field.getText().toString(), delta, min, max);
        field.setText(Integer.toString(next));
    }
}
