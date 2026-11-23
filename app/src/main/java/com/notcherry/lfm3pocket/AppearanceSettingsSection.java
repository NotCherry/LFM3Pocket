package com.notcherry.lfm3pocket;

import android.os.Handler;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Settings scroll section for interface scale: presets, slider, and density recreate.
 * Owns view wiring and paint logic; {@link MainActivity} keeps lifecycle and activity results.
 */
final class AppearanceSettingsSection {
    private final MainActivity activity;
    private final Handler mainHandler;
    private Button interfaceCompactButton;
    private Button interfaceDefaultButton;
    private Button interfaceLargeButton;
    private SeekBar interfaceSizeSlider;
    private TextView interfaceSizeValue;
    private boolean bindingInterfaceSize;
    private boolean interfaceSliderTracking;
    private float pendingInterfaceSliderScale = InterfaceSize.DEFAULT;
    private final Runnable applyInterfaceSlider;

    AppearanceSettingsSection(MainActivity activity, Handler mainHandler) {
        this.activity = activity;
        this.mainHandler = mainHandler;
        this.applyInterfaceSlider = () -> {
            if (activity.isDestroyedFlag() || bindingInterfaceSize || interfaceSliderTracking) return;
            applyInterfaceSize(pendingInterfaceSliderScale);
        };
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        LinearLayout section = new LinearLayout(activity);
        section.setId(R.id.settings_appearance);
        section.setOrientation(LinearLayout.VERTICAL);
        TextView heading = activity.createLabel(activity.getString(R.string.appearance_heading), 11, inkMuted);
        heading.setTypeface(StillwaterUi.sansMedium(activity));
        heading.setLetterSpacing(0.12f);
        heading.setPadding(0, activity.dp(16), 0, activity.dp(4));
        section.addView(heading);
        TextView title = activity.createLabel(activity.getString(R.string.interface_size_label), 14, ink);
        StillwaterReadable.prepareWrappingLabel(title);
        section.addView(title);
        TextView help = activity.createLabel(activity.getString(R.string.interface_size_help), 12, inkMuted);
        StillwaterReadable.prepareWrappingLabel(help);
        section.addView(help);

        interfaceCompactButton = interfacePreset(activity.getString(R.string.interface_size_compact),
                InterfaceSize.COMPACT, R.id.settings_interface_compact);
        interfaceDefaultButton = interfacePreset(activity.getString(R.string.interface_size_default),
                InterfaceSize.DEFAULT, R.id.settings_interface_default);
        interfaceLargeButton = interfacePreset(activity.getString(R.string.interface_size_large),
                InterfaceSize.LARGE, R.id.settings_interface_large);
        LinearLayout presets = StillwaterReadable.addFlexibleButtonRow(section, activity, 44,
                interfaceCompactButton, interfaceDefaultButton, interfaceLargeButton);
        presets.setId(R.id.settings_interface_size);

        interfaceSizeValue = activity.createLabel("", 12, inkMuted);
        interfaceSizeValue.setId(R.id.settings_interface_value);
        StillwaterReadable.prepareWrappingLabel(interfaceSizeValue);
        interfaceSizeValue.setPadding(0, activity.dp(8), 0, activity.dp(2));
        section.addView(interfaceSizeValue);

        interfaceSizeSlider = new SeekBar(activity);
        interfaceSizeSlider.setId(R.id.settings_interface_slider);
        interfaceSizeSlider.setMax(50); // 80..130 in 1% steps
        interfaceSizeSlider.setMinimumHeight(InterfaceSize.minTouchPx(activity));
        interfaceSizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = InterfaceSize.fromPercent(80 + progress);
                interfaceSizeValue.setText(activity.getString(R.string.interface_size_percent,
                        InterfaceSize.percent(scale)));
                if (!fromUser || bindingInterfaceSize) return;
                highlightInterfaceSize(scale);
                // TalkBack / D-pad change progress without start/stop tracking. Debounce those
                // writes so finger drags still apply only on stop.
                if (!interfaceSliderTracking) {
                    pendingInterfaceSliderScale = scale;
                    mainHandler.removeCallbacks(applyInterfaceSlider);
                    mainHandler.postDelayed(applyInterfaceSlider, 350);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {
                interfaceSliderTracking = true;
                mainHandler.removeCallbacks(applyInterfaceSlider);
            }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                interfaceSliderTracking = false;
                if (bindingInterfaceSize) return;
                applyInterfaceSize(InterfaceSize.fromPercent(80 + seekBar.getProgress()));
            }
        });
        section.addView(interfaceSizeSlider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        configuration.addView(section);
        syncControls();
    }

    private Button interfacePreset(String title, float scale, int id) {
        Button presetButton = activity.createButton(title);
        presetButton.setId(id);
        StillwaterUi.quiet(presetButton);
        presetButton.setOnClickListener(v -> {
            if (bindingInterfaceSize) return;
            applyInterfaceSize(scale);
        });
        return presetButton;
    }

    private void applyInterfaceSize(float scale) {
        float next = InterfaceSize.clamp(scale);
        if (Math.abs(next - InterfaceSize.read(activity)) < 0.001f) {
            syncControls();
            return;
        }
        InterfaceSize.write(activity, next);
        activity.prepareSettingsRecreateForInterfaceSize();
    }

    void syncControls() {
        if (interfaceSizeSlider == null) return;
        bindingInterfaceSize = true;
        float scale = InterfaceSize.read(activity);
        interfaceSizeSlider.setProgress(InterfaceSize.percent(scale) - 80);
        interfaceSizeValue.setText(activity.getString(R.string.interface_size_percent,
                InterfaceSize.percent(scale)));
        highlightInterfaceSize(scale);
        bindingInterfaceSize = false;
    }

    void dispose() {
        mainHandler.removeCallbacks(applyInterfaceSlider);
    }

    private void highlightInterfaceSize(float scale) {
        styleInterfacePreset(interfaceCompactButton, InterfaceSize.isCompact(scale));
        styleInterfacePreset(interfaceDefaultButton, InterfaceSize.isDefault(scale));
        styleInterfacePreset(interfaceLargeButton, InterfaceSize.isLarge(scale));
    }

    private void styleInterfacePreset(Button button, boolean selected) {
        if (button == null) return;
        if (selected) StillwaterUi.primary(button);
        else StillwaterUi.quiet(button);
    }
}