package com.notcherry.lfm3pocket;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * On-device-only timing and stress breadcrumbs. Never opens sockets or uploads.
 * Operators can pull {@code diagnostics.ndjson} from app-private storage after ARM64 runs.
 */
final class LocalDiagnostics {
    enum Kind {
        MODEL_LOAD,
        FIRST_TOKEN,
        TURN_COMPLETE,
        SPEECH,
        MEMORY,
        STRESS
    }

    static final class Event {
        final Kind kind;
        final long atElapsedMs;
        final long durationMs;
        final String detail;

        Event(Kind kind, long atElapsedMs, long durationMs, String detail) {
            this.kind = kind;
            this.atElapsedMs = atElapsedMs;
            this.durationMs = durationMs;
            this.detail = detail == null ? "" : detail;
        }
    }

    private final LongSupplier clock;
    private final List<Event> events = new ArrayList<>();
    private final Object lock = new Object();

    LocalDiagnostics(LongSupplier clock) {
        this.clock = clock;
    }

    /** Explicit privacy contract for tests and release notes. */
    boolean uploadsTelemetry() {
        return false;
    }

    void record(Kind kind, long durationMs, String detail) {
        long at = clock.getAsLong();
        synchronized (lock) {
            events.add(new Event(kind, at, Math.max(0L, durationMs), detail));
        }
    }

    List<Event> snapshot() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    void clear() {
        synchronized (lock) {
            events.clear();
        }
    }

    int count(Kind kind) {
        synchronized (lock) {
            int n = 0;
            for (Event event : events) if (event.kind == kind) n++;
            return n;
        }
    }

    String toNdjson() {
        StringBuilder out = new StringBuilder();
        for (Event event : snapshot()) {
            out.append(String.format(Locale.US,
                    "{\"kind\":\"%s\",\"atElapsedMs\":%d,\"durationMs\":%d,\"detail\":%s}%n",
                    event.kind.name(), event.atElapsedMs, event.durationMs, jsonString(event.detail)));
        }
        return out.toString();
    }

    void writeTo(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IOException("Could not create diagnostics directory");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            writer.write(toNdjson());
        }
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') escaped.append('\\').append(c);
            else if (c == '\n') escaped.append("\\n");
            else if (c == '\r') escaped.append("\\r");
            else if (c < 0x20) escaped.append(String.format(Locale.US, "\\u%04x", (int) c));
            else escaped.append(c);
        }
        return escaped.append('"').toString();
    }
}
