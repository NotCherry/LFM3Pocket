package com.notcherry.lfm3pocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects decode-thread defaults from CPU frequency clusters (big / mid cores).
 *
 * <p>Two-cluster phones (e.g. RMX2170 2×A76 + 6×A55) must not count the little
 * cluster. A second cluster is included only when there are three or more
 * distinct max-frequency clusters (big + mid + little). Homogeneous SoCs and
 * missing sysfs fall back to PocketPal's 80%-of-cores rule.
 */
public final class CpuTopology {
    public static final int MAX_THREADS = 8;
    public static final int MIN_THREADS = 1;

    private CpuTopology() {}

    public static int logicalProcessors() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    /** Recommended decode threads from live sysfs (or PocketPal fallback). */
    public static int recommendedDecodeThreads() {
        return recommendedDecodeThreads(readMaxFrequencies(), logicalProcessors());
    }

    /**
     * Pure policy for unit tests. {@code freqs} is one max-frequency (kHz) per
     * logical CPU; empty means sysfs unavailable.
     */
    public static int recommendedDecodeThreads(List<Integer> freqs, int logicalProcessors) {
        int logical = Math.max(1, logicalProcessors);
        int recommended;
        if (freqs == null || freqs.size() < 2) {
            recommended = pocketPalThreads(logical);
        } else {
            recommended = threadsFromFrequencyClusters(freqs);
            if (recommended < 1) recommended = pocketPalThreads(logical);
        }
        return clampThreads(recommended, logical);
    }

    /**
     * Count cores in the highest cluster; include the second-highest only when
     * there are ≥3 distinct frequency clusters (big+mid+little).
     */
    static int threadsFromFrequencyClusters(List<Integer> freqs) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        List<Integer> sortedUnique = new ArrayList<>();
        for (int f : freqs) {
            if (!counts.containsKey(f)) sortedUnique.add(f);
            counts.put(f, counts.getOrDefault(f, 0) + 1);
        }
        if (sortedUnique.isEmpty()) return 0;
        Collections.sort(sortedUnique);
        int highest = sortedUnique.get(sortedUnique.size() - 1);
        int threads = counts.get(highest);
        if (sortedUnique.size() >= 3) {
            int mid = sortedUnique.get(sortedUnique.size() - 2);
            threads += counts.get(mid);
        }
        return threads;
    }

    /** PocketPal deviceCapabilities.getRecommendedThreadCount */
    static int pocketPalThreads(int logical) {
        return logical <= 4 ? logical : Math.max(1, (int) Math.floor(logical * 0.8));
    }

    public static int clampThreads(int threads) {
        return clampThreads(threads, logicalProcessors());
    }

    public static int clampThreads(int threads, int logicalProcessors) {
        int logical = Math.max(1, logicalProcessors);
        int capped = Math.min(threads, Math.min(MAX_THREADS, logical));
        return Math.max(MIN_THREADS, capped);
    }

    public static String cpuFeatures() {
        StringBuilder features = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("features") || lower.startsWith("flags")) {
                    int idx = lower.indexOf(':');
                    if (idx >= 0 && idx + 1 < line.length()) {
                        features.append(line.substring(idx + 1).trim()).append(' ');
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return features.toString().trim();
    }

    public static boolean hasDotProd() {
        String f = cpuFeatures().toLowerCase(Locale.ROOT);
        return f.contains("dotprod") || f.contains("asimddp");
    }

    static List<Integer> readMaxFrequencies() {
        List<Integer> freqs = new ArrayList<>();
        File cpuRoot = new File("/sys/devices/system/cpu");
        File[] children = cpuRoot.listFiles();
        if (children == null) return freqs;
        for (File child : children) {
            String name = child.getName();
            if (!name.startsWith("cpu") || name.length() < 4) continue;
            boolean digits = true;
            for (int i = 3; i < name.length(); i++) {
                if (!Character.isDigit(name.charAt(i))) { digits = false; break; }
            }
            if (!digits) continue;
            File freqFile = new File(child, "cpufreq/cpuinfo_max_freq");
            if (!freqFile.isFile()) continue;
            try (BufferedReader br = new BufferedReader(new FileReader(freqFile))) {
                String line = br.readLine();
                if (line == null) continue;
                freqs.add(Integer.parseInt(line.trim()));
            } catch (Exception ignored) {
            }
        }
        return freqs;
    }
}
