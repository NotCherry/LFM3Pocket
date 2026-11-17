package com.notcherry.lfm3pocket;

/** Stable notification and alarm request codes per reminder id. */
public final class ReminderIds {
  private ReminderIds() {}

  public static int notificationId(String reminderId) {
    if (reminderId == null || reminderId.isEmpty()) reminderId = "reminder";
    return 0x50000000 | (reminderId.hashCode() & 0x0FFFFFFF);
  }
}