package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** ADR 0024: default-assistant and crash-log settings UI live in section classes. */
public final class MainActivitySettingsTailSectionsContractTest {
    @Test
    public void mainActivityDoesNotWireDefaultAssistantOrCrashLogBlocks() throws Exception {
        String source = read(repoRoot().resolve(
                "app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java"));
        assertFalse(source.contains("settings_default_assistant"));
        assertFalse(source.contains("settings_crash_log_view"));
        assertFalse(source.contains("settings_crash_log_export"));
        assertFalse(source.contains("settings_crash_log_clear"));
        assertFalse(source.contains("settings_crash_log_text"));
        assertFalse(source.contains("CrashLog.read"));
        assertFalse(source.contains("CrashLog.clear"));
        assertFalse(source.contains("openDefaultAssistantPicker"));
        assertFalse(source.contains("Hanami crash log"));
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
