package com.notcherry.lfm3pocket;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Complete persistent text/voice/tool history journey, also split around a force-stop in CI. */
@RunWith(AndroidJUnit4.class)
public final class HistoryJourneyE2eTest {
    private static final long TIMEOUT=12000;
    private static final String PRIMARY="h05-journey-primary";
    private static final String SECONDARY="h05-journey-secondary";

    @Test public void persistentJourneySurvivesRestartAndSupportsInspectAndDeletion() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String phase=InstrumentationRegistry.getArguments().getString("h05Phase","single");
        if(!phase.equals("verify"))prepare(context);
        if(!phase.equals("prepare"))inspectAndDelete(context);
    }

    private static void prepare(Context context) {
        HistoryDatabase db=HistoryDatabase.get(context);HistoryDao dao=db.history();
        dao.deleteAllConversations();
        HistoryRepository repository=new HistoryRepository(db);
        long now=System.currentTimeMillis();
        assertTrue(repository.completeTurn(new HistoryRepository.Turn("turn-"+SECONDARY,SECONDARY,
                "Secondary transcript","Second conversation question","Second conversation answer",null,
                HistoryRepository.Status.COMPLETED,now-1,null)));
        HistoryRepository.ToolExecution tool=new HistoryRepository.ToolExecution("request-h05","set_timer",
                "{\"seconds\":45}",true,"PERFORMED","Timer created",now);
        assertTrue(repository.completeTurn(new HistoryRepository.Turn("turn-"+PRIMARY,PRIMARY,
                "Primary voice and tool transcript","What is on my schedule?","A 45 second timer was created.",
                "Set a timer for forty five seconds",HistoryRepository.Status.COMPLETED,now,tool)));
        assertExactPrimary(dao);
        assertEquals(2,dao.conversations().size());
    }

    private static void inspectAndDelete(Context context) {
        TestLfm3PocketApplication app=(TestLfm3PocketApplication)context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        HistoryDao dao=HistoryDatabase.get(context).history();
        assertExactPrimary(dao);
        assertEquals("Rows are newest first",PRIMARY,dao.conversations().get(0).getId());
        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String pkg=context.getPackageName();
        try(ActivityScenario<ComposeHostActivity> home=ActivityScenario.launch(ComposeHostActivity.class)) {
            click(device,pkg,"history_open");
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_retention_notice")),TIMEOUT));
            UiObject2 item=device.wait(Until.findObject(By.res(pkg,"history_item")),TIMEOUT);
            assertNotNull("Primary saved conversation must be first",item);
            item.click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_reader_title")),TIMEOUT));
            assertNotNull(device.wait(Until.findObject(By.desc("Primary voice and tool transcript")),TIMEOUT));
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_message_user")),TIMEOUT));
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_message_speech")),TIMEOUT));
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_message_assistant")),TIMEOUT));
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_tool_outcome_result")),TIMEOUT));
            assertNotNull("The voice question text must be exposed on the transcript",
                    device.wait(Until.findObject(By.desc("What is on my schedule?")),TIMEOUT));
            assertNotNull("The recognized speech must be exposed on the transcript",
                    device.wait(Until.findObject(By.desc("Set a timer for forty five seconds")),TIMEOUT));
            assertNotNull("The assistant reply must be exposed on the transcript",
                    device.wait(Until.findObject(By.desc("A 45 second timer was created.")),TIMEOUT));
            assertNotNull("The action result must be exposed on the transcript",
                    device.wait(Until.findObject(By.desc("Timer created")),TIMEOUT));
            assertEquals("Reopening history must not run inference",0,app.scriptedEngine().callCount());

            click(device,pkg,"history_new_chat");
            UiObject2 newTranscript=device.wait(Until.findObject(By.res(pkg,"chat_transcript")
                    .textContains("No conversation yet")),TIMEOUT);
            assertNotNull("New chat must not inherit persisted turns",newTranscript);
            assertFalse(newTranscript.getText().contains("schedule"));
            assertFalse(newTranscript.getText().contains("timer was created"));
            assertEquals("New chat must start without invoking the model",0,app.scriptedEngine().callCount());

            device.pressBack();
            click(device,pkg,"history_open");
            click(device,pkg,"history_delete_"+PRIMARY);
            click(device,pkg,"history_confirm_delete");
            waitForDeleted(dao,PRIMARY);
            assertEquals("Deleting one transcript must preserve the other",SECONDARY,
                    dao.conversations().get(0).getId());
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_delete_"+SECONDARY)),TIMEOUT));
            click(device,pkg,"history_delete_all");
            click(device,pkg,"history_confirm_delete_all");
            waitUntilEmpty(dao);
            assertEquals(0,dao.messages(PRIMARY).size());assertEquals(0,dao.toolOutcomes(PRIMARY).size());
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_empty")),TIMEOUT));
            assertEquals("Inspecting and deleting history must not invoke inference",0,app.scriptedEngine().callCount());
        }
    }

    private static void assertExactPrimary(HistoryDao dao) {
        List<HistoryMessage> messages=dao.messages(PRIMARY);
        assertEquals(2,messages.size());
        assertEquals("What is on my schedule?",messages.get(0).getText());
        assertEquals("Set a timer for forty five seconds",messages.get(0).getSpeechTranscript());
        assertEquals("A 45 second timer was created.",messages.get(1).getText());
        List<HistoryToolOutcome> outcomes=dao.toolOutcomes(PRIMARY);
        assertEquals(1,outcomes.size());
        assertEquals("set_timer",outcomes.get(0).getTool());
        assertEquals("{\"seconds\":45}",outcomes.get(0).getArgumentsJson());
        assertEquals("Timer created",outcomes.get(0).getResultText());
    }

    private static void waitForDeleted(HistoryDao dao,String id) {
        long end=System.currentTimeMillis()+TIMEOUT;
        while(System.currentTimeMillis()<end && dao.conversation(id)!=null)sleep();
        assertNull(dao.conversation(id));assertTrue(dao.messages(id).isEmpty());
        assertTrue(dao.toolOutcomes(id).isEmpty());
    }
    private static void waitUntilEmpty(HistoryDao dao) {
        long end=System.currentTimeMillis()+TIMEOUT;
        while(System.currentTimeMillis()<end && !dao.conversations().isEmpty())sleep();
        assertTrue(dao.conversations().isEmpty());
    }
    private static void sleep() { try { Thread.sleep(50); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
    private static void click(UiDevice device,String pkg,String id) {
        UiObject2 target=device.wait(Until.findObject(By.res(pkg,id).enabled(true)),TIMEOUT);
        assertNotNull("Expected enabled journey control "+id,target);target.click();
    }
}
