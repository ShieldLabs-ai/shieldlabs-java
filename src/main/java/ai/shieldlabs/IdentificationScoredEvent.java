package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * The {@code identification.scored} event: the verdict for one identification.
 *
 * <p>Today ShieldLabs sends one such event per identification and endpoint, with a one-second timeout
 * and no retries. Answer with a 2xx status within one second, and make your handler idempotent on
 * {@code getIdentification().getRequestId()}: a later server release adds retries that resend
 * identical bytes. Use the History API for guaranteed reads and for the latest state: a delivery can
 * fail, and a History row can be refined after the webhook was sent (the webhook is not sent again).
 */
public final class IdentificationScoredEvent extends WebhookEvent {
    private final Identification data;

    IdentificationScoredEvent(String schemaVersion, Instant createdAt, Identification data, Map<String, Object> raw) {
        super(IDENTIFICATION_SCORED, schemaVersion, createdAt, raw);
        this.data = data;
    }

    /**
     * Returns the identification carried by the event.
     *
     * @return the identification
     */
    @JsonProperty("data")
    public Identification getIdentification() {
        return data;
    }
}
