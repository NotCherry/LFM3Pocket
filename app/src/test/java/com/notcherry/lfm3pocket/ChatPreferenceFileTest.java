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

/**
 * A seeded model path has to be the file chat actually opens. The Hanami install
 * id would make {@code Activity.getPreferences} pick a different file.
 */
public final class ChatPreferenceFileTest {
    @Test public void hanamiInstallIdMustNotRenameTheSharedChatFile() throws Exception {
        assertEquals("MainActivity", RuntimePreferences.activityPreferenceName(
                "com.notcherry.lfm3pocket", "com.notcherry.lfm3pocket.MainActivity"));
        assertEquals("com.notcherry.lfm3pocket.MainActivity", RuntimePreferences.activityPreferenceName(
                "dev.notcherry.hanami", "com.notcherry.lfm3pocket.MainActivity"));
        assertEquals("MainActivity", RuntimePreferences.FILE);

        String source = read(repoRoot().resolve(
                "app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java"));
        assertTrue(source.contains("getSharedPreferences(RuntimePreferences.FILE"));
        assertFalse(source.contains("getPreferences("));
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
