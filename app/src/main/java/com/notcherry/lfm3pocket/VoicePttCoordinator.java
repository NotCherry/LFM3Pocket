package com.notcherry.lfm3pocket;

import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

/** Hold-to-talk for voice actions; tap-to-toggle when TalkBack touch exploration is on. */
public final class VoicePttCoordinator implements View.OnTouchListener {
    private final ModelRuntime runtime;
    private final Runnable requestMicPermission;

    public VoicePttCoordinator(ModelRuntime runtime, Runnable requestMicPermission) {
        this.runtime = runtime;
        this.requestMicPermission = requestMicPermission;
    }

    public boolean useAccessibilityToggle(View view) {
        AccessibilityManager am = (AccessibilityManager) view.getContext().getSystemService(
                android.content.Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isTouchExplorationEnabled();
    }

    /** Double-tap path when press-and-hold is unreliable with TalkBack. */
    public boolean onClick(View view) {
        if (!runtime.voice().isEnabled() || !useAccessibilityToggle(view)) return false;
        if (runtime.voice().isListening()) {
            runtime.voice().pttReleased();
        } else if (!runtime.voice().pttPressed() && requestMicPermission != null) {
            requestMicPermission.run();
            return false;
        }
        return true;
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        if (!runtime.voice().isEnabled() || useAccessibilityToggle(view)) return false;
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