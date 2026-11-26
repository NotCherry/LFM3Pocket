package com.notcherry.lfm3pocket;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.function.IntConsumer;

/** Foreground holder for {@link ModelRuntime}. The process keeps the model mapped while this runs. */
public final class ModelRuntimeService extends Service {
    static final String ACTION_STOP = "com.notcherry.lfm3pocket.action.STOP_TURN";
    static final String ACTION_UNLOAD = "com.notcherry.lfm3pocket.action.UNLOAD_MODEL";
    static final String ACTION_APPROVE = "com.notcherry.lfm3pocket.action.APPROVE_TOOL";
    static final String ACTION_REJECT = "com.notcherry.lfm3pocket.action.REJECT_TOOL";
    static final String ACTION_ASK = "com.notcherry.lfm3pocket.action.ASK_FROM_NOTIFICATION";
    static final String ACTION_TALK = "com.notcherry.lfm3pocket.action.VOICE_TALK";
    static final String EXTRA_PROPOSAL_ID = "proposal_id";

    private boolean promoted;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        // Satisfy a foreground start before any other work. If Android denies foreground
        // promotion here (mAllowStartForeground == false), onStartCommand handles fallback cleanly.
        RuntimeNotice starting = RuntimeNotice.of(RuntimeNotice.Kind.LOADING, "Model runtime",
                "Starting…", false, 0, 0, "", false, RuntimeNotice.Action.OPEN);
        promote(RuntimeNotifications.build(this, starting, "", ""), starting);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ModelRuntime runtime = ((Lfm3PocketApplication) getApplication()).modelRuntime();
        RuntimeNotice notice = runtime.currentNotice();
        if (notice.kind == RuntimeNotice.Kind.HIDDEN) {
            notice = RuntimeNotice.of(RuntimeNotice.Kind.LOADING, "Model runtime", "Starting…",
                    false, 0, 0, "", false, RuntimeNotice.Action.OPEN);
        }
        boolean active = promote(
                RuntimeNotifications.build(this, notice, runtime.conversationId(), runtime.historyTitle()),
                notice);
        if (active) {
            runtime.serviceReady(this);
        } else {
            runtime.serviceStartRefused(this);
        }
        if (intent != null && intent.getAction() != null) {
            String ask = null;
            if (ACTION_ASK.equals(intent.getAction())) {
                android.os.Bundle remote = android.app.RemoteInput.getResultsFromIntent(intent);
                if (remote != null) {
                    CharSequence value = remote.getCharSequence(RuntimeNotifications.REMOTE_ASK_KEY);
                    ask = value == null ? null : value.toString();
                }
            }
            runtime.onServiceAction(intent.getAction(), intent.getStringExtra(EXTRA_PROPOSAL_ID), ask);
        }
        if (!active && !promoted) {
            stopSelf(startId);
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

    void render(Notification notification, RuntimeNotice notice) {
        if (!promote(notification, notice == null ? RuntimeNotice.hidden() : notice) && !promoted) {
            stopSelf();
        }
    }

    void stopNow() {
        if (promoted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            promoted = false;
        }
        stopSelf();
    }

    private boolean promote(Notification notification, RuntimeNotice notice) {
        final boolean ok;
        if (AndroidPermissionGate.requiresExplicitForegroundServiceType(Build.VERSION.SDK_INT)) {
            boolean mediaPlayback = notice != null && notice.mediaPlayback;
            boolean micCapture = notice != null && notice.microphoneCapture;
            int requestedType = AndroidPermissionGate.computeRuntimeFgsTypeMask(
                    Build.VERSION.SDK_INT,
                    mediaPlayback,
                    micCapture,
                    AndroidPermissionGate.hasRecordAudioPermission(this));
            ok = promoteWithFallback(
                    Build.VERSION.SDK_INT,
                    requestedType,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    type -> startForeground(RuntimeNotifications.NOTIFICATION_ID, notification, type),
                    () -> startForeground(RuntimeNotifications.NOTIFICATION_ID, notification));
        } else {
            ok = promoteWithFallback(
                    Build.VERSION.SDK_INT,
                    0,
                    0,
                    type -> startForeground(RuntimeNotifications.NOTIFICATION_ID, notification),
                    () -> startForeground(RuntimeNotifications.NOTIFICATION_ID, notification));
        }
        if (ok) {
            promoted = true;
        } else {
            recordPromotionRefused();
        }
        return ok;
    }

    /**
     * Promotes to foreground while guarding against Android 12+
     * {@code ForegroundServiceStartNotAllowedException} (extends {@link IllegalStateException})
     * and Android 14+ {@link SecurityException} on restricted FGS types.
     */
    static boolean promoteWithFallback(int sdkInt, int requestedType, int fallbackType,
                                       IntConsumer startWithType, Runnable startLegacy) {
        if (sdkInt >= 34) {
            try {
                startWithType.accept(requestedType);
                return true;
            } catch (IllegalStateException | SecurityException refused) {
                if (requestedType != fallbackType) {
                    try {
                        startWithType.accept(fallbackType);
                        return true;
                    } catch (IllegalStateException | SecurityException fallbackRefused) {
                        return false;
                    }
                }
                return false;
            }
        }
        try {
            startLegacy.run();
            return true;
        } catch (IllegalStateException | SecurityException refused) {
            return false;
        }
    }

    private void recordPromotionRefused() {
        try {
            ((Lfm3PocketApplication) getApplication()).diagnostics().record(
                    LocalDiagnostics.Kind.STRESS, 0L, "fgs startForeground refused");
        } catch (Throwable ignored) {
            // Diagnostics must never mask service lifecycle handling.
        }
    }
}
