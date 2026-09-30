package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class RetryTest {
    private static final String EMPTY = Fixtures.text("history-empty.json");

    private static Transport transport(double random) {
        return new Transport(
                HttpClient.newHttpClient(), Duration.ofSeconds(1), 2, true, new String[0], new FakeTimer(), () -> random);
    }

    @Test
    void backoffIsExponentialWithJitterAndCapped() {
        Transport upper = transport(1.0);
        Transport lower = transport(0.0);
        long[] raw = {500, 1000, 2000, 4000, 8000, 8000, 8000, 8000};
        for (int retry = 0; retry < raw.length; retry++) {
            assertEquals(raw[retry], upper.backoff(retry).toMillis(), "upper bound, retry " + retry);
            assertEquals(raw[retry] / 2, lower.backoff(retry).toMillis(), "lower bound, retry " + retry);
        }
        assertEquals(250, transport(-3.0).backoff(0).toMillis(), "jitter is clamped");
        assertEquals(500, transport(7.0).backoff(0).toMillis(), "jitter is clamped");
    }

    @Test
    void serverErrorsAreRetriedWithBackoff() {
        try (TestServer server = TestServer.start()) {
            server.json(500, "{\"error\":\"internal error\"}").json(502, "").json(200, EMPTY);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            client.history().search(LookupType.USER_HID, "u");
            assertEquals(3, server.requestCount());
            assertEquals(List.of(500L, 1000L), timer.waitMillis());
        }
    }

    @Test
    void retryAfterIsHonouredAndCapped() {
        try (TestServer server = TestServer.start()) {
            server.enqueue(429, "{\"error\":\"too many requests\"}", "application/json", 0, "Retry-After", "3")
                    .enqueue(503, "{\"error\":\"server is busy\"}", "application/json", 0, "Retry-After", "60")
                    .json(200, EMPTY);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            client.history().search(LookupType.USER_HID, "u");
            assertEquals(List.of(3000L, 10000L), timer.waitMillis());
        }
    }

    @Test
    void historyRateLimitIsRetriedThenRaised() {
        try (TestServer server = TestServer.start()) {
            server.always(429, "{\"error\":\"too many requests\"}\n", "application/json");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).maxRetries(3).build();
            RateLimitException error =
                    assertThrows(RateLimitException.class, () -> client.history().search(LookupType.USER_HID, "u"));
            assertEquals(4, server.requestCount());
            assertEquals(List.of(1000L, 1000L, 2000L), timer.waitMillis());
            assertEquals("too many requests", error.getErrorMessage().orElse(null));
            assertTrue(error.getRetryAfter().isEmpty());
        }
    }

    @Test
    void rateLimitWithoutRetryAfterWaitsAtLeastOneSecond() {
        Transport lower = transport(0.0);
        RateLimitException limited = new RateLimitException("{\"error\":\"too many requests\"}\n", null);
        assertEquals(1000, lower.retryDelay(0, limited).toMillis(), "not 250 ms: the window is one second");
        assertEquals(1000, lower.retryDelay(1, limited).toMillis());
        assertEquals(1000, lower.retryDelay(2, limited).toMillis());
        assertEquals(2000, lower.retryDelay(3, limited).toMillis(), "backoff above one second is kept");
        assertEquals(
                250,
                lower.retryDelay(0, new ServerException(503, "", null)).toMillis(),
                "other retries keep the plain backoff");

        try (TestServer server = TestServer.start()) {
            server.json(429, "{\"error\":\"too many requests\"}\n").json(200, EMPTY);
            server.json(429, "{\"error\":\"too many requests\"}\n").json(200, EMPTY);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).random(() -> 0.0).build();
            client.history().search(LookupType.USER_HID, "u");
            client.history().searchAsync(LookupType.USER_HID, "u").join();
            assertEquals(4, server.requestCount());
            assertEquals(List.of(1000L, 1000L), timer.waitMillis());
        }
    }

    @Test
    void retryAfterIsFollowedAsSentWithoutTheOneSecondMinimum() {
        // Outside the wait for a verdict, a Retry-After is followed as sent (capped at 10 s): the
        // one-second minimum applies only to a 429 that has none.
        Transport lower = transport(0.0);
        assertEquals(0, lower.retryDelay(0, new RateLimitException("", null, Duration.ZERO)).toMillis(), "Retry-After: 0");
        assertEquals(400, lower.retryDelay(0, new RateLimitException("", null, Duration.ofMillis(400))).toMillis());
        assertEquals(3000, lower.retryDelay(1, new RateLimitException("", null, Duration.ofSeconds(3))).toMillis());
        assertEquals(10000, lower.retryDelay(0, new RateLimitException("", null, Duration.ofMinutes(1))).toMillis());

        for (String[] c : new String[][] {{"0", "0"}, {"0.5", "500"}, {"Thu, 01 Jan 2026 00:00:00 GMT", "0"}}) {
            try (TestServer server = TestServer.start()) {
                server.enqueue(429, "{\"error\":\"too many requests\"}", "application/json", 0, "Retry-After", c[0])
                        .json(200, EMPTY);
                server.enqueue(429, "{\"error\":\"too many requests\"}", "application/json", 0, "Retry-After", c[0])
                        .json(200, EMPTY);
                FakeTimer timer = new FakeTimer();
                ShieldLabsClient client = Clients.history(server, timer).random(() -> 0.0).build();
                client.history().search(LookupType.USER_HID, "u");
                client.history().searchAsync(LookupType.USER_HID, "u").join();
                assertEquals(4, server.requestCount());
                long expected = Long.parseLong(c[1]);
                assertEquals(List.of(expected, expected), timer.waitMillis(), "Retry-After: " + c[0]);
            }
        }
    }

    @Test
    void clientErrorsAreNeverRetried() {
        for (int status : new int[] {400, 401, 402, 403, 404, 409}) {
            try (TestServer server = TestServer.start()) {
                server.always(status, "{\"error\":\"nope\"}", "application/json");
                FakeTimer timer = new FakeTimer();
                ShieldLabsClient client = Clients.history(server, timer).build();
                ApiException error =
                        assertThrows(ApiException.class, () -> client.history().search(LookupType.USER_HID, "u"));
                assertEquals(status, error.getStatusCode());
                assertEquals(1, server.requestCount(), "status " + status);
            }
        }
    }

    @Test
    void zeroRetriesMeansOneAttempt() {
        try (TestServer server = TestServer.start()) {
            server.always(503, "{\"error\":\"server is busy\"}", "application/json");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).maxRetries(0).build();
            assertThrows(ServerException.class, () -> client.history().search(LookupType.USER_HID, "u"));
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    void asyncRetriesToo() {
        try (TestServer server = TestServer.start()) {
            server.json(500, "{\"error\":\"internal error\"}").json(200, EMPTY);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            client.history().searchAsync(LookupType.USER_HID, "u").join();
            assertEquals(2, server.requestCount());
            assertEquals(List.of(500L), timer.waitMillis());
        }
        try (TestServer server = TestServer.start()) {
            server.always(400, "\"value cannot be empty\"", "application/json");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            CompletionException error =
                    assertThrows(
                            CompletionException.class,
                            () -> client.history().searchAsync(LookupType.USER_HID, "u").join());
            BadRequestException cause = assertInstanceOf(BadRequestException.class, error.getCause());
            assertEquals("value cannot be empty", cause.getErrorMessage().orElse(null));
            assertEquals(1, server.requestCount());
        }
    }

    private static URI closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
    }

    @Test
    void connectionErrorsAreRetried() throws IOException {
        FakeTimer timer = new FakeTimer();
        ShieldLabsClient client =
                ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl(closedPort()).timer(timer).random(() -> 0.0).build();
        ApiConnectionException error =
                assertThrows(ApiConnectionException.class, () -> client.history().search(LookupType.USER_HID, "u"));
        assertTrue(error.getMessage().startsWith("Connection failed"));
        assertEquals(List.of(250L, 500L), timer.waitMillis());

        FakeTimer asyncTimer = new FakeTimer();
        ShieldLabsClient asyncClient =
                ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl(closedPort()).timer(asyncTimer).maxRetries(1).build();
        CompletionException asyncError =
                assertThrows(
                        CompletionException.class,
                        () -> asyncClient.history().searchAsync(LookupType.USER_HID, "u").join());
        assertInstanceOf(ApiConnectionException.class, asyncError.getCause());
        assertEquals(1, asyncTimer.waits().size());
    }

    @Test
    void timeoutsAreRetriedAndReported() {
        try (TestServer server = TestServer.start()) {
            server.enqueue(200, EMPTY, "application/json", 1500).enqueue(200, EMPTY, "application/json", 1500);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client =
                    Clients.history(server, timer).timeout(Duration.ofMillis(200)).maxRetries(1).build();
            ApiTimeoutException error =
                    assertThrows(ApiTimeoutException.class, () -> client.history().search(LookupType.USER_HID, "u"));
            assertTrue(error.getMessage().contains("200 ms"));
            assertEquals(1, timer.waits().size());
        }
        try (TestServer server = TestServer.start()) {
            server.enqueue(200, EMPTY, "application/json", 1500);
            ShieldLabsClient client =
                    Clients.history(server, new FakeTimer()).timeout(Duration.ofMillis(200)).maxRetries(0).build();
            CompletionException error =
                    assertThrows(
                            CompletionException.class,
                            () -> client.history().searchAsync(LookupType.USER_HID, "u").join());
            assertInstanceOf(ApiTimeoutException.class, error.getCause());
        }
    }

    @Test
    void interruptedCallsAreNotRetried() {
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            Thread.currentThread().interrupt();
            try {
                ApiConnectionException error =
                        assertThrows(ApiConnectionException.class, () -> client.history().search(LookupType.USER_HID, "u"));
                assertInstanceOf(InterruptedException.class, error.getCause());
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is restored");
                assertTrue(timer.waits().isEmpty());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void interruptWhileWaitingToRetryStops() {
        try (TestServer server = TestServer.start()) {
            server.always(503, "", "application/json");
            Timer interrupting =
                    new Timer() {
                        @Override
                        public long nanoTime() {
                            return 0;
                        }

                        @Override
                        public void sleep(Duration duration) throws InterruptedException {
                            throw new InterruptedException("test");
                        }

                        @Override
                        public java.util.concurrent.CompletableFuture<Void> delay(Duration duration) {
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        }
                    };
            ShieldLabsClient client =
                    ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl(server.uri()).timer(interrupting).build();
            try {
                ApiConnectionException error =
                        assertThrows(ApiConnectionException.class, () -> client.history().search(LookupType.USER_HID, "u"));
                assertEquals(1, error.getSuppressed().length);
                assertInstanceOf(ServerException.class, error.getSuppressed()[0]);
                assertTrue(Thread.interrupted(), "the interrupt flag is restored");
                ApiConnectionException polling =
                        assertThrows(ApiConnectionException.class, () -> client.identifications().get(Clients.REQUEST_ID));
                assertTrue(polling.getMessage().contains("waiting for the verdict"));
            } finally {
                Thread.interrupted();
            }
        }
    }
}
