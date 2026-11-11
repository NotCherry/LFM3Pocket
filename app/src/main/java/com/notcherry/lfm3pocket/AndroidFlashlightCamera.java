package com.notcherry.lfm3pocket;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** CameraManager adapter. It only uses torch mode and never opens or captures from a camera. */
final class AndroidFlashlightCamera implements FlashlightController.Camera {
    private final CameraManager manager;

    AndroidFlashlightCamera(Context context) {
        CameraManager found = null;
        try {
            found = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        } catch (RuntimeException ignored) {
            // An unattached Application is also used by a default-deny contract test.
        }
        manager = found;
    }

    @Override public List<String> flashCameraIds() throws Exception {
        if (manager == null) return Collections.emptyList();
        List<String> available = new ArrayList<>();
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Boolean hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(hasFlash)) available.add(id);
        }
        return available;
    }

    @Override public void setTorchMode(String cameraId, boolean enabled) throws Exception {
        if (manager == null) throw new IllegalStateException("Camera service is unavailable");
        manager.setTorchMode(cameraId, enabled);
    }
}
