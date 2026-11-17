package com.notcherry.lfm3pocket

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Compose Settings *recipe* labels remain fully on-screen at large fontScale / narrow width /
 * Polish locale / interface-size presets. Production Settings chrome is covered by
 * [TextReadableAtScaleE2eTest] against MainActivity views; this suite also asserts Compose
 * semantics report no visual overflow.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ComposeTextReadableTest {
    @get:Rule val compose = createComposeRule()

    private val packageName = "com.notcherry.lfm3pocket"

    @Test fun settingsScreenReadableAtFontScaleTwoEnglish() {
        renderSettings(fontScale = 2f, widthDp = 320, heightDp = 1200, locale = Locale.ENGLISH)
        assertDisplayedWithoutOverflow("Settings")
        assertDisplayedWithoutOverflow("Back to chat")
        assertDisplayedWithoutOverflow("Save")
        assertDisplayedWithoutOverflow("Save search")
        assertDisplayedWithoutOverflow("Interface size")
        assertScrollableDisplayedWithoutOverflow("Context")
        assertScrollableDisplayedWithoutOverflow("Decode threads")
        assertScrollableDisplayedWithoutOverflow("Model")
    }

    @Test fun settingsScreenReadableAtFontScaleTwoPolish() {
        renderSettings(
            fontScale = 2f,
            widthDp = 320,
            heightDp = 1400,
            locale = Locale.forLanguageTag("pl"),
        )
        assertDisplayedWithoutOverflow("Ustawienia")
        assertDisplayedWithoutOverflow("Wróć do czatu")
        assertDisplayedWithoutOverflow("Zapisz")
        assertDisplayedWithoutOverflow("Zapisz wyszukiwanie")
        assertDisplayedWithoutOverflow("Rozmiar interfejsu")
        assertScrollableDisplayedWithoutOverflow("Kontekst")
        assertScrollableDisplayedWithoutOverflow("Wątki dekodowania")
        assertScrollableDisplayedWithoutOverflow("Model")
    }

    @Test fun settingsScreenReadableAtSmallFont() {
        renderSettings(fontScale = 0.85f, widthDp = 411, heightDp = 800, locale = Locale.ENGLISH)
        assertDisplayedWithoutOverflow("Settings")
        assertScrollableDisplayedWithoutOverflow("Response tokens")
        assertDisplayedWithoutOverflow("Compact")
    }

    @Test fun settingsScreenReadableAtFontScaleOnePointThree() {
        renderSettings(fontScale = 1.3f, widthDp = 360, heightDp = 1000, locale = Locale.ENGLISH)
        assertDisplayedWithoutOverflow("Settings")
        assertDisplayedWithoutOverflow("Save search")
        assertDisplayedWithoutOverflow("Default")
    }

    @Test fun settingsScreenReadableAtEachInterfaceSizePreset() {
        // One setContent; drive presets through mutable state (cannot call setContent twice).
        var scaleState: (Float) -> Unit = {}
        compose.setContent {
            var interfaceScale by remember { mutableFloatStateOf(InterfaceSize.COMPACT) }
            scaleState = { interfaceScale = it }
            renderSettingsContent(
                fontScale = 1.0f,
                widthDp = 360,
                heightDp = 800,
                locale = Locale.ENGLISH,
                interfaceScale = interfaceScale,
                onInterfaceScaleChange = { interfaceScale = it },
            )
        }
        for (scale in floatArrayOf(InterfaceSize.COMPACT, InterfaceSize.DEFAULT, InterfaceSize.LARGE)) {
            compose.runOnIdle { scaleState(scale) }
            compose.waitForIdle()
            assertDisplayedWithoutOverflow("Interface size")
            assertDisplayedWithoutOverflow("Compact")
            assertDisplayedWithoutOverflow("Default")
            assertDisplayedWithoutOverflow("Large")
            assertDisplayedWithoutOverflow("Settings")
        }
    }

    @Test fun settingsScreenReadableAtCompactInterfaceAndLargeFontPolish() {
        renderSettings(
            fontScale = 2f,
            widthDp = 320,
            heightDp = 1400,
            locale = Locale.forLanguageTag("pl"),
            interfaceScale = InterfaceSize.COMPACT,
        )
        assertDisplayedWithoutOverflow("Rozmiar interfejsu")
        assertDisplayedWithoutOverflow("Kompaktowy")
        assertDisplayedWithoutOverflow("Ustawienia")
    }

    private fun assertDisplayedWithoutOverflow(text: String) {
        assertReadable(text, scroll = false)
    }

    private fun assertScrollableDisplayedWithoutOverflow(text: String) {
        assertReadable(text, scroll = true)
    }

    private fun assertReadable(text: String, scroll: Boolean) {
        val interaction = compose.onNodeWithText(text)
        if (scroll) interaction.performScrollTo()
        interaction.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        val node = interaction.fetchSemanticsNode()
        val fetched = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(layouts)
            ?: false
        assertTrue("no TextLayoutResult for: $text", fetched && layouts.isNotEmpty())
        val layout = layouts[0]
        val nodeH = node.boundsInRoot.height
        val nodeW = node.boundsInRoot.width
        assertTrue(
            "Compose text clipped vertically: $text layoutH=${layout.size.height} nodeH=$nodeH",
            layout.size.height <= nodeH + 3f,
        )
        assertTrue(
            "Compose text clipped horizontally: $text layoutW=${layout.size.width} nodeW=$nodeW lines=${layout.lineCount}",
            layout.lineCount > 1 || layout.size.width <= nodeW + 3f,
        )
    }

    private fun renderSettings(
        fontScale: Float,
        widthDp: Int,
        heightDp: Int,
        locale: Locale,
        interfaceScale: Float = InterfaceSize.DEFAULT,
    ) {
        compose.setContent {
            renderSettingsContent(
                fontScale = fontScale,
                widthDp = widthDp,
                heightDp = heightDp,
                locale = locale,
                interfaceScale = interfaceScale,
                onInterfaceScaleChange = {},
            )
        }
    }

    @Composable
    private fun renderSettingsContent(
        fontScale: Float,
        widthDp: Int,
        heightDp: Int,
        locale: Locale,
        interfaceScale: Float,
        onInterfaceScaleChange: (Float) -> Unit,
    ) {
        DeviceConfigurationOverride(
            DeviceConfigurationOverride.FontScale(fontScale)
                then DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, heightDp.dp)),
        ) {
            val context = LocalContext.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocale(locale)
                this.fontScale = fontScale
                screenWidthDp = widthDp
                screenHeightDp = heightDp
            }
            val localized = context.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
            ) {
                PocketTheme {
                    var contextTokens by remember { mutableStateOf("4096") }
                    var threads by remember { mutableStateOf("4") }
                    var maxTokens by remember { mutableStateOf("512") }
                    var endpoint by remember { mutableStateOf("") }
                    var search by remember { mutableStateOf(false) }
                    var size by remember(interfaceScale) { mutableFloatStateOf(interfaceScale) }
                    SettingsScreen(
                        packageName = packageName,
                        modelSummary = "Model: None selected",
                        contextTokens = contextTokens,
                        threads = threads,
                        maxTokens = maxTokens,
                        searchEndpoint = endpoint,
                        searchEnabled = search,
                        error = null,
                        onContextChange = { contextTokens = it },
                        onThreadsChange = { threads = it },
                        onTokensChange = { maxTokens = it },
                        onEndpointChange = { endpoint = it },
                        onSearchEnabledChange = { search = it },
                        onSaveInference = {},
                        onSaveSearch = {},
                        onBack = {},
                        modifier = Modifier.fillMaxSize(),
                        interfaceScale = size,
                        onInterfaceScaleChange = {
                            size = it
                            onInterfaceScaleChange(it)
                        },
                    )
                }
            }
        }
    }
}
