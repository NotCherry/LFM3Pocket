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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.StillwaterControls
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasTypography

/**
 * Atlas Settings layout (U13). Production host is [MainActivity] settings mode so SAF
 * import / model load stay on the existing Activity; this composable documents the visual
 * recipe and can be embedded by future hosts.
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
) {
    val tag = { name: String -> "$packageName:id/$name" }
    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
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
            .padding(16.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag(tag("settings_back")),
            colors = StillwaterControls.textButtonColors(),
        ) {
            Text("Back to chat")
        }
        Text("Settings", style = type.headline, color = atlas.ink)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .border(1.dp, atlas.border)
                .background(atlas.surfaceRaised)
                .padding(12.dp)
                .testTag(tag("model_configuration_scroll")),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Model", style = type.title, color = atlas.ink)
            Text(modelSummary, style = type.bodySmall, color = atlas.inkMuted)
            Text("Inference", style = type.title, color = atlas.ink)
            StepperField("Context", "512–8192, step 256", contextTokens, onContextChange, 256, 512, 8192, fieldColors, Modifier.fillMaxWidth().testTag(tag("settings_context")))
            StepperField("Decode threads", "1–8, default from big/mid CPU cores", threads, onThreadsChange, 1, 1, 8, fieldColors, Modifier.fillMaxWidth().testTag(tag("settings_threads")))
            StepperField("Response tokens", "1–2048, below context", maxTokens, onTokensChange, 1, 1, 2048, fieldColors, Modifier.fillMaxWidth().testTag(tag("settings_tokens")))
            Text("Benchmark", style = type.title, color = atlas.ink)
            Text(
                "Prompt-processing (pp) and decode (tg) tok/s on the loaded model. Results wrap and can be copied from the Settings host.",
                style = type.bodySmall,
                color = atlas.inkMuted,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!error.isNullOrBlank()) {
                Text(error, color = atlas.danger, style = type.bodySmall, modifier = Modifier.fillMaxWidth())
            }
            Text("SearXNG", style = type.title, color = atlas.ink)
            OutlinedTextField(
                value = searchEndpoint,
                onValueChange = onEndpointChange,
                label = { Text("HTTPS search endpoint") },
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
                if (searchEnabled) "Web search: on" else "Web search: off",
                style = type.label,
                color = atlas.inkMuted,
                modifier = Modifier.testTag(tag("web_search")),
            )
        }
        // Sticky Save row — Stage 1 Automator must find settings_save / search_save on-screen.
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSaveInference,
                modifier = Modifier.fillMaxWidth().testTag(tag("settings_save")),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                colors = StillwaterControls.buttonColors(),
            ) {
                Text("Save")
            }
            Button(
                onClick = onSaveSearch,
                modifier = Modifier.fillMaxWidth().testTag(tag("search_save")),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                colors = StillwaterControls.buttonColors(),
            ) {
                Text("Save search")
            }
        }
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
            colors = StillwaterControls.textButtonColors(),
        ) { Text("+") }
    }
}

private fun nudge(value: String, delta: Int, min: Int, max: Int): String {
    val current = value.toIntOrNull() ?: min
    return (current + delta).coerceIn(min, max).toString()
}
