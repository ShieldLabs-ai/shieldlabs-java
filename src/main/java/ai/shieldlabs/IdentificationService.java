package ai.shieldlabs;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reads the verdict for one request ID. Obtain it from {@link ShieldLabsClient#identifications()}.
 * Thread-safe.
 *
 * <p>The browser receives a request ID as soon as the identification starts, but scoring is
 * asynchronous: the History row appears about 1 to 3 seconds after the browser call and can be
 * refined for up to about 10 seconds while follow-up checks finish. Start the identification when the
 * user begins the action (for example when the signup form gets focus), so the verdict is usually
 * stored by the time the form is submitted. {@link #get(String)} polls the History API until the row
 * appears and returns the first version it sees. {@link GetIdentificationOptions#getWaitFor()} is the
 * total time budget of the call, 10 seconds by default:
 *
 * <ul>
 *   <li>The first poll runs right away, then after waits of 250 ms, 500 ms, 1 s, 1.5 s and then every
 *       2 s. With another {@link GetIdentificationOptions#getPollInterval() poll interval} p the waits
 *       are p, 2p, 4p, 6p, 8p and then 8p again, each capped at 2 s, or at p when p is longer: 1 s
 *       waits 1, 2, 2, 2 s, and 3 s polls every 3 s. A wait that would pass the deadline is cut
 *       short, so the last poll runs at the deadline.</li>
 *   <li>Each poll is one HTTP attempt, without the client's retries. Its timeout is the client timeout,
 *       shortened to the time left but never below one second.</li>
 *   <li>A 429, a 5xx response, a connection error or a timeout does not end the wait: the next poll
 *       follows on the schedule. At the deadline, the exception of the last poll is thrown if it
 *       failed; otherwise the result is empty.</li>
 *   <li>The History API allows about 15 requests per second per domain for all callers together,
 *       counted per one-second window. After a 429 the next poll waits at least one second: the
 *       longest of the next scheduled wait, one second and {@code Retry-After} (at most 10 seconds). A
 *       {@code Retry-After} of 0 or a date in the past counts as 0, so the one-second minimum still
 *       applies. The wait is cut short only to keep the last poll at the deadline, and a
 *       {@code Retry-After} longer than the time left throws the {@link RateLimitException} at
 *       once.</li>
 *   <li>A 400, 401, 403 or 404 ends the wait at once with its exception: a wrong key or base URL does
 *       not heal.</li>
 * </ul>
 *
 * <p>An empty result means "unverified", never "clean". It also covers an identification that was
 * never stored: while a visitor's IP is over the per-IP limit of identifications, the browser still
 * receives a request ID, but no row is written for it.
 */
public final class IdentificationService {
    /**
     * Longest wait between polls for a poll interval up to 2 s. A longer interval is its own cap: each
     * wait is at most max(2 s, interval).
     */
    static final Duration MAX_POLL_WAIT = Duration.ofSeconds(2);
    static final Duration MIN_ATTEMPT_TIMEOUT = Duration.ofSeconds(1);
    private static final int[] POLL_MULTIPLIERS = {1, 2, 4, 6, 8};

    private final Transport transport;
    private final HistoryService history;

    IdentificationService(Transport transport, HistoryService history) {
        this.transport = transport;
        this.history = history;
    }

    /**
     * Reads the identification for a request ID, waiting up to 10 seconds for the verdict.
     *
     * @param requestId the request ID the browser sent with the protected action
     * @return the identification, or empty when none appeared in time. Treat empty as "unverified",
     *     never as "clean".
     * @throws ValidationException when the request ID is not a UUID (nothing is sent)
     * @throws ShieldLabsException when the request fails
     * @see #get(String, GetIdentificationOptions)
     */
    public Optional<Identification> get(String requestId) {
        return get(requestId, null);
    }

    /**
     * Reads the identification for a request ID.
     *
     * <p>By default this polls within a total budget of 10 seconds, as described in the class
     * documentation. A zero budget ({@code waitFor(Duration.ZERO)}) makes one poll without waiting: a
     * single HTTP attempt without retries, with the client timeout but at most one second, whose
     * failure is thrown. With {@link GetIdentificationOptions.Builder#noWait()} it makes a single
     * lookup, retried like any History request.
     *
     * @param requestId the request ID the browser sent with the protected action
     * @param options how long to wait, or {@code null} for the defaults
     * @return the identification, or empty when none appeared in time (the last poll answered without
     *     a row). Treat empty as "unverified", never as "clean".
     * @throws ValidationException when the request ID is not a UUID (nothing is sent)
     * @throws BadRequestException for a 400 (polling stops at once)
     * @throws AuthenticationException for a 401 or 403: the key is rejected (polling stops at once)
     * @throws NotFoundException for a 404, usually a wrong base URL (polling stops at once)
     * @throws RateLimitException when the last poll was rate limited, or when {@code Retry-After} asks
     *     for longer than the time left
     * @throws ShieldLabsException when the last poll failed ({@link ServerException},
     *     {@link ApiConnectionException}, {@link ApiTimeoutException}) or the response is unusable
     */
    public Optional<Identification> get(String requestId, GetIdentificationOptions options) {
        GetIdentificationOptions opts = options == null ? GetIdentificationOptions.defaults() : options;
        URI uri = lookupUri(requestId);
        if (opts.isNoWait()) {
            return first(HistoryService.parsePage(transport.get(uri)));
        }
        Poll poll = new Poll(opts);
        while (true) {
            JsonResponse response = null;
            ShieldLabsException error = null;
            try {
                response = transport.attempt(uri, poll.attemptTimeout());
            } catch (ShieldLabsException e) {
                error = e;
            }
            Step step = poll.after(response, error);
            if (step.failure != null) {
                throw step.failure;
            }
            if (step.result != null) {
                return step.result;
            }
            try {
                transport.timer().sleep(step.wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiConnectionException("Interrupted while waiting for the verdict", e);
            }
        }
    }

    /**
     * Asynchronous {@link #get(String)}.
     *
     * @param requestId the request ID the browser sent with the protected action
     * @return a future with the identification or empty; it fails with a {@link ShieldLabsException}
     */
    public CompletableFuture<Optional<Identification>> getAsync(String requestId) {
        return getAsync(requestId, null);
    }

    /**
     * Asynchronous {@link #get(String, GetIdentificationOptions)}, with the same polling rules. Waiting
     * uses scheduled delays and does not block a thread. Cancelling the returned future stops further
     * polls.
     *
     * @param requestId the request ID the browser sent with the protected action
     * @param options how long to wait, or {@code null} for the defaults
     * @return a future with the identification or empty; it fails with a {@link ShieldLabsException}
     *     (including {@link ValidationException}, in which case nothing is sent)
     */
    public CompletableFuture<Optional<Identification>> getAsync(
            String requestId, GetIdentificationOptions options) {
        GetIdentificationOptions opts = options == null ? GetIdentificationOptions.defaults() : options;
        URI uri;
        try {
            uri = lookupUri(requestId);
        } catch (ValidationException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (opts.isNoWait()) {
            return transport.getAsync(uri).thenApply(response -> first(HistoryService.parsePage(response)));
        }
        CompletableFuture<Optional<Identification>> result = new CompletableFuture<>();
        pollStep(uri, new Poll(opts), result);
        return result;
    }

    /** One poll; schedules the next one unless the caller has cancelled or completed {@code result}. */
    private void pollStep(URI uri, Poll poll, CompletableFuture<Optional<Identification>> result) {
        if (result.isDone()) {
            return;
        }
        transport.attemptAsync(uri, poll.attemptTimeout())
                .whenComplete(
                        (response, error) -> {
                            try {
                                ShieldLabsException failure = null;
                                if (error != null) {
                                    Throwable cause = Transport.unwrap(error);
                                    if (!(cause instanceof ShieldLabsException)) {
                                        result.completeExceptionally(cause);
                                        return;
                                    }
                                    failure = (ShieldLabsException) cause;
                                }
                                Step step = poll.after(response, failure);
                                if (step.failure != null) {
                                    result.completeExceptionally(step.failure);
                                } else if (step.result != null) {
                                    result.complete(step.result);
                                } else {
                                    transport.timer().delay(step.wait).whenComplete((ignored, e) -> pollStep(uri, poll, result));
                                }
                            } catch (RuntimeException e) {
                                result.completeExceptionally(e);
                            }
                        });
    }

    private URI lookupUri(String requestId) {
        String id = Validation.uuid(requestId, "requestId");
        return history.uri(LookupType.REQUEST_ID, id, 1, 0);
    }

    private static Optional<Identification> first(HistoryPage page) {
        List<Identification> items = page.getIdentifications();
        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
    }

    /**
     * Failures that do not end a wait, because a later poll can succeed: 429, 5xx, connection errors
     * (except an interrupt) and timeouts.
     */
    static boolean keepsWaiting(ShieldLabsException error) {
        return error instanceof RateLimitException || Transport.isTransient(error);
    }

    /**
     * The wait after the n-th poll (1-based): interval x1, x2, x4, x6, x8, then x8 again, each capped at
     * max(2 s, interval), so an interval above 2 s is used as it is.
     */
    static Duration pollWait(Duration interval, int pollsDone) {
        if (interval.compareTo(MAX_POLL_WAIT) >= 0) {
            // The interval is its own cap and no multiple is shorter; returning early also avoids
            // multiplying a huge interval.
            return interval;
        }
        int index = Math.min(Math.max(pollsDone, 1) - 1, POLL_MULTIPLIERS.length - 1);
        return min(interval.multipliedBy(POLL_MULTIPLIERS[index]), MAX_POLL_WAIT);
    }

    /** Timeout of one poll: {@code min(client timeout, max(time left, 1 s))}. */
    static Duration attemptTimeout(Duration clientTimeout, long remainingNanos) {
        return min(clientTimeout, max(Duration.ofNanos(Math.max(0L, remainingNanos)), MIN_ATTEMPT_TIMEOUT));
    }

    /** Nanoseconds of a duration, capped instead of overflowing (a wait of centuries stays valid). */
    static long saturatedNanos(Duration duration) {
        return duration.compareTo(Duration.ofNanos(Long.MAX_VALUE / 4)) > 0 ? Long.MAX_VALUE / 4 : duration.toNanos();
    }

    static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? (a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE) : sum;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** What follows a poll: a result to return, an exception to throw, or a wait before the next poll. */
    private static final class Step {
        final Optional<Identification> result;
        final ShieldLabsException failure;
        final Duration wait;

        private Step(Optional<Identification> result, ShieldLabsException failure, Duration wait) {
            this.result = result;
            this.failure = failure;
            this.wait = wait;
        }

        static Step found(Identification identification) {
            return new Step(Optional.of(identification), null, null);
        }

        static Step notFound() {
            return new Step(Optional.empty(), null, null);
        }

        static Step fail(ShieldLabsException failure) {
            return new Step(null, failure, null);
        }

        static Step pause(Duration wait) {
            return new Step(null, null, wait);
        }
    }

    /** State of one wait-for-verdict call. Steps run one after another, never concurrently. */
    private final class Poll {
        private final Duration interval;
        private final long deadline;
        private int waits;
        private boolean lastPoll;

        Poll(GetIdentificationOptions options) {
            this.interval = options.getPollInterval();
            this.deadline = saturatedAdd(transport.timer().nanoTime(), saturatedNanos(options.getWaitFor()));
        }

        private long remainingNanos() {
            return deadline - transport.timer().nanoTime();
        }

        Duration attemptTimeout() {
            return IdentificationService.attemptTimeout(transport.timeout(), remainingNanos());
        }

        /** Decides what follows a poll that returned {@code response} or failed with {@code error}. */
        Step after(JsonResponse response, ShieldLabsException error) {
            if (error == null) {
                HistoryPage page;
                try {
                    page = HistoryService.parsePage(response);
                } catch (ShieldLabsException e) {
                    return Step.fail(e);
                }
                if (!page.getIdentifications().isEmpty()) {
                    return Step.found(page.getIdentifications().get(0));
                }
            } else if (!keepsWaiting(error)) {
                // 400, 401, 403, 404 and any other failure that another poll cannot change.
                return Step.fail(error);
            }
            long remaining = remainingNanos();
            if (lastPoll || remaining <= 0) {
                // The time is up: the outcome of the last poll stands.
                return error == null ? Step.notFound() : Step.fail(error);
            }
            waits++;
            Duration wait = pollWait(interval, waits);
            if (error instanceof RateLimitException) {
                // Retry-After capped at 10 s. A missing header, 0 and a date in the past all count as 0.
                Duration retryAfter = min(
                        ((RateLimitException) error).getRetryAfter().orElse(Duration.ZERO), Transport.RETRY_AFTER_CAP);
                if (retryAfter.toNanos() > remaining) {
                    // No later poll fits before the deadline.
                    return Step.fail(error);
                }
                // The one-second minimum applies with or without Retry-After: the limit counts requests
                // per one-second window, so a sooner poll would be refused again.
                wait = max(wait, max(Transport.MIN_RATE_LIMIT_WAIT, retryAfter));
            }
            Duration left = Duration.ofNanos(remaining);
            if (wait.compareTo(left) >= 0) {
                // Cut short: the poll after this wait runs at the deadline and is the last one, whatever
                // the clock reads when it wakes up.
                wait = left;
                lastPoll = true;
            }
            return Step.pause(wait);
        }
    }
}
