package com.notcherry.lfm3pocket;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Map;

/** Converts model-facing reminder time fields into epoch seconds (device zone). */
public final class ReminderTimeParser {
  private static final DateTimeFormatter AT_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd['T'][' ']HH:mm[:ss]")
          .withResolverStyle(ResolverStyle.STRICT);

  private ReminderTimeParser() {}

  public static long toEpochSec(Map<String, Object> args, long nowEpochSec, ZoneId zone) {
    boolean hasMinutes = args.containsKey("in_minutes");
    boolean hasAt = args.containsKey("at");
    if (hasMinutes == hasAt) {
      throw new IllegalArgumentException("Provide exactly one of in_minutes or at");
    }
    if (hasMinutes) {
      Object raw = args.get("in_minutes");
      if (!(raw instanceof Number)) throw new IllegalArgumentException("in_minutes must be an integer");
      long minutes = ((Number) raw).longValue();
      if (minutes < 1 || minutes > ReminderController.MAX_HORIZON_SECONDS / 60L) {
        throw new IllegalArgumentException("in_minutes must be between 1 and one year");
      }
      long target = nowEpochSec + minutes * 60L;
      if (target - nowEpochSec < ReminderController.MIN_LEAD_SECONDS) {
        throw new IllegalArgumentException("Reminder time must be at least 30 seconds from now");
      }
      return target;
    }
    String at = (String) args.get("at");
    if (at == null || at.isEmpty()) throw new IllegalArgumentException("at must be a local date-time");
    LocalDateTime when;
    try {
      when = LocalDateTime.parse(at, AT_FORMAT);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("at must be ISO-8601 local date-time uuuu-MM-ddTHH:mm");
    }
  long epoch = when.atZone(zone == null ? ZoneId.systemDefault() : zone).toEpochSecond();
    long lead = epoch - nowEpochSec;
    if (lead < ReminderController.MIN_LEAD_SECONDS) {
      throw new IllegalArgumentException("Reminder time must be at least 30 seconds from now");
    }
    if (lead > ReminderController.MAX_HORIZON_SECONDS) {
      throw new IllegalArgumentException("Reminder time must be within one year");
    }
    if (epoch < ReminderController.MIN_EPOCH || epoch > ReminderController.MAX_EPOCH) {
      throw new IllegalArgumentException("Reminder time is out of supported bounds");
    }
    return epoch;
  }
}