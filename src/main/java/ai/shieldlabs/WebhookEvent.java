package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Map;

/**
 * A verified webhook delivery, returned by {@link Webhooks#constructEvent(byte[], String, String...)}.
 *
 * <p>The concrete type tells you what arrived:
 *
 * <ul>
 *   <li>{@link IdentificationScoredEvent}: the verdict for one identification ({@code
 *       identification.scored}).
 *   <li>{@link WebhookPingEvent}: the endpoint check sent when you verify an endpoint in the analytics
 *       dashboard ({@code webhook.ping}).
 *   <li>{@link UnknownWebhookEvent}: an event type this SDK version does not know yet. Acknowledge it
 *       with a 2xx response and ignore it.
 * </ul>
 *
 * <pre>{@code
 * WebhookEvent event = Webhooks.constructEvent(body, signature, secret);
 * if (event instanceof IdentificationScoredEvent) {
 *     Identification identification = ((IdentificationScoredEvent) event).getIdentification();
 * }
 * }</pre>
 */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({"event_type", "schema_version", "created_at", "data"})
public abstract class WebhookEvent {
    /** Event type of a scored identification. */
    public static final String IDENTIFICATION_SCORED = "identification.scored";
    /** Event type of the endpoint check. */
    public static final String WEBHOOK_PING = "webhook.ping";
    /** The schema version this SDK was written for. Other values are accepted. */
    public static final String SCHEMA_VERSION = "2026-06-01";

    private final String type;
    private final String schemaVersion;
    private final Instant createdAt;
    private final Map<String, Object> raw;

    WebhookEvent(String type, String schemaVersion, Instant createdAt, Map<String, Object> raw) {
        this.type = type;
        this.schemaVersion = schemaVersion;
        this.createdAt = createdAt;
        this.raw = raw;
    }

    /**
     * Returns the event type ({@code event_type}).
     *
     * @return for example {@code "identification.scored"}
     */
    @JsonProperty("event_type")
    public String getType() {
        return type;
    }

    /**
     * Returns the schema version ({@code schema_version}).
     *
     * @return for example {@code "2026-06-01"}, or {@code null} when absent
     */
    @JsonProperty("schema_version")
    public String getSchemaVersion() {
        return schemaVersion;
    }

    /**
     * Returns when the event was created (UTC).
     *
     * @return the timestamp, or {@code null} when it is missing or cannot be parsed
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    @JsonProperty("created_at")
    private String createdAtJson() {
        return createdAt == null ? null : Timestamps.format(createdAt);
    }

    /**
     * Returns the whole parsed envelope.
     *
     * @return an unmodifiable map
     */
    public Map<String, Object> raw() {
        return raw;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{type=" + type + ", schemaVersion=" + schemaVersion + "}";
    }
}
