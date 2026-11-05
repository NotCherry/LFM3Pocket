package com.notcherry.lfm3pocket

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * Incremental Compose shell. Native inference, SAF import, media and chat remain
 * in the existing Java MainActivity until covered by later feature tests.
 */
class ComposeHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PocketNavigation(onOpenChat = {
                    startActivity(Intent(this@ComposeHostActivity, MainActivity::class.java))
                })
            }
        }
    }
}

@Composable
private fun PocketNavigation(onOpenChat: () -> Unit) {
    val controller = rememberNavController()
    NavHost(navController = controller, startDestination = "home") {
        composable("home") {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("LFM3 Pocket", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text("Offline-first, local model assistant")
                Spacer(Modifier.height(20.dp))
                Button(onClick = onOpenChat) {
                    Text("Open chat")
                }
                TextButton(onClick = { controller.navigate("about") }) {
                    Text("About offline mode")
                }
            }
        }
        composable("about") {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Local inference", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "The existing chat runs models locally. Optional SearXNG web search " +
                        "is off unless you explicitly enable it in chat."
                )
                TextButton(onClick = { controller.popBackStack() }) {
                    Text("Back to home")
                }
            }
        }
    }
}
