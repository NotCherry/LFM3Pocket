package com.notcherry.lfm3pocket;

import java.util.Map;

/** Dispatches only concrete actions implemented in this build; all other allowlisted tools fail closed. */
final class AndroidToolsExecutor implements AndroidActionExecutor {
    private final AndroidActionExecutor flashlight;
    private final AndroidActionExecutor volume;
    private final AndroidActionExecutor timer;
    private final AndroidActionExecutor alarm;

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm) {
        if (flashlight == null || volume == null || timer == null || alarm == null) {
            throw new IllegalArgumentException("Missing action executor");
        }
        this.flashlight = flashlight;
        this.volume = volume;
        this.timer = timer;
        this.alarm = alarm;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if ("toggle_flashlight".equals(approvedToolName)) return flashlight.execute(approvedToolName, arguments);
        if ("set_volume".equals(approvedToolName)) return volume.execute(approvedToolName, arguments);
        if ("set_timer".equals(approvedToolName)) return timer.execute(approvedToolName, arguments);
        if ("set_alarm".equals(approvedToolName)) return alarm.execute(approvedToolName, arguments);
        return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
    }
}
