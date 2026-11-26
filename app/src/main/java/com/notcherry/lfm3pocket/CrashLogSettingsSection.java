package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Settings scroll section for viewing, exporting, and sharing sanitized on-device diagnostics,
 * processing traces, crash logs, one-tap GitHub issue reports, opt-in paste uploads, and the
 * zero-ADB local Wi-Fi log viewer.
 */
final class CrashLogSettingsSection {
    static final class Deps {
        final Consumer<CharSequence> chatStatus;
        final Supplier<DiagnosticsReportBuilder.DeviceInfo> deviceInfo;
        final Supplier<ProcessingTelemetry> telemetry;
        final Supplier<LocalDiagnostics> localDiagnostics;
        final ExecutorService ioWorker;
        final Consumer<Runnable> runOnUiThread;

        Deps(Consumer<CharSequence> chatStatus,
             Supplier<DiagnosticsReportBuilder.DeviceInfo> deviceInfo,
             Supplier<ProcessingTelemetry> telemetry,
             Supplier<LocalDiagnostics> localDiagnostics,
             ExecutorService ioWorker,
             Consumer<Runnable> runOnUiThread) {
            this.chatStatus = chatStatus;
            this.deviceInfo = deviceInfo;
            this.telemetry = telemetry;
            this.localDiagnostics = localDiagnostics;
            this.ioWorker = ioWorker;
            this.runOnUiThread = runOnUiThread;
        }
    }

    private final SettingsUiFactory ui;
    private final Deps deps;
    private TextView crashLogText;
    private TextView lanUrlText;
    private Button lanToggleButton;
    private LanDiagnosticsServer lanServer;

    CrashLogSettingsSection(SettingsUiFactory ui, Deps deps) {
        this.ui = ui;
        this.deps = deps;
    }

    static DiagnosticsReportBuilder.DeviceInfo collectDeviceInfo(Activity activity,
                                                                 String modelPath,
                                                                 boolean projectorLoaded,
                                                                 InferenceSettings settings) {
        String appVersion = "unknown";
        try {
            android.content.pm.PackageInfo pi = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            if (pi != null && pi.versionName != null) appVersion = pi.versionName;
        } catch (Throwable ignored) {}
        String nativeBuild = "";
        try {
            nativeBuild = NativeBridge.nativeBuildInfo();
        } catch (Throwable ignored) {}
        InferenceSettings limits = settings == null ? InferenceSettings.DEFAULT : settings;
        String limitSummary = "ctx=" + limits.contextTokens
                + ", threads=" + limits.threads
                + ", maxTokens=" + limits.maxTokens
                + ", promptLookup=" + limits.promptLookup;
        String abis = Build.SUPPORTED_ABIS == null ? "" : String.join(", ", Arrays.asList(Build.SUPPORTED_ABIS));
        String modelLabel = Build.MANUFACTURER + " " + Build.MODEL;
        int pageSizeBytes = queryPageSizeBytes();
        return new DiagnosticsReportBuilder.DeviceInfo(
                appVersion,
                modelLabel.trim(),
                Build.VERSION.SDK_INT,
                Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE,
                pageSizeBytes,
                abis,
                nativeBuild,
                modelPath,
                projectorLoaded,
                limitSummary,
                DeviceResourceMonitor.sample(activity));
    }

