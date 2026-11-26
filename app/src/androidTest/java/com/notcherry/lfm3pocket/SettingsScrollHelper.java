package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNotNull;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

/**
 * Deterministic, fast ScrollView helper for Settings UI E2E tests.
 *
 * <p>UiAutomator's {@code UiScrollable.scrollIntoView} performs up to 30 slow 55-step touch swipes
 * on the long Settings {@link ScrollView} (often 15-35s per call). Scrolling the real ScrollView
 * directly keeps all UiDevice click, text-entry, and accessibility assertions intact in
 * milliseconds.
 */
final class SettingsScrollHelper {
    private SettingsScrollHelper() {}

    /** Opens Settings from chat via the overflow (⋮) menu if not already open. */
    static void openSettings(UiDevice device, String pkg, long timeoutMs) {
        if (device.findObject(By.res(pkg, "model_configuration_scroll")) != null) return;
        UiObject2 overflow = device.wait(Until.findObject(By.res(pkg, "chat_overflow").enabled(true)), timeoutMs);
        assertNotNull("Expected chat_overflow control", overflow);
        overflow.click();
        String settingsTitle = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getString(R.string.settings_title);
        UiObject2 settingsItem = device.wait(Until.findObject(By.text(settingsTitle)), timeoutMs);
        assertNotNull("Expected Settings item in chat overflow menu", settingsItem);
        settingsItem.click();
        assertNotNull("Settings did not open",
                device.wait(Until.findObject(By.res(pkg, "model_configuration_scroll")), timeoutMs));
    }

    static void scrollToViewId(ActivityScenario<MainActivity> scenario, UiDevice device, int viewId) {
        scenario.onActivity(activity -> {
            ScrollView scroll = activity.findViewById(R.id.model_configuration_scroll);
            View target = activity.findViewById(viewId);
            if (scroll == null || target == null) return;
            Rect rect = new Rect(0, 0, target.getWidth(), target.getHeight());
            try {
                scroll.offsetDescendantRectToMyCoords(target, rect);
                int targetY = Math.max(0, rect.top - Math.max(scroll.getHeight() / 5, 24));
                scroll.scrollTo(0, targetY);
            } catch (IllegalArgumentException ignored) {
                // Target is outside the ScrollView (e.g., sticky footer); nothing to scroll.
            }
        });
        device.waitForIdle(250);
    }

    static UiObject2 scrollToText(
            ActivityScenario<MainActivity> scenario,
            UiDevice device,
            String text,
            boolean exact,
            long timeoutMs) {
        BySelector selector = exact ? By.text(text) : By.textContains(text);
        UiObject2 existing = findInsideScrollViewport(scenario, device, selector);
        if (existing != null) return existing;

        long deadline = SystemClock.uptimeMillis() + Math.max(timeoutMs, 1000L);
        do {
            final int[] metrics = new int[2];
            scenario.onActivity(activity -> {
                ScrollView scroll = activity.findViewById(R.id.model_configuration_scroll);
                if (scroll == null) return;
                metrics[0] = scroll.getHeight();
                View child = scroll.getChildCount() > 0 ? scroll.getChildAt(0) : null;
                metrics[1] = child == null ? 0 : child.getHeight();
            });
            int viewHeight = Math.max(metrics[0], 200);
            int maxScroll = Math.max(0, metrics[1] - viewHeight);
            int step = Math.max(120, (viewHeight * 2) / 3);
            for (int y = 0; ; y = Math.min(maxScroll, y + step)) {
                final int scrollY = y;
                scenario.onActivity(activity -> {
                    ScrollView scroll = activity.findViewById(R.id.model_configuration_scroll);
                    if (scroll != null) scroll.scrollTo(0, scrollY);
                });
                device.waitForIdle(150);
                UiObject2 found = findInsideScrollViewport(scenario, device, selector);
                if (found != null) return found;
                if (y >= maxScroll) break;
            }
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < deadline);
        return device.findObject(selector);
    }

    static UiObject2 scrollToTextContains(
            ActivityScenario<MainActivity> scenario,
            UiDevice device,
            String text,
            long timeoutMs) {
        return scrollToText(scenario, device, text, false, timeoutMs);
    }

    private static UiObject2 findInsideScrollViewport(
            ActivityScenario<MainActivity> scenario,
            UiDevice device,
            BySelector selector) {
        UiObject2 match = device.findObject(selector);
        if (match == null) return null;
        Rect matchBounds = match.getVisibleBounds();
        if (matchBounds == null || matchBounds.height() <= 0 || matchBounds.width() <= 0) {
            return null;
        }
        final Rect scrollBounds = new Rect();
        scenario.onActivity(activity -> {
            ScrollView scroll = activity.findViewById(R.id.model_configuration_scroll);
            if (scroll != null && scroll.getWidth() > 0 && scroll.getHeight() > 0) {
                int[] loc = new int[2];
                scroll.getLocationOnScreen(loc);
                scrollBounds.set(loc[0], loc[1], loc[0] + scroll.getWidth(), loc[1] + scroll.getHeight());
            }
        });
        if (scrollBounds.isEmpty()) return match;
        int centerY = matchBounds.centerY();
        if (centerY >= scrollBounds.top && centerY <= scrollBounds.bottom) {
            return match;
        }
        return null;
    }
}
