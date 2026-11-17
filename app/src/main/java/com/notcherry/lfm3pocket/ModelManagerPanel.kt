package com.notcherry.lfm3pocket

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.stringResource
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
            val ink = MaterialTheme.colorScheme.onSurface
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val modelName = state.model.ifEmpty { stringResource(R.string.model_none_selected) }
            val projectorName = state.projector.ifEmpty { stringResource(R.string.projector_none) }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                // Wrap long GGUF names instead of ellipsizing chrome labels.
                Text(stringResource(R.string.model_label, modelName), color = ink,
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.projector_label, projectorName), color = muted,
                    style = MaterialTheme.typography.bodySmall)
                Text(settingsSummary, style = MaterialTheme.typography.bodySmall, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { reload.run() },
                        enabled = !state.busy && state.model.isNotEmpty(),
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = StillwaterControls.outlinedColors(),
                    ) {
                        Text(
                            when {
                                state.failed -> stringResource(R.string.retry_load)
                                state.loaded -> stringResource(R.string.reload_model)
                                else -> stringResource(R.string.load_model)
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { unload.run() },
                        enabled = !state.busy && state.loaded,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = StillwaterControls.outlinedColors(),
                    ) {
                        Text(stringResource(R.string.unload_model))
                    }
                    TextButton(
                        onClick = { settings.run() },
                        enabled = !state.busy,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        colors = StillwaterControls.textButtonColors(),
                    ) { Text(stringResource(R.string.settings_title)) }
                }
            }
        }
    }
}
