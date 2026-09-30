package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IdentificationPollingTest {
    private static final String EMPTY = Fixtures.text("history-empty.json");
    private static final String FOUND = Clients.page(1, Clients.row(Clients.REQUEST_ID));
    private static final String TOO_MANY = "{\"error\":\"too many requests\"}";
    private static final String INTERNAL = "{\"error\":\"internal error\"}";
    /** The waits of a default call that never finds a row: they add up to the 10 s budget. */
    private static final List<Long> DEFAULT_WAITS = List.of(250L, 500L, 1000L, 1500L, 2000L, 2000L, 2000L, 750L);
    /** {@code Retry-After} HTTP dates: one in the past (counts as 0) and one far ahead (capped at 10 s). */
    private static final String PAST_DATE = "Thu, 01 Jan 2026 00:00:00 GMT";
    private static final String FAR_FUTURE_DATE = "Fri, 01 Jan 2100 00:00:00 GMT";

    /** Calls get() synchronously or through getAsync().join(), unwrapping async failures. */
    interface Get {
        Optional<Identification> apply(ShieldLabsClient client, GetIdentificationOptions options);
    }

    static Stream<Arguments> modes() {
        Get sync = (client, options) -> client.identifications().get(Clients.REQUEST_ID, options);
        Get async =
                (client, options) -> {
                    try {
                        return client.identifications().getAsync(Clients.REQUEST_ID, options).join();
                    } catch (CompletionException e) {
                        throw (RuntimeException) e.getCause();
                    }
                };
        return Stream.of(Arguments.of("sync", sync), Arguments.of("async", async));
    }

    /** Every mode with every status that ends the wait at once, and the exception it maps to. */
    static Stream<Arguments> modesAndStoppingStatuses() {
        Object[][] statuses = {
            {400, BadRequestException.class},
            {401, AuthenticationException.class},
            {403, AuthenticationException.class},
            {404, NotFoundException.class},
        };
        return modes().flatMap(
                mode -> Stream.of(statuses).map(s -> Arguments.of(mode.get()[0], mode.get()[1], s[0], s[1])));
    }

    private static GetIdentificationOptions budget(Duration waitFor) {
        return GetIdentificationOptions.builder().waitFor(waitFor).build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void pollsImmediatelyThenBacksOff(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.json(200, EMPTY).json(200, EMPTY).json(200, EMPTY).json(200, EMPTY).json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            Optional<Identification> found = get.apply(client, null);
            assertTrue(found.isPresent());
            assertEquals(Clients.REQUEST_ID, found.get().getRequestId());
            assertEquals(List.of(250L, 500L, 1000L, 1500L), timer.waitMillis());
            assertEquals(5, server.requestCount());
            TestServer.Recorded request = server.requests().get(0);
            assertEquals("/api/v1/history/request_id/" + Clients.REQUEST_ID, request.rawPath);
            assertEquals("limit=1&offset=0", request.rawQuery);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void theLastPollRunsAtTheDeadline(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer);
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            Optional<Identification> found = get.apply(client, GetIdentificationOptions.defaults());
            assertFalse(found.isPresent(), "not found means unverified");
            assertEquals(DEFAULT_WAITS, timer.waitMillis());
            assertEquals(List.of(0L, 250L, 750L, 1750L, 3250L, 5250L, 7250L, 9250L, 10000L), http.sentAtMillis());
        }
        // A budget between two steps of the schedule: the last wait is cut short to end at the deadline.
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer);
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            assertFalse(get.apply(client, budget(Duration.ofMillis(3300))).isPresent());
            assertEquals(List.of(0L, 250L, 750L, 1750L, 3250L, 3300L), http.sentAtMillis());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void thePollAfterTheShortenedWaitIsTheLastEvenWhenTheTimerWakesEarly(String mode, Get get) {
        // A system timer can wake a fraction of a millisecond early; the poll after the wait that was cut
        // to the deadline must still be the last one.
        assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                () -> {
                    try (TestServer server = TestServer.start()) {
                        server.always(200, EMPTY, "application/json");
                        FakeTimer timer = new FakeTimer(Duration.ofMillis(1));
                        ShieldLabsClient client = Clients.history(server, timer).build();
                        assertFalse(get.apply(client, null).isPresent());
                        assertEquals(9, server.requestCount());
                        assertEquals(
                                List.of(250L, 500L, 1000L, 1500L, 2000L, 2000L, 2000L, 757L), timer.waitMillis());
                    }
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void customPollIntervalScalesTheSchedule(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            GetIdentificationOptions options =
                    GetIdentificationOptions.builder()
                            .waitFor(Duration.ofSeconds(4))
                            .pollInterval(Duration.ofMillis(500))
                            .build();
            get.apply(client, options);
            assertEquals(List.of(500L, 1000L, 2000L, 500L), timer.waitMillis());
        }
        // 100 ms: x1, x2, x4, x6, x8 and then x8 again, all below the 2 s cap.
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            GetIdentificationOptions options =
                    GetIdentificationOptions.builder()
                            .waitFor(Duration.ofSeconds(5))
                            .pollInterval(Duration.ofMillis(100))
                            .build();
            get.apply(client, options);
            assertEquals(List.of(100L, 200L, 400L, 600L, 800L, 800L, 800L, 800L, 500L), timer.waitMillis());
            assertEquals(10, server.requestCount());
        }
        // 50 ms: an interval below 100 ms is used as it is, without a floor.
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer);
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            GetIdentificationOptions options =
                    GetIdentificationOptions.builder()
                            .waitFor(Duration.ofSeconds(2))
                            .pollInterval(Duration.ofMillis(50))
                            .build();
            assertFalse(get.apply(client, options).isPresent());
            assertEquals(List.of(50L, 100L, 200L, 300L, 400L, 400L, 400L, 150L), timer.waitMillis());
            assertEquals(List.of(0L, 50L, 150L, 350L, 650L, 1050L, 1450L, 1850L, 2000L), http.sentAtMillis());
        }
        // An interval above 2 s is its own cap: a poll every 3 s, then one at the deadline.
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer);
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            GetIdentificationOptions options =
                    GetIdentificationOptions.builder()
                            .waitFor(Duration.ofSeconds(5))
                            .pollInterval(Duration.ofSeconds(3))
                            .build();
            assertFalse(get.apply(client, options).isPresent());
            assertEquals(List.of(3000L, 2000L), timer.waitMillis());
            assertEquals(List.of(0L, 3000L, 5000L), http.sentAtMillis());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void theScheduleFollowsTheLadderFor250Milliseconds1SecondAnd3Seconds(String mode, Get get) {
        // Waits of p, 2p, 4p, 6p, 8p, then 8p, each capped at max(2 s, p), in the default 10 s budget; the
        // last wait is cut short so the last poll runs at the deadline.
        Object[][] cases = {
            {Duration.ofMillis(250), DEFAULT_WAITS, List.of(0L, 250L, 750L, 1750L, 3250L, 5250L, 7250L, 9250L, 10000L)},
            {Duration.ofSeconds(1), List.of(1000L, 2000L, 2000L, 2000L, 2000L, 1000L),
                List.of(0L, 1000L, 3000L, 5000L, 7000L, 9000L, 10000L)},
            {Duration.ofSeconds(3), List.of(3000L, 3000L, 3000L, 1000L), List.of(0L, 3000L, 6000L, 9000L, 10000L)},
        };
        for (Object[] c : cases) {
            try (TestServer server = TestServer.start()) {
                server.always(200, EMPTY, "application/json");
                FakeTimer timer = new FakeTimer();
                RecordingHttpClient http = new RecordingHttpClient(timer);
                ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
                GetIdentificationOptions options =
                        GetIdentificationOptions.builder().pollInterval((Duration) c[0]).build();
                assertFalse(get.apply(client, options).isPresent());
                assertEquals(c[1], timer.waitMillis(), "poll interval " + c[0]);
                assertEquals(c[2], http.sentAtMillis(), "poll interval " + c[0]);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void eachPollIsOneAttemptWithTheTimeLeftAsItsTimeout(String mode, Get get) {
        // min(client timeout, max(time left, 1 s)) for client timeouts of 5 s, 20 s and 800 ms.
        Object[][] cases = {
            {Duration.ofSeconds(5), List.of(5000L, 5000L, 5000L, 5000L, 5000L, 4750L, 2750L, 1000L, 1000L)},
            {Duration.ofSeconds(20), List.of(10000L, 9750L, 9250L, 8250L, 6750L, 4750L, 2750L, 1000L, 1000L)},
            {Duration.ofMillis(800), List.of(800L, 800L, 800L, 800L, 800L, 800L, 800L, 800L, 800L)},
        };
        for (Object[] c : cases) {
            try (TestServer server = TestServer.start()) {
                server.always(200, EMPTY, "application/json");
                FakeTimer timer = new FakeTimer();
                RecordingHttpClient http = new RecordingHttpClient(timer);
                ShieldLabsClient client =
                        Clients.history(server, timer).timeout((Duration) c[0]).httpClient(http).build();
                assertFalse(get.apply(client, null).isPresent());
                assertEquals(c[1], http.timeoutMillis(), "client timeout " + c[0]);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void slowPollsCountAgainstTheBudget(String mode, Get get) {
        // Every answer takes 3 s of a 5 s budget: the second poll starts with 1.75 s left, gets that as
        // its timeout, answers after the deadline and is the last one.
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer).latency(Duration.ofSeconds(3));
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            assertFalse(get.apply(client, budget(Duration.ofSeconds(5))).isPresent());
            assertEquals(List.of(0L, 3250L), http.sentAtMillis());
            assertEquals(List.of(5000L, 1750L), http.timeoutMillis());
            assertEquals(List.of(250L), timer.waitMillis());
        }
        // The same when the late answer is an error: it is thrown.
        try (TestServer server = TestServer.start()) {
            server.json(200, EMPTY).always(502, "", null);
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer).latency(Duration.ofSeconds(3));
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            assertThrows(ServerException.class, () -> get.apply(client, budget(Duration.ofSeconds(5))));
            assertEquals(2, http.requestCount());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void unexpectedExceptionsFromTheHttpClientEndTheWait(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer).fail(2, new IllegalStateException("broken client"));
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> get.apply(client, null));
            assertEquals("broken client", error.getMessage());
            assertEquals(2, http.requestCount());
            assertEquals(List.of(250L), timer.waitMillis());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void transientErrorsKeepPollingAndTheLastErrorIsThrownAtTheDeadline(String mode, Get get) {
        // Every poll fails, far more often than maxRetries: 5xx responses (a Retry-After on them does not
        // change the schedule), connection errors and timeouts. Each poll is one attempt, and the error
        // of the last poll, at the deadline, is thrown.
        try (TestServer server = TestServer.start()) {
            server.enqueue(503, "{\"error\":\"server is busy\"}", "application/json", 0, "Retry-After", "5")
                    .enqueue(502, "<html>bad gateway</html>", "text/html", 0)
                    .json(500, INTERNAL)
                    .enqueue(504, "", null, 0)
                    .always(500, INTERNAL, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http =
                    new RecordingHttpClient(timer)
                            .fail(3, new ConnectException("Connection refused"))
                            .fail(5, new HttpTimeoutException("request timed out"))
                            .fail(9, new HttpTimeoutException("request timed out"));
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            ApiTimeoutException error = assertThrows(ApiTimeoutException.class, () -> get.apply(client, null));
            assertEquals("No response within 1000 ms", error.getMessage(), "the timeout of the last poll");
            assertEquals(9, http.requestCount(), "one attempt per poll");
            assertEquals(6, server.requestCount());
            assertEquals(DEFAULT_WAITS, timer.waitMillis());
        }
        // The last poll answered with a 5xx.
        try (TestServer server = TestServer.start()) {
            server.always(503, "{\"error\":\"server is busy\"}", "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer).fail(1, new ConnectException("Connection refused"));
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).maxRetries(0).build();
            ServerException error = assertThrows(ServerException.class, () -> get.apply(client, null));
            assertEquals(503, error.getStatusCode());
            assertEquals(9, http.requestCount());
        }
        // The last poll could not connect.
        try (TestServer server = TestServer.start()) {
            server.always(500, INTERNAL, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer).fail(9, new ConnectException("Connection refused"));
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).maxRetries(5).build();
            ApiConnectionException error = assertThrows(ApiConnectionException.class, () -> get.apply(client, null));
            assertInstanceOf(ConnectException.class, error.getCause());
            assertEquals(9, http.requestCount());
            assertEquals(DEFAULT_WAITS, timer.waitMillis());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void theOutcomeOfTheLastPollStands(String mode, Get get) {
        // Failed polls followed by an empty last poll: not found yet, no exception.
        try (TestServer server = TestServer.start()) {
            for (int i = 0; i < 7; i++) {
                server.json(500, INTERNAL);
            }
            server.json(429, TOO_MANY).always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            assertEquals(Optional.empty(), get.apply(client, null));
            assertEquals(9, server.requestCount());
        }
        // A row that appears after more failed polls than maxRetries is returned.
        try (TestServer server = TestServer.start()) {
            server.json(500, INTERNAL).json(502, "").json(503, "").json(500, INTERNAL).json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).maxRetries(0).build();
            Optional<Identification> found = get.apply(client, null);
            assertEquals(Clients.REQUEST_ID, found.orElseThrow().getRequestId());
            assertEquals(5, server.requestCount());
            assertEquals(List.of(250L, 500L, 1000L, 1500L), timer.waitMillis());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void rateLimitWaitsAtLeastOneSecond(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.json(429, TOO_MANY).json(429, TOO_MANY).json(200, EMPTY).json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            assertTrue(get.apply(client, null).isPresent());
            // The schedule alone would wait 250 ms and 500 ms after the first two polls.
            assertEquals(List.of(1000L, 1000L, 1000L), timer.waitMillis());
            assertEquals(4, server.requestCount());
        }
        // A shorter Retry-After does not lower the minimum: 0, a fraction of a second, a date in the past
        // (counts as 0) and an unreadable value (ignored) all wait one second.
        for (String retryAfter : new String[] {"0", "0.4", PAST_DATE, "soon"}) {
            try (TestServer server = TestServer.start()) {
                server.enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", retryAfter).json(200, FOUND);
                FakeTimer timer = new FakeTimer();
                ShieldLabsClient client = Clients.history(server, timer).build();
                assertTrue(get.apply(client, null).isPresent());
                assertEquals(List.of(1000L), timer.waitMillis(), "Retry-After: " + retryAfter);
                assertEquals(2, server.requestCount());
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void afterARateLimitTheLongestOfScheduleMinimumAndRetryAfterApplies(String mode, Get get) {
        // Four empty polls bring the schedule to 2 s. A 429 with Retry-After: 1 and a 429 without it then
        // both wait the scheduled 2 s; a Retry-After of 3 s is longer than the schedule and wins.
        try (TestServer server = TestServer.start()) {
            server.json(200, EMPTY)
                    .json(200, EMPTY)
                    .json(200, EMPTY)
                    .json(200, EMPTY)
                    .enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", "1")
                    .json(429, TOO_MANY)
                    .enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", "3")
                    .json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer);
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            assertTrue(get.apply(client, budget(Duration.ofSeconds(20))).isPresent());
            assertEquals(List.of(250L, 500L, 1000L, 1500L, 2000L, 2000L, 3000L), timer.waitMillis());
            assertEquals(List.of(0L, 250L, 750L, 1750L, 3250L, 5250L, 7250L, 10250L), http.sentAtMillis());
        }
        // With a 3 s poll interval every step is 3 s: after a 429 without Retry-After, and after one with
        // Retry-After: 2, the step is kept; a Retry-After of 5 s is longer and wins.
        try (TestServer server = TestServer.start()) {
            server.json(429, TOO_MANY)
                    .enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", "2")
                    .enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", "5")
                    .json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            GetIdentificationOptions options =
                    GetIdentificationOptions.builder()
                            .waitFor(Duration.ofSeconds(20))
                            .pollInterval(Duration.ofSeconds(3))
                            .build();
            assertTrue(get.apply(client, options).isPresent());
            assertEquals(List.of(3000L, 3000L, 5000L), timer.waitMillis());
            assertEquals(4, server.requestCount());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void rateLimitInsideTheWaitMeansWaitLonger(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.json(200, EMPTY)
                    .json(429, TOO_MANY + "\n")
                    .json(200, EMPTY)
                    .enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", "3")
                    .json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            Optional<Identification> found = get.apply(client, null);
            assertTrue(found.isPresent());
            assertEquals(List.of(250L, 1000L, 1000L, 3000L), timer.waitMillis());
            assertEquals(5, server.requestCount());
        }
        // Retry-After is capped at 10 seconds, as delta-seconds and as an HTTP date.
        for (String retryAfter : new String[] {"60", FAR_FUTURE_DATE}) {
            try (TestServer server = TestServer.start()) {
                server.enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", retryAfter).json(200, FOUND);
                FakeTimer timer = new FakeTimer();
                ShieldLabsClient client = Clients.history(server, timer).build();
                assertTrue(get.apply(client, budget(Duration.ofSeconds(20))).isPresent());
                assertEquals(List.of(10000L), timer.waitMillis(), "Retry-After: " + retryAfter);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void retryAfterLongerThanTheTimeLeftIsThrownAtOnce(String mode, Get get) {
        // At the first poll: 2 s left, the server asks for 5 s.
        try (TestServer server = TestServer.start()) {
            server.always(429, TOO_MANY, "application/json", "Retry-After", "5");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            RateLimitException error =
                    assertThrows(RateLimitException.class, () -> get.apply(client, budget(Duration.ofSeconds(2))));
            assertEquals(Optional.of(Duration.ofSeconds(5)), error.getRetryAfter());
            assertEquals(1, server.requestCount());
            assertTrue(timer.waits().isEmpty());
        }
        // Later: a Retry-After of 2 s fits into the 2.75 s left, one of 1 s does not fit into 0.75 s.
        try (TestServer server = TestServer.start()) {
            server.json(200, EMPTY)
                    .enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", "2")
                    .enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", "1")
                    .always(200, FOUND, "application/json");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            RateLimitException error =
                    assertThrows(RateLimitException.class, () -> get.apply(client, budget(Duration.ofSeconds(3))));
            assertEquals(Optional.of(Duration.ofSeconds(1)), error.getRetryAfter());
            assertEquals(3, server.requestCount());
            assertEquals(List.of(250L, 2000L), timer.waitMillis());
        }
        // A far-future HTTP date is capped at 10 s, which is still longer than the 5 s left.
        try (TestServer server = TestServer.start()) {
            server.always(429, TOO_MANY, "application/json", "Retry-After", FAR_FUTURE_DATE);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            assertThrows(RateLimitException.class, () -> get.apply(client, budget(Duration.ofSeconds(5))));
            assertEquals(1, server.requestCount());
            assertTrue(timer.waits().isEmpty());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void rateLimitAtTheDeadlineIsRaised(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.always(429, TOO_MANY, "application/json");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            assertThrows(RateLimitException.class, () -> get.apply(client, budget(Duration.ofMillis(1500))));
            // The one-second minimum is cut short to keep the last poll at the deadline.
            assertEquals(List.of(1000L, 500L), timer.waitMillis());
            assertEquals(3, server.requestCount());
        }
        // The same with Retry-After: 0, which fits into any time left and keeps the one-second minimum.
        try (TestServer server = TestServer.start()) {
            server.always(429, TOO_MANY, "application/json", "Retry-After", "0");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer);
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            RateLimitException error =
                    assertThrows(RateLimitException.class, () -> get.apply(client, budget(Duration.ofMillis(1500))));
            assertEquals(Optional.of(Duration.ZERO), error.getRetryAfter());
            assertEquals(List.of(1000L, 500L), timer.waitMillis());
            assertEquals(List.of(0L, 1000L, 1500L), http.sentAtMillis());
        }
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("modesAndStoppingStatuses")
    void clientErrorsStopPollingAtOnce(String mode, Get get, int status, Class<? extends ApiException> type) {
        try (TestServer server = TestServer.start()) {
            server.always(status, "{\"error\":\"rejected\"}\n", "text/plain; charset=utf-8");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            ApiException error = assertThrows(type, () -> get.apply(client, null));
            assertEquals(status, error.getStatusCode());
            assertEquals(1, server.requestCount());
            assertTrue(timer.waits().isEmpty());
        }
        // Also in the middle of a wait.
        try (TestServer server = TestServer.start()) {
            server.json(200, EMPTY).json(500, INTERNAL).always(status, "", null);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            assertThrows(type, () -> get.apply(client, null));
            assertEquals(3, server.requestCount());
            assertEquals(List.of(250L, 500L), timer.waitMillis());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void otherFailuresThatAnotherPollCannotChangeEndTheWait(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.always(402, "{\"error\":\"no requests left\"}", "application/json");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            assertThrows(QuotaExceededException.class, () -> get.apply(client, null));
            assertEquals(1, server.requestCount());
        }
        try (TestServer server = TestServer.start()) {
            server.always(200, "[]", "application/json");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            ApiException error = assertThrows(ApiException.class, () -> get.apply(client, null));
            assertTrue(error.getMessage().contains("Unexpected response body"));
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    void anInterruptEndsTheWait() {
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            Thread.currentThread().interrupt();
            try {
                ApiConnectionException error =
                        assertThrows(ApiConnectionException.class, () -> client.identifications().get(Clients.REQUEST_ID));
                assertInstanceOf(InterruptedException.class, error.getCause());
                assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is restored");
                assertTrue(timer.waits().isEmpty());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void noWaitMakesASingleLookup(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer);
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            assertFalse(get.apply(client, GetIdentificationOptions.builder().noWait().build()).isPresent());
            assertEquals(1, http.requestCount());
            assertEquals(List.of(5000L), http.timeoutMillis(), "the client timeout, not a poll timeout");
            assertTrue(timer.waits().isEmpty());
        }
        // The single lookup is retried like any History request.
        try (TestServer server = TestServer.start()) {
            server.json(503, "").json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            assertTrue(get.apply(client, GetIdentificationOptions.builder().noWait().build()).isPresent());
            assertEquals(2, server.requestCount());
            assertEquals(List.of(500L), timer.waitMillis());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void aZeroBudgetPollsOnceWithoutWaiting(String mode, Get get) {
        // waitFor(Duration.ZERO) waits with no time left: one poll, a single HTTP attempt without the
        // client's retries, whose timeout is the client timeout but at most one second.
        try (TestServer server = TestServer.start()) {
            server.json(200, EMPTY).json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer);
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            assertFalse(get.apply(client, budget(Duration.ZERO)).isPresent(), "a poll without a row");
            assertTrue(get.apply(client, budget(Duration.ZERO)).isPresent());
            assertEquals(2, http.requestCount());
            assertEquals(List.of(1000L, 1000L), http.timeoutMillis(), "min(client timeout of 5 s, 1 s)");
            assertTrue(timer.waits().isEmpty());
        }
        // A client timeout below one second is kept.
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer);
            ShieldLabsClient client =
                    Clients.history(server, timer).timeout(Duration.ofMillis(800)).httpClient(http).build();
            assertFalse(get.apply(client, budget(Duration.ZERO)).isPresent());
            assertEquals(List.of(800L), http.timeoutMillis());
        }
        // A failure of that poll is thrown at once, without a retry: a 5xx response, a 429 even with
        // Retry-After: 0, and a connection error.
        try (TestServer server = TestServer.start()) {
            server.json(503, "{\"error\":\"server is busy\"}").json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            assertThrows(ServerException.class, () -> get.apply(client, budget(Duration.ZERO)));
            assertEquals(1, server.requestCount());
            assertTrue(timer.waits().isEmpty());
        }
        try (TestServer server = TestServer.start()) {
            server.enqueue(429, TOO_MANY, "application/json", 0, "Retry-After", "0").json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            RateLimitException error =
                    assertThrows(RateLimitException.class, () -> get.apply(client, budget(Duration.ZERO)));
            assertEquals(Optional.of(Duration.ZERO), error.getRetryAfter());
            assertEquals(1, server.requestCount());
            assertTrue(timer.waits().isEmpty());
        }
        try (TestServer server = TestServer.start()) {
            server.always(200, FOUND, "application/json");
            FakeTimer timer = new FakeTimer();
            RecordingHttpClient http = new RecordingHttpClient(timer).fail(1, new ConnectException("Connection refused"));
            ShieldLabsClient client = Clients.history(server, timer).httpClient(http).build();
            assertThrows(ApiConnectionException.class, () -> get.apply(client, budget(Duration.ZERO)));
            assertEquals(1, http.requestCount());
            assertTrue(timer.waits().isEmpty());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    void theLaterOfWaitForAndNoWaitWins(String mode, Get get) {
        try (TestServer server = TestServer.start()) {
            server.json(503, "").json(200, FOUND).json(503, "").json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            // noWait() after waitFor(Duration.ZERO): a single lookup with the client retries.
            GetIdentificationOptions single = GetIdentificationOptions.builder().waitFor(Duration.ZERO).noWait().build();
            assertTrue(get.apply(client, single).isPresent());
            assertEquals(2, server.requestCount());
            // waitFor(Duration.ZERO) after noWait(): one poll without retries.
            GetIdentificationOptions onePoll = GetIdentificationOptions.builder().noWait().waitFor(Duration.ZERO).build();
            assertThrows(ServerException.class, () -> get.apply(client, onePoll));
            assertEquals(3, server.requestCount());
            assertEquals(List.of(500L), timer.waitMillis());
        }
    }

    @Test
    void asyncValidationFailsTheFutureWithoutRequest() {
        try (TestServer server = TestServer.start()) {
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            CompletionException error =
                    assertThrows(CompletionException.class, () -> client.identifications().getAsync("nope").join());
            assertInstanceOf(ValidationException.class, error.getCause());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void uppercaseRequestIdIsSentLowercase() {
        try (TestServer server = TestServer.start()) {
            server.json(200, FOUND);
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            client.identifications().get(Clients.REQUEST_ID.toUpperCase(java.util.Locale.ROOT));
            assertEquals("/api/v1/history/request_id/" + Clients.REQUEST_ID, server.requests().get(0).rawPath);
        }
    }

    @Test
    void cancellingTheAsyncWaitStopsPolling() throws Exception {
        try (TestServer server = TestServer.start()) {
            server.always(200, EMPTY, "application/json");
            BlockingQueue<CompletableFuture<Void>> pending = new LinkedBlockingQueue<>();
            Timer manual =
                    new Timer() {
                        @Override
                        public long nanoTime() {
                            return 0L;
                        }

                        @Override
                        public void sleep(Duration duration) {
                        }

                        @Override
                        public CompletableFuture<Void> delay(Duration duration) {
                            CompletableFuture<Void> future = new CompletableFuture<>();
                            pending.add(future);
                            return future;
                        }
                    };
            ShieldLabsClient client =
                    ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl(server.uri()).timer(manual).build();
            CompletableFuture<Optional<Identification>> future = client.identifications().getAsync(Clients.REQUEST_ID);
            CompletableFuture<Void> firstWait = pending.poll(10, TimeUnit.SECONDS);
            assertEquals(1, server.requestCount());
            assertTrue(future.cancel(true));
            firstWait.complete(null);
            Thread.sleep(200);
            assertEquals(1, server.requestCount(), "no poll after cancellation");
            assertTrue(pending.isEmpty());
        }
    }

    @Test
    void veryLongWaitsDoNotOverflow() {
        try (TestServer server = TestServer.start()) {
            server.json(200, EMPTY).json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            assertTrue(client.identifications().get(Clients.REQUEST_ID, budget(Duration.ofDays(1_000_000))).isPresent());
            assertEquals(List.of(250L), timer.waitMillis());
        }
        try (TestServer server = TestServer.start()) {
            server.json(200, EMPTY).json(200, FOUND);
            FakeTimer timer = new FakeTimer();
            ShieldLabsClient client = Clients.history(server, timer).build();
            // The longest possible interval is used as it is without overflowing, and the wait is cut
            // short at the (saturated) deadline, where the last poll runs.
            GetIdentificationOptions options =
                    GetIdentificationOptions.builder()
                            .waitFor(Duration.ofDays(1_000_000))
                            .pollInterval(Duration.ofSeconds(Long.MAX_VALUE))
                            .build();
            assertTrue(client.identifications().get(Clients.REQUEST_ID, options).isPresent());
            assertEquals(List.of(Duration.ofNanos(Long.MAX_VALUE / 4)), timer.waits());
        }
        assertEquals(Long.MAX_VALUE, IdentificationService.saturatedAdd(Long.MAX_VALUE - 1, 5));
        assertEquals(Long.MIN_VALUE, IdentificationService.saturatedAdd(Long.MIN_VALUE + 1, -5));
        assertEquals(7, IdentificationService.saturatedAdd(3, 4));
        assertEquals(Long.MAX_VALUE / 4, IdentificationService.saturatedNanos(Duration.ofDays(1_000_000)));
        assertEquals(1_000_000L, IdentificationService.saturatedNanos(Duration.ofMillis(1)));
    }

    @Test
    void scheduleHelper() {
        // The wait after the 1st, 2nd, ... poll: x1, x2, x4, x6, x8, then x8 again, each capped at
        // max(2 s, interval).
        assertSchedule(Duration.ofMillis(250), 250, 500, 1000, 1500, 2000, 2000, 2000);
        assertSchedule(Duration.ofMillis(100), 100, 200, 400, 600, 800, 800, 800);
        // No floor below 100 ms: 50 ms stays 50 ms.
        assertSchedule(Duration.ofMillis(50), 50, 100, 200, 300, 400, 400, 400);
        assertSchedule(Duration.ofMillis(300), 300, 600, 1200, 1800, 2000, 2000);
        assertSchedule(Duration.ofSeconds(1), 1000, 2000, 2000, 2000, 2000, 2000);
        assertSchedule(Duration.ofSeconds(2), 2000, 2000, 2000);
        assertSchedule(Duration.ofMillis(2500), 2500, 2500, 2500, 2500, 2500, 2500);
        assertSchedule(Duration.ofSeconds(3), 3000, 3000, 3000, 3000, 3000, 3000);
        for (int polls = 1; polls <= 6; polls++) {
            assertEquals(
                    Duration.ofSeconds(Long.MAX_VALUE),
                    IdentificationService.pollWait(Duration.ofSeconds(Long.MAX_VALUE), polls),
                    "the longest interval is used as it is, after poll " + polls);
        }
        assertEquals(250, IdentificationService.pollWait(Duration.ofMillis(250), 0).toMillis(), "counts start at 1");
    }

    private static void assertSchedule(Duration interval, long... expectedMillis) {
        for (int polls = 1; polls <= expectedMillis.length; polls++) {
            assertEquals(
                    expectedMillis[polls - 1],
                    IdentificationService.pollWait(interval, polls).toMillis(),
                    interval + " after poll " + polls);
        }
    }

    @Test
    void attemptTimeoutHelper() {
        Duration client = Duration.ofSeconds(5);
        assertEquals(Duration.ofSeconds(5), IdentificationService.attemptTimeout(client, Duration.ofSeconds(9).toNanos()));
        assertEquals(Duration.ofMillis(2500), IdentificationService.attemptTimeout(client, Duration.ofMillis(2500).toNanos()));
        assertEquals(Duration.ofSeconds(1), IdentificationService.attemptTimeout(client, Duration.ofMillis(300).toNanos()));
        assertEquals(Duration.ofSeconds(1), IdentificationService.attemptTimeout(client, -Duration.ofSeconds(3).toNanos()));
        assertEquals(Duration.ofMillis(400), IdentificationService.attemptTimeout(Duration.ofMillis(400), 0L));
    }

    @Test
    void optionValidation() {
        assertThrows(ValidationException.class, () -> GetIdentificationOptions.builder().waitFor(Duration.ofSeconds(-1)));
        assertThrows(ValidationException.class, () -> GetIdentificationOptions.builder().waitFor(null));
        assertThrows(ValidationException.class, () -> GetIdentificationOptions.builder().pollInterval(Duration.ZERO));
        assertThrows(ValidationException.class, () -> GetIdentificationOptions.builder().pollInterval(null));
        assertThrows(ValidationException.class, () -> GetIdentificationOptions.builder().pollInterval(Duration.ofMillis(-5)));
        assertEquals(GetIdentificationOptions.DEFAULT_WAIT, GetIdentificationOptions.defaults().getWaitFor());
        assertEquals(GetIdentificationOptions.DEFAULT_POLL_INTERVAL, GetIdentificationOptions.defaults().getPollInterval());
        assertFalse(GetIdentificationOptions.defaults().isNoWait());

        GetIdentificationOptions single =
                GetIdentificationOptions.builder().waitFor(Duration.ofSeconds(3)).noWait().build();
        assertTrue(single.isNoWait());
        assertEquals(Duration.ofSeconds(3), single.getWaitFor(), "kept, but it does not apply");
        GetIdentificationOptions onePoll = GetIdentificationOptions.builder().noWait().waitFor(Duration.ZERO).build();
        assertFalse(onePoll.isNoWait());
        assertEquals(Duration.ZERO, onePoll.getWaitFor());
    }

    @Test
    void realTimerDelaysWork() throws Exception {
        long start = Timer.SYSTEM.nanoTime();
        Timer.SYSTEM.sleep(Duration.ofMillis(20));
        Timer.SYSTEM.delay(Duration.ofMillis(20)).get();
        Timer.SYSTEM.delay(Duration.ZERO).get();
        Timer.SYSTEM.sleep(Duration.ZERO);
        assertTrue(System.nanoTime() - start >= Duration.ofMillis(40).toNanos());
    }
}
