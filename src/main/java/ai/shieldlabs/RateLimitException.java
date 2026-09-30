package ai.shieldlabs;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thrown for HTTP 429.
 *
 * <p>The History API allows about 15 requests per second per domain (all callers together) and has
 * no ban, so the History client retries a 429 after {@code Retry-After} as sent (at most 10 seconds),
 * or after at least one second when the response has none (the limit counts requests per one-second
 * window). While {@link IdentificationService#get(String, GetIdentificationOptions)} waits for a
 * verdict, the next poll after a 429 waits at least one second, even with {@code Retry-After: 0}. The
 * Management API allows about 15 requests per minute per caller IP and then blocks that IP for 10
 * minutes, so the Management client never retries a 429: retrying would only keep the block in place.
 */
public class RateLimitException extends ApiException {
    private static final long serialVersionUID = 1L;

    /** The delay from {@code Retry-After}, or {@code null}. */
    private final Duration retryAfter;

    /**
     * Creates the exception, reading {@code Retry-After} from the headers.
     *
     * @param body the raw response body, may be {@code null}
     * @param headers the response headers, may be {@code null}
     */
    public RateLimitException(String body, Map<String, List<String>> headers) {
        this(body, headers, HttpErrors.retryAfter(headers));
    }

    /**
     * Creates the exception with an explicit retry delay.
     *
     * @param body the raw response body, may be {@code null}
     * @param headers the response headers, may be {@code null}
     * @param retryAfter the delay requested by the server, or {@code null} when none was given
     */
    public RateLimitException(String body, Map<String, List<String>> headers, Duration retryAfter) {
        super(429, body, headers);
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the delay the server asked for in its {@code Retry-After} header.
     *
     * @return the delay, or empty when the response had no usable {@code Retry-After} header
     */
    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
