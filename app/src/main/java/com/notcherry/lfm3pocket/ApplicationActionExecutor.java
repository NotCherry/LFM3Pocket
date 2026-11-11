package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter for the validated open_application request. */
public final class ApplicationActionExecutor implements AndroidActionExecutor {
    private final InstalledAppController controller;

    public ApplicationActionExecutor(InstalledAppController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing installed-app controller");
        this.controller = controller;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"open_application".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || arguments.size() != 1 || !arguments.containsKey("package")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected exactly one package argument.");
        }
        return controller.open(arguments.get("package"));
    }
}
