package com.notcherry.lfm3pocket;

import android.app.job.JobParameters;

/** Distinguishes user cancel from system job stop (constraints, health, task manager). */
public final class ModelDownloadJobStop {
    private ModelDownloadJobStop() {}

    public enum Action {
        USER_CANCEL,
        SYSTEM_STOP
    }

  public static Action actionForStopReason(int stopReason) {
    if (stopReason == JobParameters.STOP_REASON_CANCELLED_BY_APP
            || stopReason == JobParameters.STOP_REASON_USER) {
      return Action.USER_CANCEL;
    }
    return Action.SYSTEM_STOP;
  }

    public static boolean onStopWantsReschedule(Action action) {
        return action == Action.SYSTEM_STOP;
    }
}