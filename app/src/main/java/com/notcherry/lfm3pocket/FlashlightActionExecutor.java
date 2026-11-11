package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter from the already-approved flashlight wire call to the Android camera boundary. */
public final class FlashlightActionExecutor implements AndroidActionExecutor {
    private final FlashlightController controller;

    public FlashlightActionExecutor(FlashlightController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing flashlight controller");
        this.controller = controller;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"toggle_flashlight".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || arguments.size() != 1 || !arguments.containsKey("on")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected exactly one boolean on argument.");
        }
        String value = arguments.get("on");
        if (!"true".equals(value) && !"false".equals(value)) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "The on argument must be true or false.");
        }
        return controller.setEnabled(Boolean.parseBoolean(value));
    }
}
