package ai.shieldlabs;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Timestamp parsing and formatting shared by the models. */
final class Timestamps {
    private static final Pattern HISTORY_TIME =
            Pattern.compile(
                    "([0-9]{4})-([0-9]{2})-([0-9]{2})[ T]([0-9]{2}):([0-9]{2}):([0-9]{2})"
                            + "(?:\\.([0-9]{1,9}))?(Z|[+-][0-9]{2}:?[0-9]{2})?");
    private static final Pattern RFC3339 =
            Pattern.compile(
                    "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})"
                            + "(?:\\.([0-9]{1,9}))?(Z|[+-][0-9]{2}:[0-9]{2})");

    private Timestamps() {
    }

    /**
     * Parses a History API {@code created_at} value ({@code YYYY-MM-DD HH:MM:SS[.fff]}, UTC). The value
     * is always read as UTC; a zone designator, when present, is accepted but not applied.
     */
    static Instant parseHistoryTime(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String text = Text.strip((String) value);
        if (text.isEmpty()) {
            return null;
        }
        Matcher m = HISTORY_TIME.matcher(text);
        if (!m.matches()) {
            return null;
        }
        LocalDateTime base = dateTime(m);
        return base == null ? null : base.toInstant(ZoneOffset.UTC).plusNanos(nanos(m.group(7)));
    }

    /** Parses an RFC 3339 timestamp with up to 9 fractional digits and a {@code Z} or {@code +HH:MM} zone. */
    static Instant parseRfc3339(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        Matcher m = RFC3339.matcher(text);
        if (!m.matches()) {
            // A single trailing newline is tolerated, like a "$" anchor.
            if (!text.endsWith("\n")) {
                return null;
            }
            m = RFC3339.matcher(text.substring(0, text.length() - 1));
            if (!m.matches()) {
                return null;
            }
        }
        LocalDateTime base = dateTime(m);
        if (base == null) {
            return null;
        }
        String zone = m.group(8);
        long offsetSeconds = 0;
        if (!"Z".equals(zone)) {
            int sign = zone.charAt(0) == '+' ? 1 : -1;
            long hours = Long.parseLong(zone.substring(1, 3));
            long minutes = Long.parseLong(zone.substring(4, 6));
            offsetSeconds = sign * (hours * 3600L + minutes * 60L);
        }
        return base.toInstant(ZoneOffset.UTC).minusSeconds(offsetSeconds).plusNanos(nanos(m.group(7)));
    }

    /**
     * Formats a timestamp as RFC 3339 in UTC with at least millisecond precision, for example
     * {@code 2026-09-30T12:34:56.123Z}; finer precision is kept when present.
     */
    static String format(Instant instant) {
        LocalDateTime t = LocalDateTime.ofEpochSecond(instant.getEpochSecond(), 0, ZoneOffset.UTC);
        String fraction = String.format(Locale.ROOT, "%09d", instant.getNano());
        int end = fraction.length();
        while (end > 3 && fraction.charAt(end - 1) == '0') {
            end--;
        }
        return String.format(
                Locale.ROOT,
                "%04d-%02d-%02dT%02d:%02d:%02d.%sZ",
                t.getYear(),
                t.getMonthValue(),
                t.getDayOfMonth(),
                t.getHour(),
                t.getMinute(),
                t.getSecond(),
                fraction.substring(0, end));
    }

    private static LocalDateTime dateTime(Matcher m) {
        try {
            int year = Integer.parseInt(m.group(1));
            if (year < 1) {
                return null;
            }
            LocalDate date = LocalDate.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            LocalTime time =
                    LocalTime.of(
                            Integer.parseInt(m.group(4)),
                            Integer.parseInt(m.group(5)),
                            Integer.parseInt(m.group(6)));
            return LocalDateTime.of(date, time);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static long nanos(String fraction) {
        if (fraction == null) {
            return 0L;
        }
        StringBuilder digits = new StringBuilder(fraction);
        while (digits.length() < 9) {
            digits.append('0');
        }
        return Long.parseLong(digits.toString());
    }
}
