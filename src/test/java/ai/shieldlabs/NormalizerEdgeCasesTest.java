package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NormalizerEdgeCasesTest {

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    void scoreDetailsAreParsedDefensively() {
        assertTrue(Identification.fromHistoryRow(row("score_details", "not json")).getSignals().isEmpty());
        assertTrue(Identification.fromHistoryRow(row("score_details", "{\"Value\":10}")).getSignals().isEmpty());
        assertTrue(Identification.fromHistoryRow(row("score_details", "null")).getSignals().isEmpty());
        assertTrue(Identification.fromHistoryRow(row("score_details", "")).getSignals().isEmpty());
        assertTrue(Identification.fromHistoryRow(row("score_details", List.of())).getSignals().isEmpty());
        assertTrue(Identification.fromHistoryRow(row("score_details", 5)).getSignals().isEmpty());
        assertTrue(Identification.fromHistoryRow(row()).getSignals().isEmpty());

        String details =
                "[1, \"x\", {\"Value\":10.0,\"Description\":\"Is proxy\"}, {\"Value\":true,\"Description\":\"Is tor\"},"
                        + "{\"Value\":NaN,\"Description\":\"Is VPN\"}, {\"Description\":\"Is abuser\"},"
                        + "{\"Value\":99999999999,\"Description\":\"Huge\"}, {\"Value\":15,\"Description\":null},"
                        + "{\"Value\":-30,\"Description\":\"Stun passed (late arrival, corrected)\"}]";
        List<Signal> signals = Identification.fromHistoryRow(row("score_details", details)).getSignals();
        // Floats, booleans, NaN and missing values are skipped; a weight outside the int range cannot be
        // represented by Signal.getWeight() and is skipped too.
        assertEquals(2, signals.size());
        assertEquals("unknown", signals.get(0).getName());
        assertEquals("", signals.get(0).getDescription());
        assertEquals(15, signals.get(0).getWeight());
        assertEquals(SignalName.STUN_LATE_CORRECTION, signals.get(1).getName());
        assertEquals(-30, signals.get(1).getWeight());
    }

    @Test
    void informationalLeakDetailSetsIpMismatchUnlessSearchBot() {
        String details = "[{\"Value\":0,\"Description\":\"IP \u2260 leakIP (192.0.2.1 \u2260 198.51.100.2, source=shield)\"}]";
        Identification leak = Identification.fromHistoryRow(row("score_details", details));
        assertTrue(leak.getDetectionFlags().isIpMismatch());
        assertTrue(leak.getSignals().isEmpty(), "zero-weight details are not signals");
        Identification bot = Identification.fromHistoryRow(row("score_details", details, "is_search_bot", true));
        assertFalse(bot.getDetectionFlags().isIpMismatch());
        assertTrue(bot.getDetectionFlags().isSearchBot());
    }

    @Test
    void ipMismatchFromDifferentAddresses() {
        Identification same = Identification.fromHistoryRow(row("ip", "192.0.2.1", "web_rtc_ip", "192.0.2.1"));
        assertFalse(same.getDetectionFlags().isIpMismatch());
        Identification different = Identification.fromHistoryRow(row("ip", "192.0.2.1", "web_rtc_ip", "192.0.2.2"));
        assertTrue(different.getDetectionFlags().isIpMismatch());
        Identification missingLocal = Identification.fromHistoryRow(row("ip", "192.0.2.1", "web_rtc_ip", "0.0.0.0"));
        assertFalse(missingLocal.getDetectionFlags().isIpMismatch());
        assertEquals("", missingLocal.getLocalIp().getIp());
        Identification spaced = Identification.fromHistoryRow(row("ip", " 192.0.2.1 ", "web_rtc_ip", 7));
        assertEquals("192.0.2.1", spaced.getPublicIp().getIp());
        assertEquals("", spaced.getLocalIp().getIp());
    }

    @Test
    void leakSourceSelectsTheLocalAddress() {
        Map<String, Object> base =
                row(
                        "web_rtc_ip", "192.0.2.10",
                        "web_rtc_country", "Germany",
                        "webrtc_leak_ip", "198.51.100.20",
                        "webrtc_leak_country", "Spain");
        Map<String, Object> scanner = new LinkedHashMap<>(base);
        scanner.put("webrtc_leak_source", " scanner ");
        assertEquals("198.51.100.20", Identification.fromHistoryRow(scanner).getLocalIp().getIp());
        assertEquals("Spain", Identification.fromHistoryRow(scanner).getLocalIp().getCountry());
        Map<String, Object> none = new LinkedHashMap<>(base);
        none.put("webrtc_leak_source", "none");
        assertEquals("192.0.2.10", Identification.fromHistoryRow(none).getLocalIp().getIp());
        Map<String, Object> odd = new LinkedHashMap<>(base);
        odd.put("webrtc_leak_source", 1);
        assertEquals("Germany", Identification.fromHistoryRow(odd).getLocalIp().getCountry());
    }

    @Test
    void flagsFollowTruthiness() {
        Identification id =
                Identification.fromHistoryRow(
                        row(
                                "is_vpn", "false",
                                "is_tor", 0,
                                "is_proxy", 1,
                                "is_datacenter", "",
                                "is_abuser", List.of(1),
                                "is_incognito", null,
                                "is_antidetect", 0.0,
                                "is_js_disabled", Double.NaN,
                                "connection_type", "browser_vpn_proxy"));
        assertTrue(id.getDetectionFlags().isVpn(), "a non-empty string is true");
        assertFalse(id.getDetectionFlags().isTor());
        assertTrue(id.getDetectionFlags().isProxy());
        assertFalse(id.getDetectionFlags().isDatacenterIp());
        assertTrue(id.getDetectionFlags().isAbuser());
        assertFalse(id.getDetectionFlags().isIncognito());
        assertFalse(id.getDetectionFlags().isAntiDetectBrowser());
        assertTrue(id.getDetectionFlags().isJavascriptDisabled());
        assertTrue(id.getDetectionFlags().isBrowserVpnProxy(), "derived from connection_type");
        assertFalse(id.getDetectionFlags().isSuspiciousPaidClick(), "omitted means false");

        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("tor", 1);
        flags.put("vpn", "yes");
        flags.put("proxy", BigInteger.ZERO);
        flags.put("unknown_future_flag", true);
        Identification webhook = Identification.fromWebhookData(row("detection_flags", flags));
        assertTrue(webhook.getDetectionFlags().isTor());
        assertTrue(webhook.getDetectionFlags().isVpn());
        assertFalse(webhook.getDetectionFlags().isProxy());
        assertEquals(2, webhook.getDetectionFlags().active().size());
        assertTrue(Identification.fromWebhookData(row("detection_flags", List.of(1))).getDetectionFlags().active().isEmpty());
    }

    @Test
    void fieldDefaultsAndPlaceholders() {
        Identification id =
                Identification.fromHistoryRow(
                        row(
                                "request_id", null,
                                "user_hid", 42,
                                "domain", "shop.example.com",
                                "site_domain", "",
                                "score", "80",
                                "country", 0,
                                "os", 5,
                                "traffic_channel", null));
        assertEquals("", id.getRequestId());
        assertEquals("42", id.getUserHid());
        assertEquals("shop.example.com", id.getDomain(), "empty site_domain falls back to domain");
        assertEquals(0, id.getRiskScore(), "non-integer scores read as 0");
        assertEquals("", id.getPublicIp().getCountry());
        assertEquals("5", id.getOs());
        assertEquals("", id.getTrafficSource().getChannel());
        assertNull(id.getObservedAt());

        assertEquals("fail", Identification.fromHistoryRow(row("user_hid", "fail")).getUserHid());
        assertEquals("anonymous", Identification.fromWebhookData(row("user_hid", "anonymous")).getUserHid());
        assertNull(Identification.fromWebhookData(row("user_hid", "")).getUserHid());
        assertNull(Identification.fromWebhookData(row("user_hid", List.of())).getUserHid());
        assertEquals("false", Identification.fromWebhookData(row("user_hid", false)).getUserHid());
        assertEquals(0, Identification.fromWebhookData(row("risk_score", 1.5)).getRiskScore());
    }

    @Test
    void webhookSignalsAreKeptInOrderIncludingOddEntries() {
        List<Object> signals = new ArrayList<>();
        signals.add(Map.of("name", "stun_not_checked", "weight", 30));
        signals.add("not an object");
        signals.add(Map.of("name", "stun_late_correction", "weight", -30));
        signals.add(Map.of("weight", "10"));
        Identification id = Identification.fromWebhookData(row("signals", signals, "public_ip", "203.0.113.1"));
        assertEquals(3, id.getSignals().size());
        assertEquals(-30, id.getSignals().get(1).getWeight());
        assertEquals("", id.getSignals().get(2).getName());
        assertEquals(0, id.getSignals().get(2).getWeight());
        assertEquals("", id.getPublicIp().getIp(), "a non-object public_ip is empty");
        assertTrue(Identification.fromWebhookData(row("signals", Map.of("a", 1))).getSignals().isEmpty());
        assertEquals(
                "",
                Identification.fromWebhookData(row("public_ip", Map.of("ip", "0.0.0.0", "country", "")))
                        .getPublicIp()
                        .getIp(),
                "0.0.0.0 on the webhook is treated as no address");
    }

    @Test
    void rawIsAnUnmodifiableDeepCopy() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("ip", "203.0.113.5");
        Map<String, Object> input = row("public_ip", nested, "signals", new ArrayList<>(Arrays.asList(1, 2)));
        Identification id = Identification.fromWebhookData(input);
        nested.put("ip", "changed");
        assertEquals("203.0.113.5", Fixtures.map(id.raw().get("public_ip")).get("ip"));
        assertThrows(UnsupportedOperationException.class, () -> id.raw().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> Fixtures.list(id.raw().get("signals")).add(3));
        assertThrows(UnsupportedOperationException.class, () -> id.getSignals().clear());
        assertThrows(ValidationException.class, () -> Identification.fromWebhookData(null));
        assertThrows(ValidationException.class, () -> Identification.fromHistoryRow(null));
    }

    @Test
    void valueSemantics() {
        Map<String, Object> input = Fixtures.map(Fixtures.normalizationCase("webhook_scored").get("input"));
        Identification a = Identification.fromWebhookData(input);
        Identification b = Identification.fromWebhookData(Fixtures.copy(input));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a.getSignals().get(0), b.getSignals().get(0));
        assertEquals(a.getSignals().get(0).hashCode(), b.getSignals().get(0).hashCode());
        assertEquals(a.getPublicIp(), b.getPublicIp());
        assertEquals(a.getPublicIp().hashCode(), b.getPublicIp().hashCode());
        assertEquals(a.getTrafficSource(), b.getTrafficSource());
        assertEquals(a.getTrafficSource().hashCode(), b.getTrafficSource().hashCode());
        assertEquals(a.getDetectionFlags(), b.getDetectionFlags());
        assertEquals(a.getDetectionFlags().hashCode(), b.getDetectionFlags().hashCode());
        assertTrue(a.toString().contains("02f1d973-84db-4156-a7f7-e799e6bf389b"));
        assertTrue(a.toString().contains("band=dangerous"));
        assertTrue(a.getSignals().get(0).toString().contains("proxy"));
        assertTrue(a.getPublicIp().toString().contains("Netherlands"));
        assertTrue(a.getTrafficSource().toString().contains("Google Ads"));
        assertTrue(a.getDetectionFlags().toString().contains("proxy"));
        assertEquals("203.0.113.9", new IpInfo("203.0.113.9", "").toString());

        Identification history =
                Identification.fromHistoryRow(Fixtures.map(Fixtures.normalizationCase("history_02f1d973").get("input")));
        assertNotEquals(a, history, "different sources are different identifications");
        assertNotEquals(a, null);
        assertNotEquals(a, "x");
        assertEquals(a, a);
        assertNotEquals(a.getSignals().get(0), "x");
        assertNotEquals(a.getPublicIp(), "x");
        assertNotEquals(a.getTrafficSource(), "x");
        assertNotEquals(a.getDetectionFlags(), "x");
        assertEquals(a.getSignals().get(0), a.getSignals().get(0));
        assertEquals(a.getPublicIp(), a.getPublicIp());
        assertEquals(a.getTrafficSource(), a.getTrafficSource());
        assertEquals(a.getDetectionFlags(), a.getDetectionFlags());
        Map<String, Object> other = Fixtures.copy(input);
        other.put("risk_score", 85);
        assertNotEquals(a, Identification.fromWebhookData(other));
        assertFalse(a.getDetectionFlags().get(null));
    }

    @Test
    void enumsExposeWireValues() {
        for (DetectionFlag flag : DetectionFlag.values()) {
            assertEquals(flag, DetectionFlag.fromValue(flag.getValue()));
            assertEquals(flag.getValue(), flag.toString());
        }
        assertThrows(ValidationException.class, () -> DetectionFlag.fromValue("is_vpn"));
        assertEquals("webhook", Identification.Source.WEBHOOK.toString());
        assertEquals(19, DetectionFlag.values().length);
    }
}
