package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class HistoryNavigationE2eTest {
    private static final long TIMEOUT=12000;

    @Test public void listsAndOpensSavedTranscriptThenStartsEmptyOfflineSession() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestLfm3PocketApplication app=(TestLfm3PocketApplication)context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        String conversationId="h03-saved-conversation";
        HistoryDatabase db=HistoryDatabase.get(context);
        db.history().deleteConversation(conversationId);
        HistoryRepository repository=new HistoryRepository(db);
        assertTrue(repository.completeTurn(new HistoryRepository.Turn("h03-saved-turn",conversationId,
                "Saved test title","Old question","Old assistant answer",null,
                HistoryRepository.Status.COMPLETED,1000,null)));

        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String packageName=context.getPackageName();
        try(ActivityScenario<ComposeHostActivity> home=ActivityScenario.launch(ComposeHostActivity.class)) {
            click(device,packageName,"history_open");
            UiObject2 row=device.wait(Until.findObject(By.res(packageName,"history_item")),TIMEOUT);
            assertNotNull("Saved conversation should appear in the list",row);
            assertNotNull("Conversation preview should be rendered",
                    device.wait(Until.findObject(By.res(packageName,"history_item_preview")),TIMEOUT));
            assertNotNull("Conversation timestamp should be rendered",
                    device.wait(Until.findObject(By.res(packageName,"history_item_timestamp")),TIMEOUT));
            assertEquals("Listing history must not invoke inference",0,app.scriptedEngine().callCount());

            row.click();
            assertNotNull("Transcript title should render",device.wait(
                    Until.findObject(By.res(packageName,"history_reader_title")),TIMEOUT));
            assertNotNull("Saved user message should render",device.wait(
                    Until.findObject(By.res(packageName,"history_message_user")),TIMEOUT));
            assertNotNull("Saved assistant message should render",device.wait(
                    Until.findObject(By.res(packageName,"history_message_assistant")),TIMEOUT));
            assertEquals("Opening a transcript is read-only and must not invoke inference",
                    0,app.scriptedEngine().callCount());

            click(device,packageName,"history_new_chat");
            UiObject2 transcript=device.wait(Until.findObject(By.res(packageName,"chat_transcript")
                    .textContains("No conversation yet")),TIMEOUT);
            assertNotNull("New chat must start with an empty transcript",transcript);
            assertFalse(transcript.getText().contains("Old question"));
            assertFalse(transcript.getText().contains("Old assistant answer"));
            assertEquals("Starting a fresh chat must not run the model",0,app.scriptedEngine().callCount());
            String newConversation=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
                    .getString("history_conversation_id","");
            assertFalse("New chat must receive its own conversation id",conversationId.equals(newConversation));
        }
    }

    private static void click(UiDevice device,String packageName,String id) {
        UiObject2 target=device.wait(Until.findObject(By.res(packageName,id).enabled(true)),TIMEOUT);
        assertNotNull("Expected enabled history control "+id,target);
        target.click();
    }
}
