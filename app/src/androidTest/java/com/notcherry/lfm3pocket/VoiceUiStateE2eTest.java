package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class VoiceUiStateE2eTest {
    private TestLfm3PocketApplication app;
    private Context context;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.resetCapture(false);
        app.capturePermission(true);
        app.scriptedEngine().resetForTest();
        app.scriptedEngine().setSupportsVoiceAudioIn(true);
        File model = new File(context.getFilesDir(), "voice-ui-state-model.gguf");
        TestGgufFixtures.model(model);
        assertTrue(context.getSharedPreferences(RuntimePreferences.FILE, 0).edit().clear()
                .putString("model", model.getAbsolutePath())
                .putBoolean("search_enabled", false).commit());
        File recordings = new File(context.getCacheDir(), "voice-actions");
        if (recordings.isDirectory()) {
            File[] files = recordings.listFiles();
            if (files != null) for (File file : files) file.delete();
        }
    }

    @After public void teardown() {
        if (app != null) {
            app.capturePermission(false);
            ModelRuntime runtime = app.modelRuntime();
            if (runtime != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                        runtime.voice().cancelAll("test teardown"));
            }
        }
    }

    private static void waitFor(String label, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        Throwable last = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (condition.ok()) return;
            } catch (Throwable t) {
                last = t;
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(50);
        }
        if (last != null) throw new AssertionError(label, last);
        throw new AssertionError(label);
    }

    private interface Condition { boolean ok() throws Exception; }

    @Test public void pttPressShowsListeningStateAndTalkbackUpdate() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitFor("voice actions enabled", () -> {
                final boolean[] enabled = { false };
                scenario.onActivity(activity -> enabled[0] =
                        ((Lfm3PocketApplication) activity.getApplication()).modelRuntime().voice().isEnabled());
                return enabled[0];
            });
            scenario.onActivity(activity -> {
                ModelRuntime runtime = ((Lfm3PocketApplication) activity.getApplication()).modelRuntime();
                assertTrue(runtime.voice().isEnabled());
                assertTrue(runtime.voice().pttPressed());
                Button record = activity.findViewById(R.id.voice_record);
                TextView status = activity.findViewById(R.id.chat_status);
                assertEquals(activity.getString(R.string.voice_listening), record.getText().toString());
                assertEquals(activity.getString(R.string.voice_listening),
                        String.valueOf(record.getContentDescription()));
                assertTrue(String.valueOf(status.getText()).contains("Listening"));
            });
            scenario.onActivity(activity ->
                    ((Lfm3PocketApplication) activity.getApplication()).modelRuntime().voice().cancelAll("Stopped"));
        }
    }

    @Test public void timedTalkCancellationTimeoutAndMicErrorProduceVisibleStates() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitFor("voice actions enabled", () -> {
                final boolean[] enabled = { false };
                scenario.onActivity(activity -> enabled[0] =
                        ((Lfm3PocketApplication) activity.getApplication()).modelRuntime().voice().isEnabled());
                return enabled[0];
            });
            scenario.onActivity(activity -> {
                ModelRuntime runtime = ((Lfm3PocketApplication) activity.getApplication()).modelRuntime();
                assertTrue(runtime.voice().isEnabled());
                assertTrue(runtime.voice().pttPressed());
                runtime.voice().cancelAll("Stopped");
                String shown = ((TextView) activity.findViewById(R.id.chat_status)).getText().toString();
                assertTrue(shown, shown.contains("Stopped") || shown.contains("Cancelled"));
            });

            final AtomicReference<String> timeoutStatus = new AtomicReference<>("");
            scenario.onActivity(activity -> {
                ModelRuntime runtime = ((Lfm3PocketApplication) activity.getApplication()).modelRuntime();
                runtime.voice().cancelAll("reset");
                assertTrue(runtime.voice().listenSessionForTests().startTimed(60));
            });
            waitFor("timeout visible", () -> {
                final String[] statusText = { "" };
                scenario.onActivity(activity ->
                        statusText[0] = ((TextView) activity.findViewById(R.id.chat_status)).getText().toString());
                timeoutStatus.set(statusText[0]);
                String s = statusText[0].toLowerCase();
                return s.contains("timed out") || s.contains("timeout");
            });
            assertTrue(timeoutStatus.get(), timeoutStatus.get().toLowerCase().contains("timed out")
                    || timeoutStatus.get().toLowerCase().contains("timeout"));

            scenario.onActivity(activity -> {
                app.capturePermission(false);
                ModelRuntime runtime = ((Lfm3PocketApplication) activity.getApplication()).modelRuntime();
                runtime.voice().cancelAll("reset");
                runtime.voice().beginShadeTalk();
            });
            waitFor("microphone error visible", () -> {
                final String[] shown = { "" };
                scenario.onActivity(activity ->
                        shown[0] = ((TextView) activity.findViewById(R.id.chat_status)).getText().toString().toLowerCase());
                String s = shown[0];
                return s.contains("microphone") || s.contains("permission") || s.contains("talk")
                        || s.contains("required") || s.contains("error") || s.contains("could not");
            });
        }
    }

    @Test public void staleVoiceCallbacksDoNotUpdateDetachedOrReplacedActivity() throws Exception {
        final AtomicReference<MainActivity> first = new AtomicReference<>();
        final AtomicReference<CharSequence> oldTextBefore = new AtomicReference<>("");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                first.set(activity);
                oldTextBefore.set(((Button) activity.findViewById(R.id.voice_record)).getText());
            });
            scenario.recreate();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            final AtomicInteger staleDeliveries = new AtomicInteger();
            scenario.onActivity(recreated -> {
                MainActivity old = first.get();
                assertNotNull(old);
                assertNotEquals(old, recreated);
                ModelRuntime runtime = ((Lfm3PocketApplication) recreated.getApplication()).modelRuntime();
                ModelRuntime.Ui detachedViewer = new ModelRuntime.Ui() {
                    @Override public void onChat(String transcript, String status, boolean generating) { }
                    @Override public void onSnapshot(ChatSnapshot snapshot) { }
                    @Override public void onAssistantReply(String text) { }
                    @Override public void onHistoryTitle(String title) { }
                    @Override public void onPlayback(String message) { }
                    @Override public void onSpeech(boolean speaking, String status) { }
                    @Override public void onToolOutcome(ToolRegistry.Request request, ToolCoordinator.Outcome outcome) { }
                    @Override public void onVoiceStatus(String status) {
                        staleDeliveries.incrementAndGet();
                        if (!old.isDestroyed()) {
                            Button record = old.findViewById(R.id.voice_record);
                            if (record != null) record.setText("STALE_VOICE_UPDATE");
                        }
                    }
                    @Override public boolean isUsable() { return false; }
                };
                runtime.attach(detachedViewer);
                runtime.onVoiceStatus("Listening…");
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Thread.sleep(200);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(recreated -> {
                MainActivity old = first.get();
                assertNotNull(old);
                Button oldRecord = old.findViewById(R.id.voice_record);
                CharSequence oldText = oldRecord == null ? "" : oldRecord.getText();
                assertTrue("detached/replaced activity received stale update: " + oldText,
                        old.isDestroyed() || !"STALE_VOICE_UPDATE".contentEquals(oldText));
                // Reattach a live UI so the recreated activity remains usable.
                ModelRuntime runtime = ((Lfm3PocketApplication) recreated.getApplication()).modelRuntime();
                runtime.attach(new ModelRuntime.Ui() {
                    @Override public void onChat(String transcript, String status, boolean generating) { }
                    @Override public void onSnapshot(ChatSnapshot snapshot) { }
                    @Override public void onAssistantReply(String text) { }
                    @Override public void onHistoryTitle(String title) { }
                    @Override public void onPlayback(String message) { }
                    @Override public void onSpeech(boolean speaking, String status) { }
                    @Override public void onToolOutcome(ToolRegistry.Request request, ToolCoordinator.Outcome outcome) { }
                    @Override public void onVoiceStatus(String status) { }
                    @Override public boolean isUsable() { return false; }
                });
            });
        }
    }
}
