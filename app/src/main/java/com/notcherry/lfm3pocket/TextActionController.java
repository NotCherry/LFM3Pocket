package com.notcherry.lfm3pocket;

/** Coordinates exact text copy/share actions after the app's approval policy has resolved. */
public final class TextActionController {
    public interface Clipboard { void copy(String text); }
    public interface ShareSheet { void open(String text); }

    public static final class NoShareSheetException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Clipboard clipboard;
    private final ShareSheet shareSheet;

    public TextActionController(Clipboard clipboard, ShareSheet shareSheet) {
        if (clipboard == null || shareSheet == null) throw new IllegalArgumentException("Missing text action adapter");
        this.clipboard = clipboard;
        this.shareSheet = shareSheet;
    }

    public AndroidActionExecutor.ActionResult copy(String text) {
        if (text == null || text.length() > 4000) {
            return result(false, "INVALID_ARGUMENTS", "Clipboard text must contain no more than 4000 characters.");
        }
        try {
            clipboard.copy(text);
            return result(true, "TEXT_COPIED", "Copied the requested text to the clipboard.");
        } catch (RuntimeException e) {
            return result(false, "CLIPBOARD_FAILED", "Android could not copy the requested text.");
        }
    }

    public AndroidActionExecutor.ActionResult share(String text) {
        if (text == null || text.isEmpty() || text.length() > 4000) {
            return result(false, "INVALID_ARGUMENTS", "Share text must contain 1..4000 characters.");
        }
        try {
            shareSheet.open(text);
            return result(true, "SHARE_SHEET_OPENED", "Opened Android's share sheet. Choose where to share; nothing has been sent yet.");
        } catch (NoShareSheetException e) {
            return result(false, "SHARE_SHEET_UNAVAILABLE", "Android has no app available to show the share sheet.");
        } catch (RuntimeException e) {
            return result(false, "SHARE_SHEET_FAILED", "Android could not open the share sheet.");
        }
    }

    private static AndroidActionExecutor.ActionResult result(boolean success, String status, String message) {
        return new AndroidActionExecutor.ActionResult(success, status, message);
    }
}
