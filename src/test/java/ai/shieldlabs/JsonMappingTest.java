package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonMappingTest {

    @Test
    void identificationSerializesWithWebhookNamesInOrder() {
        Identification id =
                Identification.fromHistoryRow(Fixtures.map(Fixtures.normalizationCase("history_a5b7c9d1").get("input")));
        Map<String, Object> json = Fixtures.serialized(id);
        assertEquals(
                List.of(
                        "request_id",
                        "visitor_id",
                        "device_id",
                        "session_id",
                        "cookie_id",
                        "user_hid",
                        "domain",
                        "public_ip",
                        "local_ip",
                        "connection_type",
                        "os",
                        "browser",
                        "device_type",
                        "traffic_source",
                        "risk_score",
                        "signals",
                        "detection_flags",
                        "observed_at",
                        "source"),
                new ArrayList<>(json.keySet()));
        assertEquals("2026-09-30T12:34:56.123Z", json.get("observed_at"));
        assertEquals("history", json.get("source"));
        List<Object> flagKeys = new ArrayList<>(Fixtures.map(json.get("detection_flags")).keySet());
        assertEquals(19, flagKeys.size());
        assertEquals("vpn", flagKeys.get(0));
        assertEquals("check_incomplete", flagKeys.get(18));
        assertEquals(
                List.of("channel", "referrer_domain", "landing_url", "click_id_type", "utm_source", "utm_medium",
                        "utm_campaign", "utm_content", "utm_term"),
                new ArrayList<>(Fixtures.map(json.get("traffic_source")).keySet()));
    }

    @Test
    void historyPageSerializes() {
        HistoryPage page =
                new HistoryPage(
                        List.of(Identification.fromWebhookData(
                                Fixtures.map(Fixtures.normalizationCase("webhook_scored").get("input")))),
                        37,
                        1);
        Map<String, Object> json = Fixtures.serialized(page);
        assertEquals(List.of("data", "total"), new ArrayList<>(json.keySet()));
        assertEquals(37, json.get("total"));
        assertEquals(1, Fixtures.list(json.get("data")).size());
    }

    @Test
    void enumsSerializeAsWireValues() {
        assertEquals("\"rate_limited\"", Fixtures.json(RiskBand.RATE_LIMITED));
        assertEquals("\"anti_detect_browser\"", Fixtures.json(DetectionFlag.ANTI_DETECT_BROWSER));
        assertEquals("\"no_device_signals\"", Fixtures.json(Evaluation.Reason.NO_DEVICE_SIGNALS));
        assertEquals("\"history\"", Fixtures.json(Identification.Source.HISTORY));
        Evaluation blocked = new Evaluation(false, Evaluation.Reason.BLOCKED_FLAG, RiskBand.SUSPICIOUS, DetectionFlag.TOR);
        assertEquals(
                "{\"ok\":false,\"reason\":\"blocked_flag\",\"band\":\"suspicious\",\"flag\":\"tor\"}",
                Fixtures.json(blocked));
    }
}
