package com.notcherry.lfm3pocket;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class InstalledAppControllerTest {
    @Test public void launchesOnlyTheSingleVisibleLaunchActivityForTheRequestedPackage() {
        FakeCatalog catalog = new FakeCatalog(target("com.example.notes", "com.example.notes.Home"));
        AndroidActionExecutor.ActionResult result = new InstalledAppController(catalog).open("com.example.notes");

        assertTrue(result.succeeded);
        assertEquals("APP_OPENED", result.status);
        assertEquals("com.example.notes", catalog.requestedPackage);
        assertEquals(1, catalog.opens);
        assertEquals("com.example.notes.Home", catalog.opened.className);
        assertTrue(result.message.contains("Notes"));
    }

    @Test public void missingOrAmbiguousLaunchActivitiesReturnErrorsWithoutLaunching() {
        FakeCatalog missing = new FakeCatalog();
        assertEquals("APP_NOT_FOUND", new InstalledAppController(missing).open("com.example.missing").status);
        assertEquals(0, missing.opens);

        FakeCatalog ambiguous = new FakeCatalog(target("com.example.mail", "com.example.mail.Inbox"),
                target("com.example.mail", "com.example.mail.Compose"));
        assertEquals("APP_AMBIGUOUS", new InstalledAppController(ambiguous).open("com.example.mail").status);
        assertEquals(0, ambiguous.opens);
    }

    @Test public void malformedOrMismatchedTargetsFailClosed() {
        FakeCatalog malformed = new FakeCatalog(target("com.example.notes", "com.example.notes.Home"));
        assertEquals("INVALID_ARGUMENTS", new InstalledAppController(malformed).open("intent:#Intent;end").status);
        assertEquals(0, malformed.lookups);

        FakeCatalog mismatch = new FakeCatalog(target("com.attacker.app", "com.attacker.app.Main"));
        assertEquals("APP_NOT_FOUND", new InstalledAppController(mismatch).open("com.example.notes").status);
        assertEquals(0, mismatch.opens);
    }

    @Test public void launchFailureNeverReportsSuccess() {
        FakeCatalog catalog = new FakeCatalog(target("com.example.notes", "com.example.notes.Home"));
        catalog.failure = new SecurityException("denied");

        AndroidActionExecutor.ActionResult result = new InstalledAppController(catalog).open("com.example.notes");

        assertFalse(result.succeeded);
        assertEquals("APP_LAUNCH_DENIED", result.status);
    }

    @Test public void adapterAndDispatcherRequireTheExactPackageArgument() {
        FakeCatalog catalog = new FakeCatalog(target("com.example.notes", "com.example.notes.Home"));
        ApplicationActionExecutor apps = new ApplicationActionExecutor(new InstalledAppController(catalog));
        AndroidActionExecutor unused = (name, arguments) ->
                new AndroidActionExecutor.ActionResult(true, "WRONG_HANDLER", name);
        AndroidToolsExecutor dispatcher = new AndroidToolsExecutor(unused, unused, unused,
                new AlarmActionExecutor(new AlarmController((hour, minute, label) -> { })), apps, unused, unused);
        Map<String, String> args = new LinkedHashMap<>();
        args.put("package", "com.example.notes");

        assertEquals("INVALID_ARGUMENTS", apps.execute("open_application", Collections.emptyMap()).status);
        Map<String, String> extra = new LinkedHashMap<>(args); extra.put("activity", "Anything");
        assertEquals("INVALID_ARGUMENTS", apps.execute("open_application", extra).status);
        assertEquals("APP_OPENED", dispatcher.execute("open_application", args).status);
        assertEquals("UNKNOWN_TOOL", dispatcher.execute("set_settings", args).status);
        assertEquals(1, catalog.opens);
    }

    private static InstalledAppController.LaunchTarget target(String packageName, String className) {
        return new InstalledAppController.LaunchTarget(packageName, className, "Notes");
    }

    private static final class FakeCatalog implements InstalledAppController.Launcher {
        final List<InstalledAppController.LaunchTarget> targets;
        int lookups, opens;
        String requestedPackage;
        InstalledAppController.LaunchTarget opened;
        RuntimeException failure;
        FakeCatalog(InstalledAppController.LaunchTarget... targets) { this.targets = Arrays.asList(targets); }
        @Override public List<InstalledAppController.LaunchTarget> findLaunchActivities(String packageName) {
            lookups++; requestedPackage = packageName; return targets;
        }
        @Override public void launch(InstalledAppController.LaunchTarget target) {
            opens++; if (failure != null) throw failure; opened = target;
        }
    }
}
