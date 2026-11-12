package com.notcherry.lfm3pocket

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatLiveStatusContractTest {
    @get:Rule val compose = createComposeRule()

    private val packageName = "com.notcherry.lfm3pocket"
    private val modeTag = "$packageName:id/chat_live_mode"
    private val statusTag = "$packageName:id/chat_live_status"
    private val progressTag = "$packageName:id/chat_live_progress"
    private val announcerTag = "$packageName:id/chat_live_announcer"

    private fun render(mode: String, status: String) {
        compose.setContent {
            MaterialTheme { ChatLiveStatus(mode, status, packageName) }
        }
    }

    @Test fun searchingModeAnnouncesProgressAndShowsSpinner() {
        val mode = "Mode · Web search + local model"
        val status = "Searching web · looking for useful sources"
        val label = "Web search · searching…"
        render(mode, status)

        compose.onNodeWithTag(modeTag).assertTextEquals(mode)
        compose.onNodeWithTag(statusTag).assertTextEquals(label)
        compose.onNodeWithTag(announcerTag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
        assertEquals(1, compose.onAllNodesWithTag(progressTag).fetchSemanticsNodes().size)
    }

    @Test fun searchFailureClearlyLabelsLocalFallbackWhileActive() {
        val mode = "Mode · Web search + local model"
        val status = "Generating locally · web search unavailable"
        val label = "Local fallback · web search unavailable; answering locally…"
        render(mode, status)

        compose.onNodeWithTag(modeTag).assertTextEquals(mode)
        compose.onNodeWithTag(statusTag).assertTextEquals(label)
        compose.onNodeWithTag(announcerTag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
        assertEquals(1, compose.onAllNodesWithTag(progressTag).fetchSemanticsNodes().size)
    }

    @Test fun completedResponseKeepsSelectedModeWithoutShowingProgress() {
        val mode = "Mode · Local / offline"
        val status = "Response complete · local model"
        render(mode, status)

        compose.onNodeWithTag(modeTag).assertTextEquals(mode)
        compose.onNodeWithTag(statusTag).assertTextEquals(status)
        assertEquals(0, compose.onAllNodesWithTag(progressTag).fetchSemanticsNodes().size)
    }
}
