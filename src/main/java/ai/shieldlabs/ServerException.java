package ai.shieldlabs;

import java.util.List;
import java.util.Map;

/**
 * Thrown for HTTP 5xx, including HTML error pages from edge proxies (502, 504) and the Management
 * API's 503 when it is busy. GET requests that fail this way are retried with backoff. While
 * {@link IdentificationService#get(String, GetIdentificationOptions)} waits for a verdict, a 5xx does
 * not end the wait: the next poll follows on the schedule.
 */
public class ServerException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param statusCode the 5xx status code
     * @param body the raw response body, may be {@code null}
     * @param headers the response headers, may be {@code null}
     */
    public ServerException(int statusCode, String body, Map<String, List<String>> headers) {
        super(statusCode, body, headers);
    }
}
