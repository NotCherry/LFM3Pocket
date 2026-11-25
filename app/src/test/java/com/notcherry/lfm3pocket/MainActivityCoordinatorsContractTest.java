package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Contracts and unit tests for the non-UI coordinators extracted from {@link MainActivity} (#322).
 */
public final class MainActivityCoordinatorsContractTest {
    private static final int MAX_MAIN_ACTIVITY_LINES = 1200;

    @Test
    public void mainActivityStaysUnderLineCeilingAndDelegatesToCoordinators() throws Exception {
        Path mainPath = repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java");
        List<String> lines = Files.readAllLines(mainPath);
        assertTrue("MainActivity.java grew to " + lines.size() + " lines (ceiling " + MAX_MAIN_ACTIVITY_LINES + ")",
                lines.size() < MAX_MAIN_ACTIVITY_LINES);

        String source = read(mainPath);
        assertTrue(source.contains("ModelImportCoordinator"));
        assertTrue(source.contains("AttachmentImportCoordinator"));
        assertTrue(source.contains("VoiceInputCoordinator"));
        assertTrue(source.contains("ModelDownloadDialogs"));
        assertTrue(source.contains("ToolOutcomePresenter"));
        assertTrue(source.contains("CalendarPickerHelper"));
        assertTrue(source.contains("SettingStepperHelper"));

        assertFalse("Model import I/O belongs in ModelImportCoordinator",
                source.contains("new ModelImporter("));
        assertFalse("Attachment preprocessing belongs in AttachmentImportCoordinator",
                source.contains("ImagePreprocessor.prepare("));
        assertFalse("Document parsing belongs in AttachmentImportCoordinator",
                source.contains("DocumentAttachmentReader.read("));
        assertFalse("CalendarContract queries belong in CalendarPickerHelper",
                source.contains("CalendarContract.Calendars"));
        assertTrue("AttachmentImportCoordinator must run on dedicated ioWorker, never hanami-infer worker",
                source.contains("new AttachmentImportCoordinator.Deps(\n                this, ioWorker"));
        assertTrue("recomputeBusy must include attachmentCoordinator.isImporting()",
                source.contains("attachmentCoordinator.isImporting()"));
        assertTrue("recomputeBusy must include modelImportCoordinator.isBusy()",
                source.contains("modelImportCoordinator.isBusy()"));
    }

    @Test
    public void isContentUriRecognizesContentSchemeOnly() {
        assertTrue(ModelImportCoordinator.isContentUri("content://com.android.externalstorage/doc/1"));
        assertFalse(ModelImportCoordinator.isContentUri("/data/user/0/app/files/models/model.gguf"));
        assertFalse(ModelImportCoordinator.isContentUri("file:///sdcard/model.gguf"));
        assertFalse(ModelImportCoordinator.isContentUri(""));
        assertFalse(ModelImportCoordinator.isContentUri(null));
    }

    @Test
    public void formatMbFormatsMegabytesWithOneDecimalPlace() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertEquals("0.0 MB", ModelDownloadDialogs.formatMb(0L));
            assertEquals("1.0 MB", ModelDownloadDialogs.formatMb(1024L * 1024L));
            assertEquals("2.5 MB", ModelDownloadDialogs.formatMb((long) (2.5 * 1024 * 1024)));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void clampNudgeHandlesValidOutOfRangeAndInvalidInput() {
        assertEquals(1024, SettingStepperHelper.clampNudge("768", 256, 512, 8192));
        assertEquals(8192, SettingStepperHelper.clampNudge("8100", 256, 512, 8192));
        assertEquals(512, SettingStepperHelper.clampNudge("600", -256, 512, 8192));
        assertEquals(768, SettingStepperHelper.clampNudge("not-a-number", 256, 512, 8192));
        assertEquals(512, SettingStepperHelper.clampNudge("", -10, 512, 8192));
        assertEquals(512, SettingStepperHelper.clampNudge(null, -10, 512, 8192));
    }

    @Test
    public void toolOutcomeLabelCoversAllStatusesAndResults() {
        assertEquals("Needs your approval", ToolOutcomePresenter.labelFor("PENDING_CONFIRMATION", null));
        assertEquals("Declined", ToolOutcomePresenter.labelFor("DECLINED", null));
        assertEquals("Approval expired", ToolOutcomePresenter.labelFor("EXPIRED", null));
        assertEquals("Permission denied", ToolOutcomePresenter.labelFor("PERMISSION_DENIED", null));
        assertEquals("Request blocked", ToolOutcomePresenter.labelFor("UNTRUSTED_SOURCE", null));
        assertEquals("Action already resolved", ToolOutcomePresenter.labelFor("ALREADY_RESOLVED", null));
        assertEquals("Action no longer available", ToolOutcomePresenter.labelFor("UNKNOWN_PROPOSAL", null));
        assertEquals("Action unavailable", ToolOutcomePresenter.labelFor("EXECUTED", null));
        assertEquals("Action complete", ToolOutcomePresenter.labelFor("EXECUTED",
                new AndroidActionExecutor.ActionResult(true, "OK", "Done")));
        assertEquals("Action failed", ToolOutcomePresenter.labelFor("EXECUTED",
                new AndroidActionExecutor.ActionResult(false, "ERR", "Failed")));

        ToolCoordinator coordinator = new ToolCoordinator(
                req -> new AndroidActionExecutor.ActionResult(true, "EXECUTED", "Done"),
                req -> true, () -> 1_000L);
        ToolRegistry.Request req = new ToolRegistry().parse("{\"tool\":\"show_alarms\",\"arguments\":{}}").request;
        assertEquals("Action complete", ToolOutcomePresenter.labelFor(
                coordinator.propose(req, ToolCoordinator.Source.USER_ACTION)));
        assertEquals("Request blocked", ToolOutcomePresenter.labelFor(
                coordinator.propose(req, ToolCoordinator.Source.IMAGE)));
        assertEquals("Action no longer available", ToolOutcomePresenter.labelFor(
                coordinator.lookup("missing-id")));
    }

    private static String read(Path path) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) dir = dir.getParent();
        assertNotNull("settings.gradle", dir);
        return dir;
    }
}
