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
        assertContains(source, "public void stopTurn()", "stopTurn must stop system TTS", "systemSpeech.stop()");
        assertContains(source, "public void unload(", "unload must stop system TTS", "systemSpeech.stop()");
        assertContains(source, "public void discardForTest()", "discardForTest must stop system TTS", "systemSpeech.stop()");
    }

    @Test
    public void speakRoutesOnWorkerNotUiThread() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        int speak = source.indexOf("public void speak(");
        assertTrue("speak method missing", speak >= 0);
        int worker = source.indexOf("worker.execute", speak);
        int select = source.indexOf("speechRouter.select", speak);
        assertTrue("router must run on worker", worker > speak);
        assertTrue("router select off UI path", select > worker);
    }

    @Test
    public void unloadCancelsVoiceActionsBeforeRetiringModel() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        int unload = source.indexOf("public void unload(String cause, Runnable done)");
        if (unload < 0) unload = source.indexOf("public void unload(");
        assertTrue("unload method missing", unload >= 0);
        int cancel = source.indexOf("voiceActions.cancelAll(\"Model unloading\")", unload);
        int retire = source.indexOf("engine.unloadModel", unload);
        assertTrue("unload must cancel voice actions", cancel > unload);
        assertTrue("unload must retire engine model", retire > unload);
        assertTrue("voice must be cancelled before model retirement", cancel < retire);
    }

    @Test
    public void serviceTimeoutAndNotificationUnloadRouteThroughRuntimeUnload() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        assertContains(source, "public void onServiceTimeout()", "timeout must unload fgs-timeout",
                "unload(\"fgs-timeout\", null)");
        int action = source.indexOf("public void onServiceAction(String action, String proposalId, String askText)");
        assertTrue("onServiceAction missing", action >= 0);
        assertTrue("notification unload route missing ACTION_UNLOAD",
                source.indexOf("ACTION_UNLOAD", action) > action);
        assertTrue("notification unload route missing unload(notification, null)",
                source.indexOf("unload(\"notification\", null)", action) > action);
    }

    @Test
    public void memoryTrimUnloadRoutesThroughPolicyEffectsToUnload() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        int trim = source.indexOf("public void onTrimMemory(");
        assertTrue("onTrimMemory missing", trim >= 0);
        assertTrue("trim must apply policy.onTrim", source.indexOf("policy.onTrim", trim) > trim);
        assertTrue("trim must call apply", source.indexOf("apply(", trim) > trim);

        int apply = source.indexOf("private void apply(RuntimePolicy.Effects effects)");
        assertTrue("apply missing", apply >= 0);
        assertTrue("apply must handle unloadModel", source.indexOf("effects.unloadModel", apply) > apply);
        assertTrue("apply must call unload", source.indexOf("unload(", apply) > apply);
    }

    private static void assertContains(String source, String anchor, String message, String needle) {
        int start = source.indexOf(anchor);
        assertTrue(anchor + " missing", start >= 0);
        assertTrue(message, source.indexOf(needle, start) > start);
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
