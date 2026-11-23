package com.notcherry.lfm3pocket;

import androidx.work.ListenableWorker;

/** Pure mapping from coordinator results to JobScheduler / WorkManager completion. */
public final class ModelDownloadOutcomes {
    private ModelDownloadOutcomes() {}

    /** Second argument to {@link android.app.job.JobService#jobFinished}. */
    public static boolean jobWantsReschedule(ModelDownloadCoordinator.Result outcome) {
        return outcome == ModelDownloadCoordinator.Result.STOPPED;
    }

    public static ListenableWorker.Result workManagerResult(ModelDownloadCoordinator.Result outcome) {
        switch (outcome) {
            case SUCCESS:
                return ListenableWorker.Result.success();
            case STOPPED:
                return ListenableWorker.Result.retry();
            case CANCELLED:
            case FAILED:
            case ALREADY_RUNNING:
            default:
                return ListenableWorker.Result.failure();
        }
    }

    public enum ForegroundPromotion {
        OK,
        FAILED
    }

    public static ForegroundPromotion foregroundPromotion(boolean promoted) {
        return promoted ? ForegroundPromotion.OK : ForegroundPromotion.FAILED;
    }
}