package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stacking thresholds for readable Stillwater control rows. */
public final class StillwaterReadableTest {
    @Test public void stacksAtElevatedFontScaleEvenOnWideScreens() {
        assertTrue(StillwaterReadable.shouldStack(1.3f, 720));
        assertTrue(StillwaterReadable.shouldStack(1.5f, 720));
        assertTrue(StillwaterReadable.shouldStack(2.0f, 1080));
    }

    @Test public void stacksOnlyOnExtremelyNarrowWidthAtDefaultFontScale() {
        assertTrue(StillwaterReadable.shouldStack(1.0f, 319));
        assertTrue(StillwaterReadable.shouldStack(0.85f, 300));
    }

    @Test public void staysSideBySideOnTypicalPhoneWidthsAtDefaultOrSmallFont() {
        // WRAP_CONTENT height keeps wrapped labels readable without forcing a column.
        assertFalse(StillwaterReadable.shouldStack(1.0f, 320));
        assertFalse(StillwaterReadable.shouldStack(1.0f, 360));
        assertFalse(StillwaterReadable.shouldStack(1.0f, 393));
        assertFalse(StillwaterReadable.shouldStack(1.0f, 411));
        assertFalse(StillwaterReadable.shouldStack(0.85f, 360));
        assertFalse(StillwaterReadable.shouldStack(1.29f, 360));
    }

    @Test public void polishOpenFromStorageLabelsAreLongerThanEnglish() throws IOException {
        Path root = repoRoot();
        String en = readString(root.resolve("app/src/main/res/values/strings.xml"),
                "open_model_from_storage");
        String pl = readString(root.resolve("app/src/main/res/values-pl/strings.xml"),
                "open_model_from_storage");
        assertTrue("PL longer than EN: en=" + en + " pl=" + pl, pl.length() > en.length());
        String enProj = readString(root.resolve("app/src/main/res/values/strings.xml"),
                "open_projector_from_storage");
        String plProj = readString(root.resolve("app/src/main/res/values-pl/strings.xml"),
                "open_projector_from_storage");
        assertTrue(plProj.length() > enProj.length());
    }

    private static String readString(Path file, String name) throws IOException {
        String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "<string name=\"" + Pattern.quote(name) + "\">([^<]+)</string>").matcher(xml);
        assertTrue("missing " + name + " in " + file, matcher.find());
        return matcher.group(1);
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path probe = cwd;
        while (probe != null) {
            if (Files.isDirectory(probe.resolve("app/src/main"))) return probe;
            probe = probe.getParent();
        }
        throw new IllegalStateException("repo root not found from " + cwd);
    }
}
