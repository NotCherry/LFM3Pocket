package com.notcherry.lfm3pocket;

import android.app.job.JobParameters;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ModelDownloadJobStopTest {
    @Test public void userStopIsCancelNotReschedule() {
        ModelDownloadJobStop.Action action = ModelDownloadJobStop.actionForStopReason(
                JobParameters.STOP_REASON_USER);
        assertEquals(ModelDownloadJobStop.Action.USER_CANCEL, action);
        assertFalse(ModelDownloadJobStop.onStopWantsReschedule(action));
    }

    @Test public void appCancelIsUserCancel() {
        ModelDownloadJobStop.Action action = ModelDownloadJobStop.actionForStopReason(
                JobParameters.STOP_REASON_CANCELLED_BY_APP);
        assertEquals(ModelDownloadJobStop.Action.USER_CANCEL, action);
    }

    @Test public void constraintLossIsSystemStopAndReschedules() {
        ModelDownloadJobStop.Action action = ModelDownloadJobStop.actionForStopReason(
                JobParameters.STOP_REASON_CONSTRAINTS_NOT_SATISFIED);
        assertEquals(ModelDownloadJobStop.Action.SYSTEM_STOP, action);
        assertTrue(ModelDownloadJobStop.onStopWantsReschedule(action));
    }
}