package com.notcherry.lfm3pocket;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * User-initiated data transfer job on API 34+ ({@link android.app.job.JobInfo.Builder#setUserInitiated}).
 */
public final class ModelDownloadJobService extends JobService {
    public static final int JOB_ID_WHISTLE = 34_001;

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    @Override public boolean onStartJob(JobParameters params) {
        if (Build.VERSION.SDK_INT < 34) return false;
        String raw = params.getExtras().getString(ModelDownloadWorker.KEY_KIND);
        if (raw == null) {
            jobFinished(params, false);
            return false;
        }
        ModelDownloadKind kind;
        try {
            kind = ModelDownloadKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            jobFinished(params, false);
            return false;
        }
        EXECUTOR.execute(() -> runJob(kind, params));
        return true;
    }

    private void runJob(ModelDownloadKind kind, JobParameters params) {
        ModelDownloadTask task = ModelDownloadManager.taskFor(getApplicationContext(), kind);
        if (task == null) {
            jobFinished(params, false);
            return;
        }
        ModelDownloadCancelToken cancel = new ModelDownloadCancelToken();
        ModelDownloadProgressRelay.setListener((k, label, percent) ->
                ModelDownloadNotifications.showProgress(getApplicationContext(), k,
                        getString(R.string.model_download_notification_title), label, percent));
        try {
            ModelDownloadCoordinator.Result outcome = ModelDownloadCoordinator.run(task, cancel);
            jobFinished(params, outcome == ModelDownloadCoordinator.Result.SUCCESS);
        } finally {
            ModelDownloadProgressRelay.setListener(null);
            ModelDownloadNotifications.dismiss(getApplicationContext(), kind);
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        String raw = params.getExtras().getString(ModelDownloadWorker.KEY_KIND);
        if (raw != null) {
            try {
                ModelDownloadManager.getInstance(getApplicationContext())
                        .cancel(ModelDownloadKind.valueOf(raw));
            } catch (IllegalArgumentException ignored) { }
        }
        return true;
    }
}