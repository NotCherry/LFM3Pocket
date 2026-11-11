package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class SettingsPageControllerTest {
    @Test public void opensOnlyTheFiveSupportedSettingsPages() {
        FakeLauncher launcher = new FakeLauncher();
        SettingsPageController controller = new SettingsPageController(launcher);

        for (String page : new String[]{"wifi", "bluetooth", "sound", "display", "application"}) {
            AndroidActionExecutor.ActionResult result = controller.open(page);
            assertTrue(page + ": " + result.message, result.succeeded);
            assertEquals("SETTINGS_OPENED", result.status);
            assertEquals(page, launcher.lastPage.wireName);
        }
        assertEquals(5, launcher.calls);
    }

    @Test public void unsupportedPageIsRejectedBeforeAnAndroidIntentIsRequested() {
        FakeLauncher launcher = new FakeLauncher();

        AndroidActionExecutor.ActionResult result = new SettingsPageController(launcher).open("developer");

        assertFalse(result.succeeded);
        assertEquals("UNSUPPORTED_SETTINGS_PAGE", result.status);
        assertEquals(0, launcher.calls);
    }

    @Test public void missingHandlerAndAndroidDenialAreReportedAsFailures() {
        FakeLauncher missing = new FakeLauncher();
        missing.failure = new SettingsPageController.NoSettingsPageException();
        assertEquals("SETTINGS_PAGE_UNAVAILABLE", new SettingsPageController(missing).open("wifi").status);

        FakeLauncher denied = new FakeLauncher();
        denied.failure = new SecurityException("denied");
        AndroidActionExecutor.ActionResult result = new SettingsPageController(denied).open("sound");
        assertFalse(result.succeeded);
        assertEquals("SETTINGS_OPEN_DENIED", result.status);
    }

    @Test public void actionExecutorAndDispatcherRequireExactlyOneSupportedPage() {
        FakeLauncher launcher = new FakeLauncher();
        SettingsActionExecutor settings = new SettingsActionExecutor(new SettingsPageController(launcher));
        AndroidActionExecutor unused = (name, arguments) ->
                new AndroidActionExecutor.ActionResult(true, "WRONG_HANDLER", name);
        AndroidToolsExecutor dispatcher = new AndroidToolsExecutor(unused, unused, unused, unused, unused, settings);
        Map<String, String> args = new LinkedHashMap<>(); args.put("page", "wifi");

        assertEquals("INVALID_ARGUMENTS", settings.execute("open_settings", Collections.emptyMap()).status);
        Map<String, String> extra = new LinkedHashMap<>(args); extra.put("package", "com.example.app");
        assertEquals("INVALID_ARGUMENTS", settings.execute("open_settings", extra).status);
        assertEquals("SETTINGS_OPENED", dispatcher.execute("open_settings", args).status);
        assertEquals("UNKNOWN_TOOL", dispatcher.execute("open_developer_options", args).status);
        assertEquals(1, launcher.calls);
    }

    private static final class FakeLauncher implements SettingsPageController.Launcher {
        int calls;
        SettingsPageController.Page lastPage;
        RuntimeException failure;
        @Override public void open(SettingsPageController.Page page) {
            calls++; if (failure != null) throw failure; lastPage = page;
        }
    }
}
