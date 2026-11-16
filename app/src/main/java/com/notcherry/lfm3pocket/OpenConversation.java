package com.notcherry.lfm3pocket;

/** Which conversation a chat screen opens, and whether a running reply must be confirmed first. */
public final class OpenConversation {
    public final String id;
    public final boolean confirmSwitch;

    private OpenConversation(String id, boolean confirmSwitch) {
        this.id = id;
        this.confirmSwitch = confirmSwitch;
    }

    /**
     * Preference and explicit extras win over the resident id so a stored conversation
     * (history, a notification, or a test seed) is the one that receives new rows.
     * The resident id is only a fallback before anything has been stored.
     * Switching away from a reply that is still generating must be confirmed.
     */
    public static OpenConversation choose(String intentId, String preferenceId, String runtimeId,
                                          boolean generating, String minted) {
        String id = !blank(intentId) ? intentId
                : !blank(preferenceId) ? preferenceId
                : !blank(runtimeId) ? runtimeId
                : minted;
        boolean switching = !blank(runtimeId) && !runtimeId.equals(id);
        return new OpenConversation(id, switching && generating);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
