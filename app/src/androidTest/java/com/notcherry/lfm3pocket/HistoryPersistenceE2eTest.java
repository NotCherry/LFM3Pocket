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
import java.io.File;
import java.util.List;
import static org.junit.Assert.*;

/** Persists completed and cancelled chat turns across a real app-process restart. */
@RunWith(AndroidJUnit4.class)
public final class HistoryPersistenceE2eTest {
    private static final long TIMEOUT=12000;
    private static final String CONVERSATION_ID="history-process-restart";

    @Test public void completedAndCancelledTurnsSurviveForceStopAndRestartExactlyOnce() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestLfm3PocketApplication app=(TestLfm3PocketApplication)context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String packageName=context.getPackageName();
        String phase=InstrumentationRegistry.getArguments().getString("historyPhase","prepare");
        if(!phase.equals("verify")) {
            HistoryDatabase.get(context).history().deleteConversation(CONVERSATION_ID);
            File model=new File(context.getFilesDir(),"history-e2e-dummy.gguf");
            TestGgufFixtures.model(model);
            assertTrue(context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE).edit().clear()
                    .putString("model",model.getAbsolutePath()).putBoolean("search_enabled",false)
                    .putString("history_conversation_id",CONVERSATION_ID)
                    .putString("history_title","History restart test").commit());
        }

        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            UiObject2 send=device.wait(Until.findObject(By.res(packageName,"chat_send").enabled(true)),TIMEOUT);
            assertNotNull("Chat should become ready with the injected model",send);
            if(phase.equals("verify")) {
                assertRecords(context);
                return;
            }
            UiObject2 input=device.findObject(By.res(packageName,"chat_input"));
            input.setText("Cancelled question");
            device.findObject(By.res(packageName,"chat_send").enabled(true)).click();
            UiObject2 partial=device.wait(Until.findObject(By.res(packageName,"chat_transcript")
                    .textContains("FAKE_STREAM_BEGIN")),TIMEOUT);
            assertNotNull(partial);
            device.findObject(By.res(packageName,"chat_stop").enabled(true)).click();
            assertNotNull(device.wait(Until.findObject(By.res(packageName,"chat_live_status")
                    .textContains("Response stopped")),TIMEOUT));
            assertNotNull(device.wait(Until.findObject(By.res(packageName,"chat_send").enabled(true)),TIMEOUT));

            input=device.findObject(By.res(packageName,"chat_input"));
            input.setText("Completed question");
            device.findObject(By.res(packageName,"chat_send").enabled(true)).click();
            assertNotNull(device.wait(Until.findObject(By.res(packageName,"chat_transcript")
                    .textContains("FAKE_RETRY_COMPLETE")),TIMEOUT));
            assertNotNull(device.wait(Until.findObject(By.res(packageName,"chat_live_status")
                    .textContains("Response complete · local model")),TIMEOUT));
        }
        assertRecords(context);
    }

    private void assertRecords(Context context) {
        List<HistoryMessage> messages=HistoryDatabase.get(context).history().messages(CONVERSATION_ID);
        assertEquals("Both whole turns survive the app restart",4,messages.size());
        assertEquals("Cancelled question",messages.get(0).getText());
        assertEquals("CANCELLED",messages.get(1).getTurnStatus());
        assertEquals("Completed question",messages.get(2).getText());
        assertEquals("COMPLETED",messages.get(3).getTurnStatus());
        assertTrue(messages.get(3).getText().contains("FAKE_RETRY_COMPLETE"));
        assertEquals("One conversation row must be kept",1,dbConversationCount(context));
    }

    private int dbConversationCount(Context context) {
        return HistoryDatabase.get(context).history().conversations().stream()
                .map(HistoryConversation::getId).filter(CONVERSATION_ID::equals).toArray().length;
    }
}
