package ai.shieldlabs;

import java.util.List;
import java.util.Map;

/**
 * Thrown for HTTP 400. The request was rejected as invalid; it is never retried, and
 * {@link IdentificationService#get(String, GetIdentificationOptions)} stops polling on it.
 */
public class BadRequestException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param body the raw response body, may be {@code null}
     * @param headers the response headers, may be {@code null}
     */
    public BadRequestException(String body, Map<String, List<String>> headers) {
        super(400, body, headers);
    }
}
