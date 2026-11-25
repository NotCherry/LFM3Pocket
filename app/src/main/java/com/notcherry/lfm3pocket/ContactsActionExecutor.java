package com.notcherry.lfm3pocket;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Executes read-only {@code lookup_contact} offline when the user has granted READ_CONTACTS. */
public final class ContactsActionExecutor implements AndroidActionExecutor {
    private final BooleanSupplier hasPermission;
    private final ContactsGateway gateway;

    public ContactsActionExecutor(BooleanSupplier hasPermission, ContactsGateway gateway) {
        if (hasPermission == null || gateway == null) {
            throw new IllegalArgumentException("Missing contacts executor dependencies");
        }
        this.hasPermission = hasPermission;
        this.gateway = gateway;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!"lookup_contact".equals(approvedToolName)) {
            return new ActionResult(false, "UNKNOWN_TOOL", "This contacts tool is not enabled.");
        }
        if (!hasPermission.getAsBoolean()) {
            return new ActionResult(false, "PERMISSION_DENIED",
                    "Grant Contacts permission in Settings → External Tools & Shortcuts to look up contacts.");
        }
        String query = arguments == null ? "" : arguments.getOrDefault("query", "").trim();
        if (query.isEmpty()) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "query is required");
        }
        int limit = 5;
        if (arguments != null && arguments.containsKey("limit")) {
            try {
                limit = Math.max(1, Math.min(10, Integer.parseInt(arguments.get("limit").trim())));
            } catch (NumberFormatException ignored) {}
        }
        try {
            List<ContactsGateway.ContactMatch> matches = gateway.search(query, limit);
            if (matches == null || matches.isEmpty()) {
                return new ActionResult(true, "CONTACTS_EMPTY",
                        "UNTRUSTED_CONTACTS No contacts matched \"" + query + "\"");
            }
            StringBuilder out = new StringBuilder("UNTRUSTED_CONTACTS Found ");
            out.append(matches.size()).append(" contact(s)");
            for (ContactsGateway.ContactMatch m : matches) {
                out.append('\n').append(m.displayName.isEmpty() ? "(unnamed)" : m.displayName);
                if (!m.phone.isEmpty()) out.append(" | phone=").append(m.phone);
                if (!m.email.isEmpty()) out.append(" | email=").append(m.email);
            }
            return new ActionResult(true, "CONTACTS_OK", out.toString());
        } catch (SecurityException e) {
            return new ActionResult(false, "PERMISSION_DENIED",
                    "Contacts permission was revoked. Re-enable it in Android Settings.");
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new ActionResult(false, "CONTACTS_FAILED", "Contact lookup failed: " + msg);
        }
    }
}
