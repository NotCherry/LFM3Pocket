package com.notcherry.lfm3pocket;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Human-readable reminder times for consent UI and speech. */
public final class ReminderDescriptions {
  private ReminderDescriptions() {}

  public static String formatLocal(long whenEpochSec) {
    return formatLocal(whenEpochSec, Locale.getDefault(), ZoneId.systemDefault());
  }

  public static String formatLocal(long whenEpochSec, Locale locale, ZoneId zone) {
    if (locale == null) locale = Locale.US;
    if (zone == null) zone = ZoneId.of("UTC");
    DateTimeFormatter formatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone);
    return formatter.format(Instant.ofEpochSecond(whenEpochSec));
  }
}