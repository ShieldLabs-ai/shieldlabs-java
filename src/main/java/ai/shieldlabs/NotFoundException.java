package ai.shieldlabs;

import java.util.List;
import java.util.Map;

/**
 * Thrown for HTTP 404. The ShieldLabs APIs answer 404 only for unknown paths, which usually means a
 * wrong base URL. A request ID that has no identification yet is not a 404: the History API returns an
 * empty page and {@link IdentificationService#get(String, GetIdentificationOptions)} returns an empty
 * {@code Optional}. Never retried, and polling stops on it.
 */
public class NotFoundException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param body the raw response body, may be {@code null}
     * @param headers the response headers, may be {@code null}
     */
    public NotFoundException(String body, Map<String, List<String>> headers) {
        super(404, body, headers);
    }
}
