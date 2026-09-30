package ai.shieldlabs;

import java.util.List;
import java.util.Map;

/**
 * Thrown for HTTP 401 and 403: the key is missing, wrong, revoked, or the domain is disabled. Check
 * that the History API client uses a Private API Key ({@code sec_...}) and that the Management API
 * client uses the Secret Key together with the exact registered domain. Never retried, and
 * {@link IdentificationService#get(String, GetIdentificationOptions)} stops polling on it.
 */
public class AuthenticationException extends ApiException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param statusCode 401 or 403
     * @param body the raw response body, may be {@code null}
     * @param headers the response headers, may be {@code null}
     */
    public AuthenticationException(int statusCode, String body, Map<String, List<String>> headers) {
        super(statusCode, body, headers);
    }
}
