package com.notcherry.lfm3pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasSpacing
import com.notcherry.lfm3pocket.ui.theme.atlasTypography

/**
 * Atlas Settings **visual recipe** (U13) for readable-at-scale / Compose tests.
 *
 * **Not the production Settings host.** Production Settings is the View scroll built in
 * [MainActivity] (`model_configuration_scroll`). New sections (Speech, Images, Crash log,
 * etc.) must land there — or in a shared section model both hosts consume — so this recipe
 * does not drift further (audit finding F04 / Phase 3 Settings single-source).
 */
@Composable
fun SettingsScreen(
    packageName: String,
    modelSummary: String,
    contextTokens: String,
    threads: String,
    maxTokens: String,
    searchEndpoint: String,
    searchEnabled: Boolean,
    error: String?,
    onContextChange: (String) -> Unit,
    onThreadsChange: (String) -> Unit,
    onTokensChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onSearchEnabledChange: (Boolean) -> Unit,
    onSaveInference: () -> Unit,
    onSaveSearch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    interfaceScale: Float = InterfaceSize.DEFAULT,
    onInterfaceScaleChange: (Float) -> Unit = {},
) {
    val tag = { name: String -> "$packageName:id/$name" }
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    val space = MaterialTheme.atlasSpacing
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = atlas.ink,
        unfocusedTextColor = atlas.ink,
        focusedBorderColor = atlas.borderStrong,
        unfocusedBorderColor = atlas.border,
        cursorColor = atlas.accent,
        focusedContainerColor = atlas.surfaceRaised,
        unfocusedContainerColor = atlas.surfaceRaised,
    )
    Column(
        modifier
            .fillMaxSize()
            .background(atlas.surface)
            .semantics { testTagsAsResourceId = true }
            .padding(space.screen),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(tag("settings_back")),
            colors = StillwaterControls.textButtonColors(),
        ) {
            Text(stringResource(R.string.back_to_chat))
        }
        Text(
            stringResource(R.string.settings_title),
            style = type.headline,
            color = atlas.ink,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(space.space2))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .border(1.dp, atlas.border)
                .background(atlas.surfaceRaised)
                .padding(space.space4)
                .testTag(tag("model_configuration_scroll")),
            verticalArrangement = Arrangement.spacedBy(space.space3),
        ) {
            Text(stringResource(R.string.appearance_heading), style = type.title, color = atlas.ink)
            Text(stringResource(R.string.interface_size_label), style = type.body, color = atlas.ink)
            Text(stringResource(R.string.interface_size_help), style = type.bodySmall, color = atlas.inkMuted)
            // Recipe / test host: keep a local draft while dragging; commit on finish so a
            // future write+recreate host does not recreate on every step.
            var draftScale by remember(interfaceScale) {
                mutableFloatStateOf(InterfaceSize.clamp(interfaceScale))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag(tag("settings_interface_size")),
                horizontalArrangement = Arrangement.spacedBy(space.space2),
            ) {
                InterfacePresetChip(
                    label = stringResource(R.string.interface_size_compact),
                    selected = InterfaceSize.isCompact(draftScale),
                    onClick = {
                        draftScale = InterfaceSize.COMPACT
                        onInterfaceScaleChange(InterfaceSize.COMPACT)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(tag("settings_interface_compact")),
                )
                InterfacePresetChip(
                    label = stringResource(R.string.interface_size_default),
                    selected = InterfaceSize.isDefault(draftScale),
                    onClick = {
                        draftScale = InterfaceSize.DEFAULT
                        onInterfaceScaleChange(InterfaceSize.DEFAULT)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(tag("settings_interface_default")),
                )
                InterfacePresetChip(
                    label = stringResource(R.string.interface_size_large),
                    selected = InterfaceSize.isLarge(draftScale),
                    onClick = {
                        draftScale = InterfaceSize.LARGE
                        onInterfaceScaleChange(InterfaceSize.LARGE)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(tag("settings_interface_large")),
                )
            }
            Text(
                stringResource(R.string.interface_size_percent, InterfaceSize.percent(draftScale)),
                style = type.bodySmall,
                color = atlas.inkMuted,
                modifier = Modifier.testTag(tag("settings_interface_value")),
            )
            Slider(
                value = draftScale,
                onValueChange = { draftScale = InterfaceSize.clamp(it) },
                onValueChangeFinished = { onInterfaceScaleChange(draftScale) },
                valueRange = InterfaceSize.MIN..InterfaceSize.MAX,
                steps = 49,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag(tag("settings_interface_slider")),
            )

            Text(stringResource(R.string.model_section), style = type.title, color = atlas.ink)
            Text(modelSummary, style = type.bodySmall, color = atlas.inkMuted)
            Text(stringResource(R.string.inference_heading), style = type.title, color = atlas.ink)
            StepperField(
                stringResource(R.string.context_label),
                stringResource(R.string.context_hint),
                contextTokens, onContextChange, 256, 512, 8192, fieldColors,
                Modifier.fillMaxWidth().testTag(tag("settings_context")),
            )
            StepperField(
                stringResource(R.string.decode_threads_label),
                stringResource(R.string.decode_threads_hint),
                threads, onThreadsChange, 1, 1, 8, fieldColors,
                Modifier.fillMaxWidth().testTag(tag("settings_threads")),
            )
            StepperField(
                stringResource(R.string.response_tokens_label),
                stringResource(R.string.response_tokens_hint),
                maxTokens, onTokensChange, 1, 1, 2048, fieldColors,
                Modifier.fillMaxWidth().testTag(tag("settings_tokens")),
            )
            Text(stringResource(R.string.benchmark_heading), style = type.title, color = atlas.ink)
            Text(
                stringResource(R.string.benchmark_help),
                style = type.bodySmall,
                color = atlas.inkMuted,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!error.isNullOrBlank()) {
                Text(error, color = atlas.danger, style = type.bodySmall, modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.searxng_heading), style = type.title, color = atlas.ink)
            OutlinedTextField(
                value = searchEndpoint,
                onValueChange = onEndpointChange,
                label = { Text("https://your-searxng.example.org/search") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth().testTag(tag("search_endpoint")),
            )
            androidx.compose.material3.Checkbox(
                checked = searchEnabled,
                onCheckedChange = onSearchEnabledChange,
                modifier = Modifier.testTag(tag("search_enabled")),
            )
            Text(
                if (searchEnabled) stringResource(R.string.web_search_on) else stringResource(R.string.web_search_off),
                style = type.label,
                color = atlas.inkMuted,
                modifier = Modifier.testTag(tag("web_search")),
            )
        }
        // Sticky Save row — Stage 1 Automator must find settings_save / search_save on-screen.
        Column(
            Modifier.fillMaxWidth().padding(top = space.space2),
            verticalArrangement = Arrangement.spacedBy(space.space2),
        ) {
            Button(
                onClick = onSaveInference,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 48.dp)
                    .testTag(tag("settings_save")),
                shape = RoundedCornerShape(0.dp),
                colors = StillwaterControls.buttonColors(),
            ) {
                Text(stringResource(R.string.save))
            }
            Button(
                onClick = onSaveSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .heightIn(min = 40.dp)
                    .testTag(tag("search_save")),
                shape = RoundedCornerShape(0.dp),
                colors = StillwaterControls.buttonColors(),
            ) {
                Text(stringResource(R.string.save_search))
            }
        }
    }
}

@Composable
private fun InterfacePresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp),
            shape = RoundedCornerShape(0.dp),
            colors = StillwaterControls.buttonColors(),
        ) { Text(label) }
    } else {
        TextButton(
            onClick = onClick,
            modifier = modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp),
            colors = StillwaterControls.textButtonColors(),
        ) { Text(label) }
    }
}

@Composable
private fun StepperField(
    title: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    step: Int,
    min: Int,
    max: Int,
    colors: androidx.compose.material3.TextFieldColors,
    modifier: Modifier = Modifier,
) {
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    Text(title, style = type.body, color = atlas.ink)
    Text(hint, style = type.bodySmall, color = atlas.inkMuted)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(
            onClick = { onChange(nudge(value, -step, min, max)) },
            modifier = Modifier.minimumInteractiveComponentSize(),
            colors = StillwaterControls.textButtonColors(),
        ) { Text("–") }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            shape = RoundedCornerShape(0.dp),
            colors = colors,
            modifier = modifier.weight(1f),
        )
        TextButton(
            onClick = { onChange(nudge(value, step, min, max)) },
            modifier = Modifier.minimumInteractiveComponentSize(),
            colors = StillwaterControls.textButtonColors(),
        ) { Text("+") }
    }
}

private fun nudge(value: String, delta: Int, min: Int, max: Int): String {
    val current = value.toIntOrNull() ?: min
    return (current + delta).coerceIn(min, max).toString()
}
