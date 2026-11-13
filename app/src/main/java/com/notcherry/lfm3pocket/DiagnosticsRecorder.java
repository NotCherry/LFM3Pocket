package com.notcherry.lfm3pocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Local-only timing and lifecycle marks for Stage 1 diagnostics.
 * Marks never leave the device; render() states that fact explicitly.
 */
public final class DiagnosticsRecorder {
    public static final class Mark {
        public final String name;
        public final long elapsedMs;
        public final String detail;

        Mark(String name, long elapsedMs, String detail) {
            this.name = name;
            this.elapsedMs = elapsedMs;
            this.detail = detail == null ? "" : detail;
        }
    }

    private final long startedAt = System.nanoTime();
    private final List<Mark> marks = new ArrayList<>();

    public synchronized void mark(String name) {
        mark(name, "");
    }

    public synchronized void mark(String name, String detail) {
        if (name == null || name.isBlank()) return;
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000L;
        marks.add(new Mark(name.trim(), elapsed, detail == null ? "" : detail.trim()));
    }

    public synchronized List<Mark> marks() {
        return Collections.unmodifiableList(new ArrayList<>(marks));
    }

    public synchronized void clear() {
        marks.clear();
    }

    /** Human-readable local report. Always states that nothing is uploaded. */
    public synchronized String render() {
        StringBuilder out = new StringBuilder();
        out.append("LFM3 Pocket local diagnostics\n");
        out.append("These marks stay on-device and are never uploaded.\n");
        if (marks.isEmpty()) {
            out.append("(no marks yet)\n");
            return out.toString();
        }
        for (Mark mark : marks) {
            out.append(String.format(Locale.US, "%6d ms  %s", mark.elapsedMs, mark.name));
            if (!mark.detail.isEmpty()) out.append(" — ").append(mark.detail);
            out.append('\n');
        }
        return out.toString();
    }
}
