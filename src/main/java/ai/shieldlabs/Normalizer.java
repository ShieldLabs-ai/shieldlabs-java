package ai.shieldlabs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds {@link Identification} objects from History API rows and webhook {@code data} objects, with
 * the field mapping, signal-name function and tolerant handling of missing or oddly typed values that
 * every ShieldLabs server SDK applies; the shared test fixtures pin the output.
 */
final class Normalizer {
    private static final String IP_LEAK_PREFIX = "IP \u2260 leakIP";
    private static final String STICKY_PREFIX = "Sticky verdict: ";

    private static final Map<String, String> EXACT_SLUGS;
    private static final String[][] PREFIX_SLUGS = {
        {"Antidetect browser", "antidetect_browser"},
        {"Os_mismatch", "os_mismatch"},
        {"OS mismatch2", "os_mismatch2"},
        {"TCP handshake", "tcp_handshake_v2"},
        {"Latency test", "ws_tcp_latency"},
        {"JavaScript disabled", "javascript_disabled"},
    };

    static {
        Map<String, String> exact = new LinkedHashMap<>();
        exact.put("Is tor", "tor");
        exact.put("Is VPN", "vpn");
        exact.put("Is privacy relay", "privacy_relay");
        exact.put("Is proxy", "proxy");
        exact.put("Is datacenter", "datacenter_ip");
        exact.put("Is abuser", "abuser");
        exact.put("Stun is not checked", "stun_not_checked");
        exact.put("Stun passed (late arrival, corrected)", "stun_late_correction");
        exact.put("UA OS is not detected", "os_not_detected");
        exact.put("Network OS is not detected", "os_not_detected");
        exact.put("Browser timezone \u2260 IP-timezone", "timezone_mismatch");
        exact.put("Browser VPN/Proxy", "browser_vpn_proxy");
        exact.put("Browser Automation", "browser_automation");
        exact.put("Port scan routed via proxy (antidetect browser pattern)", "proxy_routed_antidetect");
        exact.put("User has been banned 1H, to many requests", "rate_limited");
        EXACT_SLUGS = Collections.unmodifiableMap(exact);
    }

    private Normalizer() {
    }

    /** Signal name for a History {@code score_details} description. */
    static String signalSlug(String description) {
        String exact = EXACT_SLUGS.get(description);
        if (exact != null) {
            return exact;
        }
        for (String[] prefix : PREFIX_SLUGS) {
            if (description.startsWith(prefix[0])) {
                return prefix[1];
            }
        }
        if (description.startsWith(STICKY_PREFIX)) {
            int idx = description.indexOf(':');
            String rest =
                    idx >= 0 && idx + 1 < description.length()
                            ? Text.strip(description.substring(idx + 1))
                            : description;
            return fallbackSlug(rest);
        }
        return fallbackSlug(description);
    }

