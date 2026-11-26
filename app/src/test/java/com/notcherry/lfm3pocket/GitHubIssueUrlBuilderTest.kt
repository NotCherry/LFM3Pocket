package com.notcherry.lfm3pocket

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for lightweight, multi-purpose GitHub Issue Form URLs (ADR 0028 / ADR 0027). */
class GitHubIssueUrlBuilderTest {
    private fun sampleDeviceInfo(): DiagnosticsReportBuilder.DeviceInfo {
        val resources = DeviceResourceMonitor.Snapshot(12288, 4096, 920, false, "light")
        return DiagnosticsReportBuilder.DeviceInfo(
            "0.20.4",
            "Xiaomi 24129PN74G",
            35,
            "15",
            16384,
            "arm64-v8a",
            "Hanami native | arm64 | token=secretToken999999",
            "/data/user/0/dev.notcherry.hanami/files/models/LFM3-2B-Q4.gguf",
            true,
            "ctx=4096, threads=4, maxTokens=512",
            resources,
        )
    }

    @Test
    fun blankIssueHasExactEmptyBaseUrl() {
        val url = GitHubIssueUrlBuilder.buildUrl(GitHubIssueUrlBuilder.IssueKind.BLANK, sampleDeviceInfo())
        assertEquals(GitHubIssueUrlBuilder.GITHUB_NEW_ISSUE_BASE, url)
    }

    @Test
    fun featureIdeaUsesCleanTemplateWithZeroLogsAndNoBugTitle() {
        val url = GitHubIssueUrlBuilder.buildUrl(GitHubIssueUrlBuilder.IssueKind.FEATURE_IDEA, sampleDeviceInfo())
        val decoded = decode(url)
        assertTrue(decoded.contains("template=feature_request.yml"))
        assertTrue(decoded.contains("app_version=0.20.4"))
        assertFalse(decoded.contains("Bug report"))
        assertFalse(decoded.contains("Xiaomi"))
        assertFalse(decoded.contains("LFM3-2B"))
        assertFalse(decoded.contains("NDJSON"))
        assertTrue("Feature URL should stay tiny", url.length < 250)
    }

    @Test
    fun bugDeviceInfoIncludesCompactEnvironmentOnlyAndRedactsPaths() {
        val url = GitHubIssueUrlBuilder.buildUrl(GitHubIssueUrlBuilder.IssueKind.BUG_DEVICE_INFO, sampleDeviceInfo())
        val decoded = decode(url)
        assertTrue(decoded.contains("template=bug_report.yml"))
        assertTrue(decoded.contains("environment="))
        assertTrue(decoded.contains("Xiaomi 24129PN74G (Android 15, SDK 35)"))
        assertTrue(decoded.contains("Page size: 16 KB"))
        assertTrue(decoded.contains("LFM3-2B-Q4.gguf"))
        assertFalse(decoded.contains("/data/user/0/"))
        assertFalse(decoded.contains("Recent Processing Trace"))
        assertFalse(decoded.contains("Crash / Process Exit Evidence"))
        assertFalse(decoded.contains("diagnostics_url="))
        assertTrue("Lightweight bug URL must stay under 1500 chars", url.length < 1500)
    }

    @Test
    fun bugWithPasteLinksPasteUrlAndCompactEnvWithoutDuplicatingRawLog() {
        val url = GitHubIssueUrlBuilder.buildUrl(
            GitHubIssueUrlBuilder.IssueKind.BUG_WITH_PASTE,
            sampleDeviceInfo(),
            "https://dpaste.org/AbCdEf",
        )
        val decoded = decode(url)
        assertTrue(decoded.contains("template=bug_report.yml"))
        assertTrue(decoded.contains("diagnostics_url=https://dpaste.org/AbCdEf"))
        assertTrue(decoded.contains("environment="))
        assertFalse(decoded.contains("Recent Processing Trace"))
        assertFalse(decoded.contains("Local Diagnostics Events"))
        assertTrue("Paste bug URL must stay under 1500 chars", url.length < 1500)
    }

    @Test
    fun deviceCompatibilityRedactsSecretsAndIncludesPageSizeAndAbi() {
        val url = GitHubIssueUrlBuilder.buildUrl(
            GitHubIssueUrlBuilder.IssueKind.DEVICE_COMPATIBILITY,
            sampleDeviceInfo(),
        )
        val decoded = decode(url)
        assertTrue(decoded.contains("template=device_compatibility.yml"))
        assertTrue(decoded.contains("device_info="))
        assertTrue(decoded.contains("Xiaomi 24129PN74G (Android 15, SDK 35)"))
        assertTrue(decoded.contains("Page size: 16 KB"))
        assertTrue(decoded.contains("arm64-v8a"))
        assertFalse(decoded.contains("secretToken999999"))
        assertTrue(decoded.contains("[REDACTED]"))
        assertTrue(url.length < 1500)
    }

    private fun decode(url: String): String =
        URLDecoder.decode(url, StandardCharsets.UTF_8.name())
}
