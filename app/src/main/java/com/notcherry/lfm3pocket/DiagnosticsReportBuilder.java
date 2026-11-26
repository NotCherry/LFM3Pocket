package com.notcherry.lfm3pocket;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Builds strictly sanitized, human-readable Markdown diagnostic reports and pre-filled GitHub
 * issue URLs without leaking user prompts, file contents, full filesystem paths, or tokens.
 */
public final class DiagnosticsReportBuilder {
    public static final String GITHUB_NEW_ISSUE_BASE = "https://github.com/NotCherry/LFM3Pocket/issues/new";
    private static final int MAX_ISSUE_URL_BODY_CHARS = 4800;
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-+/=]{8,}");
    private static final Pattern TOKEN_KV_PATTERN = Pattern.compile("(?i)((?:token|api[_-]?key|authorization|secret|password)\"?\\s*[:=]\\s*\"?)[^\\s\",&]{6,}");

    public static final class DeviceInfo {
        public final String appVersion;
        public final String deviceModel;
        public final int androidSdk;
        public final String androidRelease;
        public final int pageSizeBytes;
        public final String supportedAbis;
        public final String nativeBuildInfo;
        public final String modelBasename;
        public final boolean projectorLoaded;
        public final String inferenceSummary;
        public final DeviceResourceMonitor.Snapshot resources;

        public DeviceInfo(String appVersion, String deviceModel, int androidSdk, String supportedAbis,
                          String nativeBuildInfo, String modelBasename, boolean projectorLoaded,
                          String inferenceSummary, DeviceResourceMonitor.Snapshot resources) {
            this(appVersion, deviceModel, androidSdk, "", 0, supportedAbis,
                    nativeBuildInfo, modelBasename, projectorLoaded, inferenceSummary, resources);
        }

        public DeviceInfo(String appVersion, String deviceModel, int androidSdk, String androidRelease,
                          int pageSizeBytes, String supportedAbis, String nativeBuildInfo,
                          String modelBasename, boolean projectorLoaded, String inferenceSummary,
                          DeviceResourceMonitor.Snapshot resources) {
            this.appVersion = nonNull(appVersion);
            this.deviceModel = nonNull(deviceModel);
            this.androidSdk = androidSdk;
            this.androidRelease = nonNull(androidRelease);
            this.pageSizeBytes = Math.max(0, pageSizeBytes);
            this.supportedAbis = nonNull(supportedAbis);
            this.nativeBuildInfo = nonNull(nativeBuildInfo);
            this.modelBasename = basenameOnly(modelBasename);
            this.projectorLoaded = projectorLoaded;
            this.inferenceSummary = nonNull(inferenceSummary);
            this.resources = resources == null
                    ? new DeviceResourceMonitor.Snapshot(0, 0, 0, false, "unknown")
                    : resources;
        }
    }

    private DiagnosticsReportBuilder() {}

    /** Builds the full sanitized Markdown diagnostics report (safe for GitHub Issues / Paste / Share). */
    public static String buildMarkdown(DeviceInfo info,
                                       List<String> processingLines,
                                       String localDiagnosticsNdjson,
                                       String crashLog) {
        StringBuilder out = new StringBuilder(2048);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

        out.append("## Hanami On-Device Diagnostics Report\n");
        out.append("- **Generated (UTC):** ").append(fmt.format(new Date())).append('\n');
        out.append("- **Privacy:** Sanitized automatically (no chat messages, document contents, or tokens).\n\n");

        if (info != null) {
            out.append("### Environment\n");
            out.append("- **App version:** ").append(safeOr(info.appVersion, "unknown")).append('\n');
            out.append("- **Device:** ").append(safeOr(info.deviceModel, "unknown"));
            if (!info.androidRelease.trim().isEmpty()) {
                out.append(" (Android ").append(info.androidRelease.trim())
                        .append(", SDK ").append(info.androidSdk).append(")\n");
            } else {
                out.append(" (Android SDK ").append(info.androidSdk).append(")\n");
            }
            out.append("- **ABIs:** ").append(safeOr(info.supportedAbis, "unknown")).append('\n');
            if (info.pageSizeBytes > 0) {
                out.append("- **Page size:** ").append(info.pageSizeBytes / 1024).append(" KB\n");
            }
            out.append("- **RAM:** ").append(info.resources.availRamMb).append(" MB free / ")
                    .append(info.resources.totalRamMb).append(" MB total (app RSS ")
                    .append(info.resources.appRssMb).append(" MB, lowMemory=")
                    .append(info.resources.lowMemory).append(")\n");
            out.append("- **Thermal:** ").append(info.resources.thermalState).append('\n');
            out.append("- **Model:** ").append(safeOr(info.modelBasename, "none"))
                    .append(" (projector=").append(info.projectorLoaded ? "loaded" : "none").append(")\n");
            out.append("- **Inference limits:** ").append(safeOr(info.inferenceSummary, "default")).append('\n');
            if (!info.nativeBuildInfo.isEmpty()) {
                out.append("- **Native build:** `").append(redactSecrets(info.nativeBuildInfo)).append("`\n");
            }
            out.append('\n');
        }

        out.append("### Recent Processing Trace\n");
        if (processingLines == null || processingLines.isEmpty()) {
            out.append("_No processing phases recorded yet in this session._\n\n");
        } else {
            out.append("```text\n");
            for (String line : processingLines) {
                out.append(redactSecrets(line)).append('\n');
            }
            out.append("```\n\n");
        }

        String ndjson = localDiagnosticsNdjson == null ? "" : localDiagnosticsNdjson.trim();
        if (!ndjson.isEmpty()) {
            out.append("### Local Diagnostics Events (NDJSON)\n```json\n");
            out.append(redactSecrets(tail(ndjson, 3500))).append("\n```\n\n");
        }

        String crash = crashLog == null ? "" : crashLog.trim();
        out.append("### Crash / Process Exit Evidence\n");
        if (crash.isEmpty()) {
            out.append("_No crash or process-exit logs recorded._\n");
        } else {
            out.append("```text\n");
            out.append(redactSecrets(tail(crash, 4000))).append("\n```\n");
        }
        return out.toString();
    }

    /**
     * Builds a zero-cost, zero-token pre-filled GitHub Issue URL.
     * If {@code pasteUrl} is provided, includes the link cleanly instead of truncating large traces.
     */
    public static String buildGitHubIssueUrl(String suggestedTitle, String fullMarkdownReport, String pasteUrl) {
        String title = (suggestedTitle == null || suggestedTitle.trim().isEmpty())
                ? "Bug report: on-device processing / performance issue"
                : suggestedTitle.trim();
        StringBuilder body = new StringBuilder();
        body.append("### What happened\n<!-- Describe what you did (e.g. attached a 4 MB PDF, asked a question) -->\n\n");
        if (pasteUrl != null && !pasteUrl.trim().isEmpty()) {
            body.append("### Full Sanitized Diagnostics Log\n").append(pasteUrl.trim()).append("\n\n");
        }
        String sanitized = redactSecrets(fullMarkdownReport == null ? "" : fullMarkdownReport);
        int budget = Math.max(600, MAX_ISSUE_URL_BODY_CHARS - body.length());
        if (sanitized.length() > budget) {
            body.append(sanitized.substring(0, budget)).append("\n\n_(Truncated for URL length — full report was copied to clipboard; paste below if needed.)_");
        } else {
            body.append(sanitized);
        }
        return GITHUB_NEW_ISSUE_BASE
                + "?title=" + urlEncode(title)
                + "&body=" + urlEncode(body.toString());
    }

    /** Strips any accidental Bearer tokens or key/value secrets before export or upload. */
    public static String redactSecrets(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String cleaned = BEARER_PATTERN.matcher(raw).replaceAll("$1[REDACTED]");
        cleaned = TOKEN_KV_PATTERN.matcher(cleaned).replaceAll("$1[REDACTED]");
        return cleaned;
    }

    static String basenameOnly(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        String trimmed = path.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 && slash + 1 < trimmed.length() ? trimmed.substring(slash + 1) : trimmed;
    }

    private static String tail(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        return "…(truncated)\n" + text.substring(text.length() - maxChars);
    }

    private static String safeOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }
}
