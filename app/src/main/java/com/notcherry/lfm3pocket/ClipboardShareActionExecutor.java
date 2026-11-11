package com.notcherry.lfm3pocket;

import java.util.Map;

/** Strict adapter for the copy_text and share_text requests. */
public final class ClipboardShareActionExecutor implements AndroidActionExecutor {
    private final TextActionController controller;

    public ClipboardShareActionExecutor(TextActionController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing text action controller");
        this.controller = controller;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"copy_text".equals(approvedToolName) && !"share_text".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
        }
        if (arguments == null || arguments.size() != 1 || !arguments.containsKey("text")) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "Expected exactly one text argument.");
        }
        String text = arguments.get("text");
        if ("copy_text".equals(approvedToolName)) return controller.copy(text);
        return controller.share(text);
    }
}
