package com.notcherry.lfm3pocket;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.StatFs;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

/** Schedules model downloads outside the Activity and survives backgrounding. */
public final class ModelDownloadManager {
    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        INSUFFICIENT_SPACE,
        METERED_NEEDS_CONFIRMATION
    }

    private static volatile ModelDownloadManager instance;

    private final Context appContext;
    private final ModelDownloadPolicies.UsableSpace usableSpace;
    private final ModelDownloadPolicies.MeteredNetwork meteredNetwork;

    private ModelDownloadManager(Context context) {
        this(context, path -> {
            try {
                StatFs stats = new StatFs(path);
                return stats.getAvailableBytes();
            } catch (RuntimeException e) {
                return 0L;
            }
        }, () -> {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            return cm != null && cm.isActiveNetworkMetered();
        });
    }

    ModelDownloadManager(Context context, ModelDownloadPolicies.UsableSpace usableSpace,
                         ModelDownloadPolicies.MeteredNetwork meteredNetwork) {
        this.appContext = context.getApplicationContext();
        this.usableSpace = usableSpace;
        this.meteredNetwork = meteredNetwork;
    }

    public static ModelDownloadManager getInstance(Context context) {
        if (instance == null) {
            synchronized (ModelDownloadManager.class) {
                if (instance == null) instance = new ModelDownloadManager(context);
            }
        }
        return instance;
    }

    static void resetInstanceForTests() {
        instance = null;
    }

    public static ModelDownloadTask taskFor(Context context, ModelDownloadKind kind) {
        if (kind == ModelDownloadKind.CACTUS_WHISTLE) {
            return new CactusWhistleModelDownloadTask(context);
        }
        return null;
    }

    public boolean isDownloading(ModelDownloadKind kind) {
        return ModelDownloadCoordinator.isDownloading(kind);
    }

    public StartResult startWhistle(boolean meteredConfirmed) {
        return start(ModelDownloadKind.CACTUS_WHISTLE, meteredConfirmed);
    }

    public StartResult start(ModelDownloadKind kind, boolean meteredConfirmed) {
        ModelDownloadTask task = taskFor(appContext, kind);
        if (task == null) return StartResult.ALREADY_RUNNING;
        if (ModelDownloadPolicies.shouldConfirmMetered(meteredNetwork, meteredConfirmed)) {
            return StartResult.METERED_NEEDS_CONFIRMATION;
        }
        String filesPath = appContext.getFilesDir().getAbsolutePath();
        if (!ModelDownloadPolicies.hasSufficientSpace(usableSpace, filesPath, task.expectedBytes())) {
            return StartResult.INSUFFICIENT_SPACE;
        }
        if (ModelDownloadCoordinator.isInFlight()) {
            return StartResult.ALREADY_RUNNING;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            scheduleUserInitiatedJob(kind);
        } else {
            scheduleWorkManager(kind);
        }
        return StartResult.STARTED;
    }

    public void cancel(ModelDownloadKind kind) {
        ModelDownloadCoordinator.requestCancel(kind);
        if (Build.VERSION.SDK_INT < 34) {
            WorkManager.getInstance(appContext).cancelUniqueWork(kind.workUniqueName());
        } else {
            JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.cancel(jobId(kind));
        }
    }

    private void scheduleWorkManager(ModelDownloadKind kind) {
        Data input = new Data.Builder().putString(ModelDownloadWorker.KEY_KIND, kind.name()).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ModelDownloadWorker.class)
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build();
        WorkManager.getInstance(appContext).enqueueUniqueWork(
                kind.workUniqueName(), ExistingWorkPolicy.KEEP, request);
    }

    private void scheduleUserInitiatedJob(ModelDownloadKind kind) {
        JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            scheduleWorkManager(kind);
            return;
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putString(ModelDownloadWorker.KEY_KIND, kind.name());
        JobInfo info = new JobInfo.Builder(jobId(kind),
                new ComponentName(appContext, ModelDownloadJobService.class))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
                .build();
        scheduler.schedule(info);
    }

    private static int jobId(ModelDownloadKind kind) {
        if (kind == ModelDownloadKind.CACTUS_WHISTLE) return ModelDownloadJobService.JOB_ID_WHISTLE;
        return 34_000 + kind.ordinal();
    }

    public String insufficientSpaceMessage(ModelDownloadKind kind) {
        ModelDownloadTask task = taskFor(appContext, kind);
        if (task == null) return "";
        long required = task.expectedBytes() + ModelDownloadPolicies.FREE_SPACE_MARGIN_BYTES;
        long usable = usableSpace.usableBytes(appContext.getFilesDir().getAbsolutePath());
        return ModelDownloadPolicies.insufficientSpaceMessage(usable, required);
    }
}