    /**
     * Fallback slug: text before the first "(", lowercased letters and digits, spaces, "-" and "/"
     * collapsed to "_", the not-equal sign spelled "_neq_", everything else dropped.
     */
    static String fallbackSlug(String description) {
        String text = Text.strip(description);
        int paren = text.indexOf('(');
        if (paren >= 0) {
            text = Text.strip(text.substring(0, paren));
        }
        StringBuilder out = new StringBuilder();
        boolean prevSep = false;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == ' ' || cp == '-' || cp == '/') {
                if (!prevSep && out.length() > 0) {
                    out.append('_');
                    prevSep = true;
                }
            } else if (cp == 0x2260) {
                out.append("_neq_");
                prevSep = false;
            } else if (Character.isLetterOrDigit(cp)) {
                out.append(new String(Character.toChars(cp)).toLowerCase(Locale.ROOT));
                prevSep = false;
            }
        }
        int start = 0;
        int end = out.length();
        while (start < end && out.charAt(start) == '_') {
            start++;
        }
        while (end > start && out.charAt(end - 1) == '_') {
            end--;
        }
        String slug = out.substring(start, end);
        return slug.isEmpty() ? "unknown" : slug;
    }

    /** IP sentinel handling: {@code ""} and {@code "0.0.0.0"} both mean "no address". */
    static String ip(Object value) {
        String text = value instanceof String ? Text.strip((String) value) : "";
        return text.isEmpty() || "0.0.0.0".equals(text) ? "" : text;
    }

    static Identification fromHistoryRow(Map<?, ?> row) {
        Object leakSourceValue = row.get("webrtc_leak_source");
        String leakSource = leakSourceValue instanceof String ? Text.strip((String) leakSourceValue) : "";
        String localIp;
        String localCountry;
        if (!leakSource.isEmpty() && !"none".equals(leakSource)) {
            localIp = ip(row.get("webrtc_leak_ip"));
            localCountry = Json.orEmpty(row.get("webrtc_leak_country"));
        } else {
            localIp = ip(row.get("web_rtc_ip"));
            localCountry = Json.orEmpty(row.get("web_rtc_country"));
        }
        String publicIp = ip(row.get("ip"));

        List<Signal> signals = new ArrayList<>();
        boolean ipLeakDetail = false;
        for (Object entry : scoreDetails(row.get("score_details"))) {
            Map<?, ?> detail = Json.object(entry);
            if (detail == null) {
                continue;
            }
            String description = Json.orEmpty(detail.get("Description"));
            if (description.startsWith(IP_LEAK_PREFIX)) {
                ipLeakDetail = true;
            }
            Integer weight = Json.intValue(detail.containsKey("Value") ? detail.get("Value") : 0);
            if (weight == null || weight == 0) {
                continue;
            }
            signals.add(new Signal(signalSlug(description), weight, description));
        }

        boolean searchBot = Json.truthy(row.get("is_search_bot"));
        EnumSet<DetectionFlag> flags = EnumSet.noneOf(DetectionFlag.class);
        for (DetectionFlag flag : DetectionFlag.values()) {
            boolean value;
            if (flag == DetectionFlag.BROWSER_VPN_PROXY) {
                value = ConnectionType.BROWSER_VPN_PROXY.equals(row.get("connection_type"));
            } else if (flag == DetectionFlag.IP_MISMATCH) {
                value = !searchBot
                        && (ipLeakDetail
                                || (!publicIp.isEmpty() && !localIp.isEmpty() && !publicIp.equals(localIp)));
            } else {
                value = Json.truthy(row.get(flag.historyKey()));
            }
            if (value) {
                flags.add(flag);
            }
        }

        String siteDomain = Json.orEmpty(row.get("site_domain"));
        TrafficSource traffic =
                new TrafficSource(
                        Json.orEmpty(row.get("traffic_channel")),
                        Json.orEmpty(row.get("referrer_domain")),
                        Json.orEmpty(row.get("entry_url")),
                        Json.orEmpty(row.get("click_id_type")),
                        Json.orEmpty(row.get("utm_source")),
                        Json.orEmpty(row.get("utm_medium")),
                        Json.orEmpty(row.get("utm_campaign")),
                        Json.orEmpty(row.get("utm_content")),
                        Json.orEmpty(row.get("utm_term")));
        return new Identification(
                Json.text(row.get("request_id")),
                Json.text(row.get("visitor_id")),
                Json.text(row.get("device_id")),
                Json.text(row.get("session_id")),
                Json.text(row.get("cookie_id")),
                userHid(row.get("user_hid")),
                siteDomain.isEmpty() ? Json.text(row.get("domain")) : siteDomain,
                new IpInfo(publicIp, Json.orEmpty(row.get("country"))),
                new IpInfo(localIp, localCountry),
                Json.text(row.get("connection_type")),
                Json.text(row.get("os")),
                Json.text(row.get("browser")),
                Json.text(row.get("device_type")),
                traffic,
                score(row.get("score")),
                signals,
                new DetectionFlags(flags),
                Timestamps.parseHistoryTime(row.get("created_at")),
                Identification.Source.HISTORY,
                Json.freezeObject(row));
    }

    static Identification fromWebhookData(Map<?, ?> data) {
        Map<?, ?> flagValues = Json.objectOrEmpty(data.get("detection_flags"));
        EnumSet<DetectionFlag> flags = EnumSet.noneOf(DetectionFlag.class);
        for (DetectionFlag flag : DetectionFlag.values()) {
            if (Json.truthy(flagValues.get(flag.getValue()))) {
                flags.add(flag);
            }
        }
        Map<?, ?> ts = Json.objectOrEmpty(data.get("traffic_source"));
        TrafficSource traffic =
                new TrafficSource(
                        Json.orEmpty(ts.get("channel")),
                        Json.orEmpty(ts.get("referrer_domain")),
                        Json.orEmpty(ts.get("landing_url")),
                        Json.orEmpty(ts.get("click_id_type")),
                        Json.orEmpty(ts.get("utm_source")),
                        Json.orEmpty(ts.get("utm_medium")),
                        Json.orEmpty(ts.get("utm_campaign")),
                        Json.orEmpty(ts.get("utm_content")),
                        Json.orEmpty(ts.get("utm_term")));
        List<Signal> signals = new ArrayList<>();
        Object signalValues = data.get("signals");
        if (signalValues instanceof List) {
            for (Object entry : (List<?>) signalValues) {
                Map<?, ?> signal = Json.object(entry);
                if (signal == null) {
                    continue;
                }
                Integer weight = Json.intValue(signal.get("weight"));
                signals.add(new Signal(Json.text(signal.get("name")), weight == null ? 0 : weight, null));
            }
        }
        return new Identification(
                Json.text(data.get("request_id")),
                Json.text(data.get("visitor_id")),
                Json.text(data.get("device_id")),
                Json.text(data.get("session_id")),
                Json.text(data.get("cookie_id")),
                userHid(data.get("user_hid")),
                Json.text(data.get("domain")),
                ipInfo(data.get("public_ip")),
                ipInfo(data.get("local_ip")),
                Json.text(data.get("connection_type")),
                Json.text(data.get("os")),
                Json.text(data.get("browser")),
                Json.text(data.get("device_type")),
                traffic,
                score(data.get("risk_score")),
                signals,
                new DetectionFlags(flags),
                Timestamps.parseRfc3339(data.get("observed_at")),
                Identification.Source.WEBHOOK,
                Json.freezeObject(data));
    }

    private static IpInfo ipInfo(Object value) {
        Map<?, ?> object = Json.objectOrEmpty(value);
        return new IpInfo(ip(object.get("ip")), Json.orEmpty(object.get("country")));
    }

    /** {@code ""} becomes {@code null}; every other value, including placeholders, is kept. */
    private static String userHid(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return null;
    }

    private static int score(Object value) {
        Integer score = Json.intValue(value);
        return score == null ? 0 : score;
    }

    /** Parses the JSON-encoded {@code score_details} string; anything unusable yields an empty list. */
    private static List<?> scoreDetails(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) {
            return Collections.emptyList();
        }
        try {
            Object parsed = Json.parse((String) value);
            return parsed instanceof List ? (List<?>) parsed : Collections.emptyList();
        } catch (IOException | RuntimeException e) {
            return Collections.emptyList();
        }
    }
}
