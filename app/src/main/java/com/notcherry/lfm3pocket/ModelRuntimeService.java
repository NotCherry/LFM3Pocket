package com.notcherry.lfm3pocket;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Foreground holder for {@link ModelRuntime}. The process keeps the model mapped while this runs. */
public final class ModelRuntimeService extends Service {
    static final String ACTION_STOP = "com.notcherry.lfm3pocket.action.STOP_TURN";
    static final String ACTION_UNLOAD = "com.notcherry.lfm3pocket.action.UNLOAD_MODEL";
    static final String ACTION_APPROVE = "com.notcherry.lfm3pocket.action.APPROVE_TOOL";
    static final String ACTION_REJECT = "com.notcherry.lfm3pocket.action.REJECT_TOOL";
    static final String EXTRA_PROPOSAL_ID = "proposal_id";

    private boolean promoted;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ModelRuntime runtime = ((Lfm3PocketApplication) getApplication()).modelRuntime();
        RuntimeNotice notice = runtime.currentNotice();
        if (notice.kind == RuntimeNotice.Kind.HIDDEN) {
            notice = RuntimeNotice.of(RuntimeNotice.Kind.LOADING, "Model runtime", "Starting…",
                    false, 0, 0, "", false, RuntimeNotice.Action.OPEN);
        }
        promote(RuntimeNotifications.build(this, notice, runtime.conversationId(), runtime.historyTitle()),
                notice.mediaPlayback);
        runtime.serviceReady(this);
        if (intent != null && intent.getAction() != null) {
            runtime.onServiceAction(intent.getAction(), intent.getStringExtra(EXTRA_PROPOSAL_ID));
        }
        return START_NOT_STICKY;
    }

    @Override public void onTimeout(int startId, int fgsType) {
        ((Lfm3PocketApplication) getApplication()).modelRuntime().onServiceTimeout();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        ((Lfm3PocketApplication) getApplication()).modelRuntime().unload("recents", null);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        ((Lfm3PocketApplication) getApplication()).modelRuntime().serviceGone(this);
        super.onDestroy();
    }

    void render(Notification notification, boolean mediaPlayback) {
        promote(notification, mediaPlayback);
    }

    void stopNow() {
        if (promoted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            promoted = false;
        }
        stopSelf();
    }

    private void promote(Notification notification, boolean mediaPlayback) {
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (mediaPlayback) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            startForeground(RuntimeNotifications.NOTIFICATION_ID, notification, type);
        } else {
            startForeground(RuntimeNotifications.NOTIFICATION_ID, notification);
        }
        promoted = true;
    }
}
