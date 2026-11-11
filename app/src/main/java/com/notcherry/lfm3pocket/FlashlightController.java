package com.notcherry.lfm3pocket;

import java.util.List;

/** Capability and permission checked flashlight operation, independent of Android camera APIs. */
public final class FlashlightController {
    public interface Camera {
        List<String> flashCameraIds() throws Exception;
        void setTorchMode(String cameraId, boolean enabled) throws Exception;
    }

    public interface Permission {
        boolean isGranted();
    }

    private final Camera camera;
    private final Permission permission;

    public FlashlightController(Camera camera, Permission permission) {
        if (camera == null || permission == null) throw new IllegalArgumentException("Missing flashlight dependency");
        this.camera = camera;
        this.permission = permission;
    }

    public AndroidActionExecutor.ActionResult setEnabled(boolean enabled) {
        if (!hasPermission()) return result(false, "PERMISSION_DENIED",
                "Grant Camera permission in Android app settings to control the flashlight.");

        final List<String> cameraIds;
        try {
            cameraIds = camera.flashCameraIds();
        } catch (SecurityException e) {
            return result(false, "PERMISSION_DENIED",
                    "Grant Camera permission in Android app settings to control the flashlight.");
        } catch (Exception e) {
            return unavailable();
        }
        if (cameraIds == null || cameraIds.isEmpty()) {
            return result(false, "UNSUPPORTED_DEVICE", "This device has no available camera flash.");
        }
        if (!hasPermission()) return result(false, "PERMISSION_DENIED",
                "Grant Camera permission in Android app settings to control the flashlight.");

        try {
            camera.setTorchMode(cameraIds.get(0), enabled);
            return result(true, enabled ? "FLASHLIGHT_ON" : "FLASHLIGHT_OFF",
                    enabled ? "Flashlight turned on." : "Flashlight turned off.");
        } catch (SecurityException e) {
            return result(false, "PERMISSION_DENIED",
                    "Grant Camera permission in Android app settings to control the flashlight.");
        } catch (Exception e) {
            return unavailable();
        }
    }

    private boolean hasPermission() {
        try { return permission.isGranted(); }
        catch (RuntimeException e) { return false; }
    }

    private static AndroidActionExecutor.ActionResult unavailable() {
        return result(false, "CAMERA_UNAVAILABLE",
                "The flashlight could not be changed; another camera task may be using it.");
    }

    private static AndroidActionExecutor.ActionResult result(boolean succeeded, String status, String message) {
        return new AndroidActionExecutor.ActionResult(succeeded, status, message);
    }
}
