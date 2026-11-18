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
    private final AndroidActionExecutor builtins;
    private final AndroidActionExecutor notifications;

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text) {
        this(flashlight, volume, timer, alarm, application, settings, text,
                (name, arguments) -> new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled."),
                (name, arguments) -> new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled."));
    }

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text, AndroidActionExecutor builtins) {
        this(flashlight, volume, timer, alarm, application, settings, text, builtins,
                (name, arguments) -> new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled."));
    }

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text, AndroidActionExecutor builtins,
                         AndroidActionExecutor notifications) {
        if (flashlight == null || volume == null || timer == null || alarm == null
                || application == null || settings == null || text == null || builtins == null
                || notifications == null) {
            throw new IllegalArgumentException("Missing action executor");
        }
        this.flashlight = flashlight;
        this.volume = volume;
        this.timer = timer;
        this.alarm = alarm;
        this.application = application;
        this.settings = settings;
        this.text = text;
        this.builtins = builtins;
        this.notifications = notifications;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if ("toggle_flashlight".equals(approvedToolName)) return flashlight.execute(approvedToolName, arguments);
        if ("set_volume".equals(approvedToolName)) return volume.execute(approvedToolName, arguments);
        if ("set_timer".equals(approvedToolName)) return timer.execute(approvedToolName, arguments);
        if ("set_alarm".equals(approvedToolName) || "show_alarms".equals(approvedToolName))
            return alarm.execute(approvedToolName, arguments);
        if ("open_application".equals(approvedToolName)) return application.execute(approvedToolName, arguments);
        if ("open_settings".equals(approvedToolName)) return settings.execute(approvedToolName, arguments);
        if ("copy_text".equals(approvedToolName) || "share_text".equals(approvedToolName))
            return text.execute(approvedToolName, arguments);
        if ("current_time".equals(approvedToolName) || "calculator".equals(approvedToolName)
                || "unit_convert".equals(approvedToolName) || "weather".equals(approvedToolName))
            return builtins.execute(approvedToolName, arguments);
        if ("list_notifications".equals(approvedToolName))
            return notifications.execute(approvedToolName, arguments);
        return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
    }
}
