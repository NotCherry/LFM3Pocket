package com.notcherry.lfm3pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
        TextButton(onClick = onBack, modifier = Modifier.testTag(tag("settings_back"))) {
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
            OutlinedTextField(
                value = contextTokens,
                onValueChange = onContextChange,
                label = { Text("Context tokens") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth().testTag(tag("settings_context")),
            )
            OutlinedTextField(
                value = threads,
                onValueChange = onThreadsChange,
                label = { Text("Decode threads") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth().testTag(tag("settings_threads")),
            )
            OutlinedTextField(
                value = maxTokens,
                onValueChange = onTokensChange,
                label = { Text("Max response tokens") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth().testTag(tag("settings_tokens")),
            )
            if (!error.isNullOrBlank()) {
                Text(error, color = atlas.danger, style = type.bodySmall)
            }
            Button(
                onClick = onSaveInference,
                modifier = Modifier.fillMaxWidth().testTag(tag("settings_save")),
            ) {
                Text("Save")
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
            Button(
                onClick = onSaveSearch,
                modifier = Modifier.fillMaxWidth().testTag(tag("search_save")),
            ) {
                Text("Save search")
            }
        }
    }
}
