package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter from an approved set_volume call to the checked volume controller. */
public final class VolumeActionExecutor implements AndroidActionExecutor {
    private final VolumeController controller;

    public VolumeActionExecutor(VolumeController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing volume controller");
        this.controller = controller;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"set_volume".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || arguments.size() != 2 ||
                !arguments.containsKey("stream") || !arguments.containsKey("percent")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected exactly stream and percent arguments.");
        }
        String stream = arguments.get("stream");
        String percent = arguments.get("percent");
        if (percent == null || !percent.matches("[0-9]{1,3}")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Volume percent must be an integer from 0 to 100.");
        }
        try {
            return controller.setVolume(stream, Integer.parseInt(percent));
        } catch (NumberFormatException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Volume percent must be an integer from 0 to 100.");
        }
    }
}
