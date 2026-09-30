package ai.shieldlabs;

/**
 * Thrown when a request could not be completed at the network level: DNS failure, refused or reset
 * connection, TLS failure, or an interrupted call. GET requests that fail this way are retried
 * according to the client's {@code maxRetries} setting, except when the calling thread was
 * interrupted. While {@link IdentificationService#get(String, GetIdentificationOptions)} waits for a
 * verdict, a connection failure does not end the wait: the next poll follows on the schedule.
 */
public class ApiConnectionException extends ShieldLabsException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a connection exception.
     *
     * @param message the detail message
     * @param cause the underlying I/O exception, may be {@code null}
     */
    public ApiConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
