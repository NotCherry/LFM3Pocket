package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** The model shade has one channel, and that channel is named Hanami model. */
public final class HanamiNotificationChannelTest {
    @Test public void onlyHanamiNotificationsCreatesChannels() throws Exception {
        Path root = repoRoot();
        List<String> creators = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("app/src/main"))) {
            files.filter(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".java") || name.endsWith(".kt");
            }).forEach(file -> {
                try {
                    String text = Files.readString(file);
                    if (text.contains("createNotificationChannel(")) {
                        creators.add(file.toString().replace('\\', '/'));
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        assertEquals(List.of("app/src/main/java/com/notcherry/lfm3pocket/HanamiNotifications.java"),
                creators.stream().map(path -> path.substring(path.indexOf("app/src/main"))).toList());
        String strings = Files.readString(root.resolve("app/src/main/res/values/strings.xml"), StandardCharsets.UTF_8);
        assertTrue(strings.contains("name=\"notification_channel_model_runtime\">Hanami model<"));
        String creator = Files.readString(root.resolve(
                "app/src/main/java/com/notcherry/lfm3pocket/HanamiNotifications.java"));
        assertTrue(creator.contains("MODEL_RUNTIME_CHANNEL_ID = \"model_runtime\""));
        assertTrue(creator.contains("notification_channel_model_runtime"));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) dir = dir.getParent();
        assertNotNull("settings.gradle", dir);
        return dir;
    }
}
