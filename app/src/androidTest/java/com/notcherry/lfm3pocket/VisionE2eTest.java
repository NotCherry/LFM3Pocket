package com.notcherry.lfm3pocket;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.*;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** EMU-FAKE: photo + voice multimodal turn and image-preserving follow-up. */
public final class VisionE2eTest {
    private TestLfm3PocketApplication app;
    private Context context;
    private UiDevice device;
    private String pkg;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        pkg = context.getPackageName();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.resetCapture(false);
        app.resetPlayback();
        app.capturePermission(true);
        app.scriptedEngine().resetForTest();
        app.scriptedEngine().allowFirstTurnSuccess();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        File model = new File(context.getFilesDir(), "vision-ui-model.gguf");
        TestGgufFixtures.model(model);
        context.getSharedPreferences("MainActivity", 0).edit().clear()
                .putString("model", model.getAbsolutePath()).commit();
    }

    @After public void cleanup() { app.capturePermission(false); }

    @Test public void photoPlusVoiceTurnReachesMultiMediaInferenceAndFollowUpKeepsImage() throws Exception {
        File photo = writeTinyJpeg(new File(context.getCacheDir(), "photo-vision-e2e.jpg"));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), 12000));
            scenario.onActivity(activity -> activity.setPendingMedia(photo.getAbsolutePath(), "photo"));
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "image_preview")), 12000));

            UiObject2 record = device.wait(Until.findObject(By.res(pkg, "voice_record").enabled(true)), 12000);
            assertNotNull(record);
            record.click();
            assertTrue(app.awaitCaptured());
            record.click();
            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_status")
                    .textContains("Voice recording attached")), 12000));

            final String[][] holder = new String[1][];
            scenario.onActivity(activity -> holder[0] = activity.pendingMediaPathsForTest());
            String[] pending = holder[0];
            assertEquals(2, pending.length);
            assertEquals(photo.getAbsolutePath(), pending[0]);
            assertTrue(new File(pending[1]).isFile());

            UiObject2 send = device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), 12000);
            assertNotNull(send);
            send.click();
            assertTrue(app.scriptedEngine().awaitGenerationStarted());

            long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (app.scriptedEngine().mediaByCall().size() < 2
                    && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(25);
            }
            List<List<String>> media = app.scriptedEngine().mediaByCall();
            assertTrue(media.size() >= 2);
            assertEquals(Collections.singletonList(pending[1]), media.get(0));
            assertEquals(Arrays.asList(pending[0], pending[1]), media.get(1));
            assertTrue(app.scriptedEngine().lastPrompt().contains("<__media__>"));
            assertEquals(2, app.scriptedEngine().lastPrompt().split("<__media__>", -1).length - 1);

            assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_transcript")
                    .textContains("FAKE_STREAM_BEGIN")), 12000));
            deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (new File(pending[1]).exists() && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(25);
            }
            assertFalse("Audio attachment is cleaned after the multimodal turn", new File(pending[1]).exists());
            assertTrue("Image remains available for follow-up", photo.exists());

            UiObject2 input = device.wait(Until.findObject(By.res(pkg, "chat_input")), 12000);
            assertNotNull(input);
            input.setText("What color stands out?");
            send = device.wait(Until.findObject(By.res(pkg, "chat_send").enabled(true)), 12000);
            assertNotNull(send);
            send.click();

            deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (app.scriptedEngine().mediaByCall().size() < 3
                    && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(25);
            }
            media = app.scriptedEngine().mediaByCall();
            assertEquals(Collections.singletonList(photo.getAbsolutePath()), media.get(media.size() - 1));
            assertTrue(app.scriptedEngine().lastPrompt().contains("What color stands out?"));
            assertEquals(1, app.scriptedEngine().lastPrompt().split("<__media__>", -1).length - 1);
        } finally {
            photo.delete();
        }
    }

    private static File writeTinyJpeg(File target) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xffd54a35);
        try (FileOutputStream out = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.getFD().sync();
        } finally {
            bitmap.recycle();
        }
        return target;
    }
}
