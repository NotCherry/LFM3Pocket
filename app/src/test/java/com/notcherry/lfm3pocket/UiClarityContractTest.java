package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Contract tests for chat top-bar cleanup, checkbox-only web search toggle,
 * and explicit auto-loading feedback in chat and Settings model controls.
 */
public final class UiClarityContractTest {
    @Test
    public void chatTopBarOmitsSunGearSettingsButton() throws Exception {
        Path root = repoRoot();
        String chatShell = read(root.resolve("app/src/main/java/com/notcherry/lfm3pocket/ChatShellSection.java"));
        assertFalse("Sun/gear top-bar settings button must be removed", chatShell.contains("R.id.settings_open"));
        assertFalse("Sun/gear drawable must not be referenced", chatShell.contains("ic_still_gear"));
        assertTrue("Overflow menu must remain for Settings access", chatShell.contains("R.id.chat_overflow"));
        assertFalse("Unused ic_still_gear drawable must be removed",
                Files.exists(root.resolve("app/src/main/res/drawable/ic_still_gear.xml")));
    }

    @Test
    public void searchSettingsUsesCheckboxWithoutRedundantToggleButton() throws Exception {
        Path root = repoRoot();
        String searchSection = read(root.resolve("app/src/main/java/com/notcherry/lfm3pocket/SearchSettingsSection.java"));
        assertTrue("Web search checkbox must remain", searchSection.contains("R.id.search_enabled"));
        assertTrue("Test search button must remain", searchSection.contains("R.id.search_test"));
        assertFalse("Redundant web_search toggle button must be removed", searchSection.contains("R.id.web_search"));
        assertFalse("Redundant web_search_on/off button copy must be removed",
                searchSection.contains("R.string.web_search_on") || searchSection.contains("R.string.web_search_off"));
    }

    @Test
    public void autoLoadingUiIsExposedInChatAndModelManager() throws Exception {
        Path root = repoRoot();
        String chatTurnList = read(root.resolve("app/src/main/java/com/notcherry/lfm3pocket/ChatTurnList.kt"));
        assertTrue("Empty chat must show auto-loading card", chatTurnList.contains("chat_empty_loading_model"));
        assertTrue("Auto-loading card must show informative loading button", chatTurnList.contains("chat_empty_loading_button"));
        assertTrue("Auto-loading card must use dedicated title string", chatTurnList.contains("R.string.chat_empty_loading_model_title"));

        String chatPanel = read(root.resolve("app/src/main/java/com/notcherry/lfm3pocket/ChatPanel.kt"));
        assertTrue("Non-empty chat must show loading banner", chatPanel.contains("chat_model_loading_banner"));
        assertTrue("Send button must show loading_short while model is loading", chatPanel.contains("R.string.loading_short"));

        String modelManager = read(root.resolve("app/src/main/java/com/notcherry/lfm3pocket/ModelManagerPanel.kt"));
        assertTrue("ModelManagerPanel must show auto-loading status", modelManager.contains("R.string.model_auto_loading_status"));
        assertTrue("ModelManagerPanel Load button must show loading_model while loading", modelManager.contains("R.string.loading_model"));
        assertTrue("ModelManagerPanel Unload button must explain why it is disabled while loading",
                modelManager.contains("R.string.unload_model_loading"));

        String enStrings = read(root.resolve("app/src/main/res/values/strings.xml"));
        String plStrings = read(root.resolve("app/src/main/res/values-pl/strings.xml"));
        String[] requiredKeys = {
                "chat_empty_loading_model_title",
                "chat_empty_loading_model_body",
                "model_auto_loading_status",
                "model_unloading_status",
                "unloading_model",
                "unload_model_loading",
                "loading_short",
        };
        for (String key : requiredKeys) {
            String attr = "name=\"" + key + "\"";
            assertTrue("Missing EN string: " + key, enStrings.contains(attr));
            assertTrue("Missing PL string: " + key, plStrings.contains(attr));
        }
    }

    private static String read(Path path) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(path.toFile()), StandardCharsets.UTF_8))) {
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
