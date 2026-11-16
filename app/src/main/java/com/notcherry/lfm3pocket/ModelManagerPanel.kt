package com.notcherry.lfm3pocket

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls

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
        PocketTheme {
            val state = selection
            val resources = LocalContext.current.resources
            val ink = MaterialTheme.colorScheme.onSurface
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val modelName = state.model.ifEmpty { resources.getString(R.string.model_none_selected) }
            val projectorName = state.projector.ifEmpty { resources.getString(R.string.projector_none) }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                // Wrap long GGUF names instead of ellipsizing chrome labels.
                Text(resources.getString(R.string.model_label, modelName), color = ink)
                Text(resources.getString(R.string.projector_label, projectorName), color = muted)
                Text(settingsSummary, style = MaterialTheme.typography.bodySmall, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { reload.run() },
                        enabled = !state.busy && state.model.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = StillwaterControls.outlinedColors(),
                    ) {
                        Text(
                            when {
                                state.failed -> resources.getString(R.string.retry_load)
                                state.loaded -> resources.getString(R.string.reload_model)
                                else -> resources.getString(R.string.load_model)
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { unload.run() },
                        enabled = !state.busy && state.loaded,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = StillwaterControls.outlinedColors(),
                    ) {
                        Text(resources.getString(R.string.unload_model))
                    }
                    TextButton(
                        onClick = { settings.run() },
                        enabled = !state.busy,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = StillwaterControls.textButtonColors(),
                    ) { Text(resources.getString(R.string.settings_title)) }
                }
            }
        }
    }
}
