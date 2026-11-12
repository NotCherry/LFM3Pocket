package com.notcherry.lfm3pocket;

import android.Manifest;
import android.content.Context;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import java.io.File;
import static org.junit.Assert.*;

/** Real UI with injected permission/PCM; actual Android permission denial remains ChatUiE2eTest. */
public final class RecordingE2eTest {
    private TestLfm3PocketApplication app;private Context context;private UiDevice device;private String pkg;
    @Before public void setup()throws Exception{
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();pkg=context.getPackageName();
        app=(TestLfm3PocketApplication)context.getApplicationContext();app.resetCapture(false);
        device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        app.capturePermission(true);
        File model=new File(context.getFilesDir(),"record-ui-model.gguf");TestGgufFixtures.model(model);
        context.getSharedPreferences("MainActivity",0).edit().clear().putString("model",model.getAbsolutePath()).commit();
        File dir=new File(context.getCacheDir(),"recordings");if(dir.isDirectory())for(File f:dir.listFiles())f.delete();
    }
    @After public void cleanup(){app.capturePermission(false);}
    private void click(){UiObject2 v=device.wait(Until.findObject(By.res(pkg,"voice_record").enabled(true)),12000);assertNotNull(v);v.click();}
    private void status(String text){assertNotNull(text,device.wait(Until.findObject(By.res(pkg,"chat_status").textContains(text)),12000));}
    private void ready(){assertNotNull(device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(true)),12000));}
    @Test public void explicitCaptureStopsAndAttachesWav()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            ready();assertEquals(0,app.captureOpened());click();status("Recording voice input");assertTrue(app.awaitCaptured());
            click();status("Voice recording attached");ready();assertTrue(app.awaitCaptureReleased());
            File[] files=new File(context.getCacheDir(),"recordings").listFiles();assertNotNull(files);assertEquals(1,files.length);
            assertTrue(files[0].getName().endsWith(".wav"));assertEquals(8236,files[0].length());
        }
    }
    @Test public void capturedWavIsPassedToLocalInferenceAndCleanedAfterward()throws Exception{
        app.scriptedEngine().resetForTest();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            ready();click();status("Recording voice input");assertTrue(app.awaitCaptured());
            click();status("Voice recording attached");ready();assertTrue(app.awaitCaptureReleased());
            String[] path={null};scenario.onActivity(activity->path[0]=activity.pendingMediaPathForTest());
            assertNotNull("Stopped microphone capture should remain pending for the turn",path[0]);
            File wav=new File(path[0]);assertTrue(wav.isFile());
            UiObject2 input=device.wait(Until.findObject(By.res(pkg,"chat_input")),12000);assertNotNull(input);
            input.setText("Transcribe this recording and tell me what was said.");
            app.scriptedEngine().holdFirstToken();
            UiObject2 send=device.wait(Until.findObject(By.res(pkg,"chat_send").enabled(true)),12000);assertNotNull(send);send.click();
            assertTrue("The fake local inference adapter should receive the turn",app.scriptedEngine().awaitGenerationStarted());
            assertEquals("The exact private WAV path must reach inference",path[0],app.scriptedEngine().lastMediaPath());
            assertTrue("Voice question must be encoded with an attached media placeholder",
                    app.scriptedEngine().lastPrompt().contains("Transcribe this recording")
                            && app.scriptedEngine().lastPrompt().contains("<__media__>"));
            assertFalse("Search remains disabled for captured audio",app.scriptedEngine().lastPrompt().contains("search.example"));
            app.scriptedEngine().releaseFirstToken();
            assertNotNull("Fake inference should stream a response for the captured turn",device.wait(
                    Until.findObject(By.res(pkg,"chat_transcript").textContains("FAKE_STREAM_BEGIN")),12000));
            UiObject2 stop=device.wait(Until.findObject(By.res(pkg,"chat_stop").enabled(true)),12000);assertNotNull(stop);stop.click();
            assertNotNull("The chat should finish the cancelled inference turn",device.wait(
                    Until.findObject(By.res(pkg,"chat_live_status").textContains("Response stopped")),12000));
            long deadline=android.os.SystemClock.elapsedRealtime()+5000;
            while(wav.exists()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(25);
            assertFalse("Inference completion/cancellation must remove the temporary WAV",wav.exists());
        }
    }
    @Test public void shortCaptureRecoversForAnotherUserAttempt()throws Exception{
        app.shortCapture(true);
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            ready();click();status("Recording voice input");click();status("too short");ready();
            app.shortCapture(false);click();assertTrue(app.awaitCaptured());click();status("Voice recording attached");ready();
            assertEquals(2,app.captureOpened());
        }
    }
    @Test public void backgroundingCancelsCaptureAndDoesNotRestartIt()throws Exception{
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            ready();click();status("Recording voice input");assertTrue(app.awaitCaptured());
            scenario.moveToState(Lifecycle.State.CREATED);assertTrue(app.awaitCaptureReleased());
            scenario.moveToState(Lifecycle.State.RESUMED);ready();assertEquals(1,app.captureOpened());
            assertEquals(0,new File(context.getCacheDir(),"recordings").list().length);
        }
    }
}
