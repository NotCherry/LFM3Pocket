package com.notcherry.lfm3pocket;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Resume-last-chat must stay the default, but a one-tap New chat control must start empty.
 */
@RunWith(AndroidJUnit4.class)
public final class ChatNewChatE2eTest {
    private static final long TIMEOUT = 20000L;
    private static final long RESUME_TIMEOUT = 60000L;
    private String pkg;
    private UiDevice device;
    private TestLfm3PocketApplication app;
    private Context context;

    @Before public void prepareFakeModel() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        try {
            ModelRuntime runtime = app.existingRuntime();
            if (runtime != null) {
                runtime.stopTurn();
                runtime.clearConversation();
            }
        } catch (Throwable ignored) {
        }
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        pkg = context.getPackageName();
        File file = new File(context.getFilesDir(), "new-chat-ui.gguf");
        TestGgufFixtures.model(file);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear()
                .putString("model", file.getAbsolutePath())
                .putBoolean("search_enabled", false)
                .commit());
        device.waitForIdle(500);
    }

    @Test public void openChatResumesThenTopBarNewChatStartsEmptySession() {
        String conversationId = "resume-then-new";
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit()
                .putString("history_conversation_id", conversationId)
                .putString("history_title", "Pinned title")
                .commit());
        HistoryDatabase db = HistoryDatabase.get(context);
        db.history().deleteConversation(conversationId);
        HistoryRepository repository = new HistoryRepository(db);
        assertTrue(repository.completeTurn(new HistoryRepository.Turn(
                "turn-1", conversationId, "Pinned title", "Sticky prompt", "Sticky answer",
                null, HistoryRepository.Status.COMPLETED, 1000L, null)));

        try (ActivityScenario<ComposeHostActivity> home =
                     ActivityScenario.launch(ComposeHostActivity.class)) {
            boolean resumed = false;
            for (int attempt = 0; attempt < 3 && !resumed; attempt++) {
                if (attempt == 0) {
                    click("open_chat");
                } else {
                    device.pressBack();
                    device.waitForIdle(1500);
                    UiObject2 reopen = device.wait(Until.findObject(By.res(pkg, "open_chat").enabled(true)), TIMEOUT);
                    assertNotNull("Home open_chat control missing before retry " + attempt + "; " + debugState(conversationId), reopen);
                    reopen.click();
                }
                awaitEnabled("chat_send");
                long resumeDeadline = SystemClock.uptimeMillis() + RESUME_TIMEOUT;
                while (SystemClock.uptimeMillis() < resumeDeadline && !resumed) {
                    device.waitForIdle(400);
                    resumed = transcriptContains("Sticky prompt") || transcriptContains("Sticky answer");
                    if (!resumed) SystemClock.sleep(250);
                }
            }
            assertTrue("Open chat must resume the last saved conversation; " + debugState(conversationId), resumed);

            click("chat_new_chat");
            UiObject2 empty = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("No conversation yet")), TIMEOUT);
            assertNotNull("New chat must clear the live transcript; " + debugState(conversationId), empty);
            assertFalse("Transcript still contains resumed prompt after New chat: " + transcriptText(),
                    transcriptContains("Sticky prompt"));
            assertEquals("Starting new chat must not invoke the model", 0, app.scriptedEngine().callCount());
            String nextId = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                    .getString("history_conversation_id", "");
            assertFalse("New chat must mint a new conversation id; " + debugState(conversationId),
                    conversationId.equals(nextId));

            device.pressBack();
            click("open_chat");
            awaitEnabled("chat_send");
            UiObject2 stillEmpty = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("No conversation yet")), TIMEOUT);
            assertNotNull("Open chat after new chat stays on the fresh empty session; " + debugState(conversationId), stillEmpty);
        }
    }

    @Test public void homeNewChatWithLoadedModelDoesNotReload() {
        int loadsBefore = app.scriptedEngine().loadInvocationCount();
        try (ActivityScenario<MainActivity> first = ActivityScenario.launch(MainActivity.class)) {
            awaitEnabled("chat_send");
        }
        int loadsAfterWarm = app.scriptedEngine().loadInvocationCount();
        assertTrue("Initial chat launch must load the model once", loadsAfterWarm > loadsBefore);

        try (ActivityScenario<ComposeHostActivity> home =
                     ActivityScenario.launch(ComposeHostActivity.class)) {
            click("home_new_chat");
            awaitEnabled("chat_send");
            assertEquals("Home New chat must not reload an in-memory model",
                    loadsAfterWarm, app.scriptedEngine().loadInvocationCount());
        }
    }

    @Test public void homeNewChatShortcutStartsEmptyWithoutTouchingResumePath() {
        try (ActivityScenario<ComposeHostActivity> home =
                     ActivityScenario.launch(ComposeHostActivity.class)) {
            click("home_new_chat");
            awaitEnabled("chat_send");
            UiObject2 empty = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("No conversation yet")), TIMEOUT);
            assertNotNull(empty);
            assertEquals(0, app.scriptedEngine().callCount());
            UiObject2 status = device.wait(Until.findObject(By.res(pkg, "chat_status")), TIMEOUT);
            assertNotNull(status);
            assertFalse("Home new chat must not use the system-assistant welcome",
                    status.getText().contains(context.getString(R.string.fresh_chat_from_assist)));
        }
    }

    @Test public void topBarNewChatWhileGeneratingShowsStopDialog() throws Exception {
        try (ActivityScenario<MainActivity> chat = ActivityScenario.launch(MainActivity.class)) {
            beginHeldGeneration();
            click("chat_new_chat");

            UiObject2 keep = awaitDialogButton(false);
            keep.click();
            assertTrue("Keeping the reply must leave generation in flight", runtimeGenerating());

            click("chat_new_chat");
            UiObject2 stop = awaitDialogButton(true);
            stop.click();
            device.wait(Until.gone(By.text(context.getString(R.string.reply_still_running_title))), TIMEOUT);
            UiObject2 empty = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("No conversation yet")), TIMEOUT);
            if (empty == null) {
                empty = device.wait(Until.findObject(
                        By.res(pkg, "chat_transcript").descContains("No conversation yet")), TIMEOUT);
            }
            assertNotNull("Stop and start new must clear the live transcript", empty);
            assertFalse("Held generation must be cancelled", runtimeGenerating());
        }
    }

    @Test public void homeNewChatWhileGeneratingShowsStopDialog() throws Exception {
        try (ActivityScenario<MainActivity> chat = ActivityScenario.launch(MainActivity.class)) {
            beginHeldGeneration();
            context.startActivity(new android.content.Intent(context, ComposeHostActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            device.waitForIdle(1500);
            UiObject2 homeNew = device.wait(Until.findObject(By.res(pkg, "home_new_chat")), TIMEOUT);
            assertNotNull("Home New chat control should appear over the generating session", homeNew);
            homeNew.click();

            UiObject2 keep = awaitDialogButton(false);
            keep.click();
            assertTrue("Keeping the reply must leave generation in flight", runtimeGenerating());

            context.startActivity(new android.content.Intent(context, ComposeHostActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            device.waitForIdle(1500);
            homeNew = device.wait(Until.findObject(By.res(pkg, "home_new_chat")), TIMEOUT);
            assertNotNull(homeNew);
            homeNew.click();
            UiObject2 stop = awaitDialogButton(true);
            stop.click();
            device.wait(Until.gone(By.text(context.getString(R.string.reply_still_running_title))), TIMEOUT);
            UiObject2 empty = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("No conversation yet")), TIMEOUT);
            if (empty == null) {
                empty = device.wait(Until.findObject(
                        By.res(pkg, "chat_transcript").descContains("No conversation yet")), TIMEOUT);
            }
            assertNotNull(empty);
            assertFalse(runtimeGenerating());
        }
    }

    private void beginHeldGeneration() throws Exception {
        awaitEnabled("chat_send");
        UiObject2 input = device.wait(Until.findObject(By.res(pkg, "chat_input")), TIMEOUT);
        assertNotNull(input);
        input.setText("Dialog generation turn");
        app.scriptedEngine().holdFirstToken();
        awaitEnabled("chat_send").click();
        assertTrue(app.scriptedEngine().awaitGenerationStarted());
        assertTrue("Generation must be active before opening new-chat dialog", runtimeGenerating());
        awaitEnabled("chat_stop");
    }

    private UiObject2 awaitResumedTranscript() {
        UiObject2 resumed = device.wait(Until.findObject(
                By.res(pkg, "chat_transcript").textContains("Sticky prompt")), 30000L);
        if (resumed == null) {
            resumed = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").descContains("Sticky prompt")), 5000L);
        }
        if (resumed == null) {
            // Allow async history restore to repopulate the transcript on slower CI runs.
            resumed = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("Sticky answer")), 5000L);
        }
        if (resumed == null) {
            resumed = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").descContains("Sticky answer")), 5000L);
        }
        return resumed;
    }

    private boolean runtimeGenerating() {
        return ((TestLfm3PocketApplication) context.getApplicationContext())
                .modelRuntime().isGenerating();
    }

    private UiObject2 awaitDialogButton(boolean positive) {
        assertNotNull(device.wait(Until.findObject(
                By.text(context.getString(R.string.reply_still_running_title))), TIMEOUT));
        String buttonId = positive ? "button1" : "button2";
        UiObject2 button = device.wait(Until.findObject(By.res("android", buttonId)), TIMEOUT);
        assertNotNull("Expected dialog control android:id/" + buttonId, button);
        return button;
    }

    private void click(String id) {
        UiObject2 target = device.wait(Until.findObject(By.res(pkg, id).enabled(true)), TIMEOUT);
        assertNotNull("Expected enabled control: " + id, target);
        target.click();
    }

    private UiObject2 awaitEnabled(String id) {
        UiObject2 object = device.wait(Until.findObject(By.res(pkg, id).enabled(true)), TIMEOUT);
        assertNotNull("Expected enabled UI control: " + id, object);
        return object;
    }

    private boolean transcriptContains(String text) {
        UiObject2 transcript = device.wait(Until.findObject(
                By.res(pkg, "chat_transcript").textContains(text)), 1200L);
        if (transcript == null) {
            transcript = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").descContains(text)), 1200L);
        }
        return transcript != null;
    }

    private String transcriptText() {
        UiObject2 transcript = device.findObject(By.res(pkg, "chat_transcript"));
        return transcript == null ? "<missing>" : String.valueOf(transcript.getText());
    }

    private String debugState(String expectedConversationId) {
        String prefsId = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .getString("history_conversation_id", "");
        String runtimeId = "<none>";
        String runtimeNotice = "<none>";
        boolean generating = false;
        try {
            ModelRuntime runtime = app.existingRuntime();
            if (runtime != null) {
                runtimeId = String.valueOf(runtime.conversationId());
                RuntimeNotice notice = runtime.currentNotice();
                runtimeNotice = notice == null ? "<null>" : notice.kind + ":" + notice.title;
                generating = runtime.isGenerating();
            }
        } catch (Throwable t) {
            runtimeId = "<error:" + t.getClass().getSimpleName() + ">";
        }
        int dbCount = -1;
        try {
            HistoryRepository repository = new HistoryRepository(HistoryDatabase.get(context));
            dbCount = repository.messages(expectedConversationId == null ? "" : expectedConversationId).size();
        } catch (Throwable t) {
            dbCount = -2;
        }
        return "prefsId=" + prefsId
                + ", runtimeId=" + runtimeId
                + ", runtimeNotice=" + runtimeNotice
                + ", generating=" + generating
                + ", dbMessages(" + expectedConversationId + ")=" + dbCount
                + ", transcript=" + transcriptText();
    }
}