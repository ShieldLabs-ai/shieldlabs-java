package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookEventsTest {
    private static final String SECRET = "whsec_00112233445566778899aabbccddeeff";

    private static WebhookEvent construct(byte[] body) {
        return Webhooks.constructEvent(body, Fixtures.sign(body, SECRET), SECRET);
    }

    private static WebhookEvent construct(String body) {
        return construct(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void scoredEventFromTheRawBytesAsSent() {
        byte[] body = Fixtures.bytes("webhook-identification-scored.raw.txt");
        String header = "sha256=c4d44b7873625bdfda98cdb7a02d460f8492ca6a68fad8d147a5f30ad16f92a9";
        WebhookEvent event = Webhooks.constructEvent(body, header, SECRET);

        IdentificationScoredEvent scored = assertInstanceOf(IdentificationScoredEvent.class, event);
        assertEquals(WebhookEvent.IDENTIFICATION_SCORED, scored.getType());
        assertEquals("2026-06-01", scored.getSchemaVersion());
        assertEquals(Instant.parse("2026-09-30T12:34:57.482913041Z"), scored.getCreatedAt());
        Identification data = scored.getIdentification();
        NormalizationFixturesTest.assertMatches(
                Fixtures.map(Fixtures.normalizationCase("webhook_scored").get("expected")), data);
        assertEquals(
                "https://shop.example.com/signup?utm_source=google&utm_medium=cpc&gclid=abc123",
                data.getTrafficSource().getLandingUrl(),
                "escaped ampersands are decoded after verification");
        assertEquals("identification.scored", scored.raw().get("event_type"));
        assertTrue(scored.toString().contains("identification.scored"));
    }

    @Test
    void prettyFixtureParsesToTheSameEvent() {
        WebhookEvent pretty = construct(Fixtures.bytes("webhook-identification-scored.json"));
        WebhookEvent raw = construct(Fixtures.bytes("webhook-identification-scored.raw.txt"));
        assertEquals(
                ((IdentificationScoredEvent) raw).getIdentification(), ((IdentificationScoredEvent) pretty).getIdentification());
    }

    @Test
    void rateLimitedEventCarriesTheMarker() {
        IdentificationScoredEvent event =
                assertInstanceOf(IdentificationScoredEvent.class, construct(Fixtures.bytes("webhook-rate-limited.json")));
        Identification data = event.getIdentification();
        assertEquals(999, data.getRiskScore());
        assertTrue(Risk.isRateLimited(data.getRiskScore()));
        assertEquals(RiskBand.RATE_LIMITED, Risk.band(data.getRiskScore()));
        assertEquals(SignalName.RATE_LIMITED, data.getSignals().get(0).getName());
        assertEquals(999, data.getSignals().get(0).getWeight());
        assertEquals(Risk.NIL_DEVICE_ID, data.getDeviceId());
        assertEquals(Instant.parse("2026-09-30T13:10:00.500Z"), event.getCreatedAt());
        NormalizationFixturesTest.assertMatches(
                Fixtures.map(Fixtures.normalizationCase("webhook_rate_limited").get("expected")), data);
    }

    @Test
    void pingHasNoData() {
        byte[] body = Fixtures.bytes("webhook-ping.raw.txt");
        String header = "sha256=ea2685733d254f7028fb031c4214583b0650de01e6c8c93131236024edd9fdd8";
        WebhookPingEvent ping = assertInstanceOf(WebhookPingEvent.class, Webhooks.constructEvent(body, header, SECRET));
        assertEquals(WebhookEvent.WEBHOOK_PING, ping.getType());
        assertEquals(Instant.parse("2026-09-30T12:34:56Z"), ping.getCreatedAt());
        assertFalse(ping.raw().containsKey("data"));
        assertEquals(
                Map.of("event_type", "webhook.ping", "schema_version", "2026-06-01", "created_at", "2026-09-30T12:34:56.000Z"),
                Fixtures.serialized(ping));
        assertInstanceOf(WebhookPingEvent.class, construct(Fixtures.bytes("webhook-ping.json")));
    }

    @Test
    void testDeliveryWithSeventeenFlagsParses() {
        Map<String, Object> envelope = Fixtures.object("webhook-test-delivery.json");
        assertEquals(17, Fixtures.map(Fixtures.map(envelope.get("data")).get("detection_flags")).size());
        IdentificationScoredEvent event =
                assertInstanceOf(IdentificationScoredEvent.class, construct(Fixtures.bytes("webhook-test-delivery.json")));
        Identification data = event.getIdentification();
        assertFalse(data.getDetectionFlags().isBrowserAutomation(), "missing flag reads as false");
        assertFalse(data.getDetectionFlags().isSearchBot(), "missing flag reads as false");
        assertEquals(19, data.getDetectionFlags().asMap().size());
        assertTrue(data.getDetectionFlags().isProxy());
        assertTrue(data.getDetectionFlags().isDatacenterIp());
        assertTrue(data.getDetectionFlags().isAbuser());
        assertNull(data.getUserHid());
        assertEquals(Instant.parse("2026-09-30T12:34:56Z"), event.getCreatedAt());
        NormalizationFixturesTest.assertMatches(
                Fixtures.map(Fixtures.normalizationCase("webhook_test_delivery").get("expected")), data);
    }

    @Test
    void unknownEventTypesAndSchemaVersionsAreAccepted() {
        WebhookEvent event =
                construct("{\"event_type\":\"identification.refined\",\"schema_version\":\"2027-01-01\","
                        + "\"created_at\":\"2027-01-01T00:00:00+01:00\",\"data\":{\"x\":1}}");
        UnknownWebhookEvent unknown = assertInstanceOf(UnknownWebhookEvent.class, event);
        assertEquals("identification.refined", unknown.getType());
        assertEquals("2027-01-01", unknown.getSchemaVersion());
        assertEquals(Instant.parse("2026-12-31T23:00:00Z"), unknown.getCreatedAt());
        assertEquals(Map.of("x", 1), unknown.raw().get("data"));

        WebhookPingEvent futurePing =
                assertInstanceOf(
                        WebhookPingEvent.class,
                        construct("{\"event_type\":\"webhook.ping\",\"schema_version\":\"2027-01-01\",\"extra\":[1,2]}"));
        assertNull(futurePing.getCreatedAt());
        WebhookPingEvent noVersion =
                assertInstanceOf(WebhookPingEvent.class, construct("{\"event_type\":\"webhook.ping\"}"));
        assertNull(noVersion.getSchemaVersion());
    }

    @Test
    void scoredEventToleratesSparseData() {
        IdentificationScoredEvent event =
                assertInstanceOf(
                        IdentificationScoredEvent.class,
                        construct("{\"event_type\":\"identification.scored\",\"data\":{\"request_id\":\"abc\","
                                + "\"risk_score\":35,\"user_hid\":null}}"));
        Identification data = event.getIdentification();
        assertEquals("abc", data.getRequestId());
        assertEquals(35, data.getRiskScore());
        assertEquals("", data.getDomain());
        assertEquals("", data.getPublicIp().getIp());
        assertEquals("", data.getTrafficSource().getChannel());
        assertTrue(data.getSignals().isEmpty());
        assertTrue(data.getDetectionFlags().active().isEmpty());
        assertNull(data.getObservedAt());
    }

    @Test
    void malformedBodiesAreParseErrors() {
        assertThrows(WebhookParseException.class, () -> construct("not json"));
        assertThrows(WebhookParseException.class, () -> construct("{\"event_type\":\"webhook.ping\"} trailing"));
        assertThrows(WebhookParseException.class, () -> construct("[1,2,3]"));
        assertThrows(WebhookParseException.class, () -> construct("{\"schema_version\":\"2026-06-01\"}"));
        assertThrows(WebhookParseException.class, () -> construct("{\"event_type\":7}"));
        assertThrows(WebhookParseException.class, () -> construct("{\"event_type\":\"\"}"));
        assertThrows(WebhookParseException.class, () -> construct("{\"event_type\":\"identification.scored\"}"));
        WebhookParseException notObject =
                assertThrows(
                        WebhookParseException.class,
                        () -> construct("{\"event_type\":\"identification.scored\",\"data\":[]}"));
        assertTrue(notObject.getMessage().contains("data"));
    }

    @Test
    void eventsSerializeWithWireNames() {
        IdentificationScoredEvent event =
                (IdentificationScoredEvent) construct(Fixtures.bytes("webhook-identification-scored.raw.txt"));
        Map<String, Object> json = Fixtures.serialized(event);
        assertEquals("identification.scored", json.get("event_type"));
        assertEquals("2026-06-01", json.get("schema_version"));
        assertEquals("2026-09-30T12:34:57.482913041Z", json.get("created_at"));
        Map<String, Object> data = new LinkedHashMap<>(Fixtures.map(json.get("data")));
        assertEquals("a5b7c9d1-e3f5-4a7b-9c1d-3e5f7a9b1c3d", data.get("request_id"));
        assertEquals(80, data.get("risk_score"));
    }
}
