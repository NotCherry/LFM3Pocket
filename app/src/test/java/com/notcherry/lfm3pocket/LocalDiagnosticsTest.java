package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LocalDiagnosticsTest {
    @Test public void recordsLocalMetricsWithoutTelemetryUpload() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        LocalDiagnostics diagnostics = new LocalDiagnostics(clock::get);
        assertFalse("Diagnostics must never upload telemetry", diagnostics.uploadsTelemetry());

        diagnostics.record(LocalDiagnostics.Kind.MODEL_LOAD, 250, "path=local.gguf");
        clock.set(1300);
        diagnostics.record(LocalDiagnostics.Kind.FIRST_TOKEN, 40, "tokens=1");
        clock.set(1800);
        diagnostics.record(LocalDiagnostics.Kind.TURN_COMPLETE, 500, "tokens=12");
        diagnostics.record(LocalDiagnostics.Kind.SPEECH, 90, "pcmBytes=480");
        diagnostics.record(LocalDiagnostics.Kind.MEMORY, 0, "usedMb=64");
        diagnostics.record(LocalDiagnostics.Kind.STRESS, 0, "unload-reload");

        List<LocalDiagnostics.Event> events = diagnostics.snapshot();
        assertEquals(6, events.size());
        assertEquals(LocalDiagnostics.Kind.MODEL_LOAD, events.get(0).kind);
        assertEquals(250, events.get(0).durationMs);
        assertEquals(1, diagnostics.count(LocalDiagnostics.Kind.FIRST_TOKEN));

        File file = File.createTempFile("diagnostics", ".ndjson");
        try {
            diagnostics.writeTo(file);
            String body = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"kind\":\"MODEL_LOAD\""));
            assertTrue(body.contains("\"kind\":\"SPEECH\""));
            assertFalse(body.contains("http://"));
            assertFalse(body.contains("https://"));
        } finally {
            assertTrue(file.delete());
        }

        diagnostics.clear();
        assertEquals(0, diagnostics.snapshot().size());
    }
}
