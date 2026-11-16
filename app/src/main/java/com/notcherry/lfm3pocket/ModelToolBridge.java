package com.notcherry.lfm3pocket;

/**
 * Turns a finished assistant reply into an allowlisted tool proposal.
 *
 * <p>Only a whole-message JSON object, or one fenced {@code json} block that is
 * itself a single object, is considered. Prose around a fragment is ignored, so
 * the model cannot smuggle an action inside a paragraph. Authorization still
 * goes through {@link ToolCoordinator} as {@code MODEL_PROPOSAL}.
 */
public final class ModelToolBridge {
    private ModelToolBridge() {}

    public static String extractToolJson(String assistantText) {
        if (assistantText == null) return null;
        String trimmed = assistantText.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
        int fence = trimmed.indexOf("```");
        if (fence < 0) return null;
        int langEnd = trimmed.indexOf('\n', fence + 3);
        if (langEnd < 0) return null;
        String lang = trimmed.substring(fence + 3, langEnd).trim().toLowerCase();
        if (!lang.isEmpty() && !lang.equals("json")) return null;
        int close = trimmed.indexOf("```", langEnd + 1);
        if (close < 0) return null;
        String block = trimmed.substring(langEnd + 1, close).trim();
        if (block.startsWith("{") && block.endsWith("}")) return block;
        return null;
    }

    /**
     * @return a coordinator outcome when the reply was a tool call, otherwise null
     *     when there was nothing to propose (not an error).
     */
    public static ToolCoordinator.Outcome proposeFromAssistant(
            ToolRegistry registry, ToolCoordinator coordinator, String assistantText) {
        String json = extractToolJson(assistantText);
        if (json == null) return null;
        ToolRegistry.Parsed parsed = registry.parse(json);
        // Invalid or unknown tools stay inert. They never reach a handler.
        if (!parsed.isValid()) return null;
        return coordinator.propose(parsed.request, ToolCoordinator.Source.MODEL_PROPOSAL);
    }
}
