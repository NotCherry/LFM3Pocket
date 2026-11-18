package com.notcherry.lfm3pocket;

import java.util.Map;

/** Adapter for draft_message and send_message tools. */
public final class MessageActionExecutor implements AndroidActionExecutor {
    private final MessageActionController controller;

    public MessageActionExecutor(MessageActionController controller) {
        if (controller == null) throw new IllegalArgumentException("Missing message controller");
        this.controller = controller;
    }

    @Override
    public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (arguments == null) return new ActionResult(false, "INVALID_ARGUMENTS", "Missing arguments");
        if ("draft_message".equals(approvedToolName)) {
            String channel = arguments.get("channel");
            String to = arguments.get("to");
            String body = arguments.get("body");
            String pkg = arguments.get("package");
            return controller.draftMessage(channel, to, body, pkg);
        }
        if ("send_message".equals(approvedToolName)) {
            String draftId = arguments.get("draft_id");
            return controller.sendMessage(draftId);
        }
        return new ActionResult(false, "UNKNOWN_TOOL", "This Android action is not enabled.");
    }
}
