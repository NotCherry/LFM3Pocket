package com.notcherry.lfm3pocket

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds clean, lightweight, multi-purpose GitHub Issue URLs targeting repository Issue Forms
 * (YAML templates under `.github/ISSUE_TEMPLATE/`) without dumping raw traces or crash logs
 * into the query string (ADR 0028 / ADR 0027).
 */
object GitHubIssueUrlBuilder {
    const val GITHUB_NEW_ISSUE_BASE = DiagnosticsReportBuilder.GITHUB_NEW_ISSUE_BASE
    const val TEMPLATE_BUG_REPORT = "bug_report.yml"
    const val TEMPLATE_FEATURE_REQUEST = "feature_request.yml"
    const val TEMPLATE_DEVICE_COMPAT = "device_compatibility.yml"

    private const val MAX_FIELD_CHARS = 900

    enum class IssueKind {
        FEATURE_IDEA,
        BUG_DEVICE_INFO,
        BUG_WITH_PASTE,
        DEVICE_COMPATIBILITY,
        BLANK,
    }

    /**
     * Compact (~8-line) sanitized environment block for bug reports: device specs, Android version,
     * RAM/thermal snapshot, and active model basename only. Never includes traces, NDJSON, or crashes.
     */
    @JvmStatic
    fun buildCompactEnvironment(info: DiagnosticsReportBuilder.DeviceInfo?): String {
        if (info == null) return ""
        val out = StringBuilder(256)
        out.append("- App version: ").append(safeOr(info.appVersion, "unknown")).append('\n')
        out.append("- Device: ").append(formatDeviceOs(info)).append('\n')
        out.append("- ABIs: ").append(safeOr(info.supportedAbis, "unknown")).append('\n')
        if (info.pageSizeBytes > 0) {
            out.append("- Page size: ").append(info.pageSizeBytes / 1024).append(" KB\n")
        }
        out.append("- RAM: ").append(info.resources.availRamMb).append(" MB free / ")
            .append(info.resources.totalRamMb).append(" MB total (app RSS ")
            .append(info.resources.appRssMb).append(" MB, lowMemory=")
            .append(info.resources.lowMemory).append(")\n")
        out.append("- Thermal: ").append(safeOr(info.resources.thermalState, "unknown")).append('\n')
        out.append("- Model: ").append(safeOr(info.modelBasename, "none"))
            .append(" (projector=").append(if (info.projectorLoaded) "loaded" else "none").append(")\n")
        out.append("- Inference limits: ").append(safeOr(info.inferenceSummary, "default"))
        return clampAndRedact(out.toString(), MAX_FIELD_CHARS)
    }

    /** Minimal device/OS summary for install & device compatibility reports. */
    @JvmStatic
    fun buildCompactDeviceSummary(info: DiagnosticsReportBuilder.DeviceInfo?): String {
        if (info == null) return ""
        val out = StringBuilder(192)
        out.append("- App version: ").append(safeOr(info.appVersion, "unknown")).append('\n')
        out.append("- Device: ").append(formatDeviceOs(info)).append('\n')
        out.append("- ABIs: ").append(safeOr(info.supportedAbis, "unknown"))
        if (info.pageSizeBytes > 0) {
            out.append("\n- Page size: ").append(info.pageSizeBytes / 1024).append(" KB")
        }
        if (info.nativeBuildInfo.isNotBlank()) {
            out.append("\n- Native build: ").append(DiagnosticsReportBuilder.redactSecrets(info.nativeBuildInfo.trim()))
        }
        return clampAndRedact(out.toString(), MAX_FIELD_CHARS)
    }

    /** Builds the GitHub Issue Form URL for the chosen {@link IssueKind}. */
    @JvmStatic
    @JvmOverloads
    fun buildUrl(
        kind: IssueKind,
        info: DiagnosticsReportBuilder.DeviceInfo?,
        pasteUrl: String? = null,
    ): String {
        return when (kind) {
            IssueKind.BLANK -> GITHUB_NEW_ISSUE_BASE
            IssueKind.FEATURE_IDEA -> {
                val params = mutableListOf("template=" + urlEncode(TEMPLATE_FEATURE_REQUEST))
                val version = info?.appVersion?.trim().orEmpty()
                if (version.isNotEmpty() && version != "unknown") {
                    params.add("app_version=" + urlEncode(clampAndRedact(version, 64)))
                }
                "$GITHUB_NEW_ISSUE_BASE?${params.joinToString("&")}"
            }
            IssueKind.BUG_DEVICE_INFO -> {
                val params = mutableListOf("template=" + urlEncode(TEMPLATE_BUG_REPORT))
                val env = buildCompactEnvironment(info)
                if (env.isNotEmpty()) {
                    params.add("environment=" + urlEncode(env))
                }
                "$GITHUB_NEW_ISSUE_BASE?${params.joinToString("&")}"
            }
            IssueKind.BUG_WITH_PASTE -> {
                val params = mutableListOf("template=" + urlEncode(TEMPLATE_BUG_REPORT))
                val env = buildCompactEnvironment(info)
                if (env.isNotEmpty()) {
                    params.add("environment=" + urlEncode(env))
                }
                val cleanPaste = pasteUrl?.trim().orEmpty()
                if (cleanPaste.isNotEmpty()) {
                    params.add("diagnostics_url=" + urlEncode(clampAndRedact(cleanPaste, 256)))
                }
                "$GITHUB_NEW_ISSUE_BASE?${params.joinToString("&")}"
            }
            IssueKind.DEVICE_COMPATIBILITY -> {
                val params = mutableListOf("template=" + urlEncode(TEMPLATE_DEVICE_COMPAT))
                val summary = buildCompactDeviceSummary(info)
                if (summary.isNotEmpty()) {
                    params.add("device_info=" + urlEncode(summary))
                }
                "$GITHUB_NEW_ISSUE_BASE?${params.joinToString("&")}"
            }
        }
    }

    private fun formatDeviceOs(info: DiagnosticsReportBuilder.DeviceInfo): String {
        val model = safeOr(info.deviceModel, "unknown")
        val release = info.androidRelease.trim()
        return if (release.isNotEmpty()) {
            "$model (Android $release, SDK ${info.androidSdk})"
        } else {
            "$model (Android SDK ${info.androidSdk})"
        }
    }

    private fun clampAndRedact(raw: String, maxChars: Int): String {
        val redacted = DiagnosticsReportBuilder.redactSecrets(raw).trim()
        return if (redacted.length <= maxChars) redacted else redacted.substring(0, maxChars)
    }

    private fun safeOr(value: String?, fallback: String): String =
        if (value == null || value.trim().isEmpty()) fallback else value.trim()

    private fun urlEncode(value: String): String =
        try {
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            ""
        }
}
