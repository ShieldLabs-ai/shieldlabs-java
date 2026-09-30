package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NormalizationFixturesTest {

    static Stream<Arguments> cases() {
        return Fixtures.cases("normalization-cases.json").stream()
                .map(item -> Arguments.of(item.get("name"), item));
    }

    /** Compares an identification with an expected object; observed_at at millisecond precision (truncated). */
    static void assertMatches(Map<String, Object> expected, Identification actual) {
        Map<String, Object> expectedFields = new LinkedHashMap<>(expected);
        Map<String, Object> actualFields = new LinkedHashMap<>(Fixtures.serialized(actual));
        Object expectedObservedAt = expectedFields.remove("observed_at");
        actualFields.remove("observed_at");
        if (expectedObservedAt == null) {
            assertNull(actual.getObservedAt());
        } else {
            assertNotNull(actual.getObservedAt());
            assertEquals(
                    Instant.parse((String) expectedObservedAt),
                    actual.getObservedAt().truncatedTo(ChronoUnit.MILLIS));
        }
        assertEquals(expectedFields, actualFields);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void producesTheExpectedIdentification(String name, Map<String, Object> item) {
        Map<String, Object> input = Fixtures.map(item.get("input"));
        Map<String, Object> expected = Fixtures.map(item.get("expected"));
        Identification actual =
                "history".equals(item.get("source"))
                        ? Identification.fromHistoryRow(input)
                        : Identification.fromWebhookData(input);
        assertMatches(expected, actual);
        assertEquals(input, actual.raw());
        assertEquals(item.get("source"), actual.getSource().getValue());
    }

    @Test
    void coversBothSources() {
        long history = cases().filter(a -> "history".equals(((Map<?, ?>) a.get()[1]).get("source"))).count();
        long webhook = cases().filter(a -> "webhook".equals(((Map<?, ?>) a.get()[1]).get("source"))).count();
        assertTrue(history >= 5, "history cases");
        assertTrue(webhook >= 3, "webhook cases");
    }

    @Test
    void gettersExposeTheNormalizedValues() {
        Identification id =
                Identification.fromHistoryRow(Fixtures.map(Fixtures.normalizationCase("history_9e8d7c6b").get("input")));
        assertEquals("9e8d7c6b-5a49-4382-9716-05f4e3d2c1b0", id.getRequestId());
        assertNull(id.getUserHid(), "empty user_hid becomes null");
        assertEquals("198.51.100.7", id.getPublicIp().getIp());
        assertEquals("France", id.getPublicIp().getCountry());
        assertEquals("203.0.113.9", id.getLocalIp().getIp(), "leak IP used when a leak source is set");
        assertEquals("Spain", id.getLocalIp().getCountry());
        assertEquals(ConnectionType.VPN, id.getConnectionType());
        assertEquals(45, id.getRiskScore());
        assertEquals(RiskBand.SUSPICIOUS, Risk.band(id.getRiskScore()));
        assertTrue(id.getDetectionFlags().isVpn());
        assertTrue(id.getDetectionFlags().isIpMismatch());
        assertTrue(id.getDetectionFlags().isStunNotChecked());
        assertTrue(id.getDetectionFlags().isCheckIncomplete());
        assertEquals(Instant.parse("2026-09-30T13:05:12Z"), id.getObservedAt());
        List<Signal> signals = id.getSignals();
        assertEquals(4, signals.size());
        assertEquals(SignalName.STUN_LATE_CORRECTION, signals.get(2).getName());
        assertEquals(-30, signals.get(2).getWeight(), "negative weights are kept");
        assertEquals("Stun passed (late arrival, corrected)", signals.get(2).getDescription());
        assertEquals(1790773512000L, ((Number) id.raw().get("ver")).longValue(), "diagnostic fields stay in raw");
    }

    @Test
    void webhookObservedAtKeepsFullPrecision() {
        Identification id =
                Identification.fromWebhookData(Fixtures.map(Fixtures.normalizationCase("webhook_scored").get("input")));
        assertEquals(Instant.parse("2026-09-30T12:34:57.482913041Z"), id.getObservedAt());
        assertEquals("2026-09-30T12:34:57.482913041Z", Fixtures.serialized(id).get("observed_at"));
        assertNull(id.getSignals().get(0).getDescription(), "webhook signals carry no description");
    }

    @Test
    void historyPageRowsMatchTheirNormalizationCases() {
        Map<String, Object> page = Fixtures.object("history-page.json");
        List<Object> rows = Fixtures.list(page.get("data"));
        assertEquals(5, rows.size());
        for (Object row : rows) {
            Map<String, Object> input = Fixtures.map(row);
            Identification actual = Identification.fromHistoryRow(input);
            Map<String, Object> expected =
                    Fixtures.map(Fixtures.normalizationCase("history_" + actual.getRequestId().substring(0, 8)).get("expected"));
            assertMatches(expected, actual);
        }
    }
}
