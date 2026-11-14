package com.notcherry.lfm3pocket

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasTypography
import kotlinx.coroutines.delay

/**
 * Incremental Compose shell. Native inference, SAF import, media and chat remain
 * in the existing Java MainActivity until covered by later feature tests.
 */
class ComposeHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            AtlasHome(
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
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Local inference", style = type.headline, color = atlas.ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The existing chat runs models locally. Optional SearXNG web search " +
                        "is off unless you explicitly enable it in Settings.",
                    style = type.body,
                    color = atlas.inkMuted,
                )
                TextButton(onClick = { controller.popBackStack() }) {
                    Text("Back to home")
                }
            }
        }
    }
}

@Composable
private fun AtlasHome(
    onOpenChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    var showCtas by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(40)
        showCtas = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(atlas.surface, atlas.surfaceRaised, atlas.surface),
                ),
            )
            .semantics { testTagsAsResourceId = true }
            .padding(24.dp),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .align(Alignment.Center),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("LFM3", style = type.display, color = atlas.ink)
            Spacer(Modifier.height(8.dp))
            Text(
                "Offline-first pocket assistant. Your model stays on this device.",
                style = type.body,
                color = atlas.inkMuted,
            )
            Spacer(Modifier.height(28.dp))
            AnimatedVisibility(
                visible = showCtas,
                enter = fadeIn() + slideInVertically { it / 4 },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onOpenChat,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open chat")
                    }
                    TextButton(
                        onClick = onOpenHistory,
                        modifier = Modifier.testTag("${LocalContext.current.packageName}:id/history_open"),
                    ) {
                        Text("History")
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("Settings")
                    }
                    TextButton(onClick = onAbout) {
                        Text("About offline mode")
                    }
                }
            }
        }
    }
}
