package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Settings, chat, and home chrome must stay fully readable across fontScale, display density,
 * narrow width, and English + Polish (longer) strings. Prefer wrapping over clipping.
 */
@RunWith(AndroidJUnit4.class)
public final class TextReadableAtScaleE2eTest {
    private static final long WAIT = 12000L;
    private static final float[] FONT_SCALES = {0.85f, 1.0f, 1.3f, 1.5f, 2.0f};
    private static final String[] LOCALES = {"en", "pl"};
    private static final float[] DENSITY_MULTIPLIERS = {0.85f, 1.0f, 1.3f};

    private Context context;
    private UiDevice device;
    private String pkg;
    private TestLfm3PocketApplication app;
    private Configuration originalConfig;
    private boolean captureShots;

    @Before public void prepare() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        pkg = context.getPackageName();
        captureShots = "1".equals(InstrumentationRegistry.getArguments().getString("captureShots"));
        originalConfig = new Configuration(context.getResources().getConfiguration());
        File file = new File(context.getFilesDir(), "readable-scale.gguf");
        TestGgufFixtures.model(file);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .edit().clear()
                .putString("model", file.getAbsolutePath())
                .putString("model_name", "ReadableScaleModel.gguf")
                .putBoolean("search_enabled", false)
                .commit());
    }

    @After public void restore() {
        if (originalConfig != null) {
            context.getResources().updateConfiguration(
                    originalConfig, context.getResources().getDisplayMetrics());
            Locale.setDefault(originalConfig.getLocales().get(0));
        }
    }

    @Test public void settingsImportAndOpenButtonsStayFullyVisibleAtAllFontScalesAndLocales()
            throws Exception {
        for (String language : LOCALES) {
            for (float scale : FONT_SCALES) {
                applyConfig(scale, language, 1.0f, scale >= 1.5f ? 320 : 0);
                try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                        new Intent(context, MainActivity.class)
                                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true))) {
                    assertNotNull("settings @" + scale + "/" + language,
                            device.wait(Until.findObject(By.res(pkg, "open_model_uri")), WAIT));
                    AtomicReference<String> failure = new AtomicReference<>();
                    scenario.onActivity(activity -> {
                        try {
                            assertReadable(activity.findViewById(R.id.open_model_uri),
                                    "open_model_uri@" + scale + "/" + language);
                            assertReadable(activity.findViewById(R.id.open_projector_uri),
                                    "open_projector_uri@" + scale + "/" + language);
                            assertReadable(activity.findViewById(R.id.import_model),
                                    "import_model@" + scale + "/" + language);
                            assertReadable(activity.findViewById(R.id.import_projector),
                                    "import_projector@" + scale + "/" + language);
                            assertReadable(activity.findViewById(R.id.settings_save),
                                    "settings_save@" + scale + "/" + language);
                            assertReadable(activity.findViewById(R.id.search_save),
                                    "search_save@" + scale + "/" + language);
                            assertReadable(activity.findViewById(R.id.settings_back),
                                    "settings_back@" + scale + "/" + language);
                            assertReadable(activity.findViewById(R.id.settings_default_assistant),
                                    "default_assistant@" + scale + "/" + language);
                        } catch (AssertionError error) {
                            failure.set(error.getMessage());
                        }
                    });
                    assertTrue(failure.get() == null ? "ok" : failure.get(), failure.get() == null);
                }
            }
        }
    }

    /** System font_scale shots for the PR (opt-in via -e captureShots 1). */
    @Test public void captureSettingsShotsAtSystemFontScale() throws Exception {
        if (!captureShots) return;
        try {
            for (String scale : new String[]{"1.0", "2.0"}) {
                device.executeShellCommand("settings put system font_scale " + scale);
                Thread.sleep(500);
                try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(
                        new Intent(context, MainActivity.class)
                                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true))) {
                    assertNotNull(device.wait(Until.findObject(By.res(pkg, "open_model_uri")), WAIT));
                    Thread.sleep(700);
                    shoot("settings-after-font-" + scale + "-en.png");
                }
            }
        } finally {
            device.executeShellCommand("settings put system font_scale 1.0");
        }
    }

    @Test public void chatMediaButtonsStayFullyVisibleAcrossDensityAndLocale() {
        // Representative matrix: default + largest font, both locales, three display sizes.
        float[] scales = {1.0f, 2.0f};
        for (String language : LOCALES) {
            for (float scale : scales) {
                for (float densityMul : DENSITY_MULTIPLIERS) {
                    applyConfig(scale, language, densityMul, scale >= 2.0f ? 320 : 0);
                    try (ActivityScenario<MainActivity> scenario =
                                 ActivityScenario.launch(MainActivity.class)) {
                        assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_photo")), WAIT));
                        AtomicReference<String> failure = new AtomicReference<>();
                        scenario.onActivity(activity -> {
                            try {
                                String tag = scale + "/" + language + "/d" + densityMul;
                                assertReadable(activity.findViewById(R.id.chat_photo), "photo@" + tag);
                                assertReadable(activity.findViewById(R.id.chat_camera), "camera@" + tag);
                                assertReadable(activity.findViewById(R.id.voice_record), "record@" + tag);
                                assertReadable(activity.findViewById(R.id.voice_speak), "speak@" + tag);
                            } catch (AssertionError error) {
                                failure.set(error.getMessage());
                            }
                        });
                        assertTrue(failure.get() == null ? "ok" : failure.get(), failure.get() == null);
                    }
                }
            }
        }
    }

    @Test public void homeAndModelManagerLabelsStayReadable() {
        for (float scale : new float[]{1.0f, 2.0f}) {
            applyConfig(scale, "en", 1.0f, 320);
            try (ActivityScenario<ComposeHostActivity> ignored =
                         ActivityScenario.launch(ComposeHostActivity.class)) {
                // Prefer resource-id testTag — locale mutation of app Resources does not
                // reliably rewrite ComposeHostActivity's process configuration.
                UiObject2 button = device.wait(Until.findObject(By.res(pkg, "open_chat")), WAIT);
                if (button == null) {
                    button = device.wait(Until.findObject(By.text(context.getString(R.string.open_chat))), WAIT);
                }
                assertNotNull("Home Open chat @ " + scale, button);
                assertTrue(button.getVisibleBounds().height() > 0);
            }
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                    new Intent(context, MainActivity.class)
                            .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true))) {
                assertNotNull(device.wait(Until.findObject(By.res(pkg, "model_configuration_scroll")), WAIT));
                UiObject2 manager = findFirstText(device,
                        context.getString(R.string.load_model),
                        context.getString(R.string.reload_model),
                        context.getString(R.string.unload_model));
                assertNotNull("Model manager action @ " + scale, manager);
                assertTrue(manager.getVisibleBounds().height() > 0);
                AtomicReference<String> failure = new AtomicReference<>();
                scenario.onActivity(activity -> {
                    try {
                        assertReadable(activity.findViewById(R.id.open_model_uri),
                                "open_model_uri@home-check/" + scale);
                        assertReadable(activity.findViewById(R.id.open_projector_uri),
                                "open_projector_uri@home-check/" + scale);
                    } catch (AssertionError error) {
                        failure.set(error.getMessage());
                    }
                });
                assertTrue(failure.get() == null ? "ok" : failure.get(), failure.get() == null);
            }
        }
        // Polish chrome is covered by settingsImportAndOpenButtons… (resource strings + wrap).
    }

    private void applyConfig(float scale, String language, float densityMul, int forceWidthDp) {
        Configuration conf = new Configuration(originalConfig);
        conf.fontScale = scale;
        conf.setLocales(LocaleList.forLanguageTags(language));
        if (densityMul != 1.0f && originalConfig.densityDpi > 0) {
            conf.densityDpi = Math.max(120, Math.round(originalConfig.densityDpi * densityMul));
        }
        if (forceWidthDp > 0) conf.screenWidthDp = forceWidthDp;
        context.getResources().updateConfiguration(conf, context.getResources().getDisplayMetrics());
        Locale.setDefault(Locale.forLanguageTag(language));
    }

    private static void assertReadable(View view, String label) {
        assertNotNull(label + " missing", view);
        assertTrue(label + " must be a TextView", view instanceof TextView);
        TextView text = (TextView) view;
        CharSequence value = text.getText();
        assertNotNull(label + " empty text", value);
        assertTrue(label + " blank", value.toString().trim().length() > 0);

        // If already laid out, prefer the production helper. Otherwise measure unconstrained height
        // and ensure the view grew to at least that floor (WRAP_CONTENT / minHeight path).
        if (text.getWidth() > 0 && text.getLayout() != null) {
            assertTrue(label + " text clipped or ellipsized: \"" + value + "\"",
                    StillwaterReadable.textFullyVisible(text));
            return;
        }
        int width = Math.max(text.getWidth(), text.getRootView() != null
                ? text.getRootView().getWidth() / 2 : 400);
        text.measure(
                View.MeasureSpec.makeMeasureSpec(Math.max(width, 1), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertTrue(label + " measured empty", text.getMeasuredHeight() > 0);
        if (text.getHeight() > 0) {
            assertTrue(label + " shorter than measured text",
                    text.getHeight() + 2 >= text.getMeasuredHeight());
        }
    }

    private static UiObject2 findFirstText(UiDevice device, String... labels) {
        for (String label : labels) {
            UiObject2 match = device.wait(Until.findObject(By.text(label)), 2500);
            if (match != null) return match;
        }
        return null;
    }

    private void shoot(String name) {
        File dir = new File(app.getFilesDir(), "readable-shots");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        device.takeScreenshot(new File(dir, name));
    }
}
