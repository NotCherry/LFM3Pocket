package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Stacking thresholds for readable Stillwater control rows. */
public final class StillwaterReadableTest {
    @Test public void stacksAtElevatedFontScaleEvenOnWideScreens() {
        assertTrue(StillwaterReadable.shouldStack(1.3f, 720));
        assertTrue(StillwaterReadable.shouldStack(1.5f, 720));
        assertTrue(StillwaterReadable.shouldStack(2.0f, 1080));
    }

    @Test public void stacksOnNarrowWidthAtDefaultFontScale() {
        assertTrue(StillwaterReadable.shouldStack(1.0f, 320));
        assertTrue(StillwaterReadable.shouldStack(0.85f, 360));
        assertTrue(StillwaterReadable.shouldStack(1.0f, 399));
    }

    @Test public void staysSideBySideOnPhoneWidthAtDefaultOrSmallFont() {
        assertFalse(StillwaterReadable.shouldStack(1.0f, 400));
        assertFalse(StillwaterReadable.shouldStack(1.0f, 411));
        assertFalse(StillwaterReadable.shouldStack(0.85f, 480));
        assertFalse(StillwaterReadable.shouldStack(1.29f, 411));
    }
}
