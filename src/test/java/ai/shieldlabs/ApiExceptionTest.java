package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiExceptionTest {

    @Test
    void bodyShapesAreParsedDefensively() {
        ApiException object = new ApiException(429, "{\"error\":\"too many requests\"}\n");
        assertEquals(Optional.of("too many requests"), object.getErrorMessage());
        assertEquals("HTTP 429: too many requests", object.getMessage());

        ApiException message = new ApiException(422, "{\"message\":\"bad input\",\"code\":7}");
        assertEquals(Optional.of("bad input"), message.getErrorMessage());

        ApiException other = new ApiException(400, "{\"code\":7}");
        assertEquals(Optional.empty(), other.getErrorMessage());
        assertEquals(Map.of("code", 7), other.getParsedBody());

        ApiException bareString = new ApiException(404, "\"device_id is not supported\"");
        assertEquals(Optional.of("device_id is not supported"), bareString.getErrorMessage());
        assertEquals("device_id is not supported", bareString.getParsedBody());

        ApiException jsonNull = new ApiException(400, "null");
        assertEquals(Optional.empty(), jsonNull.getErrorMessage());
        assertNull(jsonNull.getParsedBody());
        assertEquals("HTTP 400", jsonNull.getMessage());

        ApiException empty = new ApiException(401, null);
        assertEquals("", empty.getBody());
        assertEquals(Optional.empty(), empty.getErrorMessage());

        ApiException text = new ApiException(404, "404 page not found");
        assertEquals(Optional.of("404 page not found"), text.getErrorMessage());
        assertNull(text.getParsedBody());

        ApiException html = new ApiException(502, "<html><body><h1>502 Bad Gateway</h1></body></html>");
        assertEquals(Optional.empty(), html.getErrorMessage());
        assertEquals("HTTP 502", html.getMessage());

        ApiException blankString = new ApiException(400, "\"  \"");
        assertEquals(Optional.empty(), blankString.getErrorMessage());

        char[] longText = new char[500];
        Arrays.fill(longText, 'x');
        ApiException longBody = new ApiException(500, new String(longText));
        assertEquals(203, longBody.getErrorMessage().orElse("").length());

        ApiException explicit = new ApiException("custom", 200, "{}", null);
        assertEquals("custom", explicit.getMessage());
    }

    @Test
    void headersAreCaseInsensitiveAndCopied() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Retry-After", List.of("7"));
        headers.put("retry-after", List.of("8"));
        headers.put("X-Empty", null);
        headers.put(null, List.of("status line"));
        ApiException error = new ApiException(429, "", headers);
        assertEquals(Optional.of("7"), error.getHeader("RETRY-AFTER"));
        assertEquals(List.of("7", "8"), error.getHeaders().get("retry-after"));
        assertEquals(Optional.empty(), error.getHeader("x-empty"));
        assertEquals(Optional.empty(), error.getHeader(null));
        assertEquals(Optional.empty(), error.getHeader("missing"));
        assertTrue(new ApiException(500, "").getHeaders().isEmpty());
    }

    @Test
    void statusMapping() {
        assertInstanceOf(BadRequestException.class, HttpErrors.forStatus(400, "", null));
        assertInstanceOf(AuthenticationException.class, HttpErrors.forStatus(401, "", null));
        assertInstanceOf(AuthenticationException.class, HttpErrors.forStatus(403, "", null));
        assertInstanceOf(QuotaExceededException.class, HttpErrors.forStatus(402, "", null));
        assertInstanceOf(NotFoundException.class, HttpErrors.forStatus(404, "", null));
        assertInstanceOf(RateLimitException.class, HttpErrors.forStatus(429, "", null));
        assertInstanceOf(ServerException.class, HttpErrors.forStatus(500, "", null));
        assertInstanceOf(ServerException.class, HttpErrors.forStatus(599, "", null));
        assertEquals(ApiException.class, HttpErrors.forStatus(418, "", null).getClass());
        assertEquals(ApiException.class, HttpErrors.forStatus(302, "", null).getClass());
    }

    @Test
    void retryAfterParsing() {
        Instant now = Instant.parse("2026-09-30T12:00:00Z");
        assertEquals(Duration.ofSeconds(3), HttpErrors.parseRetryAfter("3", now));
        assertEquals(Duration.ofMillis(1500), HttpErrors.parseRetryAfter(" 1.5 ", now));
        assertEquals(Duration.ofSeconds(30), HttpErrors.parseRetryAfter("Wed, 30 Sep 2026 12:00:30 GMT", now));
        assertEquals(Duration.ZERO, HttpErrors.parseRetryAfter("Wed, 30 Sep 2026 11:00:00 GMT", now));
        assertNull(HttpErrors.parseRetryAfter("soon", now));
        assertNull(HttpErrors.parseRetryAfter("-5", now));
        assertNull(HttpErrors.parseRetryAfter(null, now));
        assertNull(HttpErrors.retryAfter(null));
        assertNull(HttpErrors.retryAfter(Map.of("Retry-After", List.of())));
        Map<String, List<String>> nullKey = new LinkedHashMap<>();
        nullKey.put(null, List.of("HTTP/1.1 429"));
        assertNull(HttpErrors.retryAfter(nullKey));
        RateLimitException limited = new RateLimitException("", Map.of("retry-after", List.of("2")));
        assertEquals(Optional.of(Duration.ofSeconds(2)), limited.getRetryAfter());
        assertEquals(429, limited.getStatusCode());
        assertEquals(Optional.empty(), new RateLimitException("", null).getRetryAfter());
    }
}
