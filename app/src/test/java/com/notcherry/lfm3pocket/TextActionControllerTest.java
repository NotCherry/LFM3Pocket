package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class TextActionControllerTest {
    @Test public void copiesTheExactRequestedTextAndReportsOnlyClipboardCompletion() {
        FakeTextActions actions = new FakeTextActions();
        String exact = "  keep this\nexactly 🙂  ";

        AndroidActionExecutor.ActionResult result = new TextActionController(actions, actions).copy(exact);

        assertTrue(result.succeeded);
        assertEquals("TEXT_COPIED", result.status);
        assertEquals(exact, actions.copied);
    }

    @Test public void opensShareSheetWithExactTextWithoutClaimingItWasSent() {
        FakeTextActions actions = new FakeTextActions();
        String exact = "Share this text";

        AndroidActionExecutor.ActionResult result = new TextActionController(actions, actions).share(exact);

        assertTrue(result.succeeded);
        assertEquals("SHARE_SHEET_OPENED", result.status);
        assertEquals(exact, actions.shared);
        assertTrue(result.message.contains("Choose where to share"));
        assertTrue(result.message.contains("nothing has been sent yet"));
    }

    @Test public void emptyCopyAndEmptyOrOversizedShareAreHandledWithoutSideEffects() {
        FakeTextActions actions = new FakeTextActions();
        TextActionController controller = new TextActionController(actions, actions);

        assertEquals("TEXT_COPIED", controller.copy("").status);
        assertEquals("INVALID_ARGUMENTS", controller.share("").status);
        assertEquals("INVALID_ARGUMENTS", controller.copy(repeat('x', 4001)).status);
        assertEquals("INVALID_ARGUMENTS", controller.share(repeat('x', 4001)).status);
        assertEquals("", actions.copied);
        assertEquals(0, actions.shareCalls);
    }

    @Test public void clipboardAndShareSheetFailuresAreNotReportedAsSuccess() {
        FakeTextActions clipboardFailure = new FakeTextActions();
        clipboardFailure.copyFailure = new SecurityException("clipboard denied");
        assertEquals("CLIPBOARD_FAILED", new TextActionController(clipboardFailure, clipboardFailure)
                .copy("secret").status);

        FakeTextActions shareFailure = new FakeTextActions();
        shareFailure.shareFailure = new TextActionController.NoShareSheetException();
        assertEquals("SHARE_SHEET_UNAVAILABLE", new TextActionController(shareFailure, shareFailure)
                .share("hello").status);
    }

    @Test public void adapterAndDispatcherAcceptOnlyExactCopyOrShareArguments() {
        FakeTextActions actions = new FakeTextActions();
        ClipboardShareActionExecutor text = new ClipboardShareActionExecutor(new TextActionController(actions, actions));
        AndroidActionExecutor unused = (name, arguments) ->
                new AndroidActionExecutor.ActionResult(true, "WRONG_HANDLER", name);
        AndroidToolsExecutor dispatcher = new AndroidToolsExecutor(unused, unused, unused, unused, unused, unused, text);
        Map<String, String> args = new LinkedHashMap<>(); args.put("text", "copy exactly");

        assertEquals("INVALID_ARGUMENTS", text.execute("copy_text", Collections.emptyMap()).status);
        Map<String, String> extra = new LinkedHashMap<>(args); extra.put("html", "<b>unsafe</b>");
        assertEquals("INVALID_ARGUMENTS", text.execute("copy_text", extra).status);
        assertEquals("TEXT_COPIED", dispatcher.execute("copy_text", args).status);
        args.put("text", "send to chooser");
        assertEquals("SHARE_SHEET_OPENED", dispatcher.execute("share_text", args).status);
        assertEquals("UNKNOWN_TOOL", dispatcher.execute("delete_clipboard", args).status);
        assertEquals(1, actions.copyCalls);
        assertEquals(1, actions.shareCalls);
    }

    private static String repeat(char c, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int i = 0; i < count; i++) value.append(c);
        return value.toString();
    }

    private static final class FakeTextActions implements TextActionController.Clipboard, TextActionController.ShareSheet {
        String copied, shared;
        int copyCalls, shareCalls;
        RuntimeException copyFailure, shareFailure;
        @Override public void copy(String text) { copyCalls++; if (copyFailure != null) throw copyFailure; copied = text; }
        @Override public void open(String text) { shareCalls++; if (shareFailure != null) throw shareFailure; shared = text; }
    }
}
