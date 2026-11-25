package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Layout helpers so Stillwater chrome stays readable at large fontScale / display size
 * and on narrow widths. Prefer wrapping + minHeight over fixed heights or shrinking text.
 *
 * Side-by-side rows stay horizontal on typical phone widths (~360–411dp) at default font;
 * they stack only when fontScale is elevated or the screen is extremely narrow, because
 * WRAP_CONTENT height already keeps wrapped labels fully visible in a two-column row.
 */
final class StillwaterReadable {
    /** Extreme narrow width (dp) — below a ~320dp “small phone” class. */
    static final int STACK_BELOW_WIDTH_DP = 320;
    static final float STACK_AT_FONT_SCALE = 1.3f;

    private StillwaterReadable() {}

    static boolean shouldStack(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        return shouldStack(configuration.fontScale, configuration.screenWidthDp);
    }

    /** Pure decision used by layout and JVM unit tests. */
    static boolean shouldStack(float fontScale, int widthDp) {
        return shouldStack(fontScale, widthDp, STACK_BELOW_WIDTH_DP, STACK_AT_FONT_SCALE);
    }

    static boolean shouldStack(float fontScale, int widthDp, int stackBelowWidthDp, float stackAtFontScale) {
        if (fontScale >= stackAtFontScale) return true;
        return widthDp > 0 && widthDp < stackBelowWidthDp;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Allow multi-line labels; never ellipsize chrome buttons. */
    static void prepareWrappingControl(TextView view, int minHeightPx) {
        view.setSingleLine(false);
        view.setMaxLines(6);
        view.setEllipsize(null);
        view.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        if (minHeightPx > 0) view.setMinHeight(minHeightPx);
        if (view instanceof Button) {
            ((Button) view).setAllCaps(false);
        }
    }

    static void prepareWrappingLabel(TextView view) {
        view.setSingleLine(false);
        view.setMaxLines(Integer.MAX_VALUE);
        view.setEllipsize(null);
    }

    /**
     * Adds {@code buttons} in a horizontal row, or stacked vertically when fontScale is
     * large or the width is extremely narrow. Heights are WRAP_CONTENT with a minHeight floor,
     * so wrapped labels stay fully visible even while side-by-side.
     */
    static LinearLayout addFlexibleButtonRow(LinearLayout parent, Context context, int minHeightDp,
                                             Button... buttons) {
        return addFlexibleButtonRow(parent, context, minHeightDp, null, buttons);
    }

    static LinearLayout addFlexibleButtonRow(LinearLayout parent, Context context, int minHeightDp,
                                             float[] weights, Button... buttons) {
        boolean stack = shouldStack(context);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int minHeightPx = Math.max(dp(context, minHeightDp), InterfaceSize.minTouchPx(context));
        for (int i = 0; i < buttons.length; i++) {
            Button button = buttons[i];
            prepareWrappingControl(button, minHeightPx);
            LinearLayout.LayoutParams params;
            if (stack) {
                params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) params.topMargin = dp(context, 10);
            } else {
                float weight = (weights != null && i < weights.length) ? weights[i] : 1f;
                params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
                if (i > 0) params.leftMargin = dp(context, 8);
            }
            row.addView(button, params);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(context, 8);
        parent.addView(row, rowParams);
        return row;
    }

    /** Full-width control with WRAP_CONTENT height and a minHeight floor. */
    static void addFlexibleFullWidth(LinearLayout parent, View child, int minHeightDp) {
        int minHeightPx = Math.max(dp(parent.getContext(), minHeightDp),
                InterfaceSize.minTouchPx(parent.getContext()));
        if (child instanceof TextView) {
            prepareWrappingControl((TextView) child, minHeightPx);
        } else {
            child.setMinimumHeight(minHeightPx);
        }
        LinearLayout.LayoutParams fullParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fullParams.topMargin = dp(parent.getContext(), 10);
        parent.addView(child, fullParams);
    }

    /**
     * True when a TextView's laid-out text fits inside its bounds (no vertical clip,
     * no ellipsis truncation). Call after layout.
     */
    static boolean textFullyVisible(TextView view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return true;
        CharSequence text = view.getText();
        if (text == null || text.length() == 0) return true;
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        android.text.Layout layout = view.getLayout();
        if (layout == null) return false;
        for (int i = 0; i < layout.getLineCount(); i++) {
            if (layout.getEllipsisCount(i) > 0) return false;
        }
        int textBottom = layout.getLineBottom(layout.getLineCount() - 1);
        int available = view.getHeight() - view.getCompoundPaddingTop() - view.getCompoundPaddingBottom();
        // 1px tolerance for density rounding.
        return textBottom <= available + 1;
    }

    static boolean textFullyVisible(View view) {
        return view instanceof TextView && textFullyVisible((TextView) view);
    }

    /** sp-sized text helper — never use dp for readable copy. */
    static void setTextSizeSp(TextView view, float sp) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }
}
