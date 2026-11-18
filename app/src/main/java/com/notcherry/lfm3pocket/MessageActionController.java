package com.notcherry.lfm3pocket;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Draft and send message actions (intent-based; no silent send). */
public final class MessageActionController {
    public static final Set<String> ALLOWED_SHARE_PACKAGES;
    static {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        s.add("com.whatsapp");
        s.add("org.telegram.messenger");
        s.add("org.thoughtcrime.securesms");
        ALLOWED_SHARE_PACKAGES = Collections.unmodifiableSet(s);
    }

    private final MessageDraftStore drafts;
    private final AndroidMessageActions actions;

    public MessageActionController(MessageDraftStore drafts, AndroidMessageActions actions) {
        if (drafts == null || actions == null) throw new IllegalArgumentException("Missing dependencies");
        this.drafts = drafts;
        this.actions = actions;
    }

    public static boolean isAllowedSharePackage(String pkg) {
        return pkg != null && ALLOWED_SHARE_PACKAGES.contains(pkg);
    }

    public AndroidActionExecutor.ActionResult draftMessage(String channel, String to, String body, String sharePackage) {
        if (channel == null) return fail("INVALID_ARGUMENTS", "Missing channel");
        if ("sms".equals(channel)) {
            MessageDraftStore.Draft d = drafts.create("sms", to == null ? "" : to, body, null);
            return ok("DRAFT_CREATED", "Draft created (id=" + d.id + "). Approve to open SMS composer.");
        } else if ("share".equals(channel)) {
            if (sharePackage == null || !isAllowedSharePackage(sharePackage)) {
                return fail("INVALID_ARGUMENTS", "Share package is not allowlisted.");
            }
            MessageDraftStore.Draft d = drafts.create("share", to == null ? "" : to, body, sharePackage);
            return ok("DRAFT_CREATED", "Draft created (id=" + d.id + "). Approve to open composer in the selected app.");
        }
        return fail("INVALID_ARGUMENTS", "Unsupported channel");
    }

    public AndroidActionExecutor.ActionResult sendMessage(String draftId) {
        if (draftId == null || draftId.isEmpty()) return fail("INVALID_ARGUMENTS", "Missing draft_id");
        MessageDraftStore.Draft d = drafts.get(draftId);
        if (d == null) return fail("DRAFT_NOT_FOUND", "Draft not found or expired. Create a draft first.");
        try {
            if ("sms".equals(d.channel)) {
                actions.openSmsComposer(d.to, d.body);
                return ok("SMS_COMPOSER_OPENED", "Opened SMS composer. Nothing was sent automatically.");
            } else if ("share".equals(d.channel)) {
                actions.openShareToPackage(d.sharePackage, d.body);
                return ok("SHARE_COMPOSER_OPENED", "Opened composer in the selected app. Nothing was sent automatically.");
            }
            return fail("INVALID_ARGUMENTS", "Unknown draft channel");
        } catch (Exception e) {
            String msg = e.getMessage();
            return fail("COMPOSER_FAILED", msg == null ? "Could not open composer" : msg);
        }
    }

    private static AndroidActionExecutor.ActionResult ok(String status, String message) {
        return new AndroidActionExecutor.ActionResult(true, status, message);
    }

    private static AndroidActionExecutor.ActionResult fail(String status, String message) {
        return new AndroidActionExecutor.ActionResult(false, status, message);
    }
}
