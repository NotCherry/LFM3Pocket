package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FlashlightControllerTest {
    @Test public void enablesAndDisablesFirstFlashCapableCamera() {
        FakeCamera camera = new FakeCamera(Arrays.asList("front", "rear"));
        camera.flashIds = Collections.singletonList("rear");
        FlashlightController controller = new FlashlightController(camera, () -> true);

        AndroidActionExecutor.ActionResult on = controller.setEnabled(true);
        AndroidActionExecutor.ActionResult off = controller.setEnabled(false);

        assertTrue(on.succeeded);
        assertEquals("FLASHLIGHT_ON", on.status);
        assertEquals("rear", camera.lastCameraId);
        assertEquals(Arrays.asList(true, false), camera.enabledCalls);
        assertTrue(off.succeeded);
        assertEquals("FLASHLIGHT_OFF", off.status);
        assertFalse(camera.lastEnabled);
        assertEquals(2, camera.calls);
    }

    @Test public void deniesBeforeCameraAccessWhenCameraPermissionIsMissing() {
        FakeCamera camera = new FakeCamera(Collections.singletonList("rear"));
        FlashlightController controller = new FlashlightController(camera, () -> false);

        AndroidActionExecutor.ActionResult result = controller.setEnabled(true);

        assertFalse(result.succeeded);
        assertEquals("PERMISSION_DENIED", result.status);
        assertTrue(result.message.contains("Android app settings"));
        assertEquals(0, camera.calls);
        assertEquals(0, camera.discoveries);
    }

    @Test public void returnsUnsupportedWhenNoCameraHasFlash() {
        FakeCamera camera = new FakeCamera(Arrays.asList("front", "rear"));
        camera.flashIds = Collections.emptyList();
        FlashlightController controller = new FlashlightController(camera, () -> true);

        AndroidActionExecutor.ActionResult result = controller.setEnabled(true);

        assertFalse(result.succeeded);
        assertEquals("UNSUPPORTED_DEVICE", result.status);
        assertEquals(0, camera.calls);
    }

    @Test public void cameraBusyOrOtherAccessFailureNeverReportsSuccess() {
        FakeCamera camera = new FakeCamera(Collections.singletonList("rear"));
        camera.failure = new IllegalStateException("camera busy");
        FlashlightController controller = new FlashlightController(camera, () -> true);

        AndroidActionExecutor.ActionResult result = controller.setEnabled(true);

        assertFalse(result.succeeded);
        assertEquals("CAMERA_UNAVAILABLE", result.status);
        assertEquals("The flashlight could not be changed; another camera task may be using it.", result.message);
    }

    @Test public void permissionCanBeRevokedBetweenDiscoveryAndExecution() {
        FakeCamera camera = new FakeCamera(Collections.singletonList("rear"));
        FlashlightController controller = new FlashlightController(camera, new FlashlightController.Permission() {
            int checks;
            @Override public boolean isGranted() { return ++checks == 1; }
        });

        AndroidActionExecutor.ActionResult result = controller.setEnabled(true);

        assertFalse(result.succeeded);
        assertEquals("PERMISSION_DENIED", result.status);
        assertEquals(0, camera.calls);
    }

    @Test public void actionExecutorAcceptsOnlyTheValidatedFlashlightCallShape() {
        FakeCamera camera = new FakeCamera(Collections.singletonList("rear"));
        FlashlightActionExecutor executor = new FlashlightActionExecutor(
                new FlashlightController(camera, () -> true));

        AndroidActionExecutor.ActionResult unknown = executor.execute("open_application", Collections.emptyMap());
        java.util.Map<String, String> extraArguments = new java.util.HashMap<>();
        extraArguments.put("on", "true");
        extraArguments.put("unexpected", "true");
        AndroidActionExecutor.ActionResult extra = executor.execute("toggle_flashlight", extraArguments);
        AndroidActionExecutor.ActionResult invalid = executor.execute("toggle_flashlight",
                java.util.Collections.singletonMap("on", "yes"));

        assertEquals("UNKNOWN_TOOL", unknown.status);
        assertEquals("INVALID_ARGUMENTS", extra.status);
        assertEquals("INVALID_ARGUMENTS", invalid.status);
        assertEquals(0, camera.calls);
    }

    @Test public void modelFlashlightProposalRunsOnlyAfterTheExactRequestIsApproved() {
        FakeCamera camera = new FakeCamera(Collections.singletonList("rear"));
        AndroidActionExecutor executor = new FlashlightActionExecutor(
                new FlashlightController(camera, () -> true));
        ToolCoordinator coordinator = new ToolCoordinator(request -> {
            java.util.Map<String, String> arguments = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Object> entry : request.arguments.entrySet()) {
                arguments.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            return executor.execute(request.tool.wireName, arguments);
        }, request -> true, () -> 0L);
        ToolRegistry.Request request = new ToolRegistry().parse(
                "{\"tool\":\"toggle_flashlight\",\"arguments\":{\"on\":true}}").request;

        ToolCoordinator.Outcome pending = coordinator.propose(request, ToolCoordinator.Source.MODEL_PROPOSAL);

        assertEquals("PENDING_CONFIRMATION", pending.status);
        assertEquals(0, camera.calls);
        ToolCoordinator.Outcome done = coordinator.approve(pending.proposal.id);
        assertTrue(done.result.succeeded);
        assertEquals("FLASHLIGHT_ON", done.status);
        assertEquals(1, camera.calls);
        assertTrue(camera.lastEnabled);
    }

    private static final class FakeCamera implements FlashlightController.Camera {
        final List<String> cameraIds;
        List<String> flashIds;
        String lastCameraId;
        boolean lastEnabled;
        final java.util.List<Boolean> enabledCalls = new java.util.ArrayList<>();
        int calls;
        int discoveries;
        Exception failure;

        FakeCamera(List<String> cameraIds) {
            this.cameraIds = cameraIds;
            this.flashIds = cameraIds;
        }

        @Override public List<String> flashCameraIds() {
            discoveries++;
            return flashIds;
        }

        @Override public void setTorchMode(String cameraId, boolean enabled) throws Exception {
            calls++;
            if (failure != null) throw failure;
            lastCameraId = cameraId;
            lastEnabled = enabled;
            enabledCalls.add(enabled);
        }
    }
}
