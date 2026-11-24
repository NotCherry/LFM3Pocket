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
    public static final int JOB_ID_TINY_VOCA = 34_002;

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private volatile JobParameters activeJob;
    private volatile ModelDownloadKind activeKind;

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
        String title = getString(R.string.model_download_notification_title);
        String starting = getString(R.string.model_download_starting);
        setNotification(params, kind.notificationId(),
                ModelDownloadNotifications.buildNotification(this, kind, title, starting, -1),
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
        activeJob = params;
        activeKind = kind;
        ModelDownloadProgressRelay.setListener((k, label, percent) -> updateJobNotification(k, title, label, percent));
        EXECUTOR.execute(() -> runJob(kind, params));
        return true;
    }

    private void updateJobNotification(ModelDownloadKind kind, String title, String label, int percent) {
        if (Build.VERSION.SDK_INT < 34) return;
        JobParameters params = activeJob;
        if (params == null || kind != activeKind) return;
        setNotification(params, kind.notificationId(),
                ModelDownloadNotifications.buildNotification(this, kind, title, label, percent),
                JobService.JOB_END_NOTIFICATION_POLICY_REMOVE);
    }

    private void runJob(ModelDownloadKind kind, JobParameters params) {
        ModelDownloadTask task = ModelDownloadManager.taskFor(getApplicationContext(), kind);
        if (task == null) {
            finishJob(params, ModelDownloadCoordinator.Result.FAILED);
            return;
        }
        ModelDownloadCancelToken cancel = new ModelDownloadCancelToken();
        try {
            ModelDownloadCoordinator.Result outcome = ModelDownloadCoordinator.run(task, cancel);
            finishJob(params, outcome);
        } finally {
            ModelDownloadProgressRelay.setListener(null);
            ModelDownloadNotifications.dismiss(getApplicationContext(), kind);
            activeJob = null;
            activeKind = null;
        }
    }

    private void finishJob(JobParameters params, ModelDownloadCoordinator.Result outcome) {
        jobFinished(params, ModelDownloadOutcomes.jobWantsReschedule(outcome));
    }

    @Override public boolean onStopJob(JobParameters params) {
        String raw = params.getExtras().getString(ModelDownloadWorker.KEY_KIND);
        if (raw == null) return false;
        ModelDownloadKind kind;
        try {
            kind = ModelDownloadKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int stopReason = Build.VERSION.SDK_INT >= 34
                ? params.getStopReason() : JobParameters.STOP_REASON_UNDEFINED;
        ModelDownloadJobStop.Action action = ModelDownloadJobStop.actionForStopReason(stopReason);
        if (action == ModelDownloadJobStop.Action.USER_CANCEL) {
            ModelDownloadCoordinator.requestUserCancel(kind);
            return ModelDownloadJobStop.onStopWantsReschedule(action);
        }
        ModelDownloadCoordinator.requestSystemStop(kind);
        return ModelDownloadJobStop.onStopWantsReschedule(action);
    }
}