package ai.shieldlabs;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps error responses to exception classes and reads {@code Retry-After}. */
final class HttpErrors {
    private static final Pattern DELTA_SECONDS = Pattern.compile("\\d{1,9}(\\.\\d{1,9})?");

    private HttpErrors() {
    }

    static ApiException forStatus(int status, String body, Map<String, List<String>> headers) {
        switch (status) {
            case 400:
                return new BadRequestException(body, headers);
            case 401:
            case 403:
                return new AuthenticationException(status, body, headers);
            case 402:
                return new QuotaExceededException(body, headers);
            case 404:
                return new NotFoundException(body, headers);
            case 429:
                return new RateLimitException(body, headers);
            default:
                if (status >= 500 && status <= 599) {
                    return new ServerException(status, body, headers);
                }
                return new ApiException(status, body, headers);
        }
    }

    /** Reads {@code Retry-After} as delta-seconds or an HTTP date; {@code null} when absent or unusable. */
    static Duration retryAfter(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && "retry-after".equalsIgnoreCase(entry.getKey())) {
                List<String> values = entry.getValue();
                if (values != null && !values.isEmpty()) {
                    return parseRetryAfter(values.get(0), Instant.now());
                }
            }
        }
        return null;
    }

    static Duration parseRetryAfter(String value, Instant now) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        if (DELTA_SECONDS.matcher(text).matches()) {
            double seconds = Double.parseDouble(text);
            return Duration.ofMillis(Math.round(seconds * 1000.0));
        }
        try {
            Instant date = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(now, date);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
