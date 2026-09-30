package ai.shieldlabs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Thrown when a ShieldLabs server answered with an error status.
 *
 * <p>Error bodies are not uniform: they can be empty, the JSON literal {@code null}, a bare JSON
 * string, a JSON object such as {@code {"error":"too many requests"}} (sometimes sent as
 * {@code text/plain}), plain text or an HTML page from an edge proxy. This class parses them
 * defensively and never fails while doing so: {@link #getBody()} always holds the raw text,
 * {@link #getParsedBody()} the parsed JSON value when there is one, and {@link #getErrorMessage()}
 * the server's short message when one can be found.
 *
 * <p>Subclasses identify the common cases: {@link BadRequestException} (400),
 * {@link AuthenticationException} (401, 403), {@link QuotaExceededException} (402),
 * {@link NotFoundException} (404), {@link RateLimitException} (429) and {@link ServerException} (5xx).
 * Other statuses are reported as a plain {@code ApiException}.
 */
public class ApiException extends ShieldLabsException {
    private static final long serialVersionUID = 1L;
    private static final int MAX_MESSAGE_LENGTH = 200;

    /** The HTTP status code. */
    private final int statusCode;
    /** The raw response body. */
    private final String body;
    /** The server's short error message, or {@code null}. */
    private final String errorMessage;
    private final transient Object parsedBody;
    private final transient Map<String, List<String>> headers;

    /**
     * Creates an exception for an error response.
     *
     * @param statusCode the HTTP status code
     * @param body the raw response body, may be {@code null}
     */
    public ApiException(int statusCode, String body) {
        this(null, statusCode, body, null);
    }

    /**
     * Creates an exception for an error response.
     *
     * @param statusCode the HTTP status code
     * @param body the raw response body, may be {@code null}
     * @param headers the response headers, may be {@code null}
     */
    public ApiException(int statusCode, String body, Map<String, List<String>> headers) {
        this(null, statusCode, body, headers);
    }

    /**
     * Creates an exception with an explicit message.
     *
     * @param message the detail message, or {@code null} to derive one from the status and body
     * @param statusCode the HTTP status code
     * @param body the raw response body, may be {@code null}
     * @param headers the response headers, may be {@code null}
     */
    public ApiException(String message, int statusCode, String body, Map<String, List<String>> headers) {
        this(message, statusCode, body == null ? "" : body, headers, parseBody(body));
    }

    private ApiException(
            String message, int statusCode, String body, Map<String, List<String>> headers, Object parsed) {
        super(message != null ? message : defaultMessage(statusCode, extractMessage(parsed, body)));
        this.statusCode = statusCode;
        this.body = body;
        this.parsedBody = parsed;
        this.errorMessage = extractMessage(parsed, body);
        this.headers = copyHeaders(headers);
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the status code, for example {@code 401}
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the raw response body decoded as UTF-8.
     *
     * @return the body text, empty when the response had no body
     */
    public String getBody() {
        return body;
    }

    /**
     * Returns the response body parsed as JSON: a {@code Map} for an object, a {@code String} for a
     * bare JSON string, and so on.
     *
     * @return the parsed body, or {@code null} when the body is empty, not JSON, or the literal
     *     {@code null}
     */
    public Object getParsedBody() {
        return parsedBody;
    }

    /**
     * Returns the server's short error message, taken from an {@code error} or {@code message} field,
     * from a bare JSON string, or from a short plain-text body.
     *
     * @return the message, or empty when the body carries none
     */
    public Optional<String> getErrorMessage() {
        return Optional.ofNullable(errorMessage);
    }

    /**
     * Returns the response headers. Header names are matched case-insensitively.
     *
     * @return an unmodifiable map of header names to values
     */
    public Map<String, List<String>> getHeaders() {
        return headers == null ? Collections.emptyMap() : headers;
    }

    /**
     * Returns the first value of a response header.
     *
     * @param name the header name, case-insensitive
     * @return the first value, or empty when the header is absent
     */
    public Optional<String> getHeader(String name) {
        List<String> values = name == null ? null : getHeaders().get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.get(0));
    }

    static Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return Json.parse(body);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static String extractMessage(Object parsed, String body) {
        if (parsed instanceof Map) {
            Map<?, ?> object = (Map<?, ?>) parsed;
            for (String key : new String[] {"error", "message"}) {
                Object value = object.get(key);
                if (value instanceof String && !((String) value).isBlank()) {
                    return shorten((String) value);
                }
            }
            return null;
        }
        if (parsed instanceof String) {
            return ((String) parsed).isBlank() ? null : shorten((String) parsed);
        }
        if (parsed == null && body != null) {
            String text = body.strip();
            if (!text.isEmpty() && !text.startsWith("<") && !"null".equals(text)) {
                return shorten(text);
            }
        }
        return null;
    }

    private static String shorten(String value) {
        String text = value.strip().replaceAll("\\s+", " ");
        return text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) + "..." : text;
    }

    private static String defaultMessage(int statusCode, String errorMessage) {
        String prefix = String.format(Locale.ROOT, "HTTP %d", statusCode);
        return errorMessage == null ? prefix : prefix + ": " + errorMessage;
    }

    private static Map<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            List<String> values = entry.getValue() == null ? List.of() : new ArrayList<>(entry.getValue());
            copy.merge(entry.getKey(), Collections.unmodifiableList(values), (a, b) -> {
                List<String> merged = new ArrayList<>(a);
                merged.addAll(b);
                return Collections.unmodifiableList(merged);
            });
        }
        return Collections.unmodifiableMap(copy);
    }
}
