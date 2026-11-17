package com.notcherry.lfm3pocket;

import org.junit.Test;

import static org.junit.Assert.*;

public final class InferenceStatsTest {
    @Test public void jsonRoundTripRetainsFields() {
        InferenceStats original = new InferenceStats(420L, 1800L, 32, 14.5, 880.0);
        InferenceStats parsed = InferenceStats.fromJson(original.toJson());
        assertEquals(original, parsed);
    }

    @Test public void mergePrefersNativeRates() {
        long[] nativeStats = { 120L, 96L, 800L, 24L, 350L };
        InferenceStats merged = InferenceStats.merge(400L, 2000L, 20, nativeStats);
        assertEquals(350L, merged.ttftMs);
        assertEquals(24, merged.tokensGenerated);
        assertEquals(30.0, merged.decodeTokensPerSec, 0.1);
        assertEquals(800.0, merged.prefillTokensPerSec, 0.1);
    }

    @Test public void blankJsonIsAbsent() {
        assertNull(InferenceStats.fromJson(""));
        assertNull(InferenceStats.fromJson(null));
    }

    @Test public void hasAnyMetricRequiresData() {
        assertFalse(new InferenceStats(-1L, 0L, 0, null, null).hasAnyMetric());
        assertTrue(new InferenceStats(10L, 0L, 0, null, null).hasAnyMetric());
    }

    @Test public void mergeEstimatesDecodeRateFromWallClockWhenNativeDecodeMissing() {
        InferenceStats merged = InferenceStats.merge(100L, 1_100L, 10, null);
        assertNotNull(merged);
        assertEquals(10.0, merged.decodeTokensPerSec, 0.05);
        assertNull(merged.prefillTokensPerSec);
    }
}