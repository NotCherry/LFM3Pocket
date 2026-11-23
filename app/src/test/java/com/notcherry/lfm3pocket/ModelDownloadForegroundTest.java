package com.notcherry.lfm3pocket;

import org.junit.Test;

import java.util.concurrent.Future;

import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;

public final class ModelDownloadForegroundTest {
    @Test public void returnsFailedWhenPromotionThrows() {
        ModelDownloadOutcomes.ForegroundPromotion outcome = ModelDownloadForeground.promote(() -> failingFuture());
        assertEquals(ModelDownloadOutcomes.ForegroundPromotion.FAILED, outcome);
    }

    private static Future<?> failingFuture() {
        return new Future<Object>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isDone() { return true; }
            @Override public Object get() throws java.util.concurrent.ExecutionException {
                throw new java.util.concurrent.ExecutionException(new IllegalStateException("fg"));
            }
            @Override public Object get(long timeout, java.util.concurrent.TimeUnit unit)
                    throws java.util.concurrent.ExecutionException, TimeoutException {
                return get();
            }
        };
    }
}