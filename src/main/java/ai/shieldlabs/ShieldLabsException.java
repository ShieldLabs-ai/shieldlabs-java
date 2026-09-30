package ai.shieldlabs;

/**
 * Base class of every exception thrown by the ShieldLabs SDK.
 *
 * <p>All SDK exceptions are unchecked. Catch this type to handle any SDK failure in one place, or
 * catch one of the subclasses for finer control:
 *
 * <ul>
 *   <li>{@link ApiException} and its subclasses: the server answered with an error status.
 *   <li>{@link ApiConnectionException}: the request could not be sent or the connection failed.
 *   <li>{@link ApiTimeoutException}: no response arrived within the configured timeout.
 *   <li>{@link SignatureVerificationException}: a webhook signature did not verify.
 *   <li>{@link WebhookParseException}: a verified webhook body could not be parsed.
 *   <li>{@link ValidationException}: invalid arguments, detected before any HTTP call.
 * </ul>
 */
public class ShieldLabsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public ShieldLabsException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause, may be {@code null}
     */
    public ShieldLabsException(String message, Throwable cause) {
        super(message, cause);
    }
}
