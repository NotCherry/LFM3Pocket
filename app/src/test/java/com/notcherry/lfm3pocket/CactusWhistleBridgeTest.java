package com.notcherry.lfm3pocket;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CactusWhistleBridgeTest {
    @After public void clearHook() {
        CactusWhistleBridge.testHook = null;
        CactusWhistleBridge.resetLoadedModel();
    }

    @Test public void extractsTextFromJsonResult() {
        assertEquals("hello world",
                CactusWhistleBridge.extractText("{\"text\":\" hello world \",\"language\":\"en\"}"));
    }

    @Test public void acceptsPlainTextResult() {
        assertEquals("hello world", CactusWhistleBridge.extractText("  hello world  "));
    }

    @Test public void missingTextBecomesEmpty() {
        assertEquals("", CactusWhistleBridge.extractText("{\"language\":\"en\"}"));
        assertEquals("", CactusWhistleBridge.extractText(null));
    }

    @Test public void bridgeLoadsLfm3PocketLibraryAndSkipsNeedleInit() throws Exception {
        String source = read(repoRoot().resolve(
                "app/src/main/java/com/notcherry/lfm3pocket/CactusWhistleBridge.java"));
        assertTrue("ensureLoaded must load liblfm3pocket explicitly before nativeProbe",
                source.contains("System.loadLibrary(\"lfm3pocket\")"));
        assertFalse("Whistle loadModel must not call Needle-only nativeInit",
                source.contains("nativeInit("));
        assertTrue("Whistle loadModel should configure native thread count",
                source.contains("nativeSetThreads("));
    }

    @Test public void testHookDelegatesAvailabilityAndTranscription() {
        CactusWhistleBridge.testHook = new CactusWhistleBridge.TestHook() {
            @Override public SttAvailability availability(String modelPath) {
                return SttAvailability.ready();
            }
            @Override public SttResult transcribe(String modelPath, float[] pcm16kMono, AtomicBoolean cancelled) {
                return SttResult.ok("transcribed " + pcm16kMono.length);
            }
        };
        assertTrue(CactusWhistleBridge.isNativeAvailable());
        assertTrue(CactusWhistleBridge.availability("/fake/whistle.cact").available);
        SttResult res = CactusWhistleBridge.transcribe("/fake/whistle.cact", new float[] { 0.1f, -0.1f },
                new AtomicBoolean(false));
        assertTrue(res.isOk());
        assertEquals("transcribed 2", res.text);
    }

    private static String read(Path path) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) dir = dir.getParent();
        assertNotNull("settings.gradle", dir);
        return dir;
    }
}
