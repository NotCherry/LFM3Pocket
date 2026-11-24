package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regression contracts for issue #298 (unique request codes, document attach wiring, manifest, status glyph). */
public final class MainActivityRequestCodesContractTest {
    private static final Pattern REQUEST_CODE = Pattern.compile(
            "private\\s+static\\s+final\\s+int\\s+((?:PICK|TAKE|OPEN|REQUEST)_[A-Z0-9_]+)\\s*=\\s*(\\d+)\\s*;");

    @Test
    public void activityRequestCodesAreUniqueAndPickDocumentIsHandled() throws Exception {
        String source = read(repoRoot().resolve(
                "app/src/main/java/com/notcherry/lfm3pocket/MainActivity.java"));
        Matcher matcher = REQUEST_CODE.matcher(source);
        Map<Integer, String> seen = new HashMap<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            int code = Integer.parseInt(matcher.group(2));
            String previous = seen.put(code, name);
            assertTrue("Duplicate request code " + code + " for " + name + " and " + previous,
                    previous == null);
        }
        assertTrue("Expected PICK_DOCUMENT constant", seen.containsValue("PICK_DOCUMENT"));
        assertTrue("Expected OPEN_MMPROJ_URI constant", seen.containsValue("OPEN_MMPROJ_URI"));
        assertTrue("Expected REQUEST_ASSISTANT_ROLE constant", seen.containsValue("REQUEST_ASSISTANT_ROLE"));

        int onResult = source.indexOf("onActivityResult(");
        assertTrue("onActivityResult missing", onResult >= 0);
        assertTrue("onActivityResult must handle PICK_DOCUMENT",
                source.indexOf("PICK_DOCUMENT", onResult) > onResult);
        assertTrue("onActivityResult must call importSelectedDocuments",
                source.indexOf("importSelectedDocuments(data)", onResult) > onResult);
    }

    @Test
    public void modelRuntimeStatusContainsNoAsciiBelControlCharacter() throws Exception {
        String source = read(repoRoot().resolve(
                "app/src/main/java/com/notcherry/lfm3pocket/ModelRuntime.java"));
        assertFalse("ModelRuntime must not embed ASCII BEL (\\u0007)", source.contains("\\u0007"));
        assertFalse("ModelRuntime must not embed raw BEL character", source.indexOf('\u0007') >= 0);
    }

    @Test
    public void manifestDeclaresAccessNetworkStateExplicitly() throws Exception {
        String manifest = read(repoRoot().resolve("app/src/main/AndroidManifest.xml"));
        assertTrue("AndroidManifest.xml must declare ACCESS_NETWORK_STATE",
                manifest.contains("android.permission.ACCESS_NETWORK_STATE"));
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
