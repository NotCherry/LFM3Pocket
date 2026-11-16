package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;

import androidx.core.content.res.ResourcesCompat;

/** Shared Stillwater colors and button chrome for the view-based screens. */
final class StillwaterUi {
    static final int SURFACE = 0xFF0E0E0C;
    static final int RAISED = 0xFF161614;
    static final int INK = 0xFFF3EFE6;
    static final int SOFT = 0xFFC9C2B4;
    static final int MUTED = 0xFF8F897C;
    static final int CANDLE = 0xFFE6C27A;
    static final int CANDLE_INK = 0xFF1C1508;
    static final int DANGER = 0xFFE08A78;
    static final int OK = 0xFF9DBEA4;
    static final int LINE = 0xFF2C2B28;

    private StillwaterUi() {}

    static Typeface serif(Context context) {
        Typeface face = ResourcesCompat.getFont(context, R.font.noto_serif_display_regular);
        return face != null ? face : Typeface.SERIF;
    }

    static Typeface sans(Context context) {
        Typeface face = ResourcesCompat.getFont(context, R.font.inter_regular);
        return face != null ? face : Typeface.SANS_SERIF;
    }

    static Typeface sansMedium(Context context) {
        Typeface face = ResourcesCompat.getFont(context, R.font.inter_medium);
        return face != null ? face : Typeface.SANS_SERIF;
    }

    static Typeface mono(Context context) {
        Typeface face = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular);
        return face != null ? face : Typeface.MONOSPACE;
    }

    static void primary(Button button) {
        button.setAllCaps(false);
        button.setBackgroundTintList(null);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(CANDLE);
        button.setBackground(fill);
        button.setTextColor(CANDLE_INK);
        button.setTypeface(sansMedium(button.getContext()));
    }

    static void quiet(Button button) {
        button.setAllCaps(false);
        button.setBackgroundTintList(null);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTextColor(SOFT);
        button.setTypeface(sansMedium(button.getContext()));
    }

    static void dangerQuiet(Button button) {
        quiet(button);
        button.setTextColor(DANGER);
    }

    static void tintIcon(Button button, int color) {
        button.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }
}
