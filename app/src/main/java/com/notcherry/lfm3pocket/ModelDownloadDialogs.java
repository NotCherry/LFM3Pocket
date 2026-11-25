package com.notcherry.lfm3pocket;

import android.app.Activity;
import android.app.AlertDialog;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Triggers background downloads for optional speech packs (Tiny Voca, Cactus Whistle),
 * handles metered-network confirmation dialogs, and maps start results to chat status (#322).
 */
final class ModelDownloadDialogs {
    static final class Deps {
        final Activity activity;
        final Runnable requestNotificationPermission;
        final BooleanSupplier isChatShellReady;
        final Consumer<CharSequence> setChatStatus;
        final Runnable paintTinyVocaStatus;
        final Runnable paintWhistleStatus;

        Deps(Activity activity, Runnable requestNotificationPermission, BooleanSupplier isChatShellReady,
                Consumer<CharSequence> setChatStatus, Runnable paintTinyVocaStatus,
                Runnable paintWhistleStatus) {
            this.activity = activity;
            this.requestNotificationPermission = requestNotificationPermission;
            this.isChatShellReady = isChatShellReady;
            this.setChatStatus = setChatStatus;
            this.paintTinyVocaStatus = paintTinyVocaStatus;
            this.paintWhistleStatus = paintWhistleStatus;
        }
    }

    private final Deps deps;

    ModelDownloadDialogs(Deps deps) {
        this.deps = deps;
    }

    void downloadTinyVocaPack() {
        deps.requestNotificationPermission.run();
        ModelDownloadManager manager = ModelDownloadManager.getInstance(deps.activity);
        ModelDownloadManager.StartResult outcome = manager.startTinyVoca(false);
        if (outcome == ModelDownloadManager.StartResult.METERED_NEEDS_CONFIRMATION) {
            StillwaterUi.showDialog(new AlertDialog.Builder(deps.activity)
                    .setTitle(R.string.model_download_metered_title)
                    .setMessage(R.string.model_download_metered_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        ModelDownloadManager.StartResult retry = manager.startTinyVoca(true);
                        handleStart(retry, manager, ModelDownloadKind.TINY_VOCA, deps.paintTinyVocaStatus);
                    })
                    .setNegativeButton(R.string.cancel, null));
            return;
        }
        handleStart(outcome, manager, ModelDownloadKind.TINY_VOCA, deps.paintTinyVocaStatus);
    }

    void downloadCactusWhistleModel() {
        deps.requestNotificationPermission.run();
        ModelDownloadManager manager = ModelDownloadManager.getInstance(deps.activity);
        ModelDownloadManager.StartResult outcome = manager.startWhistle(false);
        if (outcome == ModelDownloadManager.StartResult.METERED_NEEDS_CONFIRMATION) {
            StillwaterUi.showDialog(new AlertDialog.Builder(deps.activity)
                    .setTitle(R.string.model_download_metered_title)
                    .setMessage(R.string.model_download_metered_message)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        ModelDownloadManager.StartResult retry = manager.startWhistle(true);
                        handleStart(retry, manager, ModelDownloadKind.CACTUS_WHISTLE, deps.paintWhistleStatus);
                    })
                    .setNegativeButton(R.string.cancel, null));
            return;
        }
        handleStart(outcome, manager, ModelDownloadKind.CACTUS_WHISTLE, deps.paintWhistleStatus);
    }

    private void handleStart(ModelDownloadManager.StartResult outcome, ModelDownloadManager manager,
            ModelDownloadKind kind, Runnable onRunningOrStarted) {
        if (!deps.isChatShellReady.getAsBoolean()) return;
        if (outcome == ModelDownloadManager.StartResult.INSUFFICIENT_SPACE) {
            deps.setChatStatus.accept(deps.activity.getString(R.string.model_download_insufficient_space,
                    formatMb(manager.insufficientSpaceRequiredBytes(kind)),
                    formatMb(manager.insufficientSpaceUsableBytes())));
        } else if (outcome == ModelDownloadManager.StartResult.ALREADY_RUNNING) {
            deps.setChatStatus.accept(deps.activity.getString(R.string.model_download_already_running));
        } else if (outcome == ModelDownloadManager.StartResult.SCHEDULE_FAILED) {
            deps.setChatStatus.accept(deps.activity.getString(R.string.model_download_schedule_failed));
        }
        if (outcome == ModelDownloadManager.StartResult.STARTED
                || outcome == ModelDownloadManager.StartResult.ALREADY_RUNNING) {
            onRunningOrStarted.run();
        }
    }

    static String formatMb(long bytes) {
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
