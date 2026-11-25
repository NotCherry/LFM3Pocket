package com.notcherry.lfm3pocket;

import java.util.Map;

/** Dispatches only concrete actions implemented in this build; all other allowlisted tools fail closed. */
final class AndroidToolsExecutor implements AndroidActionExecutor {
    private static final AndroidActionExecutor DISABLED =
            (name, arguments) -> new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");

    private final AndroidActionExecutor flashlight;
    private final AndroidActionExecutor volume;
    private final AndroidActionExecutor timer;
    private final AndroidActionExecutor alarm;
    private final AndroidActionExecutor application;
    private final AndroidActionExecutor settings;
    private final AndroidActionExecutor text;
    private final AndroidActionExecutor builtins;
    private final AndroidActionExecutor notifications;
    private final AndroidActionExecutor reminder;
    private final AndroidActionExecutor message;
    private final AndroidActionExecutor calendar;

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text) {
        this(flashlight, volume, timer, alarm, application, settings, text, DISABLED, DISABLED, DISABLED, DISABLED, DISABLED);
    }

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text, AndroidActionExecutor builtins) {
        this(flashlight, volume, timer, alarm, application, settings, text, builtins, DISABLED, DISABLED, DISABLED, DISABLED);
    }

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text, AndroidActionExecutor builtins,
                         AndroidActionExecutor notifications) {
        this(flashlight, volume, timer, alarm, application, settings, text, builtins, notifications, DISABLED, DISABLED, DISABLED);
    }

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text, AndroidActionExecutor builtins,
                         AndroidActionExecutor notifications, AndroidActionExecutor reminder) {
        this(flashlight, volume, timer, alarm, application, settings, text, builtins, notifications, reminder, DISABLED, DISABLED);
    }

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text, AndroidActionExecutor builtins,
                         AndroidActionExecutor notifications, AndroidActionExecutor reminder,
                         AndroidActionExecutor message) {
        this(flashlight, volume, timer, alarm, application, settings, text, builtins, notifications, reminder, message, DISABLED);
    }

    AndroidToolsExecutor(AndroidActionExecutor flashlight, AndroidActionExecutor volume,
                         AndroidActionExecutor timer, AndroidActionExecutor alarm,
                         AndroidActionExecutor application, AndroidActionExecutor settings,
                         AndroidActionExecutor text, AndroidActionExecutor builtins,
                         AndroidActionExecutor notifications, AndroidActionExecutor reminder,
                         AndroidActionExecutor message, AndroidActionExecutor calendar) {
        if (flashlight == null || volume == null || timer == null || alarm == null
                || application == null || settings == null || text == null || builtins == null
                || notifications == null || reminder == null || message == null || calendar == null) {
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
        this.reminder = reminder;
        this.message = message;
        this.calendar = calendar;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if ("toggle_flashlight".equals(approvedToolName)) return flashlight.execute(approvedToolName, arguments);
        if ("set_volume".equals(approvedToolName)) return volume.execute(approvedToolName, arguments);
        if ("set_timer".equals(approvedToolName)) return timer.execute(approvedToolName, arguments);
        if ("set_reminder".equals(approvedToolName)) return reminder.execute(approvedToolName, arguments);
        if ("set_alarm".equals(approvedToolName) || "show_alarms".equals(approvedToolName))
            return alarm.execute(approvedToolName, arguments);
        if ("open_application".equals(approvedToolName)) return application.execute(approvedToolName, arguments);
        if ("open_settings".equals(approvedToolName)) return settings.execute(approvedToolName, arguments);
        if ("copy_text".equals(approvedToolName) || "share_text".equals(approvedToolName))
            return text.execute(approvedToolName, arguments);
        if ("draft_message".equals(approvedToolName) || "send_message".equals(approvedToolName))
            return message.execute(approvedToolName, arguments);
        if ("current_time".equals(approvedToolName) || "calculator".equals(approvedToolName)
                || "unit_convert".equals(approvedToolName) || "weather".equals(approvedToolName)
                || "web_search".equals(approvedToolName))
            return builtins.execute(approvedToolName, arguments);
        if ("list_notifications".equals(approvedToolName))
            return notifications.execute(approvedToolName, arguments);
        if ("list_events".equals(approvedToolName) || "create_event".equals(approvedToolName)
                || "update_event".equals(approvedToolName) || "delete_event".equals(approvedToolName))
            return calendar.execute(approvedToolName, arguments);
        return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
    }
}
