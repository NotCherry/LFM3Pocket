package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Resume-last-chat must stay the default, but a one-tap New chat control must start empty.
 */
@RunWith(AndroidJUnit4.class)
public final class ChatNewChatE2eTest {
    private static final long TIMEOUT = 12000L;
    private String pkg;
    private UiDevice device;
    private TestLfm3PocketApplication app;

    @Before public void prepareFakeModel() throws IOException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        pkg = context.getPackageName();
        File file = new File(context.getFilesDir(), "new-chat-ui.gguf");
        TestGgufFixtures.model(file);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear()
                .putString("model", file.getAbsolutePath())
                .putBoolean("search_enabled", false)
                .commit());
    }

    @Test public void openChatResumesThenTopBarNewChatStartsEmptySession() {
        String conversationId = "resume-then-new";
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
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
            click("open_chat");
            awaitEnabled("chat_send");
            UiObject2 resumed = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("Sticky prompt")), TIMEOUT);
            assertNotNull("Open chat must resume the last saved conversation", resumed);

            click("chat_new_chat");
            UiObject2 empty = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("No conversation yet")), TIMEOUT);
            assertNotNull("New chat must clear the live transcript", empty);
            assertFalse(empty.getText().contains("Sticky prompt"));
            assertEquals("Starting new chat must not invoke the model", 0, app.scriptedEngine().callCount());
            String nextId = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                    .getString("history_conversation_id", "");
            assertFalse("New chat must mint a new conversation id", conversationId.equals(nextId));

            device.pressBack();
            click("open_chat");
            awaitEnabled("chat_send");
            UiObject2 stillEmpty = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("No conversation yet")), TIMEOUT);
            assertNotNull("Open chat after new chat stays on the fresh empty session", stillEmpty);
        }
    }

    @Test public void homeNewChatShortcutStartsEmptyWithoutTouchingResumePath() throws IOException {
        try (ActivityScenario<ComposeHostActivity> home =
                     ActivityScenario.launch(ComposeHostActivity.class)) {
            click("home_new_chat");
            awaitEnabled("chat_send");
            UiObject2 empty = device.wait(Until.findObject(
                    By.res(pkg, "chat_transcript").textContains("No conversation yet")), TIMEOUT);
            assertNotNull(empty);
            assertEquals(0, app.scriptedEngine().callCount());
        }
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
}