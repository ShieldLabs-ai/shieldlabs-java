package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelsTest {

    @Test
    void domainProfileValueSemantics() {
        DomainProfile a = DomainProfile.fromJson(Fixtures.object("management-profile.json"));
        DomainProfile b = DomainProfile.fromJson(Fixtures.object("management-profile.json"));
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, "x");
        Map<String, Object> changed = Fixtures.copy(Fixtures.object("management-profile.json"));
        changed.put("Weight", 1);
        assertNotEquals(a, DomainProfile.fromJson(changed));
        changed = Fixtures.copy(Fixtures.object("management-profile.json"));
        changed.put("Domain", "other.example.com");
        assertNotEquals(a, DomainProfile.fromJson(changed));
        changed = Fixtures.copy(Fixtures.object("management-profile.json"));
        changed.put("PublicKey", "****");
        assertNotEquals(a, DomainProfile.fromJson(changed));
        changed = Fixtures.copy(Fixtures.object("management-profile.json"));
        changed.put("Secret", "****");
        assertNotEquals(a, DomainProfile.fromJson(changed));
        changed = Fixtures.copy(Fixtures.object("management-profile.json"));
        changed.put("CreatedAt", "2026-01-15T09:00:01Z");
        assertNotEquals(a, DomainProfile.fromJson(changed));
        assertThrows(UnsupportedOperationException.class, () -> a.raw().put("x", 1));
    }

    @Test
    void evaluationValueSemantics() {
        Evaluation ok = new Evaluation(true, null, RiskBand.TRUSTED, null);
        assertEquals(ok, ok);
        assertNotEquals(ok, "x");
        assertNotEquals(ok, new Evaluation(false, null, RiskBand.TRUSTED, null));
        assertNotEquals(ok, new Evaluation(true, Evaluation.Reason.STALE, RiskBand.TRUSTED, null));
        assertNotEquals(ok, new Evaluation(true, null, RiskBand.DANGEROUS, null));
        assertNotEquals(ok, new Evaluation(true, null, RiskBand.TRUSTED, DetectionFlag.TOR));
    }

    @Test
    void everyFlagGetterReadsItsFlag() {
        for (DetectionFlag flag : DetectionFlag.values()) {
            DetectionFlags flags = new DetectionFlags(EnumSet.of(flag));
            boolean[] values = {
                flags.isVpn(),
                flags.isPrivacyRelay(),
                flags.isBrowserVpnProxy(),
                flags.isTor(),
                flags.isProxy(),
                flags.isDatacenterIp(),
                flags.isAbuser(),
                flags.isOsMismatch(),
                flags.isOsNotDetected(),
                flags.isTimezoneMismatch(),
                flags.isAntiDetectBrowser(),
                flags.isBrowserAutomation(),
                flags.isIpMismatch(),
                flags.isIncognito(),
                flags.isSearchBot(),
                flags.isSuspiciousPaidClick(),
                flags.isJavascriptDisabled(),
                flags.isStunNotChecked(),
                flags.isCheckIncomplete()
            };
            for (int i = 0; i < values.length; i++) {
                assertEquals(i == flag.ordinal(), values[i], flag + " getter " + i);
            }
            assertEquals(Boolean.TRUE, flags.asMap().get(flag.getValue()));
            assertEquals(EnumSet.of(flag), flags.active());
        }
        assertThrows(UnsupportedOperationException.class, () -> new DetectionFlags(EnumSet.noneOf(DetectionFlag.class)).active().add(DetectionFlag.TOR));
    }

    @Test
    void truthinessAndIntegerHelpers() {
        assertTrue(Json.truthy(BigInteger.TEN));
        assertFalse(Json.truthy(BigInteger.ZERO));
        assertTrue(Json.truthy(new BigDecimal("0.1")));
        assertFalse(Json.truthy(BigDecimal.ZERO));
        assertTrue(Json.truthy(Map.of("a", 1)));
        assertFalse(Json.truthy(Map.of()));
        assertTrue(Json.truthy(List.of(1)));
        assertFalse(Json.truthy(List.of()));
        assertTrue(Json.truthy(new Object()));
        assertFalse(Json.truthy(-0.0));
        assertTrue(Json.truthy(1.5f));
        assertEquals(BigInteger.valueOf(7), Json.integer((short) 7));
        assertEquals(new BigInteger("123456789012345678901234567890"), Json.integer(new BigInteger("123456789012345678901234567890")));
        assertNull(Json.intValue(new BigInteger("123456789012345678901234567890")));
        assertNull(Json.longValue(new BigInteger("123456789012345678901234567890")));
        assertEquals(Long.valueOf(5_000_000_000L), Json.longValue(5_000_000_000L));
        assertNull(Json.integer(true));
        assertNull(Json.integer(1.0));
        assertEquals("", Json.text(Map.of("a", 1)));
        assertEquals("", Json.orEmpty(List.of(1)));
        assertEquals("true", Json.orEmpty(true));
        assertEquals("12", Json.text(12));
        assertEquals(Map.of(), Json.objectOrEmpty("x"));
        assertNull(Json.object(List.of()));
    }

    @Test
    void nullSecretArrayForConstructEvent() {
        byte[] body = Fixtures.bytes("webhook-ping.raw.txt");
        assertThrows(
                SignatureVerificationException.class,
                () -> Webhooks.constructEvent(body, "sha256=00", (String[]) null));
    }

    @Test
    void historyTimestampsOnModels() {
        Identification undated = Identification.fromHistoryRow(Map.of("request_id", "r"));
        assertNull(Fixtures.serialized(undated).get("observed_at"));
        assertTrue(undated.toString().contains("observedAt=null"));
        Identification dated = Identification.fromHistoryRow(Map.of("request_id", "r", "created_at", "2026-09-30 12:00:00"));
        assertEquals(Instant.parse("2026-09-30T12:00:00Z"), dated.getObservedAt());
        assertTrue(dated.toString().contains("2026-09-30T12:00:00.000Z"));
    }

    @Test
    void identificationInequalityPerField() {
        Map<String, Object> base = Fixtures.map(Fixtures.normalizationCase("webhook_scored").get("input"));
        Identification original = Identification.fromWebhookData(base);
        String[][] changes = {
            {"visitor_id", "00000000-0000-0000-0000-000000000001"},
            {"device_id", "00000000-0000-0000-0000-000000000001"},
            {"session_id", "00000000-0000-0000-0000-000000000001"},
            {"cookie_id", "00000000-0000-0000-0000-000000000001"},
            {"user_hid", "someone-else"},
            {"domain", "other.example.com"},
            {"connection_type", "vpn"},
            {"os", "Linux"},
            {"browser", "Firefox"},
            {"device_type", "mobile"},
            {"observed_at", "2026-09-30T12:34:58Z"},
            {"request_id", "00000000-0000-0000-0000-000000000001"}
        };
        for (String[] change : changes) {
            Map<String, Object> data = Fixtures.copy(base);
            data.put(change[0], change[1]);
            assertNotEquals(original, Identification.fromWebhookData(data), change[0]);
        }
        Map<String, Object> data = Fixtures.copy(base);
        data.put("public_ip", Map.of("ip", "192.0.2.1", "country", "Germany"));
        assertNotEquals(original, Identification.fromWebhookData(data));
        data = Fixtures.copy(base);
        data.put("local_ip", Map.of("ip", "192.0.2.1", "country", "Germany"));
        assertNotEquals(original, Identification.fromWebhookData(data));
        data = Fixtures.copy(base);
        data.put("traffic_source", Map.of("channel", "Direct"));
        assertNotEquals(original, Identification.fromWebhookData(data));
        data = Fixtures.copy(base);
        data.put("signals", List.of());
        assertNotEquals(original, Identification.fromWebhookData(data));
        data = Fixtures.copy(base);
        data.put("detection_flags", Map.of());
        assertNotEquals(original, Identification.fromWebhookData(data));
        assertNotEquals(original.getSignals().get(0), original.getSignals().get(1));
        assertNotEquals(new Signal("a", 1, "x"), new Signal("a", 1, null));
        assertNotEquals(new Signal("a", 1, null), new Signal("a", 2, null));
        assertNotEquals(new IpInfo("192.0.2.1", "A"), new IpInfo("192.0.2.1", "B"));
        assertNotEquals(new IpInfo("192.0.2.1", "A"), new IpInfo("192.0.2.2", "A"));
    }
}
