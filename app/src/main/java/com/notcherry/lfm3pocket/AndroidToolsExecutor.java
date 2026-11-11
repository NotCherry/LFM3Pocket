package com.notcherry.lfm3pocket;

import java.util.Map;

/** Dispatches only concrete actions implemented in this build; all other allowlisted tools fail closed. */
final class AndroidToolsExecutor implements AndroidActionExecutor {
    private final AndroidActionExecutor flashlight;
    private final AndroidActionExecutor volume;
    private final AndroidActionExecutor timer;
    private final AndroidActionExecutor alarm;
    private final AndroidActionExecutor application;
    private final AndroidActionExecutor settings;
    private final AndroidActionExecutor text;

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text) {
        if (flashlight == null || volume == null || timer == null || alarm == null
                || application == null || settings == null || text == null) {
            throw new IllegalArgumentException("Missing action executor");
        }
        this.flashlight = flashlight;
        this.volume = volume;
        this.timer = timer;
        this.alarm = alarm;
        this.application = application;
        this.settings = settings;
        this.text = text;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if ("toggle_flashlight".equals(approvedToolName)) return flashlight.execute(approvedToolName, arguments);
        if ("set_volume".equals(approvedToolName)) return volume.execute(approvedToolName, arguments);
        if ("set_timer".equals(approvedToolName)) return timer.execute(approvedToolName, arguments);
        if ("set_alarm".equals(approvedToolName)) return alarm.execute(approvedToolName, arguments);
        if ("open_application".equals(approvedToolName)) return application.execute(approvedToolName, arguments);
        if ("open_settings".equals(approvedToolName)) return settings.execute(approvedToolName, arguments);
        if ("copy_text".equals(approvedToolName) || "share_text".equals(approvedToolName))
            return text.execute(approvedToolName, arguments);
        return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
    }
}
