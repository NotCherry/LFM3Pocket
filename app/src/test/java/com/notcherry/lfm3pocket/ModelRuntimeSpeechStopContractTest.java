package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Ensures Stop/unload paths tear down system TTS, not only Speak-reply toggle. */
public final class ModelRuntimeSpeechStopContractTest {
    @Test
    public void stopTurnUnloadAndDiscardStopSystemSpeech() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        String stopTurn = methodBody(source, "public void stopTurn()");
        String unload = methodBody(source, "public void unload(");
        String discard = methodBody(source, "public void discardForTest()");
        assertTrue("stopTurn must stop system TTS", stopTurn.contains("systemSpeech.stop()"));
        assertTrue("unload must stop system TTS", unload.contains("systemSpeech.stop()"));
        assertTrue("discardForTest must stop system TTS", discard.contains("systemSpeech.stop()"));
    }

  @Test
    public void speakRoutesOnWorkerNotUiThread() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        String speak = methodBody(source, "public void speak(");
        assertTrue("router must run on worker", speak.contains("worker.execute"));
        assertTrue("router select off UI path", speak.contains("speechRouter.select"));
        int selectIndex = speak.indexOf("speechRouter.select");
        int workerIndex = speak.indexOf("worker.execute");
        assertTrue(selectIndex > workerIndex);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("missing " + signature, start >= 0);
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return source.substring(start, i + 1);
            }
        }
        return "";
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