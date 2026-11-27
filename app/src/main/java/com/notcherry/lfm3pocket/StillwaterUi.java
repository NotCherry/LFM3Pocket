package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Shared Stillwater colors, window-inset helpers, and stateful chrome for View screens. */
final class StillwaterUi {
    static final int SURFACE = 0xFF0E0E0C;
    static final int RAISED = 0xFF161614;
    static final int OVERLAY = 0xFF1C1C18;
    static final int CODE_BG = 0xFF0A0A09;
    static final int INK = 0xFFF3EFE6;
    static final int SOFT = 0xFFC9C2B4;
    static final int MUTED = 0xFF8F897C;
    static final int DISABLED = 0xFFA39E92;
    static final int CANDLE = 0xFFE6C27A;
    static final int CANDLE_PRESSED = 0xFFD2AE66;
    static final int CANDLE_INK = 0xFF1C1508;
    static final int DANGER = 0xFFE08A78;
    static final int WARN = 0xFFC4A35A;
    static final int OK = 0xFF9DBEA4;
    static final int LINE = 0xFF2C2B28;
    static final int BORDER_STRONG = 0xFF4A473F;
    static final int PRESSED_FILL = 0xFF22211D;

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

    /**
     * Configures dark Stillwater system bars and applies status-bar, navigation-bar, and IME
     * keyboard insets on top of the base content padding (critical for Android 15 targetSdk 35).
     */
    static void applySystemAndImeInsets(Activity activity, View root,
                                        int baseLeftPx, int baseTopPx, int baseRightPx, int baseBottomPx) {
        Window window = activity.getWindow();
        if (window != null) {
            if (AndroidPermissionGate.usesLegacySystemBarColorApis(android.os.Build.VERSION.SDK_INT)) {
                window.setStatusBarColor(SURFACE);
                window.setNavigationBarColor(SURFACE);
            }
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(window, window.getDecorView());
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
        }
        root.setPadding(baseLeftPx, baseTopPx, baseRightPx, baseBottomPx);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(
                    baseLeftPx + insets.left,
                    baseTopPx + insets.top,
                    baseRightPx + insets.right,
                    baseBottomPx + insets.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static float cornerPx(Context context) {
        return 2f * context.getResources().getDisplayMetrics().density;
    }

    private static int strokePx(Context context) {
        return Math.max(1, Math.round(context.getResources().getDisplayMetrics().density));
    }

    private static GradientDrawable box(Context context, int fillColor, int borderColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(cornerPx(context));
        shape.setColor(fillColor);
        if (borderColor != Color.TRANSPARENT) {
            shape.setStroke(strokePx(context), borderColor);
        }
        return shape;
    }

    private static ColorStateList enabledTextColors(int enabledColor, int disabledColor) {
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{disabledColor, enabledColor}
        );
    }

    static void primary(Button button) {
        Context context = button.getContext();
        button.setAllCaps(false);
        button.setBackgroundTintList(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{-android.R.attr.state_enabled}, box(context, RAISED, LINE));
        bg.addState(new int[]{android.R.attr.state_pressed}, box(context, CANDLE_PRESSED, Color.TRANSPARENT));
        bg.addState(new int[]{}, box(context, CANDLE, Color.TRANSPARENT));
        button.setBackground(bg);
        button.setTextColor(enabledTextColors(CANDLE_INK, MUTED));
        button.setTypeface(sansMedium(context));
    }

    static void quiet(Button button) {
        Context context = button.getContext();
        button.setAllCaps(false);
        button.setBackgroundTintList(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, box(context, PRESSED_FILL, Color.TRANSPARENT));
        bg.addState(new int[]{}, box(context, Color.TRANSPARENT, Color.TRANSPARENT));
        button.setBackground(bg);
        button.setTextColor(enabledTextColors(SOFT, MUTED));
        button.setTypeface(sansMedium(context));
    }

    /** Subtle elevated surface with a 1dp hairline border for Settings cards and actions. */
    static void outlined(Button button) {
        Context context = button.getContext();
        button.setAllCaps(false);
        button.setBackgroundTintList(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{-android.R.attr.state_enabled}, box(context, SURFACE, LINE));
        bg.addState(new int[]{android.R.attr.state_pressed}, box(context, PRESSED_FILL, BORDER_STRONG));
        bg.addState(new int[]{}, box(context, OVERLAY, LINE));
        button.setBackground(bg);
        button.setTextColor(enabledTextColors(INK, MUTED));
        button.setTypeface(sansMedium(context));
        StillwaterReadable.setTextSizeSp(button, 14.5f);
    }

    /** Emphasized candle-text outlined action (e.g. model import / open). */
    static void accentOutlined(Button button) {
        Context context = button.getContext();
        button.setAllCaps(false);
        button.setBackgroundTintList(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{-android.R.attr.state_enabled}, box(context, SURFACE, LINE));
        bg.addState(new int[]{android.R.attr.state_pressed}, box(context, PRESSED_FILL, CANDLE));
        bg.addState(new int[]{}, box(context, OVERLAY, BORDER_STRONG));
        button.setBackground(bg);
        button.setTextColor(enabledTextColors(CANDLE, MUTED));
        button.setTypeface(sansMedium(context));
        StillwaterReadable.setTextSizeSp(button, 14.5f);
    }

    /** Unified Stillwater segmented picker state (selected = candle fill, unselected = outlined). */
    static void segmented(Button button, boolean selected) {
        if (button == null) return;
        button.setSelected(selected);
        if (selected) {
            primary(button);
            StillwaterReadable.setTextSizeSp(button, 14.5f);
        } else {
            outlined(button);
        }
    }

    /** Outlined destructive/reset control whose disabled state drops cleanly to muted charcoal. */
    static void dangerOutlined(Button button) {
        Context context = button.getContext();
        button.setAllCaps(false);
        button.setBackgroundTintList(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{-android.R.attr.state_enabled}, box(context, SURFACE, LINE));
        bg.addState(new int[]{android.R.attr.state_pressed}, box(context, PRESSED_FILL, DANGER));
        bg.addState(new int[]{}, box(context, OVERLAY, DANGER));
        button.setBackground(bg);
        button.setTextColor(enabledTextColors(DANGER, MUTED));
        button.setTypeface(sansMedium(context));
        StillwaterReadable.setTextSizeSp(button, 14.5f);
    }

    static void tintStatefulIcon(Button button) {
        button.setCompoundDrawableTintList(enabledTextColors(SOFT, MUTED));
    }

    /** Chalk text, candle caret, and a light hint. Disabled text stays above WCAG AA. */
    static void styleInput(EditText field) {
        field.setTextColor(enabledTextColors(INK, DISABLED));
        field.setHintTextColor(MUTED);
        field.setHighlightColor(0x66E6C27A);
        field.setLinkTextColor(CANDLE);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setColor(CANDLE);
            cursor.setSize(Math.max(2, (int) (2 * field.getResources().getDisplayMetrics().density)), 0);
            field.setTextCursorDrawable(cursor);
        }
    }

