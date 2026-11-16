package com.notcherry.lfm3pocket

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasTypography

/**
 * Home shell. Native inference, import, media and chat stay in [MainActivity].
 */
class ComposeHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E0E0C.toInt()
        window.navigationBarColor = 0xFF0E0E0C.toInt()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent {
            PocketTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PocketNavigation(
                        onOpenChat = {
                            startActivity(
                                Intent(this@ComposeHostActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_HISTORY_CONVERSATION_ID, java.util.UUID.randomUUID().toString())
                                    .putExtra(MainActivity.EXTRA_HISTORY_TITLE, "New conversation"),
                            )
                        },
                        onOpenHistory = {
                            startActivity(Intent(this@ComposeHostActivity, HistoryActivity::class.java))
                        },
                        onOpenSettings = {
                            startActivity(
                                Intent(this@ComposeHostActivity, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PocketNavigation(
    onOpenChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val controller = rememberNavController()
    NavHost(navController = controller, startDestination = "home") {
        composable("home") {
            StillwaterHome(
                onOpenChat = onOpenChat,
                onOpenHistory = onOpenHistory,
                onOpenSettings = onOpenSettings,
                onAbout = { controller.navigate("about") },
            )
        }
        composable("about") {
            val atlas = MaterialTheme.atlasColors
            val type = MaterialTheme.atlasTypography
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(atlas.surface)
                    .statusBarsPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Local inference", style = type.headline, color = atlas.ink)
                Spacer(Modifier.height(12.dp))
                Text(
                    "The chat runs models on this phone. Optional SearXNG web search " +
                        "stays off unless you turn it on in Settings.",
                    style = type.body,
                    color = atlas.inkMuted,
                )
                TextButton(
                    onClick = { controller.popBackStack() },
                    colors = StillwaterControls.textButtonColors(),
                ) {
                    Text("Back to home")
                }
            }
        }
    }
}

@Composable
private fun StillwaterHome(
    onOpenChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val context = LocalContext.current
    val modelName = context.getSharedPreferences("MainActivity", android.content.Context.MODE_PRIVATE)
        .getString("model_name", "")
        .orEmpty()
    val modelLine = if (modelName.isBlank()) "No model loaded" else "$modelName · on device"
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF1C1A16), atlas.surface),
                    radius = 1400f,
                ),
            )
            .statusBarsPadding()
            .semantics { testTagsAsResourceId = true }
            .padding(horizontal = 26.dp, vertical = 18.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "ON DEVICE",
                style = type.label,
                color = atlas.accent,
            )
            Spacer(Modifier.height(36.dp))
            Text("Pocket", style = type.italic, color = Color(0xFFC9C2B4))
            Text("LFM3", style = type.display, color = atlas.ink)
            Spacer(Modifier.height(22.dp))
            Text(
                "Offline-first assistant. Your model stays on this phone.",
                style = type.body,
                color = Color(0xFFC9C2B4),
                modifier = Modifier.fillMaxWidth(0.86f),
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.extraSmall,
                colors = StillwaterControls.buttonColors(),
            ) {
                Text("Open chat", style = type.body.copy(color = atlas.candleInk))
            }
            Spacer(Modifier.height(16.dp))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.testTag("${context.packageName}:id/history_open"),
                    colors = StillwaterControls.textButtonColors(),
                ) {
                    Text("History", textDecoration = TextDecoration.Underline)
                }
                TextButton(onClick = onOpenSettings, colors = StillwaterControls.textButtonColors()) {
                    Text("Settings", textDecoration = TextDecoration.Underline)
                }
                TextButton(onClick = onAbout, colors = StillwaterControls.textButtonColors()) {
                    Text("About offline mode", textDecoration = TextDecoration.Underline)
                }
            }
            Text(
                modelLine,
                style = type.bodySmall,
                color = atlas.inkMuted,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
            )
        }
    }
}
