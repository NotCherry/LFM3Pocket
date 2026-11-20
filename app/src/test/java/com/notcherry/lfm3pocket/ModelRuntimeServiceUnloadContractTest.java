package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class ModelRuntimeServiceUnloadContractTest {
    @Test public void taskRemovalAndTimeoutUnloadRuntime() throws Exception {
        String source = read(repoRoot().resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelRuntimeService.java"));
        String taskRemoved = methodBody(source, "public void onTaskRemoved(");
        assertTrue(taskRemoved.contains("modelRuntime().unload(\"recents\", null)"));
        String timeout = methodBody(source, "public void onTimeout(");
        assertTrue(timeout.contains("modelRuntime().onServiceTimeout()"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("missing " + signature, start >= 0);
        int brace = source.indexOf('{', start);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return source.substring(start, i + 1);
            }
        }
        return "";
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
