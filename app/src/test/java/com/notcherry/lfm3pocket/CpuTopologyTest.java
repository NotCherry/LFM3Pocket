package com.notcherry.lfm3pocket;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class CpuTopologyTest {
    @Test public void rmx2170TwoClusterUsesOnlyBigCores() {
        // 2×A76 @ 2.3 GHz + 6×A55 @ 1.8 GHz → only the high cluster (2).
        List<Integer> freqs = freqs(2, 2300000, 6, 1800000);
        assertEquals(2, CpuTopology.recommendedDecodeThreads(freqs, 8));
        assertEquals(2, CpuTopology.threadsFromFrequencyClusters(freqs));
    }

    @Test public void threeClusterIncludesBigAndMid() {
        // 1×prime + 3×mid + 4×little
        List<Integer> freqs = new ArrayList<>();
        freqs.add(3000000);
        freqs.addAll(Collections.nCopies(3, 2400000));
        freqs.addAll(Collections.nCopies(4, 1800000));
        assertEquals(4, CpuTopology.recommendedDecodeThreads(freqs, 8));
    }

    @Test public void fourPlusFourTwoClusterUsesOnlyHighest() {
        List<Integer> freqs = freqs(4, 2800000, 4, 1800000);
        assertEquals(4, CpuTopology.recommendedDecodeThreads(freqs, 8));
    }

    @Test public void eightHomogeneousUsesAllCapped() {
        List<Integer> freqs = Collections.nCopies(8, 2000000);
        // One distinct frequency → treat as homogeneous (no "second" cluster).
        assertEquals(8, CpuTopology.threadsFromFrequencyClusters(freqs));
        assertEquals(8, CpuTopology.recommendedDecodeThreads(freqs, 8));
    }

    @Test public void missingSysfsUsesPocketPalEightyPercent() {
        assertEquals(4, CpuTopology.recommendedDecodeThreads(Collections.emptyList(), 4));
        assertEquals(6, CpuTopology.recommendedDecodeThreads(Collections.emptyList(), 8));
        assertEquals(1, CpuTopology.recommendedDecodeThreads(null, 1));
    }

    @Test public void clampHonoursLogicalAndSoftCap() {
        assertEquals(1, CpuTopology.clampThreads(0, 8));
        assertEquals(8, CpuTopology.clampThreads(99, 8));
        assertEquals(4, CpuTopology.clampThreads(8, 4));
    }

    @Test public void liveRecommendedStaysWithinSoftCap() {
        int threads = CpuTopology.recommendedDecodeThreads();
        assertTrue(threads >= CpuTopology.MIN_THREADS);
        assertTrue(threads <= CpuTopology.MAX_THREADS);
        assertTrue(threads <= CpuTopology.logicalProcessors());
    }

    private static List<Integer> freqs(int nHigh, int high, int nLow, int low) {
        List<Integer> out = new ArrayList<>();
        out.addAll(Collections.nCopies(nHigh, high));
        out.addAll(Collections.nCopies(nLow, low));
        return out;
    }
}
