package placeiq;

import java.time.*;
import java.time.format.DateTimeFormatter;

/** All date handling in one place so "today" is consistent across services and tests. */
public final class Time {
  private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  public static String now() { return LocalDateTime.now().format(ISO); }

  public static String today() { return LocalDate.now().toString(); }

  public static String nowMinusDays(int days) { return LocalDateTime.now().minusDays(days).format(ISO); }

  public static String minusDays(int days) { return LocalDate.now().minusDays(days).toString(); }

  public static String plusDays(String from, int days) { return LocalDate.parse(from).plusDays(days).toString(); }

  public static long daysBetween(String from, String to) {
    return Duration.between(LocalDate.parse(from).atStartOfDay(), LocalDate.parse(to).atStartOfDay()).toDays();
  }

  public static int daysSince(String isoDateTime) {
    if (isoDateTime == null || isoDateTime.isBlank()) return 999;
    LocalDate d = LocalDateTime.parse(isoDateTime, ISO).toLocalDate();
    return (int) Duration.between(d.atStartOfDay(), LocalDate.now().atStartOfDay()).toDays();
  }

  /** ISO week start (Monday) of a date. */
  public static String weekStart(String date) {
    LocalDate d = LocalDate.parse(date);
    return d.minusDays(d.getDayOfWeek().getValue() - 1L).toString();
  }
}
