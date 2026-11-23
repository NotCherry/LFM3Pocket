package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** ADR 0024: chat status updates must not bypass {@link MainActivity#setChatStatus}. */
public final class MainActivityChatStatusContractTest {
    @Test
    public void mainActivityDoesNotTouchStatusTextViewDirectly() throws Exception {
        String source = read(repoRoot().resolve(
                "app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java"));
        assertFalse("MainActivity must not call status.setText", source.contains("status.setText"));
        assertFalse("MainActivity must not expose statusView()", source.contains("statusView()"));
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
