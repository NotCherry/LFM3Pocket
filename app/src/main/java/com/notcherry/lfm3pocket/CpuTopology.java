package com.notcherry.lfm3pocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Detects decode-thread defaults from CPU frequency clusters (big / mid cores).
 * ChatterUI defaults to {@code availableProcessors()}; PocketPal uses 80% of cores
 * when cores &gt; 4. Prefer big+mid clusters so little cores are not oversubscribed.
 */
public final class CpuTopology {
    /** Soft cap kept for Settings UI and JNI validation. */
    public static final int MAX_THREADS = 8;
    public static final int MIN_THREADS = 1;

    private CpuTopology() {}

    /** Logical CPU count from the runtime (fallback when sysfs is unavailable). */
    public static int logicalProcessors() {
        int n = Runtime.getRuntime().availableProcessors();
        return Math.max(1, n);
    }

    /**
     * Recommended decode threads: count of cores in the highest and (when present)
     * second-highest max-frequency clusters, capped to {@link #MAX_THREADS}.
     * Falls back to PocketPal's 80%-of-cores rule when frequency data is missing.
     */
    public static int recommendedDecodeThreads() {
        List<Integer> freqs = readMaxFrequencies();
        int logical = logicalProcessors();
        int recommended;
        if (freqs.size() >= 2) {
            int max = Collections.max(freqs);
            int second = 0;
            for (int f : freqs) {
                if (f < max && f > second) second = f;
            }
            int bigMid = 0;
            for (int f : freqs) {
                if (f == max || (second > 0 && f == second)) bigMid++;
            }
            // If everything shares one frequency, treat as homogeneous.
            if (second == 0) bigMid = freqs.size();
            recommended = Math.max(1, bigMid);
        } else {
            // PocketPal deviceCapabilities.getRecommendedThreadCount
            recommended = logical <= 4 ? logical : Math.max(1, (int) Math.floor(logical * 0.8));
        }
        return clampThreads(recommended);
    }

    public static int clampThreads(int threads) {
        int logical = logicalProcessors();
        int capped = Math.min(threads, Math.min(MAX_THREADS, logical));
        return Math.max(MIN_THREADS, capped);
    }

    /** Space-separated feature tokens from {@code /proc/cpuinfo} (PocketPal/llama.rn). */
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

    public static boolean hasI8mm() {
        return cpuFeatures().toLowerCase(Locale.ROOT).contains("i8mm");
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