    /** Framed input box (`surface` recessed + `1dp` border, `borderStrong` when focused). */
    static void styleBoxedInput(EditText field) {
        Context context = field.getContext();
        styleInput(field);
        field.setBackgroundTintList(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, box(context, SURFACE, BORDER_STRONG));
        bg.addState(new int[]{}, box(context, SURFACE, LINE));
        field.setBackground(bg);
        int padH = Math.round(14 * context.getResources().getDisplayMetrics().density);
        int padV = Math.round(12 * context.getResources().getDisplayMetrics().density);
        field.setPadding(padH, padV, padH, padV);
    }

    static void styleCheck(CheckBox box) {
        Context context = box.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        box.setTextColor(enabledTextColors(INK, DISABLED));
        box.setButtonTintList(ContextCompat.getColorStateList(context, R.color.still_control));
        box.setHighlightColor(0x66E6C27A);
        box.setTypeface(sans(context));
        StillwaterReadable.setTextSizeSp(box, 15.5f);
        box.setLineSpacing(0f, 1.25f);
        box.setMinHeight(Math.round(48 * density));
        int padStart = Math.round(8 * density);
        int padV = Math.round(8 * density);
        box.setPaddingRelative(padStart, padV, 0, padV);
    }

    /** Creates an elevated Stillwater Settings section card with generous padding and bottom separation. */
    static android.widget.LinearLayout settingsCard(android.widget.LinearLayout parent, String kickerText) {
        Context context = parent.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(18 * density);
        android.widget.LinearLayout card = new android.widget.LinearLayout(context);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackground(box(context, RAISED, LINE));
        card.setPadding(pad, pad, pad, pad);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Math.round(20 * density);
        parent.addView(card, params);
        if (kickerText != null && !kickerText.isEmpty()) {
            TextView kicker = new TextView(context);
            kicker.setText(kickerText);
            StillwaterReadable.setTextSizeSp(kicker, 12f);
            kicker.setTextColor(CANDLE);
            kicker.setTypeface(sansMedium(context));
            kicker.setLetterSpacing(0.12f);
            kicker.setPadding(0, 0, 0, Math.round(10 * density));
            StillwaterReadable.prepareWrappingLabel(kicker);
            card.addView(kicker);
        }
        return card;
    }

