package com.notcherry.lfm3pocket;

import android.view.MotionEvent;
import android.view.View;

/** Hold-to-talk for voice actions without growing {@link MainActivity}. */
public final class VoicePttCoordinator implements View.OnTouchListener {
    private final ModelRuntime runtime;
    private final Runnable requestMicPermission;

    public VoicePttCoordinator(ModelRuntime runtime, Runnable requestMicPermission) {
        this.runtime = runtime;
        this.requestMicPermission = requestMicPermission;
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        if (!runtime.voice().isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!runtime.voice().pttPressed()) {
                    if (requestMicPermission != null) requestMicPermission.run();
                    return false;
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                runtime.voice().pttReleased();
                return true;
            default:
                return false;
        }
    }
}