package ai.shieldlabs;

import java.time.Instant;
import java.util.Map;

/**
 * An event whose {@code event_type} this SDK version does not know. The signature was verified; the
 * body is available through {@link #raw()}. Acknowledge it with a 2xx status.
 */
public final class UnknownWebhookEvent extends WebhookEvent {
    UnknownWebhookEvent(String type, String schemaVersion, Instant createdAt, Map<String, Object> raw) {
        super(type, schemaVersion, createdAt, raw);
    }
}
