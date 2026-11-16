package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;

public class CpuTopologyTest {
    @Test public void recommendedThreadsStayWithinSoftCap() {
        int threads = CpuTopology.recommendedDecodeThreads();
        assertTrue(threads >= CpuTopology.MIN_THREADS);
        assertTrue(threads <= CpuTopology.MAX_THREADS);
        assertTrue(threads <= CpuTopology.logicalProcessors());
    }

    @Test public void clampHonoursLogicalProcessors() {
        assertEquals(1, CpuTopology.clampThreads(0));
        assertEquals(1, CpuTopology.clampThreads(-3));
        int logical = CpuTopology.logicalProcessors();
        int expected = Math.min(CpuTopology.MAX_THREADS, logical);
        assertEquals(expected, CpuTopology.clampThreads(99));
    }

    @Test public void pocketPalStyleFallbackForHomogeneousCounts() {
        // When sysfs is empty (CI JVM), recommended uses 80% of logical cores if > 4.
        int logical = CpuTopology.logicalProcessors();
        int recommended = CpuTopology.recommendedDecodeThreads();
        if (CpuTopology.readMaxFrequencies().isEmpty()) {
            int expected = logical <= 4 ? logical : Math.max(1, (int) Math.floor(logical * 0.8));
            assertEquals(CpuTopology.clampThreads(expected), recommended);
        }
    }
}
