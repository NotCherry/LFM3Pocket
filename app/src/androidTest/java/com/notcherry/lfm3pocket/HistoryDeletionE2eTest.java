package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class HistoryDeletionE2eTest {
    private static final long TIMEOUT=12000;

    @Test public void deletesOneAndAllConversationsAndCleansPrivateMedia() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestLfm3PocketApplication app=(TestLfm3PocketApplication)context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        HistoryDao dao=HistoryDatabase.get(context).history();
        seed(dao,"h04-delete-one","Delete one");
        seed(dao,"h04-delete-two","Delete all");
        File cache=context.getCacheDir();
        File recordings=new File(cache,"recordings");assertTrue(recordings.isDirectory()||recordings.mkdirs());
        File photo=new File(cache,"photo-h04-orphan.img");photo.delete();assertTrue(photo.createNewFile());
        File camera=new File(cache,"camera-h04-orphan.jpg");camera.delete();assertTrue(camera.createNewFile());
        File voice=new File(recordings,"voice-h04-orphan.wav");voice.delete();assertTrue(voice.createNewFile());
        File unrelated=new File(cache,"unrelated-h04.tmp");unrelated.delete();assertTrue(unrelated.createNewFile());

        UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        String pkg=context.getPackageName();
        try(ActivityScenario<ComposeHostActivity> home=ActivityScenario.launch(ComposeHostActivity.class)) {
            click(device,pkg,"history_open");
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_retention_notice")),TIMEOUT));
            click(device,pkg,"history_delete_h04-delete-one");
            click(device,pkg,"history_confirm_delete");
            waitForDeleted(dao,"h04-delete-one");
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_delete_h04-delete-two")),TIMEOUT));
            assertNull(device.wait(Until.findObject(By.res(pkg,"history_delete_h04-delete-one")),1500));
            assertFalse(photo.exists());assertFalse(camera.exists());assertFalse(voice.exists());
            assertTrue("Cleanup must leave unrelated cache files alone",unrelated.exists());

            click(device,pkg,"history_delete_all");
            click(device,pkg,"history_confirm_delete_all");
            waitUntilEmpty(dao);
            assertNotNull(device.wait(Until.findObject(By.res(pkg,"history_empty")),TIMEOUT));
            assertEquals(0,dao.conversations().size());
            assertEquals(0,dao.messages("h04-delete-two").size());
            assertEquals(0,dao.toolOutcomes("h04-delete-two").size());
            assertEquals("History must be excluded from Android auto-backup",0,
                    context.getPackageManager().getApplicationInfo(pkg,0).flags&ApplicationInfo.FLAG_ALLOW_BACKUP);
            assertEquals("Browsing and deletion must not invoke inference",0,app.scriptedEngine().callCount());
            assertTrue(unrelated.delete());
        }
    }

    private static void seed(HistoryDao dao,String id,String title) {
        dao.deleteConversation(id);
        HistoryRepository repository=new HistoryRepository(HistoryDatabase.get(
                InstrumentationRegistry.getInstrumentation().getTargetContext()));
        long now=System.currentTimeMillis();
        assertTrue(repository.completeTurn(new HistoryRepository.Turn("turn-"+id,id,title,
                "Question for "+title,"Answer for "+title,null,
                HistoryRepository.Status.COMPLETED,now,null)));
    }

    private static void waitForDeleted(HistoryDao dao,String id) {
        long end=System.currentTimeMillis()+TIMEOUT;
        while(System.currentTimeMillis()<end && dao.conversation(id)!=null) sleep();
        assertNull("Deleted conversation should be unavailable from Room",dao.conversation(id));
        assertTrue(dao.messages(id).isEmpty());assertTrue(dao.toolOutcomes(id).isEmpty());
    }

    private static void waitUntilEmpty(HistoryDao dao) {
        long end=System.currentTimeMillis()+TIMEOUT;
        while(System.currentTimeMillis()<end && !dao.conversations().isEmpty()) sleep();
        assertTrue("Delete all should clear every transcript",dao.conversations().isEmpty());
    }

    private static void sleep() { try { Thread.sleep(50); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }

    private static void click(UiDevice device,String pkg,String id) {
        UiObject2 target=device.wait(Until.findObject(By.res(pkg,id).enabled(true)),TIMEOUT);
        assertNotNull("Expected enabled privacy control "+id,target);target.click();
    }
}
