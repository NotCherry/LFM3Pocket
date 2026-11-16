package com.notcherry.lfm3pocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewTreeObserver;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production Settings/chat/home chrome must stay fully readable across fontScale, display
 * density, narrow width, and English + Polish (longer) strings. Prefer wrapping over clipping.
 *
 * Font scale is applied through the system setting so a freshly launched Activity inherits it.
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
    private String originalFontScale;
    private String originalLocales;

    @Before public void prepare() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (TestLfm3PocketApplication) context.getApplicationContext();
        app.scriptedEngine().resetForTest();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        pkg = context.getPackageName();
        captureShots = "1".equals(InstrumentationRegistry.getArguments().getString("captureShots"));
        originalConfig = new Configuration(context.getResources().getConfiguration());
        originalFontScale = device.executeShellCommand("settings get system font_scale").trim();
        originalLocales = device.executeShellCommand("settings get system system_locales").trim();
        File file = new File(context.getFilesDir(), "readable-scale.gguf");
        TestGgufFixtures.model(file);
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .edit().clear()
                .putString("model", file.getAbsolutePath())
                .putString("model_name", "ReadableScaleModel.gguf")
                .putBoolean("search_enabled", false)
                .putFloat("interface_scale", InterfaceSize.DEFAULT)
                .commit());
    }

    @After public void restore() throws Exception {
        String restore = originalFontScale == null || originalFontScale.isEmpty()
                || "null".equals(originalFontScale) ? "1.0" : originalFontScale;
        device.executeShellCommand("settings put system font_scale " + restore);
        String locales = originalLocales == null || originalLocales.isEmpty()
                || "null".equals(originalLocales) ? "en-US" : originalLocales;
        device.executeShellCommand("settings put system system_locales " + locales);
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
                setSystemLocale(language);
                applyDensity(1.0f);
                setSystemFontScale(scale);
                try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                        new Intent(context, MainActivity.class)
                                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true))) {
                    assertNotNull("settings @" + scale + "/" + language,
                            device.wait(Until.findObject(By.res(pkg, "open_model_uri")), WAIT));
                    AtomicReference<String> failure = new AtomicReference<>();
                    scenario.onActivity(activity -> {
                        try {
                            assertEquals("activity fontScale @" + scale,
                                    scale, activity.getResources().getConfiguration().fontScale, 0.05f);
                            assertOpenStorageLabelIsKnownLocale(activity);
                            assertReadableAfterLayout(activity.findViewById(R.id.open_model_uri),
                                    "open_model_uri@" + scale + "/" + language);
                            assertReadableAfterLayout(activity.findViewById(R.id.open_projector_uri),
                                    "open_projector_uri@" + scale + "/" + language);
                            assertReadableAfterLayout(activity.findViewById(R.id.import_model),
                                    "import_model@" + scale + "/" + language);
                            assertReadableAfterLayout(activity.findViewById(R.id.import_projector),
                                    "import_projector@" + scale + "/" + language);
                            assertReadableAfterLayout(activity.findViewById(R.id.settings_save),
                                    "settings_save@" + scale + "/" + language);
                            assertReadableAfterLayout(activity.findViewById(R.id.search_save),
                                    "search_save@" + scale + "/" + language);
                            assertReadableAfterLayout(activity.findViewById(R.id.settings_back),
                                    "settings_back@" + scale + "/" + language);
                            assertReadableAfterLayout(activity.findViewById(R.id.settings_default_assistant),
                                    "default_assistant@" + scale + "/" + language);
                        } catch (AssertionError | InterruptedException error) {
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
                setSystemFontScale(Float.parseFloat(scale));
                try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                        new Intent(context, MainActivity.class)
                                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true))) {
                    assertNotNull(device.wait(Until.findObject(By.res(pkg, "open_model_uri")), WAIT));
                    scenario.onActivity(activity -> assertEquals(Float.parseFloat(scale),
                            activity.getResources().getConfiguration().fontScale, 0.05f));
                    Thread.sleep(700);
                    shoot("settings-after-font-" + scale + "-en.png");
                }
            }
        } finally {
            setSystemFontScale(1.0f);
        }
    }

    @Test public void chatMediaButtonsStayFullyVisibleAcrossDensityAndLocale() throws Exception {
        float[] scales = {1.0f, 2.0f};
        for (String language : LOCALES) {
            for (float scale : scales) {
                for (float densityMul : DENSITY_MULTIPLIERS) {
                    setSystemLocale(language);
                    applyDensity(densityMul);
                    setSystemFontScale(scale);
                    try (ActivityScenario<MainActivity> scenario =
                                 ActivityScenario.launch(MainActivity.class)) {
                        assertNotNull(device.wait(Until.findObject(By.res(pkg, "chat_photo")), WAIT));
                        AtomicReference<String> failure = new AtomicReference<>();
                        scenario.onActivity(activity -> {
                            try {
                                assertEquals(scale,
                                        activity.getResources().getConfiguration().fontScale, 0.05f);
                                String tag = scale + "/" + language + "/d" + densityMul;
                                assertReadableAfterLayout(activity.findViewById(R.id.chat_photo), "photo@" + tag);
                                assertReadableAfterLayout(activity.findViewById(R.id.chat_camera), "camera@" + tag);
                                assertReadableAfterLayout(activity.findViewById(R.id.voice_record), "record@" + tag);
                                assertReadableAfterLayout(activity.findViewById(R.id.voice_speak), "speak@" + tag);
                            } catch (AssertionError | InterruptedException error) {
                                failure.set(error.getMessage());
                            }
                        });
                        assertTrue(failure.get() == null ? "ok" : failure.get(), failure.get() == null);
                    }
                }
            }
        }
    }

    /**
     * Interface size presets (Compact / Default / Large) must keep Settings chrome readable
     * across the same fontScale and locale matrix as the system-scale suite.
     */
    @Test public void settingsStayReadableAcrossInterfaceSizePresetsAndFontScales() throws Exception {
        float[] interfaceSizes = {
                InterfaceSize.COMPACT, InterfaceSize.DEFAULT, InterfaceSize.LARGE,
        };
        for (String language : LOCALES) {
            for (float fontScale : new float[]{0.85f, 1.0f, 2.0f}) {
                for (float interfaceScale : interfaceSizes) {
                    setSystemLocale(language);
                    applyDensity(1.0f);
                    setSystemFontScale(fontScale);
                    setInterfaceScale(interfaceScale);
                    String tag = "iface" + InterfaceSize.percent(interfaceScale)
                            + "/font" + fontScale + "/" + language;
                    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                            new Intent(context, MainActivity.class)
                                    .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true))) {
                        assertNotNull("settings @" + tag,
                                device.wait(Until.findObject(By.res(pkg, "open_model_uri")), WAIT));
                        AtomicReference<String> failure = new AtomicReference<>();
                        scenario.onActivity(activity -> {
                            try {
                                assertEquals("activity fontScale @" + tag,
                                        fontScale, activity.getResources().getConfiguration().fontScale, 0.05f);
                                assertEquals("persisted interface @" + tag,
                                        interfaceScale, InterfaceSize.read(activity), 0.001f);
                                assertReadableAfterLayout(activity.findViewById(R.id.open_model_uri),
                                        "open_model_uri@" + tag);
                                assertReadableAfterLayout(activity.findViewById(R.id.open_projector_uri),
                                        "open_projector_uri@" + tag);
                                assertReadableAfterLayout(activity.findViewById(R.id.settings_interface_compact),
                                        "interface_compact@" + tag);
                                assertReadableAfterLayout(activity.findViewById(R.id.settings_interface_default),
                                        "interface_default@" + tag);
                                assertReadableAfterLayout(activity.findViewById(R.id.settings_interface_large),
                                        "interface_large@" + tag);
                                assertReadableAfterLayout(activity.findViewById(R.id.settings_save),
                                        "settings_save@" + tag);
                            } catch (AssertionError | InterruptedException error) {
                                failure.set(error.getMessage());
                            }
                        });
                        assertTrue(failure.get() == null ? "ok" : failure.get(), failure.get() == null);
                    }
                }
            }
        }
        setInterfaceScale(InterfaceSize.DEFAULT);
    }

    @Test public void homeAndModelManagerLabelsStayReadable() throws Exception {
        for (float scale : new float[]{1.0f, 2.0f}) {
            setSystemLocale("en");
            applyDensity(1.0f);
            setSystemFontScale(scale);
            try (ActivityScenario<ComposeHostActivity> ignored =
                         ActivityScenario.launch(ComposeHostActivity.class)) {
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
                // Wait for the Compose model manager to bind selection text, then assert view chrome.
                assertNotNull("model manager bound @" + scale,
                        device.wait(Until.findObject(By.textContains("Model:")), WAIT));
                AtomicReference<String> failure = new AtomicReference<>();
                scenario.onActivity(activity -> {
                    try {
                        assertEquals(scale, activity.getResources().getConfiguration().fontScale, 0.05f);
                        assertReadableAfterLayout(activity.findViewById(R.id.open_model_uri),
                                "open_model_uri@home-check/" + scale);
                        assertReadableAfterLayout(activity.findViewById(R.id.open_projector_uri),
                                "open_projector_uri@home-check/" + scale);
                        assertReadableAfterLayout(activity.findViewById(R.id.import_model),
                                "import_model@home-check/" + scale);
                    } catch (AssertionError | InterruptedException error) {
                        failure.set(error.getMessage());
                    }
                });
                assertTrue(failure.get() == null ? "ok" : failure.get(), failure.get() == null);
            }
        }
    }

    private void setSystemFontScale(float scale) throws Exception {
        device.executeShellCommand("settings put system font_scale " + scale);
        // Give SettingsProvider a beat before launching activities.
        Thread.sleep(400);
    }

    private void setInterfaceScale(float scale) {
        assertTrue(context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .edit()
                .putFloat("interface_scale", InterfaceSize.clamp(scale))
                .commit());
    }

    private void setSystemLocale(String language) throws Exception {
        String tag = "pl".equals(language) ? "pl-PL" : "en-US";
        device.executeShellCommand("settings put system system_locales " + tag);
        Locale.setDefault(Locale.forLanguageTag(tag));
        Thread.sleep(400);
    }

    private void applyDensity(float densityMul) {
        Configuration conf = new Configuration(context.getResources().getConfiguration());
        if (densityMul != 1.0f && originalConfig.densityDpi > 0) {
            conf.densityDpi = Math.max(120, Math.round(originalConfig.densityDpi * densityMul));
            context.getResources().updateConfiguration(conf, context.getResources().getDisplayMetrics());
        }
    }

    /**
     * API 30 emulators do not always honour {@code system_locales} for a freshly launched
     * app process. Accept either EN or PL chrome (both must stay fully readable), and keep
     * a dedicated JVM check that Polish resources are longer than English.
     */
    private static void assertOpenStorageLabelIsKnownLocale(MainActivity activity) {
        TextView view = activity.findViewById(R.id.open_model_uri);
        assertNotNull(view);
        String shown = view.getText().toString();
        String en = activity.getString(R.string.open_model_from_storage);
        Configuration plConf = new Configuration(activity.getResources().getConfiguration());
        plConf.setLocales(LocaleList.forLanguageTags("pl-PL"));
        String pl = activity.createConfigurationContext(plConf).getString(R.string.open_model_from_storage);
        assertTrue("unexpected open-model label: " + shown,
                shown.equals(en) || shown.equals(pl));
        assertTrue("Polish open-model label should be longer than English", pl.length() > en.length());
    }

    private static void assertReadableAfterLayout(View view, String label) throws InterruptedException {
        assertNotNull(label + " missing", view);
        assertTrue(label + " must be a TextView", view instanceof TextView);
        TextView text = (TextView) view;
        CharSequence value = text.getText();
        assertNotNull(label + " empty text", value);
        assertTrue(label + " blank", value.toString().trim().length() > 0);

        waitForLayout(text);
        assertTrue(label + " never laid out", text.getWidth() > 0 && text.getHeight() > 0 && text.getLayout() != null);
        assertTrue(label + " text clipped or ellipsized: \"" + value + "\" h=" + text.getHeight()
                        + " layoutH=" + text.getLayout().getHeight(),
                StillwaterReadable.textFullyVisible(text));
    }

    private static void waitForLayout(View view) throws InterruptedException {
        if (view.getWidth() > 0 && view.getHeight() > 0
                && view instanceof TextView && ((TextView) view).getLayout() != null) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        ViewTreeObserver observer = view.getViewTreeObserver();
        ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (view.getWidth() > 0 && view.getHeight() > 0) {
                    if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                    else view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    latch.countDown();
                }
            }
        };
        observer.addOnGlobalLayoutListener(listener);
        view.requestLayout();
        assertTrue(labelSafe(view) + " layout timeout", latch.await(5, TimeUnit.SECONDS));
    }

    private static String labelSafe(View view) {
        return view instanceof TextView ? String.valueOf(((TextView) view).getText()) : "view";
    }

    private static void scrollSettingsTo(UiDevice device, String pkg, String text) {
        try {
            androidx.test.uiautomator.UiScrollable scroll = new androidx.test.uiautomator.UiScrollable(
                    new androidx.test.uiautomator.UiSelector().resourceId(pkg + ":id/model_configuration_scroll"));
            scroll.setAsVerticalList();
            scroll.scrollIntoView(new androidx.test.uiautomator.UiSelector().text(text));
        } catch (Exception ignored) {
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
