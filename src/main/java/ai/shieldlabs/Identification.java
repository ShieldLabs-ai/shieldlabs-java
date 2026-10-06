package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One identification: one run of the ShieldLabs agent in one browser, with its verdict.
 *
 * <p>The same model is produced from a History API row and from the {@code data} object of an
 * {@code identification.scored} webhook, although the two use different field names on the wire.
 * Property names follow the webhook contract: serialized with Jackson, an instance produces
 * {@code request_id}, {@code risk_score}, {@code detection_flags} and so on. The original JSON object
 * stays available through {@link #raw()}.
 *
 * <p>Branch on {@link #getRiskScore()} (through {@link Risk#band(int)}) and on
 * {@link #getDetectionFlags()}. Signal names are for display and logging. A missing identification
 * means "unverified", never "clean". Instances are immutable and thread-safe.
 */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({
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
    "source"
})
public final class Identification {
    /** Where an {@link Identification} was read from. */
    public enum Source {
        /** From the {@code data} object of an {@code identification.scored} webhook. */
        WEBHOOK("webhook"),
        /** From a History API row. */
        HISTORY("history");

        private final String value;

        Source(String value) {
            this.value = value;
        }

        /**
         * Returns the lowercase name.
         *
         * @return {@code "webhook"} or {@code "history"}
         */
        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private final String requestId;
    private final String visitorId;
    private final String deviceId;
    private final String sessionId;
    private final String cookieId;
    private final String userHid;
    private final String domain;
    private final IpInfo publicIp;
    private final IpInfo localIp;
    private final String connectionType;
    private final String os;
    private final String browser;
    private final String deviceType;
    private final TrafficSource trafficSource;
    private final int riskScore;
    private final List<Signal> signals;
    private final DetectionFlags detectionFlags;
    private final Instant observedAt;
    private final Source source;
    private final Map<String, Object> raw;

    Identification(
            String requestId,
            String visitorId,
            String deviceId,
            String sessionId,
            String cookieId,
            String userHid,
            String domain,
            IpInfo publicIp,
            IpInfo localIp,
            String connectionType,
            String os,
            String browser,
            String deviceType,
            TrafficSource trafficSource,
            int riskScore,
            List<Signal> signals,
            DetectionFlags detectionFlags,
            Instant observedAt,
            Source source,
            Map<String, Object> raw) {
        this.requestId = requestId;
        this.visitorId = visitorId;
        this.deviceId = deviceId;
        this.sessionId = sessionId;
        this.cookieId = cookieId;
        this.userHid = userHid;
        this.domain = domain;
        this.publicIp = publicIp;
        this.localIp = localIp;
        this.connectionType = connectionType;
        this.os = os;
        this.browser = browser;
        this.deviceType = deviceType;
        this.trafficSource = trafficSource;
        this.riskScore = riskScore;
        this.signals = Collections.unmodifiableList(signals);
        this.detectionFlags = detectionFlags;
        this.observedAt = observedAt;
        this.source = source;
        this.raw = raw;
    }

    /**
     * Normalizes one History API row (an element of {@code data} in the History response).
     *
     * @param row the row as parsed JSON (for example {@code Map<String, Object>} from Jackson)
     * @return the identification
     * @throws ValidationException when {@code row} is {@code null}
     */
    public static Identification fromHistoryRow(Map<String, ?> row) {
        if (row == null) {
            throw new ValidationException("row must not be null");
        }
        return Normalizer.fromHistoryRow(row);
    }

    /**
     * Normalizes the {@code data} object of an {@code identification.scored} webhook. Prefer
     * {@link Webhooks#constructEvent(byte[], String, String...)}, which also verifies the signature.
     *
     * @param data the {@code data} object as parsed JSON
     * @return the identification
     * @throws ValidationException when {@code data} is {@code null}
     */
    public static Identification fromWebhookData(Map<String, ?> data) {
        if (data == null) {
            throw new ValidationException("data must not be null");
        }
        return Normalizer.fromWebhookData(data);
    }

    /**
     * Returns the request ID: the UUID the browser received for this identification.
     *
     * @return the request ID
     */
    @JsonProperty("request_id")
    public String getRequestId() {
        return requestId;
    }

    /**
     * Returns the visitor ID, a server-side identifier that stays with the device.
     *
     * @return the visitor ID (a UUID, possibly the nil UUID)
     */
    @JsonProperty("visitor_id")
    public String getVisitorId() {
        return visitorId;
    }

    /**
     * Returns the device ID. It survives cleared cookies and private windows. The nil UUID
     * {@code 00000000-0000-0000-0000-000000000000} means no usable device signals.
     *
     * @return the device ID
     */
    @JsonProperty("device_id")
    public String getDeviceId() {
        return deviceId;
    }

    /**
     * Returns the session ID: one visit on one origin.
     *
     * @return the session ID (a UUID, possibly the nil UUID)
     */
    @JsonProperty("session_id")
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Returns the cookie ID kept by the agent in the browser.
     *
     * @return the cookie ID (a UUID, possibly the nil UUID)
     */
    @JsonProperty("cookie_id")
    public String getCookieId() {
        return cookieId;
    }

    /**
     * Returns the User HID passed to the agent. Anonymous checks carry {@code "anonymous"}; other
     * placeholder values ({@code "fail"}, {@code "-1"}, {@code "unknown"}) are kept as strings.
     *
     * @return the User HID, or {@code null} when it is empty
     */
    @JsonProperty("user_hid")
    public String getUserHid() {
        return userHid;
    }

    /**
     * Returns the registered site domain the identification belongs to.
     *
     * @return the domain
     */
    @JsonProperty("domain")
    public String getDomain() {
        return domain;
    }

    /**
     * Returns the public IP address of the request and its country.
     *
     * @return the public IP information
     */
    @JsonProperty("public_ip")
    public IpInfo getPublicIp() {
        return publicIp;
    }

    /**
     * Returns the local network IP address observed by the network check, and its country.
     *
     * @return the local IP information
     */
    @JsonProperty("local_ip")
    public IpInfo getLocalIp() {
        return localIp;
    }

    /**
     * Returns the connection type. Known values are listed in {@link ConnectionType}; unknown values
     * are kept as is.
     *
     * @return the connection type, for example {@code "direct"}
     */
    @JsonProperty("connection_type")
    public String getConnectionType() {
        return connectionType;
    }

    /**
     * Returns the operating system, for example {@code "Windows"}.
     *
     * @return the operating system
     */
    @JsonProperty("os")
    public String getOs() {
        return os;
    }

    /**
     * Returns the browser, for example {@code "Chrome"}.
     *
     * @return the browser
     */
    @JsonProperty("browser")
    public String getBrowser() {
        return browser;
    }

    /**
     * Returns the device type: {@code "desktop"}, {@code "mobile"}, {@code "tablet"} or
     * {@code "unknown"}.
     *
     * @return the device type
     */
    @JsonProperty("device_type")
    public String getDeviceType() {
        return deviceType;
    }

    /**
     * Returns where the visit came from.
     *
     * @return the traffic source
     */
    @JsonProperty("traffic_source")
    public TrafficSource getTrafficSource() {
        return trafficSource;
    }

    /**
     * Returns the Risk Score: an integer from 0 to 100, or 999 as the rate-limit marker (see
     * {@link Risk#isRateLimited(int)}).
     *
     * @return the Risk Score
     */
    @JsonProperty("risk_score")
    public int getRiskScore() {
        return riskScore;
    }

    /**
     * Returns the weighted risk signals behind the score, in server order. Names can repeat and
     * weights can be negative.
     *
     * @return an unmodifiable list, possibly empty
     */
    @JsonProperty("signals")
    public List<Signal> getSignals() {
        return signals;
    }

    /**
     * Returns the 19 detection flags.
     *
     * @return the flags
     */
    @JsonProperty("detection_flags")
    public DetectionFlags getDetectionFlags() {
        return detectionFlags;
    }

    /**
     * Returns when the identification was observed (UTC): the webhook {@code observed_at} or the
     * History row {@code created_at}.
     *
     * @return the timestamp, or {@code null} when the source value is missing or cannot be parsed
     */
    public Instant getObservedAt() {
        return observedAt;
    }

    @JsonProperty("observed_at")
    private String observedAtJson() {
        return observedAt == null ? null : Timestamps.format(observedAt);
    }

    /**
     * Returns where this identification was read from.
     *
     * @return {@link Source#WEBHOOK} or {@link Source#HISTORY}
     */
    @JsonProperty("source")
    public Source getSource() {
        return source;
    }

    /**
     * Returns the original JSON object: the webhook {@code data} object or the History row, including
     * fields the model does not expose (for example the row version {@code ver}).
     *
     * @return an unmodifiable map
     */
    @JsonProperty("client_identity")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public ClientIdentity getClientIdentity() {
        return ClientIdentity.fromValue(raw.get("client_identity"));
    }

    public Map<String, Object> raw() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Identification)) {
            return false;
        }
        Identification other = (Identification) o;
        return riskScore == other.riskScore
                && requestId.equals(other.requestId)
                && visitorId.equals(other.visitorId)
                && deviceId.equals(other.deviceId)
                && sessionId.equals(other.sessionId)
                && cookieId.equals(other.cookieId)
                && Objects.equals(userHid, other.userHid)
                && domain.equals(other.domain)
                && publicIp.equals(other.publicIp)
                && localIp.equals(other.localIp)
                && connectionType.equals(other.connectionType)
                && os.equals(other.os)
                && browser.equals(other.browser)
                && deviceType.equals(other.deviceType)
                && trafficSource.equals(other.trafficSource)
                && signals.equals(other.signals)
                && detectionFlags.equals(other.detectionFlags)
                && Objects.equals(observedAt, other.observedAt)
                && source == other.source;
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, deviceId, riskScore, observedAt, source);
    }

    @Override
    public String toString() {
        String observed = observedAt == null ? "null" : Timestamps.format(observedAt);
        return "Identification{requestId=" + requestId + ", riskScore=" + riskScore + ", band="
                + Risk.band(riskScore) + ", observedAt=" + observed + ", source=" + source + "}";
    }
}
