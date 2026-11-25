package com.notcherry.lfm3pocket

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.PocketTheme
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasSpacing
import com.notcherry.lfm3pocket.ui.theme.atlasTypography

/** Incremental Compose manager, paired with the existing tested SAF controls and status view. */
class ModelManagerPanel @JvmOverloads constructor(
    context: Context,
    private val reload: Runnable,
    private val unload: Runnable,
    private val settings: Runnable,
    private val showSettingsAction: Boolean = true,
) : AbstractComposeView(context) {
    private data class Selection(
        val model: String = "",
        val projector: String = "",
        val busy: Boolean = false,
        val loaded: Boolean = false,
        val failed: Boolean = false,
    )
    private var settingsSummary by mutableStateOf("Loading inference settings...")
    fun showSettings(summary: String) { settingsSummary = summary }
    private var selection by mutableStateOf(Selection())

    fun updateSelection(model: String, projector: String, busy: Boolean, loaded: Boolean, failed: Boolean) {
        selection = Selection(model, projector, busy, loaded, failed)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        PocketTheme {
            val state = selection
            val atlas = MaterialTheme.atlasColors
            val type = MaterialTheme.atlasTypography
            val space = MaterialTheme.atlasSpacing
            val modelName = state.model.ifEmpty { stringResource(R.string.model_none_selected) }
            val projectorName = state.projector.ifEmpty { stringResource(R.string.projector_none) }
            val canReload = !state.busy && state.model.isNotEmpty()
            val canUnload = !state.busy && state.loaded
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(atlas.surface, MaterialTheme.shapes.medium)
                    .border(1.dp, atlas.border, MaterialTheme.shapes.medium)
                    .padding(space.space4),
            ) {
                // Wrap long GGUF names instead of ellipsizing chrome labels.
                Text(
                    stringResource(R.string.model_label, modelName),
                    color = atlas.ink,
                    style = type.body,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.projector_label, projectorName),
                    color = atlas.inkSoft,
                    style = type.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    settingsSummary,
                    style = type.mono,
                    color = atlas.inkMuted,
                )
                Spacer(Modifier.height(space.space3))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(space.space2),
                    verticalArrangement = Arrangement.spacedBy(space.space2),
                ) {
                    OutlinedButton(
                        onClick = { reload.run() },
                        enabled = canReload,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, if (canReload) atlas.borderStrong else atlas.border),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
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
                        enabled = canUnload,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, if (canUnload) atlas.borderStrong else atlas.border),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        colors = StillwaterControls.outlinedColors(),
                    ) {
                        Text(stringResource(R.string.unload_model))
                    }
                    if (showSettingsAction) {
                        TextButton(
                            onClick = { settings.run() },
                            enabled = !state.busy,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                            shape = MaterialTheme.shapes.medium,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            colors = StillwaterControls.textButtonColors(),
                        ) { Text(stringResource(R.string.settings_title)) }
                    }
                }
            }
        }
    }
}
