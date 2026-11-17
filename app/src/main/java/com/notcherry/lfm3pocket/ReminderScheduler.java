package com.notcherry.lfm3pocket;

/** Schedules a one-shot local notification reminder (inexact via WorkManager by default). */
public interface ReminderScheduler {
    final class Plan {
        public final String workId;
        public final long whenEpochSec;
        public final String title;
        public final String body;
        public final int notificationId;

        public Plan(String workId, long whenEpochSec, String title, String body, int notificationId) {
            this.workId = workId;
            this.whenEpochSec = whenEpochSec;
            this.title = title;
            this.body = body;
            this.notificationId = notificationId;
        }
    }

    final class Result {
        public final boolean succeeded;
        public final String status;
        public final String message;

        public Result(boolean succeeded, String status, String message) {
            this.succeeded = succeeded;
            this.status = status;
            this.message = message;
        }
    }

    Result schedule(Plan plan);
    void cancel(String workId);
}