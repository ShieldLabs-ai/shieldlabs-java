package ai.shieldlabs;

/**
 * Thrown when no response arrived within the per-attempt timeout (10 seconds by default). GET
 * requests that time out are retried according to the client's {@code maxRetries} setting. While
 * {@link IdentificationService#get(String, GetIdentificationOptions)} waits for a verdict, a timeout
 * does not end the wait: the next poll follows on the schedule.
 */
public class ApiTimeoutException extends ShieldLabsException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a timeout exception.
     *
     * @param message the detail message
     * @param cause the underlying timeout exception, may be {@code null}
     */
    public ApiTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
