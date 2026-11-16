package com.notcherry.lfm3pocket;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;

/** Formats the current wall clock for an optional IANA timezone. */
public final class CurrentTimeTool {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX '('zzzz')'", Locale.ROOT);

    private CurrentTimeTool() {}

    public static String format(String timezone, Supplier<ZonedDateTime> now) {
        ZoneId zone;
        try {
            zone = timezone == null || timezone.trim().isEmpty()
                    ? ZoneId.systemDefault()
                    : ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown timezone");
        }
        ZonedDateTime instant = now.get().withZoneSameInstant(zone);
        return "UNTRUSTED_TIME " + FORMAT.format(instant) + " zone=" + zone.getId();
    }

    public static String format(String timezone) {
        return format(timezone, ZonedDateTime::now);
    }
}
