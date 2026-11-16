package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Starts {@link ModelRuntimeService} without arming the foreground-service
 * deadline on the common path.
 *
 * <p>{@code startForegroundService} gives the main thread a few seconds to
 * reach {@code startForeground}. The first frame often still owns that thread,
 * so the deadline fires first. {@code startService} does not arm it; the
 * service still promotes itself in {@code onCreate}. If the app is already in
 * the background and plain start is refused, fall back to the foreground API.
 */
final class RuntimeServiceStarter {
    private RuntimeServiceStarter() {}

    /**
     * @param plainStartRefused true when {@code startService} already threw
     *     {@link IllegalStateException} because the app is background-restricted
     */
    static boolean preferPlainStart(boolean plainStartRefused) {
        return !plainStartRefused;
    }

    static void start(Context app, Intent intent) {
        try {
            app.startService(intent);
        } catch (IllegalStateException background) {
            // preferPlainStart(true) is false: plain start was refused.
            if (preferPlainStart(true)) throw background;
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent);
            else throw background;
        }
    }
}
