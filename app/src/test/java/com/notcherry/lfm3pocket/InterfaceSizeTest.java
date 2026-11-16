package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure JVM coverage for interface-size clamp, presets, and percent mapping. */
public final class InterfaceSizeTest {
    @Test public void clampsToSupportedRange() {
        assertEquals(InterfaceSize.MIN, InterfaceSize.clamp(0.5f), 0.0001f);
        assertEquals(InterfaceSize.MAX, InterfaceSize.clamp(2f), 0.0001f);
        assertEquals(InterfaceSize.DEFAULT, InterfaceSize.clamp(Float.NaN), 0.0001f);
        assertEquals(0.92f, InterfaceSize.clamp(0.92f), 0.0001f);
    }

    @Test public void presetsMatchNamedValues() {
        assertTrue(InterfaceSize.isCompact(0.85f));
        assertTrue(InterfaceSize.isDefault(1.0f));
        assertTrue(InterfaceSize.isLarge(1.15f));
        assertFalse(InterfaceSize.isCompact(1.0f));
        assertFalse(InterfaceSize.isLarge(0.85f));
    }

    @Test public void nearestPresetSnapsWithinTolerance() {
        assertEquals(InterfaceSize.COMPACT, InterfaceSize.nearestPreset(0.84f), 0.0001f);
        assertEquals(InterfaceSize.DEFAULT, InterfaceSize.nearestPreset(1.01f), 0.0001f);
        assertEquals(InterfaceSize.LARGE, InterfaceSize.nearestPreset(1.16f), 0.0001f);
        assertEquals(0.95f, InterfaceSize.nearestPreset(0.95f), 0.0001f);
    }

    @Test public void percentRoundTrip() {
        assertEquals(85, InterfaceSize.percent(0.85f));
        assertEquals(100, InterfaceSize.percent(1.0f));
        assertEquals(130, InterfaceSize.percent(1.3f));
        assertEquals(0.80f, InterfaceSize.fromPercent(80), 0.0001f);
        assertEquals(1.15f, InterfaceSize.fromPercent(115), 0.0001f);
    }
}
