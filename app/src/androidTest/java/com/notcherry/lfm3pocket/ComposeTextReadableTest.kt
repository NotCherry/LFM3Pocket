package com.notcherry.lfm3pocket

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Compose Settings recipe labels remain fully on-screen at large fontScale,
 * narrow width, and Polish locale (longer copy).
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class ComposeTextReadableTest {
    @get:Rule val compose = createComposeRule()

    private val packageName = "com.notcherry.lfm3pocket"

    @Test fun settingsScreenReadableAtFontScaleTwoEnglish() {
        renderSettings(fontScale = 2f, widthDp = 320, heightDp = 780, locale = Locale.ENGLISH)
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Back to chat").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Save search").assertIsDisplayed()
        compose.onNodeWithText("Context").assertIsDisplayed()
        compose.onNodeWithText("Decode threads").assertIsDisplayed()
    }

    @Test fun settingsScreenReadableAtFontScaleTwoPolish() {
        renderSettings(
            fontScale = 2f,
            widthDp = 320,
            heightDp = 900,
            locale = Locale.forLanguageTag("pl"),
        )
        compose.onNodeWithText("Ustawienia").assertIsDisplayed()
        compose.onNodeWithText("Wróć do czatu").assertIsDisplayed()
        compose.onNodeWithText("Zapisz").assertIsDisplayed()
        compose.onNodeWithText("Zapisz wyszukiwanie").assertIsDisplayed()
        compose.onNodeWithText("Kontekst").assertIsDisplayed()
        compose.onNodeWithText("Wątki dekodowania").assertIsDisplayed()
    }

    @Test fun settingsScreenReadableAtSmallFont() {
        renderSettings(fontScale = 0.85f, widthDp = 411, heightDp = 800, locale = Locale.ENGLISH)
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Response tokens").assertIsDisplayed()
    }

    @Test fun settingsScreenReadableAtFontScaleOnePointThree() {
        renderSettings(fontScale = 1.3f, widthDp = 360, heightDp = 800, locale = Locale.ENGLISH)
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Save search").assertIsDisplayed()
    }

    private fun renderSettings(fontScale: Float, widthDp: Int, heightDp: Int, locale: Locale) {
        compose.setContent {
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
                        )
                    }
                }
            }
        }
    }
}
