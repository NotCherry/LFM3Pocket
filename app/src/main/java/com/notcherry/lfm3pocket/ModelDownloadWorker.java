package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.pm.ServiceInfo;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.ListenableWorker;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Fallback background runner for model downloads below API 34 (foreground WorkManager work). */
public final class ModelDownloadWorker extends Worker {
    public static final String KEY_KIND = "kind";

    public ModelDownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public ListenableWorker.Result doWork() {
        String raw = getInputData().getString(KEY_KIND);
        if (raw == null) return ListenableWorker.Result.failure();
        ModelDownloadKind kind;
        try {
            kind = ModelDownloadKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return ListenableWorker.Result.failure();
        }
        Context app = getApplicationContext();
        ModelDownloadTask task = ModelDownloadManager.taskFor(app, kind);
        if (task == null) return ListenableWorker.Result.failure();
        ModelDownloadCancelToken cancel = new ModelDownloadCancelToken();
        String title = app.getString(R.string.model_download_notification_title);
        ModelDownloadProgressRelay.setListener((k, label, percent) ->
                ModelDownloadNotifications.showProgress(app, k, title, label, percent));
        try {
            ModelDownloadOutcomes.ForegroundPromotion promotion = ModelDownloadForeground.promote(
                    () -> setForegroundAsync(foregroundInfo(app, kind, app.getString(R.string.model_download_starting), -1)));
            if (promotion == ModelDownloadOutcomes.ForegroundPromotion.FAILED) {
                ModelDownloadCoordinator.clearQueued(kind);
                task.notifyUiError(app.getString(R.string.model_download_foreground_failed));
                return ListenableWorker.Result.failure();
            }
            ModelDownloadCoordinator.Result outcome = ModelDownloadCoordinator.run(task, cancel);
            return ModelDownloadOutcomes.workManagerResult(outcome);
        } finally {
            ModelDownloadProgressRelay.setListener(null);
            ModelDownloadNotifications.dismiss(app, kind);
        }
    }

    @NonNull @Override public ForegroundInfo getForegroundInfo() {
        Context app = getApplicationContext();
        String raw = getInputData().getString(KEY_KIND);
        ModelDownloadKind kind = ModelDownloadKind.CACTUS_WHISTLE;
        if (raw != null) {
            try {
                kind = ModelDownloadKind.valueOf(raw);
            } catch (IllegalArgumentException ignored) { }
        }
        return foregroundInfo(app, kind, app.getString(R.string.model_download_starting), -1);
    }

    @Override public void onStopped() {
        String raw = getInputData().getString(KEY_KIND);
        if (raw != null) {
            try {
                ModelDownloadCoordinator.requestSystemStop(ModelDownloadKind.valueOf(raw));
            } catch (IllegalArgumentException ignored) { }
        }
        super.onStopped();
    }

    static ForegroundInfo foregroundInfo(Context context, ModelDownloadKind kind, String body, int percent) {
        return new ForegroundInfo(kind.notificationId(),
                ModelDownloadNotifications.buildNotification(context, kind,
                        context.getString(R.string.model_download_notification_title), body, percent),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }
}