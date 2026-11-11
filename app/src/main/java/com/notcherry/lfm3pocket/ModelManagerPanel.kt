package com.notcherry.lfm3pocket

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Incremental Compose manager, paired with the existing tested SAF controls and status view. */
class ModelManagerPanel(
    context: Context,
    private val reload: Runnable,
    private val unload: Runnable,
    private val settings: Runnable,
) : AbstractComposeView(context) {
    private data class Selection(
        val model: String = "", val projector: String = "",
        val busy: Boolean = false, val loaded: Boolean = false, val failed: Boolean = false,
    )
    private var settingsSummary by mutableStateOf("Loading inference settings...")
    fun showSettings(summary: String) { settingsSummary=summary }
    private var selection by mutableStateOf(Selection())

    fun updateSelection(model: String, projector: String, busy: Boolean, loaded: Boolean, failed: Boolean) {
        selection = Selection(model, projector, busy, loaded, failed)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable override fun Content() {
        MaterialTheme {
            val state = selection
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Model: ${state.model.ifEmpty { "None selected" }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Projector: ${state.projector.ifEmpty { "None — text only" }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(settingsSummary, style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { reload.run() }, enabled = !state.busy && state.model.isNotEmpty(), contentPadding=PaddingValues(horizontal=8.dp)) {
                        Text(if (state.failed) "Retry load" else if (state.loaded) "Reload model" else "Load model")
                    }
                    OutlinedButton(onClick = { unload.run() }, enabled = !state.busy && state.loaded, contentPadding=PaddingValues(horizontal=8.dp)) {
                        Text("Unload model")
                    }
                    TextButton(onClick={settings.run()},enabled=!state.busy,contentPadding=PaddingValues(horizontal=8.dp)) { Text("Settings") }
                }
            }
        }
    }
}
