package ai.shieldlabs;

/**
 * Thrown when arguments are invalid. The SDK throws it before sending anything, so no HTTP request
 * is made for a call that fails validation.
 *
 * <p>The History API does not validate its inputs: an unknown lookup type returns the latest rows of
 * the whole domain unfiltered, and a malformed UUID or IP address returns a server error. The SDK
 * therefore checks lookup types, identifier formats, {@code limit} and {@code offset} on the client.
 */
public class ValidationException extends ShieldLabsException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a validation exception.
     *
     * @param message what is invalid and how to fix it
     */
    public ValidationException(String message) {
        super(message);
    }
}