    /** Subtle hairline divider separating logical sub-groups inside a Settings card. */
    static View settingsDivider(android.widget.LinearLayout card) {
        Context context = card.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        View line = new View(context);
        line.setBackgroundColor(LINE);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
        params.topMargin = Math.round(16 * density);
        params.bottomMargin = Math.round(14 * density);
        card.addView(line, params);
        return line;
    }

    /** Readable mobile-scale helper copy inside Settings cards. */
    static TextView settingsHelp(android.widget.LinearLayout card, String text) {
        Context context = card.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        TextView help = new TextView(context);
        help.setText(text);
        StillwaterReadable.setTextSizeSp(help, 13.5f);
        help.setTextColor(SOFT);
        help.setTypeface(sans(context));
        help.setLineSpacing(0f, 1.32f);
        help.setPadding(0, Math.round(4 * density), 0, Math.round(8 * density));
        StillwaterReadable.prepareWrappingLabel(help);
        card.addView(help);
        return help;
    }

    /** Readable recessed monospace output block (benchmark / crash log / build info). */
    static void styleCodeBlock(TextView view) {
        Context context = view.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        view.setTypeface(mono(context));
        StillwaterReadable.setTextSizeSp(view, 13.5f);
        view.setLineSpacing(0f, 1.3f);
        view.setBackground(box(context, CODE_BG, LINE));
        int padH = Math.round(14 * density);
        int padV = Math.round(12 * density);
        view.setPadding(padH, padV, padH, padV);
    }

    /** Applies Stillwater border, serif title, and mixed-case button styling to a shown AlertDialog. */
    static AlertDialog showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        Context context = dialog.getContext();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(box(context, OVERLAY, LINE));
        }
        int titleId = context.getResources().getIdentifier("alertTitle", "id", "android");
        if (titleId != 0) {
            View titleView = dialog.findViewById(titleId);
            if (titleView instanceof TextView) {
                TextView tv = (TextView) titleView;
                tv.setTypeface(serif(context));
                tv.setTextColor(INK);
            }
        }
        Button pos = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (pos != null) {
            pos.setAllCaps(false);
            pos.setTypeface(sansMedium(context));
            pos.setTextColor(CANDLE);
        }
        Button neg = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (neg != null) {
            neg.setAllCaps(false);
            neg.setTypeface(sansMedium(context));
            neg.setTextColor(SOFT);
        }
        Button neu = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (neu != null) {
            neu.setAllCaps(false);
            neu.setTypeface(sansMedium(context));
            neu.setTextColor(SOFT);
        }
        return dialog;
    }
}
