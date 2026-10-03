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
        WireModels.HistoryRow rowWire = new WireModels.HistoryRow(row);
        Object leakSourceValue = WireValue.string(rowWire.webrtc_leak_source());
        String leakSource = leakSourceValue instanceof String ? Text.strip((String) leakSourceValue) : "";
        String localIp;
        String localCountry;
        if (!leakSource.isEmpty() && !"none".equals(leakSource)) {
            localIp = ip(WireValue.string(rowWire.webrtc_leak_ip()));
            localCountry = Json.orEmpty(WireValue.string(rowWire.webrtc_leak_country()));
        } else {
            localIp = ip(WireValue.string(rowWire.web_rtc_ip()));
            localCountry = Json.orEmpty(WireValue.string(rowWire.web_rtc_country()));
        }
        String publicIp = ip(WireValue.string(rowWire.ip()));

        List<Signal> signals = new ArrayList<>();
        boolean ipLeakDetail = false;
        for (Object entry : scoreDetails(WireValue.string(rowWire.score_details()))) {
            Map<?, ?> detail = Json.object(entry);
            if (detail == null) {
                continue;
            }
            WireModels.ScoreDetail detailWire = new WireModels.ScoreDetail(detail);
            String description = Json.orEmpty(WireValue.string(detailWire.Description()));
            if (description.startsWith(IP_LEAK_PREFIX)) {
                ipLeakDetail = true;
            }
            Integer weight = Json.intValue(detail.containsKey("Value") ? WireValue.integer(detailWire.Value()) : 0);
            if (weight == null || weight == 0) {
                continue;
            }
            signals.add(new Signal(signalSlug(description), weight, description));
        }

        boolean searchBot = Json.truthy(WireValue.bool(rowWire.is_search_bot()));
        EnumSet<DetectionFlag> flags = EnumSet.noneOf(DetectionFlag.class);
        for (DetectionFlag flag : DetectionFlag.values()) {
            boolean value;
            if (flag == DetectionFlag.BROWSER_VPN_PROXY) {
                value = ConnectionType.BROWSER_VPN_PROXY.equals(WireValue.string(rowWire.connection_type()));
            } else if (flag == DetectionFlag.IP_MISMATCH) {
                value = !searchBot
                        && (ipLeakDetail
                                || (!publicIp.isEmpty() && !localIp.isEmpty() && !publicIp.equals(localIp)));
            } else {
                value = Json.truthy(flag.historyValue(rowWire));
            }
            if (value) {
                flags.add(flag);
            }
        }

        String siteDomain = Json.orEmpty(WireValue.string(rowWire.site_domain()));
        TrafficSource traffic =
                new TrafficSource(
                        Json.orEmpty(WireValue.string(rowWire.traffic_channel())),
                        Json.orEmpty(WireValue.string(rowWire.referrer_domain())),
                        Json.orEmpty(WireValue.string(rowWire.entry_url())),
                        Json.orEmpty(WireValue.string(rowWire.click_id_type())),
                        Json.orEmpty(WireValue.string(rowWire.utm_source())),
                        Json.orEmpty(WireValue.string(rowWire.utm_medium())),
                        Json.orEmpty(WireValue.string(rowWire.utm_campaign())),
                        Json.orEmpty(WireValue.string(rowWire.utm_content())),
                        Json.orEmpty(WireValue.string(rowWire.utm_term())));
        return new Identification(
                Json.text(WireValue.string(rowWire.request_id())),
                Json.text(WireValue.string(rowWire.visitor_id())),
                Json.text(WireValue.string(rowWire.device_id())),
                Json.text(WireValue.string(rowWire.session_id())),
                Json.text(WireValue.string(rowWire.cookie_id())),
                userHid(WireValue.string(rowWire.user_hid())),
                siteDomain.isEmpty() ? Json.text(WireValue.string(rowWire.domain())) : siteDomain,
                new IpInfo(publicIp, Json.orEmpty(WireValue.string(rowWire.country()))),
                new IpInfo(localIp, localCountry),
                Json.text(WireValue.string(rowWire.connection_type())),
                Json.text(WireValue.string(rowWire.os())),
                Json.text(WireValue.string(rowWire.browser())),
                Json.text(WireValue.string(rowWire.device_type())),
                traffic,
                score(WireValue.integer(rowWire.score())),
                signals,
                new DetectionFlags(flags),
                Timestamps.parseHistoryTime(WireValue.string(rowWire.created_at())),
                Identification.Source.HISTORY,
                Json.freezeObject(row));
    }

    static Identification fromWebhookData(Map<?, ?> data) {
        WireModels.IdentificationScoredData dataWire = new WireModels.IdentificationScoredData(data);
        Map<?, ?> flagValues = Json.objectOrEmpty(WireValue.object(dataWire.detection_flags()));
        WireModels.DetectionFlags flagsWire = new WireModels.DetectionFlags(flagValues);
        EnumSet<DetectionFlag> flags = EnumSet.noneOf(DetectionFlag.class);
        for (DetectionFlag flag : DetectionFlag.values()) {
            if (Json.truthy(flag.webhookValue(flagsWire))) {
                flags.add(flag);
            }
        }
        Map<?, ?> ts = Json.objectOrEmpty(WireValue.object(dataWire.traffic_source()));
        WireModels.TrafficSource tsWire = new WireModels.TrafficSource(ts);
        TrafficSource traffic =
                new TrafficSource(
                        Json.orEmpty(WireValue.string(tsWire.channel())),
                        Json.orEmpty(WireValue.string(tsWire.referrer_domain())),
                        Json.orEmpty(WireValue.string(tsWire.landing_url())),
                        Json.orEmpty(WireValue.string(tsWire.click_id_type())),
                        Json.orEmpty(WireValue.string(tsWire.utm_source())),
                        Json.orEmpty(WireValue.string(tsWire.utm_medium())),
                        Json.orEmpty(WireValue.string(tsWire.utm_campaign())),
                        Json.orEmpty(WireValue.string(tsWire.utm_content())),
                        Json.orEmpty(WireValue.string(tsWire.utm_term())));
        List<Signal> signals = new ArrayList<>();
        Object signalValues = WireValue.array(dataWire.signals());
        if (signalValues instanceof List) {
            for (Object entry : (List<?>) signalValues) {
                Map<?, ?> signal = Json.object(entry);
                if (signal == null) {
                    continue;
                }
                WireModels.Signal signalWire = new WireModels.Signal(signal);
                Integer weight = Json.intValue(WireValue.integer(signalWire.weight()));
                signals.add(new Signal(Json.text(WireValue.string(signalWire.name())), weight == null ? 0 : weight, null));
            }
        }
        return new Identification(
                Json.text(WireValue.string(dataWire.request_id())),
                Json.text(WireValue.string(dataWire.visitor_id())),
                Json.text(WireValue.string(dataWire.device_id())),
                Json.text(WireValue.string(dataWire.session_id())),
                Json.text(WireValue.string(dataWire.cookie_id())),
                userHid(WireValue.string(dataWire.user_hid())),
                Json.text(WireValue.string(dataWire.domain())),
                ipInfo(WireValue.object(dataWire.public_ip())),
                ipInfo(WireValue.object(dataWire.local_ip())),
                Json.text(WireValue.string(dataWire.connection_type())),
                Json.text(WireValue.string(dataWire.os())),
                Json.text(WireValue.string(dataWire.browser())),
                Json.text(WireValue.string(dataWire.device_type())),
                traffic,
                score(WireValue.integer(dataWire.risk_score())),
                signals,
                new DetectionFlags(flags),
                Timestamps.parseRfc3339(WireValue.string(dataWire.observed_at())),
                Identification.Source.WEBHOOK,
                Json.freezeObject(data));
    }

    private static IpInfo ipInfo(Object value) {
        Map<?, ?> object = Json.objectOrEmpty(value);
        WireModels.IpInfo objectWire = new WireModels.IpInfo(object);
        return new IpInfo(ip(WireValue.string(objectWire.ip())), Json.orEmpty(WireValue.string(objectWire.country())));
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
