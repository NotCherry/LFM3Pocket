package com.notcherry.lfm3pocket;

import androidx.concurrent.futures.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Waits for WorkManager foreground promotion; testable without a running worker. */
public final class ModelDownloadForeground {
    private ModelDownloadForeground() {}

    public interface AsyncForeground {
        ListenableFuture<?> promote();
    }

    public static ModelDownloadOutcomes.ForegroundPromotion promote(AsyncForeground async) {
        try {
            async.promote().get(30, TimeUnit.SECONDS);
            return ModelDownloadOutcomes.ForegroundPromotion.OK;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ModelDownloadOutcomes.ForegroundPromotion.FAILED;
        } catch (ExecutionException | TimeoutException e) {
            return ModelDownloadOutcomes.ForegroundPromotion.FAILED;
        }
    }
}