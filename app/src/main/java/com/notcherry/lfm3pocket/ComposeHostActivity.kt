package com.notcherry.lfm3pocket

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
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
        if (routeExternalAsk(intent)) return
        window.statusBarColor = 0xFF0E0E0C.toInt()
        window.navigationBarColor = 0xFF0E0E0C.toInt()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        setContent {
            PocketTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PocketNavigation(
                        onOpenChat = {
                            // Resume the live session. A fresh id would wipe a reply that is
                            // still generating after the user stepped back to Home.
                            startActivity(Intent(this@ComposeHostActivity, MainActivity::class.java))
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeExternalAsk(intent)
    }

    /**
     * Share targets and typed ASK intents open chat with a draft. They never
     * authorize a tool; only the user can approve from the phone UI.
     */
    private fun routeExternalAsk(intent: Intent?): Boolean {
        if (intent == null) return false
        val draft = when {
            Intent.ACTION_SEND == intent.action && intent.type?.startsWith("text/") == true ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            "com.notcherry.lfm3pocket.action.ASK" == intent.action ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra("prompt")
            else -> null
        }?.trim().orEmpty()
        val sharedImage = when {
            Intent.ACTION_SEND == intent.action && intent.type?.startsWith("image/") == true ->
                intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (draft.isEmpty() && sharedImage == null) return false
        val chat = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (draft.isNotEmpty()) chat.putExtra(MainActivity.EXTRA_DRAFT_TEXT, draft)
        if (sharedImage != null) {
            chat.data = sharedImage
            chat.clipData = android.content.ClipData.newUri(contentResolver, "shared-image", sharedImage)
            chat.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            chat.putExtra(MainActivity.EXTRA_SHARED_IMAGE, sharedImage.toString())
        }
        startActivity(chat)
        finish()
        return true
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
                Text("Hanami", style = type.headline, color = atlas.ink)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Hanami runs Liquid AI LFM3 models on this phone. LFM3 is the model family, not the name of this app.",
                    style = type.body,
                    color = atlas.ink,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Inference stays on the device through llama.cpp. Optional SearXNG web search " +
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

@OptIn(ExperimentalLayoutApi::class)
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
            Text("Hanami", style = type.display, color = atlas.ink)
            Spacer(Modifier.height(22.dp))
            Text(
                "Liquid AI LFM3 models, on this phone.",
                style = type.body,
                color = Color(0xFFC9C2B4),
                modifier = Modifier.fillMaxWidth(0.86f),
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onOpenChat,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("${context.packageName}:id/open_chat"),
                shape = MaterialTheme.shapes.extraSmall,
                colors = StillwaterControls.buttonColors(),
            ) {
                Text(stringResource(R.string.open_chat), style = type.body.copy(color = atlas.candleInk))
            }
            Spacer(Modifier.height(16.dp))
            // Flow so Polish "O trybie offline" does not collide with History/Settings at large fonts.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.testTag("${context.packageName}:id/history_open"),
                    colors = StillwaterControls.textButtonColors(),
                ) {
                    Text(stringResource(R.string.history), textDecoration = TextDecoration.Underline)
                }
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("${context.packageName}:id/home_settings"),
                    colors = StillwaterControls.textButtonColors(),
                ) {
                    Text(stringResource(R.string.settings_title), textDecoration = TextDecoration.Underline)
                }
                TextButton(onClick = onAbout, colors = StillwaterControls.textButtonColors()) {
                    Text(stringResource(R.string.about_offline_mode), textDecoration = TextDecoration.Underline)
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
