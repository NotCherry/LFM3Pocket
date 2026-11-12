package com.notcherry.lfm3pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

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
internal fun ChatLiveStatus(mode: String, status: String, packageName: String) {
    fun tag(name: String) = "$packageName:id/$name"

    Text(mode, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.testTag(tag("chat_live_mode")))
    if (status.isNotBlank()) {
        val announcedStatus = chatStatusLabel(status)
        val active = status.startsWith("Searching") || status.startsWith("Generating")
                || status.startsWith("Stopping")
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription = announcedStatus
                }.testTag(tag("chat_live_announcer")),
            verticalAlignment = Alignment.CenterVertically) {
            if (active) CircularProgressIndicator(Modifier.size(18.dp)
                .testTag(tag("chat_live_progress")), strokeWidth = 2.dp)
            Text(announcedStatus, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp).testTag(tag("chat_live_status")))
        }
    }
}
