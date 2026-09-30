package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ErrorResponsesTest {
    private static final Map<String, Class<? extends ApiException>> CLASSES =
            Map.of(
                    "BadRequestError", BadRequestException.class,
                    "AuthenticationError", AuthenticationException.class,
                    "QuotaExceededError", QuotaExceededException.class,
                    "NotFoundError", NotFoundException.class,
                    "RateLimitError", RateLimitException.class,
                    "ServerError", ServerException.class);

    static Stream<Arguments> cases() {
        return Fixtures.cases("error-responses.json").stream()
                .map(item -> Arguments.of(item.get("surface") + " " + item.get("status") + " " + item.get("expected_error"), item));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void mapsStatusToErrorClassAndRetryPolicy(String name, Map<String, Object> item) {
        int status = ((Number) item.get("status")).intValue();
        String body = (String) item.get("body");
        String contentType = (String) item.get("content_type");
        boolean retry = (Boolean) item.get("retry");
        Class<? extends ApiException> expected = CLASSES.get((String) item.get("expected_error"));

        try (TestServer server = TestServer.start()) {
            server.always(status, body, contentType);
            FakeTimer timer = new FakeTimer();
            ApiException error;
            if ("history".equals(item.get("surface"))) {
                ShieldLabsClient client = Clients.history(server, timer).maxRetries(1).build();
                error = assertThrows(expected, () -> client.history().search(LookupType.DEVICE_ID, Clients.REQUEST_ID));
            } else {
                ManagementClient client = Clients.management(server, timer).maxRetries(1).build();
                error = assertThrows(expected, client::getProfile);
            }
            assertEquals(expected, error.getClass());
            assertEquals(status, error.getStatusCode());
            assertEquals(body, error.getBody());
            assertEquals(retry ? 2 : 1, server.requestCount(), "retry: " + retry);
            assertEquals(retry ? 1 : 0, timer.waits().size());
            assertTrue(error.getMessage().startsWith("HTTP " + status));
        }
    }

    @Test
    void jsonErrorBodiesExposeTheServerMessage() {
        try (TestServer server = TestServer.start()) {
            server.always(401, "{\"error\":\"invalid api key\"}\n", "text/plain; charset=utf-8");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            AuthenticationException error =
                    assertThrows(
                            AuthenticationException.class,
                            () -> client.history().search(LookupType.REQUEST_ID, Clients.REQUEST_ID));
            assertEquals("invalid api key", error.getErrorMessage().orElse(null));
            assertEquals("HTTP 401: invalid api key", error.getMessage());
            assertEquals(Map.of("error", "invalid api key"), error.getParsedBody());
            assertEquals("text/plain; charset=utf-8", error.getHeader("content-type").orElse(null));
        }
    }
}
