package com.notcherry.lfm3pocket;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the production CameraManager adapter on the emulator without changing app permission state. */
@RunWith(AndroidJUnit4.class)
public final class FlashlightCameraManagerTest {
    @Test public void cameraManagerPermissionAndCapabilityProduceStructuredResult() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean granted = context.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        FlashlightController controller = new FlashlightController(
                new AndroidFlashlightCamera(context),
                () -> context.checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED);
        if (!granted) {
            AndroidActionExecutor.ActionResult denied = controller.setEnabled(true);
            assertFalse(denied.succeeded);
            assertEquals("PERMISSION_DENIED", denied.status);
            return;
        }

        AndroidActionExecutor.ActionResult on = controller.setEnabled(true);
        if ("UNSUPPORTED_DEVICE".equals(on.status)) {
            assertFalse("Unsupported emulator camera must report failure", on.succeeded);
        } else {
            assertTrue("CameraManager flashlight activation failed: " + on.status + " " + on.message,
                    on.succeeded);
            AndroidActionExecutor.ActionResult off = controller.setEnabled(false);
            assertTrue("CameraManager flashlight deactivation failed: " + off.status + " " + off.message,
                    off.succeeded);
            assertEquals("FLASHLIGHT_OFF", off.status);
        }
    }
}