    static int queryPageSizeBytes() {
        try {
            long sz = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
            return sz > 0 && sz <= Integer.MAX_VALUE ? (int) sz : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static void showGitHubIssueChooser(Activity activity,
                                       DiagnosticsReportBuilder.DeviceInfo deviceInfo,
                                       Consumer<CharSequence> statusSink) {
        if (activity == null) return;
        final CharSequence[] options = new CharSequence[] {
                activity.getString(R.string.settings_diag_issue_type_idea),
                activity.getString(R.string.settings_diag_issue_type_bug),
                activity.getString(R.string.settings_diag_issue_type_device),
                activity.getString(R.string.settings_diag_issue_type_blank),
        };
        final GitHubIssueUrlBuilder.IssueKind[] kinds = new GitHubIssueUrlBuilder.IssueKind[] {
                GitHubIssueUrlBuilder.IssueKind.FEATURE_IDEA,
                GitHubIssueUrlBuilder.IssueKind.BUG_DEVICE_INFO,
                GitHubIssueUrlBuilder.IssueKind.DEVICE_COMPATIBILITY,
                GitHubIssueUrlBuilder.IssueKind.BLANK,
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_diag_issue_picker_title)
                .setItems(options, (dialog, which) -> {
                    if (which < 0 || which >= kinds.length) return;
                    String url = GitHubIssueUrlBuilder.buildUrl(kinds[which], deviceInfo, null);
                    openBrowserUrl(activity, url, statusSink);
                    if (statusSink != null) {
                        statusSink.accept(activity.getString(R.string.settings_diag_github_opened));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        StillwaterUi.showDialog(builder);
    }

    private static void openBrowserUrl(Activity activity, String url, Consumer<CharSequence> statusSink) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Throwable e) {
            if (statusSink != null) {
                statusSink.accept("Could not open browser: " + e.getMessage());
            }
        }
    }

    void mount(LinearLayout configuration, int ink, int inkMuted) {
        LinearLayout card = StillwaterUi.settingsCard(configuration,
                ui.getString(R.string.settings_crash_log_heading));
        StillwaterUi.settingsHelp(card, ui.getString(R.string.settings_crash_log_body));

        crashLogText = ui.label(ui.getString(R.string.settings_crash_log_empty), 13, ink);
        crashLogText.setId(R.id.settings_crash_log_text);
        StillwaterUi.styleCodeBlock(crashLogText);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, -2);
        logParams.topMargin = ui.dp(4);
        logParams.bottomMargin = ui.dp(6);
        card.addView(crashLogText, logParams);

        Button viewCrashLog = ui.button(ui.getString(R.string.settings_crash_log_view));
        viewCrashLog.setId(R.id.settings_crash_log_view);
        StillwaterUi.outlined(viewCrashLog);
        viewCrashLog.setOnClickListener(v -> crashLogText.setText(buildDiagnosticsReport()));

        Button copyCrashLog = ui.button(ui.getString(R.string.settings_diag_copy_log));
        copyCrashLog.setId(R.id.settings_diag_copy_log);
        StillwaterUi.outlined(copyCrashLog);
        copyCrashLog.setOnClickListener(v -> {
            String report = buildDiagnosticsReport();
            copyToClipboard("Hanami diagnostics report", report);
            deps.chatStatus.accept(ui.getString(R.string.settings_diag_copied));
        });
        StillwaterReadable.addFlexibleButtonRow(card, ui.settingsActivity(), 48,
                viewCrashLog, copyCrashLog);

        Button exportCrashLog = ui.button(ui.getString(R.string.settings_crash_log_export));
        exportCrashLog.setId(R.id.settings_crash_log_export);
        StillwaterUi.outlined(exportCrashLog);
        exportCrashLog.setOnClickListener(v -> {
            String body = buildDiagnosticsReport();
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, "Hanami diagnostics report");
            share.putExtra(Intent.EXTRA_TEXT, body);
            ui.settingsActivity().startActivity(Intent.createChooser(share,
                    ui.getString(R.string.settings_crash_log_export)));
            deps.chatStatus.accept(ui.getString(R.string.settings_crash_log_exported));
        });
        StillwaterReadable.addFlexibleFullWidth(card, exportCrashLog, 48);

        Button githubIssue = ui.button(ui.getString(R.string.settings_diag_github_issue));
        githubIssue.setId(R.id.settings_diag_github_issue);
        StillwaterUi.accentOutlined(githubIssue);
        githubIssue.setOnClickListener(v -> openGitHubIssueChooser());

        Button pasteAndIssue = ui.button(ui.getString(R.string.settings_diag_paste_issue));
        pasteAndIssue.setId(R.id.settings_diag_paste_issue);
        StillwaterUi.outlined(pasteAndIssue);
        pasteAndIssue.setOnClickListener(v -> confirmAndUploadPaste(pasteAndIssue));
        StillwaterReadable.addFlexibleButtonRow(card, ui.settingsActivity(), 48,
                githubIssue, pasteAndIssue);

        lanToggleButton = ui.button(ui.getString(R.string.settings_diag_lan_start));
        lanToggleButton.setId(R.id.settings_diag_lan_toggle);
        StillwaterUi.outlined(lanToggleButton);
        lanToggleButton.setOnClickListener(v -> toggleLanViewer());
        StillwaterReadable.addFlexibleFullWidth(card, lanToggleButton, 48);

        lanUrlText = ui.label("", 13, ink);
        lanUrlText.setId(R.id.settings_diag_lan_url);
        StillwaterUi.styleCodeBlock(lanUrlText);
        lanUrlText.setVisibility(View.GONE);
        lanUrlText.setOnClickListener(v -> {
            if (lanServer != null && lanServer.isRunning()) {
                copyToClipboard("Hanami Wi-Fi log URL", lanServer.displayUrl());
                deps.chatStatus.accept(ui.getString(R.string.settings_diag_lan_copied));
            }
        });
        LinearLayout.LayoutParams lanParams = new LinearLayout.LayoutParams(-1, -2);
        lanParams.topMargin = ui.dp(4);
        lanParams.bottomMargin = ui.dp(6);
        card.addView(lanUrlText, lanParams);

        Button clearCrashLog = ui.button(ui.getString(R.string.settings_crash_log_clear));
        clearCrashLog.setId(R.id.settings_crash_log_clear);
        StillwaterUi.dangerOutlined(clearCrashLog);
        clearCrashLog.setOnClickListener(v -> {
            CrashLog.clear(ui.settingsActivity());
            ProcessingTelemetry t = deps.telemetry == null ? null : deps.telemetry.get();
            if (t != null) t.clear();
            crashLogText.setText(ui.getString(R.string.settings_crash_log_empty));
        });
        StillwaterReadable.addFlexibleFullWidth(card, clearCrashLog, 48);
    }

    void dispose() {
        if (lanServer != null) {
            lanServer.stop();
            lanServer = null;
        }
    }

    private String buildDiagnosticsReport() {
        String crash = CrashLog.read(ui.settingsActivity());
        ProcessingTelemetry t = deps.telemetry == null ? null : deps.telemetry.get();
        List<String> traces = t == null ? Collections.emptyList() : t.recentLines(40);
        LocalDiagnostics diag = deps.localDiagnostics == null ? null : deps.localDiagnostics.get();
        String ndjson = diag == null ? "" : diag.toNdjson();
        DiagnosticsReportBuilder.DeviceInfo info = deps.deviceInfo == null ? null : deps.deviceInfo.get();
        return DiagnosticsReportBuilder.buildMarkdown(info, traces, ndjson, crash);
    }

    private void openGitHubIssueChooser() {
        DiagnosticsReportBuilder.DeviceInfo info = deps.deviceInfo == null ? null : deps.deviceInfo.get();
        showGitHubIssueChooser(ui.settingsActivity(), info, deps.chatStatus);
    }

    private void confirmAndUploadPaste(Button triggerButton) {
        String report = buildDiagnosticsReport();
        DiagnosticsReportBuilder.DeviceInfo info = deps.deviceInfo == null ? null : deps.deviceInfo.get();
        String preview = report.length() > 1400 ? report.substring(0, 1400) + "\n…(truncated preview)" : report;
        AlertDialog.Builder builder = new AlertDialog.Builder(ui.settingsActivity())
                .setTitle(R.string.settings_diag_paste_confirm_title)
                .setMessage(ui.settingsActivity().getString(R.string.settings_diag_paste_confirm_body, preview))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_diag_paste_confirm_ok, (d, which) -> {
                    triggerButton.setEnabled(false);
                    deps.chatStatus.accept(ui.getString(R.string.settings_diag_paste_uploading));
                    deps.ioWorker.execute(() -> {
                        String pasteUrl = null;
                        String failure = null;
                        try {
                            pasteUrl = DiagnosticsPasteUploader.upload(report);
                        } catch (Throwable e) {
                            failure = e.getMessage() == null ? e.toString() : e.getMessage();
                        }
                        final String uploadedUrl = pasteUrl;
                        final String uploadError = failure;
                        deps.runOnUiThread.accept(() -> {
                            triggerButton.setEnabled(true);
                            if (uploadError != null || uploadedUrl == null) {
                                deps.chatStatus.accept(ui.settingsActivity().getString(
                                        R.string.settings_diag_paste_failed,
                                        uploadError == null ? "unknown error" : uploadError));
                                return;
                            }
                            copyToClipboard("Hanami diagnostics URL", uploadedUrl);
                            String issueUrl = GitHubIssueUrlBuilder.buildUrl(
                                    GitHubIssueUrlBuilder.IssueKind.BUG_WITH_PASTE, info, uploadedUrl);
                            openBrowserUrl(ui.settingsActivity(), issueUrl, deps.chatStatus);
                            deps.chatStatus.accept(ui.settingsActivity().getString(
                                    R.string.settings_diag_paste_uploaded, uploadedUrl));
                        });
                    });
                });
        StillwaterUi.showDialog(builder);
    }

