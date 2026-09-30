package ai.shieldlabs;

import java.util.List;
import java.util.Map;

/**
 * Thrown for HTTP 402: the account has no requests left. Never retried. History API reads and
 * Management API calls are free, so this status is not expected from them today; the class exists so
 * that a 402 is reported distinctly if a server starts sending it.
 */
public class QuotaExceededException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param body the raw response body, may be {@code null}
     * @param headers the response headers, may be {@code null}
     */
    public QuotaExceededException(String body, Map<String, List<String>> headers) {
        super(402, body, headers);
    }
}
