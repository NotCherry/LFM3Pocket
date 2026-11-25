package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Locks invariants that prevent Clear / Stop / unload paths from leaving
 * chat controls permanently disabled or reusing dirty KV state after clear.
 */
public final class ClearStopRecoveryContractTest {
    @Test
    public void clearConversationReleasesNativeSessionAndStopsSpeech() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        String body = methodBody(source, "public void clearConversation()");
        assertTrue("clearConversation must stop voice actions", body.contains("voiceActions.cancelAll("));
        assertTrue("clearConversation must stop playback", body.contains("playback.stop()"));
        assertTrue("clearConversation must stop system speech", body.contains("systemSpeech.stop()"));
        assertTrue("clearConversation must release cached native session", body.contains("engine.releaseSession("));
        assertTrue("clearConversation must notify UI even if session is null", body.contains("target.onChat("));
    }

    @Test
    public void mainActivityStopAndClearRecoverBusyState() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java"));
        assertFalse("MainActivity must derive busy state via isBusy() instead of a single mutable busy flag",
                source.contains("private boolean busy"));
        String stopBody = methodBody(source, "private void stopActiveWork()");
        assertTrue("stopActiveWork must call runtime.stopSpeech() when speaking", stopBody.contains("runtime.stopSpeech()"));
        assertFalse("stopActiveWork must not toggle runtime.speak()", stopBody.contains("runtime.speak("));

        String clearBody = methodBody(source, "private void onChatPanelNewChat()");
        assertTrue("Clear must cancel voice/STT via coordinator", clearBody.contains("voiceCoordinator.cancelAllOnClear()"));
        assertTrue("Clear must reset speaking", clearBody.contains("speaking = false"));
        assertTrue("Clear must refresh UI controls", clearBody.contains("updateControls()"));
    }

    @Test
    public void modelRuntimeNotifiesUiOnUnload() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        String unloadBody = methodBody(source, "private void unloadOnPolicyThread(");
        assertTrue("Policy unload must notify attached UI", unloadBody.contains("onModelStateChanged("));
    }

    @Test
    public void nativePrefixReuseRetriesCleanlyOnFreshContext() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/cpp/native.cpp"));
        assertTrue("native.cpp must fall back to fresh prompt eval when partial KV reuse fails",
                source.contains("if (shared > 0 && !state->cancelled.load())"));
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
