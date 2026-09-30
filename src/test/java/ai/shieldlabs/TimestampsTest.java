package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimestampsTest {

    @Test
    void historyTimes() {
        assertEquals(Instant.parse("2026-09-30T12:34:56.123Z"), Timestamps.parseHistoryTime("2026-09-30 12:34:56.123"));
        assertEquals(Instant.parse("2026-09-30T12:34:56Z"), Timestamps.parseHistoryTime("2026-09-30 12:34:56"));
        assertEquals(Instant.parse("2026-09-30T12:34:56.5Z"), Timestamps.parseHistoryTime(" 2026-09-30T12:34:56.5 "));
        assertEquals(
                Instant.parse("2026-09-30T12:34:56.123456789Z"),
                Timestamps.parseHistoryTime("2026-09-30 12:34:56.123456789"));
        assertEquals(
                Instant.parse("2026-09-30T12:34:56Z"),
                Timestamps.parseHistoryTime("2026-09-30 12:34:56+02:00"),
                "History times are UTC; a zone designator is accepted but not applied");
        assertEquals(Instant.parse("2026-09-30T12:34:56Z"), Timestamps.parseHistoryTime("2026-09-30 12:34:56Z"));
        assertNull(Timestamps.parseHistoryTime(""));
        assertNull(Timestamps.parseHistoryTime(null));
        assertNull(Timestamps.parseHistoryTime(12345));
        assertNull(Timestamps.parseHistoryTime("2026-02-30 12:00:00"));
        assertNull(Timestamps.parseHistoryTime("2026-09-30 24:00:00"));
        assertNull(Timestamps.parseHistoryTime("0000-01-01 00:00:00"));
        assertNull(Timestamps.parseHistoryTime("2026-09-30"));
        assertNull(Timestamps.parseHistoryTime("2026-09-30 12:34:56.1234567890"));
    }

    @Test
    void rfc3339Times() {
        assertEquals(
                Instant.parse("2026-09-30T12:34:57.482913041Z"),
                Timestamps.parseRfc3339("2026-09-30T12:34:57.482913041Z"));
        assertEquals(Instant.parse("2026-09-30T13:10:00.500Z"), Timestamps.parseRfc3339("2026-09-30T13:10:00.5Z"));
        assertEquals(Instant.parse("2026-09-30T10:34:56Z"), Timestamps.parseRfc3339("2026-09-30T12:34:56+02:00"));
        assertEquals(Instant.parse("2026-09-30T17:04:56Z"), Timestamps.parseRfc3339("2026-09-30T12:34:56-04:30"));
        assertEquals(Instant.parse("2026-09-30T12:34:56Z"), Timestamps.parseRfc3339("2026-09-30T12:34:56Z\n"));
        assertNull(Timestamps.parseRfc3339("2026-09-30T12:34:56z"));
        assertNull(Timestamps.parseRfc3339("2026-09-30 12:34:56Z"));
        assertNull(Timestamps.parseRfc3339("2026-09-30T12:34:56"));
        assertNull(Timestamps.parseRfc3339("2026-09-30T12:34:56+0200"));
        assertNull(Timestamps.parseRfc3339(" 2026-09-30T12:34:56Z"));
        assertNull(Timestamps.parseRfc3339("2026-09-30T12:34:56Z\n\n"));
        assertNull(Timestamps.parseRfc3339("2026-13-01T00:00:00Z"));
        assertNull(Timestamps.parseRfc3339(null));
        assertNull(Timestamps.parseRfc3339(true));
    }

    @Test
    void formatKeepsAtLeastMilliseconds() {
        assertEquals("2026-09-30T12:34:56.000Z", Timestamps.format(Instant.parse("2026-09-30T12:34:56Z")));
        assertEquals("2026-09-30T12:34:56.500Z", Timestamps.format(Instant.parse("2026-09-30T12:34:56.5Z")));
        assertEquals("2026-09-30T12:34:56.123456Z", Timestamps.format(Instant.parse("2026-09-30T12:34:56.123456Z")));
        assertEquals(
                "2026-09-30T12:34:57.482913041Z", Timestamps.format(Instant.parse("2026-09-30T12:34:57.482913041Z")));
        assertEquals("0001-01-01T00:00:00.000Z", Timestamps.format(Instant.parse("0001-01-01T00:00:00Z")));
    }
}
