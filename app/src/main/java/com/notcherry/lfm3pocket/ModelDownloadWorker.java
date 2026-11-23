package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.pm.ServiceInfo;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Fallback background runner for model downloads below API 34 (foreground WorkManager work). */
public final class ModelDownloadWorker extends Worker {
    public static final String KEY_KIND = "kind";

    public ModelDownloadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        String raw = getInputData().getString(KEY_KIND);
        if (raw == null) return Result.failure();
        ModelDownloadKind kind;
        try {
            kind = ModelDownloadKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return Result.failure();
        }
        Context app = getApplicationContext();
        ModelDownloadTask task = ModelDownloadManager.taskFor(app, kind);
        if (task == null) return Result.failure();
        ModelDownloadCancelToken cancel = new ModelDownloadCancelToken();
        ModelDownloadProgressRelay.setListener((k, label, percent) ->
                ModelDownloadNotifications.showProgress(app, k,
                        app.getString(R.string.model_download_notification_title), label, percent));
        try {
            setForegroundAsync(foregroundInfo(app, kind, app.getString(R.string.model_download_starting), -1));
            ModelDownloadCoordinator.Result outcome = ModelDownloadCoordinator.run(task, cancel);
            return outcome == ModelDownloadCoordinator.Result.SUCCESS ? Result.success() : Result.failure();
        } catch (Exception e) {
            return Result.failure();
        } finally {
            ModelDownloadProgressRelay.setListener(null);
            ModelDownloadNotifications.dismiss(app, kind);
        }
    }

    static ForegroundInfo foregroundInfo(Context context, ModelDownloadKind kind, String body, int percent) {
        HanamiNotifications.ensureChannels(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                HanamiNotifications.MODEL_DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_runtime)
                .setContentTitle(context.getString(R.string.model_download_notification_title))
                .setContentText(body)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(ModelDownloadNotifications.buildCancelAction(context, kind));
        if (percent >= 0) {
            builder.setProgress(100, percent, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        return new ForegroundInfo(kind.notificationId(), builder.build(), type);
    }
}