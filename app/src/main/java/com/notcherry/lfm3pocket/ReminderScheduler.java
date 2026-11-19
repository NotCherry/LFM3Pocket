package com.notcherry.lfm3pocket;

public interface ReminderScheduler {
    AndroidActionExecutor.ActionResult schedule(long whenEpochSec, String title, String body);
}
