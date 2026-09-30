package ai.shieldlabs;

/**
 * Thrown by {@link Webhooks#constructEvent(byte[], String, String...)} when the signature is valid but
 * the body is not a webhook envelope: invalid JSON, not a JSON object, no {@code event_type}, or an
 * {@code identification.scored} event without a {@code data} object.
 */
public class WebhookParseException extends ShieldLabsException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a parse exception.
     *
     * @param message what is wrong with the body
     */
    public WebhookParseException(String message) {
        super(message);
    }

    /**
     * Creates a parse exception with a cause.
     *
     * @param message what is wrong with the body
     * @param cause the underlying JSON error
     */
    public WebhookParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