    private void toggleLanViewer() {
        if (lanServer != null && lanServer.isRunning()) {
            lanServer.stop();
            lanServer = null;
            lanToggleButton.setText(ui.getString(R.string.settings_diag_lan_start));
            lanUrlText.setVisibility(View.GONE);
            deps.chatStatus.accept(ui.getString(R.string.settings_diag_lan_stopped));
            return;
        }
        try {
            LanDiagnosticsServer server = new LanDiagnosticsServer(
                    this::buildDiagnosticsReport,
                    () -> {
                        LocalDiagnostics diag = deps.localDiagnostics == null ? null : deps.localDiagnostics.get();
                        return diag == null ? "" : diag.toNdjson();
                    });
            String url = server.start();
            lanServer = server;
            lanToggleButton.setText(ui.getString(R.string.settings_diag_lan_stop));
            lanUrlText.setText(ui.settingsActivity().getString(R.string.settings_diag_lan_hint, url));
            lanUrlText.setVisibility(View.VISIBLE);
            copyToClipboard("Hanami Wi-Fi log URL", url);
            deps.chatStatus.accept(ui.getString(R.string.settings_diag_lan_copied));
        } catch (Throwable e) {
            deps.chatStatus.accept("Could not start Wi-Fi log viewer: " + e.getMessage());
        }
    }

    private void copyToClipboard(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ui.settingsActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
        } catch (Throwable ignored) {}
    }
}
