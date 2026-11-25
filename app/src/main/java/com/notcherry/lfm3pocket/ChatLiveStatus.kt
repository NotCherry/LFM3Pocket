package com.notcherry.lfm3pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.notcherry.lfm3pocket.ui.theme.atlasColors
import com.notcherry.lfm3pocket.ui.theme.atlasTypography

internal fun chatStatusLabel(status: String): String = when {
    status.startsWith("Searching web") -> "Web search · searching…"
    status.startsWith("Generating locally · using web results") ->
        "Web search · generating locally from results…"
    status.startsWith("Generating locally · web search unavailable") ->
        "Local fallback · web search unavailable; answering locally…"
    status.startsWith("Generating locally · offline") -> "Local / offline · preparing response…"
    status.startsWith("Stopping local generation · web search") ->
        "Stopping · local generation after web search…"
    status.startsWith("Stopping local generation · offline") -> "Stopping · local / offline generation…"
    else -> status
}

@Composable
internal fun ChatLiveStatus(
    mode: String,
    status: String,
    packageName: String,
    traceLines: List<String> = emptyList(),
) {
    fun tag(name: String) = "$packageName:id/$name"

    val atlas = MaterialTheme.atlasColors
    val type = MaterialTheme.atlasTypography
    var traceExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            mode,
            style = MaterialTheme.typography.labelMedium,
            color = atlas.inkMuted,
            modifier = Modifier.testTag(tag("chat_live_mode")),
        )
        if (traceLines.isNotEmpty()) {
            Text(
                text = if (traceExpanded) "Trace ▴" else "Trace ▾",
                style = MaterialTheme.typography.labelSmall,
                color = atlas.accent,
                modifier = Modifier
                    .clickable { traceExpanded = !traceExpanded }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag(tag("chat_live_trace_toggle")),
            )
        }
    }
    if (status.isNotBlank()) {
        val announcedStatus = chatStatusLabel(status)
        val active = status.startsWith("Searching") || status.startsWith("Generating")
                || status.startsWith("Stopping") || status.startsWith("Extracting")
                || status.startsWith("Running ") || status.startsWith("Prefilling")
                || status.startsWith("Transcribing") || status.startsWith("Compacting")
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = announcedStatus
                }.testTag(tag("chat_live_announcer")),
            verticalAlignment = Alignment.CenterVertically) {
            if (active) CircularProgressIndicator(
                Modifier.size(16.dp).testTag(tag("chat_live_progress")),
                color = atlas.accent,
                trackColor = atlas.border,
                strokeWidth = 1.8.dp,
            )
            Text(
                announcedStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = atlas.ink,
                modifier = Modifier
                    .padding(start = if (active) 8.dp else 0.dp)
                    .weight(1f, fill = false)
                    .testTag(tag("chat_live_status")),
            )
        }
    }
    if (traceExpanded && traceLines.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(atlas.surface, MaterialTheme.shapes.small)
                .border(1.dp, atlas.border, MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag(tag("chat_live_trace_panel")),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            traceLines.forEach { line ->
                Text(
                    text = line,
                    style = type.mono,
                    color = atlas.inkMuted,
                )
            }
        }
    }
}
