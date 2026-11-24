package com.notcherry.lfm3pocket;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.StatFs;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/** Schedules model downloads outside the Activity and survives backgrounding. */
public final class ModelDownloadManager {
    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        INSUFFICIENT_SPACE,
        METERED_NEEDS_CONFIRMATION,
        SCHEDULE_FAILED
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
        if (kind == ModelDownloadKind.TINY_VOCA) {
            return new TinyVocaPackDownloadTask(context);
        }
        return null;
    }

    public boolean isDownloading(ModelDownloadKind kind) {
        return ModelDownloadCoordinator.isActiveOrQueued(kind);
    }

    public StartResult startWhistle(boolean meteredConfirmed) {
        return start(ModelDownloadKind.CACTUS_WHISTLE, meteredConfirmed);
    }

    public StartResult startTinyVoca(boolean meteredConfirmed) {
        return start(ModelDownloadKind.TINY_VOCA, meteredConfirmed);
    }

    public StartResult start(ModelDownloadKind kind, boolean meteredConfirmed) {
        ModelDownloadTask task = taskFor(appContext, kind);
        if (task == null) throw new IllegalArgumentException("Unsupported download kind: " + kind);
        if (ModelDownloadPolicies.shouldConfirmMetered(meteredNetwork, meteredConfirmed)) {
            return StartResult.METERED_NEEDS_CONFIRMATION;
        }
        String filesPath = appContext.getFilesDir().getAbsolutePath();
        if (!ModelDownloadPolicies.hasSufficientSpace(usableSpace, filesPath, task.expectedBytes())) {
            return StartResult.INSUFFICIENT_SPACE;
        }
        if (ModelDownloadCoordinator.isActiveOrQueued(kind)) {
            return StartResult.ALREADY_RUNNING;
        }
        ModelDownloadCoordinator.markQueued(kind);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                if (!scheduleUserInitiatedJob(kind, task, meteredConfirmed)) {
                    ModelDownloadCoordinator.clearQueued(kind);
                    return StartResult.SCHEDULE_FAILED;
                }
            } else if (!scheduleWorkManager(kind, meteredConfirmed)) {
                ModelDownloadCoordinator.clearQueued(kind);
                return StartResult.SCHEDULE_FAILED;
            }
        } catch (RuntimeException failed) {
            ModelDownloadCoordinator.clearQueued(kind);
            return StartResult.SCHEDULE_FAILED;
        }
        return StartResult.STARTED;
    }

    public void cancel(ModelDownloadKind kind) {
        ModelDownloadCoordinator.requestUserCancel(kind);
        ModelDownloadCoordinator.clearQueued(kind);
        if (Build.VERSION.SDK_INT < 34) {
            WorkManager.getInstance(appContext).cancelUniqueWork(kind.workUniqueName());
        } else {
            JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.cancel(jobId(kind));
        }
    }

    private boolean scheduleWorkManager(ModelDownloadKind kind, boolean meteredConfirmed) {
        NetworkType network = meteredConfirmed ? NetworkType.CONNECTED : NetworkType.UNMETERED;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(network)
                .build();
        Data input = new Data.Builder().putString(ModelDownloadWorker.KEY_KIND, kind.name()).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ModelDownloadWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(appContext).enqueueUniqueWork(
                kind.workUniqueName(), ExistingWorkPolicy.KEEP, request);
        return true;
    }

    private boolean scheduleUserInitiatedJob(ModelDownloadKind kind, ModelDownloadTask task,
                                             boolean meteredConfirmed) {
        JobScheduler scheduler = appContext.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return scheduleWorkManager(kind, meteredConfirmed);
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putString(ModelDownloadWorker.KEY_KIND, kind.name());
        int networkType = meteredConfirmed ? JobInfo.NETWORK_TYPE_ANY : JobInfo.NETWORK_TYPE_UNMETERED;
        JobInfo.Builder builder = new JobInfo.Builder(jobId(kind),
                new ComponentName(appContext, ModelDownloadJobService.class))
                .setUserInitiated(true)
                .setRequiredNetworkType(networkType)
                .setExtras(extras);
        if (Build.VERSION.SDK_INT >= 34) {
            builder.setEstimatedNetworkBytes(task.expectedBytes(), 0L);
        }
        try {
            int result = scheduler.schedule(builder.build());
            return result == JobScheduler.RESULT_SUCCESS;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static int jobId(ModelDownloadKind kind) {
        if (kind == ModelDownloadKind.CACTUS_WHISTLE) return ModelDownloadJobService.JOB_ID_WHISTLE;
        if (kind == ModelDownloadKind.TINY_VOCA) return ModelDownloadJobService.JOB_ID_TINY_VOCA;
        return 34_000 + kind.ordinal();
    }

    public long insufficientSpaceRequiredBytes(ModelDownloadKind kind) {
        ModelDownloadTask task = taskFor(appContext, kind);
        if (task == null) return 0L;
        return task.expectedBytes() + ModelDownloadPolicies.FREE_SPACE_MARGIN_BYTES;
    }

    public long insufficientSpaceUsableBytes() {
        return usableSpace.usableBytes(appContext.getFilesDir().getAbsolutePath());
    }
}