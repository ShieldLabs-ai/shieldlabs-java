package ai.shieldlabs;

/**
 * Thrown by {@link Webhooks#constructEvent(byte[], String, String...)} when the
 * {@code X-Shield-Signature} header is missing or malformed, when no usable signing secret was given,
 * or when the signature does not match the raw body. Answer such a request with HTTP 401 and do not
 * process it.
 */
public class SignatureVerificationException extends ShieldLabsException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a signature verification exception.
     *
     * @param message why verification failed
     */
    public SignatureVerificationException(String message) {
        super(message);
    }
}
