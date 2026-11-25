package com.notcherry.lfm3pocket;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Bounded in-memory ring buffer of sanitized on-device processing phases (PDF extraction,
 * multimodal projector, prompt prefill chunks, decode tok/s, RAM/thermal snapshots).
 *
 * <p>Privacy invariant: never stores user prompt text, document content, or API secrets.
 */
public final class ProcessingTelemetry {
    public static final class Entry {
        public final long timestampMs;
        public final String stage;
        public final long durationMs;
        public final String detail;

        public Entry(long timestampMs, String stage, long durationMs, String detail) {
            this.timestampMs = timestampMs;
            this.stage = stage == null ? "INFO" : stage;
            this.durationMs = Math.max(0L, durationMs);
            this.detail = detail == null ? "" : detail;
        }

        public String formatLine() {
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
            fmt.setTimeZone(TimeZone.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append(fmt.format(new Date(timestampMs)))
                    .append(" [").append(stage).append("]");
            if (durationMs > 0L) {
                sb.append(" ").append(durationMs).append("ms");
            }
            if (!detail.isEmpty()) {
                sb.append(" · ").append(detail);
            }
            return sb.toString();
        }
    }

    private static final int MAX_ENTRIES = 60;
    private final Deque<Entry> entries = new ArrayDeque<>(MAX_ENTRIES);
    private volatile String lastLiveSummary = "";

    public synchronized void record(String stage, long durationMs, String detail) {
        Entry entry = new Entry(System.currentTimeMillis(), stage, durationMs, detail);
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
        }
        entries.addLast(entry);
        lastLiveSummary = entry.formatLine();
    }

    public synchronized List<Entry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized List<String> recentLines(int limit) {
        int cap = Math.max(1, limit);
        List<Entry> all = new ArrayList<>(entries);
        int start = Math.max(0, all.size() - cap);
        List<String> out = new ArrayList<>(all.size() - start);
        for (int i = start; i < all.size(); i++) {
            out.add(all.get(i).formatLine());
        }
        return out;
    }

    public String lastLiveSummary() {
        return lastLiveSummary;
    }

    public synchronized void clear() {
        entries.clear();
        lastLiveSummary = "";
    }
}
