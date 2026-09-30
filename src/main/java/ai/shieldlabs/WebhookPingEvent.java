package ai.shieldlabs;

import java.time.Instant;
import java.util.Map;

/**
 * The {@code webhook.ping} event sent when you verify an endpoint in the analytics dashboard. It has
 * no data. Answer with a 2xx status to mark the endpoint active.
 */
public final class WebhookPingEvent extends WebhookEvent {
    WebhookPingEvent(String schemaVersion, Instant createdAt, Map<String, Object> raw) {
        super(WEBHOOK_PING, schemaVersion, createdAt, raw);
    }
}
