package ai.shieldlabs;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * Sends GET requests with the SDK headers, maps failures to SDK exceptions and retries transient
 * failures with exponential backoff and jitter. Thread-safe.
 */
final class Transport {
    static final Duration BACKOFF_BASE = Duration.ofMillis(500);
    static final Duration BACKOFF_CAP = Duration.ofSeconds(8);
    static final Duration RETRY_AFTER_CAP = Duration.ofSeconds(10);
    /**
     * Shortest wait before retrying a 429 that came without {@code Retry-After}, and before the next
     * poll after any 429 while {@link IdentificationService} waits for a verdict. The History API
     * counts requests in fixed one-second windows per domain, so a sooner request would land in the
     * same window.
     */
    static final Duration MIN_RATE_LIMIT_WAIT = Duration.ofSeconds(1);

    private final HttpClient http;
    private final Duration timeout;
    private final int maxRetries;
    private final boolean retryRateLimited;
    private final String[] headers;
    private final Timer timer;
    private final DoubleSupplier random;

    Transport(
            HttpClient http,
            Duration timeout,
            int maxRetries,
            boolean retryRateLimited,
            String[] headers,
            Timer timer,
            DoubleSupplier random) {
        this.http = http;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.retryRateLimited = retryRateLimited;
        this.headers = Arrays.copyOf(headers, headers.length);
        this.timer = timer;
        this.random = random;
    }

    Duration timeout() {
        return timeout;
    }

    Timer timer() {
        return timer;
    }

    /** The value of the {@code User-Agent} header: SDK name and version plus runtime information. */
    static String userAgent() {
        return "shieldlabs-java/" + ShieldLabsClient.VERSION
                + " (Java " + property("java.version") + "; " + property("os.name") + ")";
    }

    private static String property(String name) {
        String value;
        try {
            value = System.getProperty(name);
        } catch (SecurityException e) {
            value = null;
        }
        String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9._+ -]", "").strip();
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /** GET with retries. */
    JsonResponse get(URI uri) {
        int retry = 0;
        while (true) {
            try {
                return attempt(uri, timeout);
            } catch (ShieldLabsException e) {
                if (retry >= maxRetries || !retryable(e)) {
                    throw e;
                }
                sleep(retryDelay(retry, e), e);
                retry++;
            }
        }
    }

    /** Asynchronous GET with retries. The future fails with a {@link ShieldLabsException}. */
    CompletableFuture<JsonResponse> getAsync(URI uri) {
        return getAsync(uri, 0);
    }

    private CompletableFuture<JsonResponse> getAsync(URI uri, int retry) {
        return attemptAsync(uri, timeout)
                .handle(
                        (response, error) -> {
                            if (error == null) {
                                return CompletableFuture.completedFuture(response);
                            }
                            Throwable cause = unwrap(error);
                            if (!(cause instanceof ShieldLabsException)) {
                                return CompletableFuture.<JsonResponse>failedFuture(cause);
                            }
                            ShieldLabsException e = (ShieldLabsException) cause;
                            if (retry >= maxRetries || !retryable(e)) {
                                return CompletableFuture.<JsonResponse>failedFuture(e);
                            }
                            return timer.delay(retryDelay(retry, e))
                                    .thenCompose(ignored -> getAsync(uri, retry + 1));
                        })
                .thenCompose(Function.identity());
    }

    /** One attempt without retries. */
    JsonResponse attempt(URI uri, Duration attemptTimeout) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request(uri, attemptTimeout), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw mapIo(e, attemptTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiConnectionException("The request was interrupted", e);
        }
        return JsonResponse.from(response);
    }

    /** One asynchronous attempt without retries. */
    CompletableFuture<JsonResponse> attemptAsync(URI uri, Duration attemptTimeout) {
        CompletableFuture<HttpResponse<byte[]>> future;
        try {
            future = http.sendAsync(request(uri, attemptTimeout), HttpResponse.BodyHandlers.ofByteArray());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return future.handle(
                (response, error) -> {
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        if (cause instanceof IOException) {
                            throw mapIo((IOException) cause, attemptTimeout);
                        }
                        throw error instanceof CompletionException
                                ? (CompletionException) error
                                : new CompletionException(cause);
                    }
                    return JsonResponse.from(response);
                });
    }

    private HttpRequest request(URI uri, Duration attemptTimeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(attemptTimeout).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return builder.build();
    }

    private static ShieldLabsException mapIo(IOException e, Duration attemptTimeout) {
        if (e instanceof HttpTimeoutException) {
            return new ApiTimeoutException(
                    "No response within " + attemptTimeout.toMillis() + " ms", e);
        }
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ApiConnectionException("Connection failed: " + detail, e);
    }

    /** Whether a failure is transient: connection errors, timeouts, 5xx and (History only) 429. */
    boolean retryable(ShieldLabsException e) {
        if (e instanceof RateLimitException) {
            return retryRateLimited;
        }
        return isTransient(e);
    }

    /** Connection errors (except interrupts), timeouts and 5xx responses. */
    static boolean isTransient(ShieldLabsException e) {
        if (e instanceof ServerException || e instanceof ApiTimeoutException) {
            return true;
        }
        return e instanceof ApiConnectionException && !(e.getCause() instanceof InterruptedException);
    }

    /**
     * Delay before retry number {@code retry} (0-based): {@code Retry-After} as sent when present,
     * capped at 10 s (0 or a date in the past retries at once); otherwise backoff, and at least
     * {@link #MIN_RATE_LIMIT_WAIT} after a 429.
     */
    Duration retryDelay(int retry, ShieldLabsException e) {
        Duration retryAfter = retryAfterOf(e);
        if (retryAfter != null) {
            return retryAfter.compareTo(RETRY_AFTER_CAP) > 0 ? RETRY_AFTER_CAP : retryAfter;
        }
        Duration delay = backoff(retry);
        if (e instanceof RateLimitException && delay.compareTo(MIN_RATE_LIMIT_WAIT) < 0) {
            return MIN_RATE_LIMIT_WAIT;
        }
        return delay;
    }

    /** Exponential backoff (0.5 s, 1 s, 2 s, ... capped at 8 s) with jitter in the upper half. */
    Duration backoff(int retry) {
        long cap = BACKOFF_CAP.toNanos();
        long base = BACKOFF_BASE.toNanos();
        long raw = retry >= 5 ? cap : Math.min(cap, base << retry);
        double jitter = Math.min(1.0, Math.max(0.0, random.getAsDouble()));
        return Duration.ofNanos(raw / 2 + (long) (jitter * (raw / 2)));
    }

    static Duration retryAfterOf(ShieldLabsException e) {
        if (e instanceof RateLimitException) {
            return ((RateLimitException) e).getRetryAfter().orElse(null);
        }
        if (e instanceof ApiException) {
            return HttpErrors.retryAfter(((ApiException) e).getHeaders());
        }
        return null;
    }

    private void sleep(Duration delay, ShieldLabsException pending) {
        try {
            timer.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ApiConnectionException interrupted = new ApiConnectionException("Interrupted while waiting to retry", ie);
            interrupted.addSuppressed(pending);
            throw interrupted;
        }
    }

    /** Removes {@link CompletionException} and {@link ExecutionException} wrappers. */
    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
