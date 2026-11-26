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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
        val loading: Boolean = false,
        val unloading: Boolean = false,
    )
    private var settingsSummary by mutableStateOf("Loading inference settings...")
    fun showSettings(summary: String) { settingsSummary = summary }
    private var selection by mutableStateOf(Selection())

    @JvmOverloads
    fun updateSelection(
        model: String,
        projector: String,
        busy: Boolean,
        loaded: Boolean,
        failed: Boolean,
        loading: Boolean = false,
        unloading: Boolean = false,
    ) {
        selection = Selection(model, projector, busy, loaded, failed, loading, unloading)
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
            val canReload = !state.busy && !state.loading && !state.unloading && state.model.isNotEmpty()
            val canUnload = !state.busy && !state.loading && !state.unloading && state.loaded
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(atlas.surface, MaterialTheme.shapes.medium)
                    .border(
                        1.dp,
                        if (state.loading) atlas.accent else atlas.border,
                        MaterialTheme.shapes.medium,
                    )
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
                if (state.loading || state.unloading) {
                    Spacer(Modifier.height(space.space3))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(space.space2),
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            color = if (state.loading) atlas.accent else atlas.inkMuted,
                            trackColor = atlas.border,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(
                                if (state.loading) R.string.model_auto_loading_status
                                else R.string.model_unloading_status,
                            ),
                            style = type.bodySmall,
                            color = if (state.loading) atlas.accent else atlas.inkMuted,
                        )
                    }
                }
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
                        border = BorderStroke(
                            1.dp,
                            when {
                                state.loading -> atlas.accent
                                canReload -> atlas.borderStrong
                                else -> atlas.border
                            },
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        colors = StillwaterControls.outlinedColors(),
                    ) {
                        if (state.loading) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                color = atlas.accent,
                                trackColor = atlas.border,
                                strokeWidth = 1.8.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                state.loading -> stringResource(R.string.loading_model)
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
                        if (state.unloading) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp),
                                color = atlas.inkMuted,
                                trackColor = atlas.border,
                                strokeWidth = 1.8.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                state.unloading -> stringResource(R.string.unloading_model)
                                state.loading -> stringResource(R.string.unload_model_loading)
                                else -> stringResource(R.string.unload_model)
                            },
                        )
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
