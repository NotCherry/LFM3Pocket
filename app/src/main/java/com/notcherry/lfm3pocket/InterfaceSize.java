package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * App-level interface scale applied on top of the system font scale / density.
 *
 * <p>Persisted as a multiplier from {@link #MIN} (80%) to {@link #MAX} (130%). Presets snap the
 * slider: Compact {@link #COMPACT}, Default {@link #DEFAULT}, Large {@link #LARGE}. The density
 * is scaled so both typography (sp) and spacing (dp) shrink or grow together while the system
 * {@code fontScale} continues to apply on top.
 */
public final class InterfaceSize {
    public static final float MIN = 0.80f;
    public static final float MAX = 1.30f;
    public static final float COMPACT = 0.85f;
    public static final float DEFAULT = 1.00f;
    public static final float LARGE = 1.15f;

    /** Accessibility minimum touch target in unscaled system dp. */
    public static final int MIN_TOUCH_DP = 48;

    private InterfaceSize() {}

    public static float clamp(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) return DEFAULT;
        return Math.max(MIN, Math.min(MAX, scale));
    }

    /** Snap a free slider value to the nearest named preset when within 0.02. */
    public static float nearestPreset(float scale) {
        float value = clamp(scale);
        float best = DEFAULT;
        float bestDelta = Math.abs(value - DEFAULT);
        for (float preset : new float[]{COMPACT, DEFAULT, LARGE}) {
            float delta = Math.abs(value - preset);
            if (delta < bestDelta) {
                best = preset;
                bestDelta = delta;
            }
        }
        return bestDelta <= 0.02f ? best : value;
    }

    public static boolean isCompact(float scale) {
        return Math.abs(clamp(scale) - COMPACT) <= 0.02f;
    }

    public static boolean isDefault(float scale) {
        return Math.abs(clamp(scale) - DEFAULT) <= 0.02f;
    }

    public static boolean isLarge(float scale) {
        return Math.abs(clamp(scale) - LARGE) <= 0.02f;
    }

    public static float read(Context context) {
        return new RuntimePreferences(context).interfaceScale();
    }

    public static void write(Context context, float scale) {
        new RuntimePreferences(context).setInterfaceScale(scale);
    }

    /**
     * Wraps {@code base} so densityDpi is multiplied by the persisted interface scale.
     * System {@code fontScale} is left intact and therefore stacks on top.
     */
    public static Context wrap(Context base) {
        float scale = read(base);
        if (Math.abs(scale - 1f) < 0.001f) return base;
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        int dpi = Math.round(configuration.densityDpi * scale);
        configuration.densityDpi = Math.max(120, dpi);
        return base.createConfigurationContext(configuration);
    }

    /**
     * Pixel size of a {@link #MIN_TOUCH_DP} target in <em>system</em> density, independent of the
     * interface scale. Use for View minWidth/minHeight so compact visuals keep a 48dp finger target.
     */
    public static int minTouchPx(Context context) {
        float scale = read(context);
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        // metrics.density already includes interface scale when the Context was wrapped.
        float systemDensity = metrics.density / Math.max(0.01f, scale);
        return Math.round(MIN_TOUCH_DP * systemDensity);
    }

    /** Percent label for the Settings slider (80–130). */
    public static int percent(float scale) {
        return Math.round(clamp(scale) * 100f);
    }

    public static float fromPercent(int percent) {
        return clamp(percent / 100f);
    }
}
