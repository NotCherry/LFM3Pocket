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


    @Test
    public void unloadCancelsVoiceActionsBeforeRetiringModel() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        int unloadStart = source.indexOf("public void unload(String cause, Runnable done)");
        if (unloadStart < 0) unloadStart = source.indexOf("public void unload(");
        assertTrue("unload method missing", unloadStart >= 0);
        int cancel = source.indexOf("voiceActions.cancelAll(\"Model unloading\")", unloadStart);
        int retire = source.indexOf("engine.unloadModel", unloadStart);
        assertTrue("unload must cancel voice actions", cancel >= 0);
        assertTrue("unload must retire engine model", retire >= 0);
        assertTrue("voice must be cancelled before model retirement", cancel < retire);
    }

    @Test
    public void serviceTimeoutAndNotificationUnloadRouteThroughRuntimeUnload() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        String timeout = methodBody(source, "public void onServiceTimeout()");
        if (timeout.isEmpty()) {
            int start = source.indexOf("public void onServiceTimeout()");
            assertTrue("onServiceTimeout missing", start >= 0);
            timeout = source.substring(start, Math.min(source.length(), start + 300));
        }
        assertTrue("timeout must unload fgs-timeout", timeout.contains("unload(\"fgs-timeout\", null)"));

        String action = methodBody(source, "public void onServiceAction(String action, String proposalId, String askText)");
        if (action.isEmpty()) {
            int start = source.indexOf("public void onServiceAction(String action, String proposalId, String askText)");
            assertTrue("onServiceAction missing", start >= 0);
            action = source.substring(start, Math.min(source.length(), start + 800));
        }
        assertTrue("notification unload route missing",
                action.contains("ACTION_UNLOAD") && action.contains("unload(\"notification\", null)"));
    }

    @Test
    public void memoryTrimUnloadRoutesThroughPolicyEffectsToUnload() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        String trim = methodBody(source, "public void onTrimMemory(int level)");
        if (trim.isEmpty()) trim = methodBody(source, "public void onTrimMemory(");
        if (trim.isEmpty()) {
            int start = source.indexOf("public void onTrimMemory(");
            assertTrue("onTrimMemory missing", start >= 0);
            trim = source.substring(start, Math.min(source.length(), start + 1200));
        }
        assertTrue("trim must apply policy.onTrim", trim.contains("policy.onTrim"));
        assertTrue("trim must call apply", trim.contains("apply("));

        String apply = methodBody(source, "private void apply(RuntimePolicy.Effects effects)");
        if (apply.isEmpty()) {
            int start = source.indexOf("private void apply(RuntimePolicy.Effects effects)");
            assertTrue("apply missing", start >= 0);
            apply = source.substring(start, Math.min(source.length(), start + 600));
        }
        assertTrue("apply must handle unloadModel", apply.contains("effects.unloadModel"));
        assertTrue("apply must call unload", apply.contains("unload("));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) return "";
        int brace = source.indexOf('{', start);
        if (brace < 0) return "";
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
