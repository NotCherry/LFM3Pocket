package com.notcherry.lfm3pocket;

import org.junit.Test;

import androidx.work.ListenableWorker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ModelDownloadOutcomesTest {
    @Test public void successDoesNotRescheduleJob() {
        assertFalse(ModelDownloadOutcomes.jobWantsReschedule(ModelDownloadCoordinator.Result.SUCCESS));
    }

    @Test public void cancelledDoesNotRescheduleJob() {
        assertFalse(ModelDownloadOutcomes.jobWantsReschedule(ModelDownloadCoordinator.Result.CANCELLED));
    }

    @Test public void stoppedReschedulesJob() {
        assertTrue(ModelDownloadOutcomes.jobWantsReschedule(ModelDownloadCoordinator.Result.STOPPED));
    }

    @Test public void failedDoesNotRescheduleJob() {
        assertFalse(ModelDownloadOutcomes.jobWantsReschedule(ModelDownloadCoordinator.Result.FAILED));
    }

    @Test public void workManagerMapsStoppedToRetry() {
        assertEquals(ListenableWorker.Result.retry(),
                ModelDownloadOutcomes.workManagerResult(ModelDownloadCoordinator.Result.STOPPED));
    }

    @Test public void workManagerMapsSuccessToSuccess() {
        assertEquals(ListenableWorker.Result.success(),
                ModelDownloadOutcomes.workManagerResult(ModelDownloadCoordinator.Result.SUCCESS));
    }

    @Test public void workManagerMapsCancelledToFailure() {
        assertEquals(ListenableWorker.Result.failure(),
                ModelDownloadOutcomes.workManagerResult(ModelDownloadCoordinator.Result.CANCELLED));
    }

    @Test public void foregroundPromotionMapsBoolean() {
        assertEquals(ModelDownloadOutcomes.ForegroundPromotion.OK,
                ModelDownloadOutcomes.foregroundPromotion(true));
        assertEquals(ModelDownloadOutcomes.ForegroundPromotion.FAILED,
                ModelDownloadOutcomes.foregroundPromotion(false));
    }
}