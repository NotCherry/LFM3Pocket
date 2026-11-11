package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter for the validated open_settings page. */
public final class SettingsActionExecutor implements AndroidActionExecutor {
    private final SettingsPageController controller;

    public SettingsActionExecutor(SettingsPageController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing settings controller");
        this.controller = controller;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"open_settings".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || arguments.size() != 1 || !arguments.containsKey("page")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected exactly one supported settings page argument.");
        }
        return controller.open(arguments.get("page"));
    }
}
