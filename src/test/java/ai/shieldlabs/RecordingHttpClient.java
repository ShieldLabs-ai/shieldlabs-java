package ai.shieldlabs;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An {@link HttpClient} for tests. It sends requests through a real client (to a {@link TestServer}),
 * records the timeout of every request and the clock time at which it was sent, can let each request
 * take time on a {@link FakeTimer}, and can fail chosen requests (with a connection error, a timeout or
 * any other exception) instead of sending them.
 */
final class RecordingHttpClient extends HttpClient {
    private static final HttpClient DELEGATE = HttpClient.newHttpClient();

    private final FakeTimer clock;
    private final long start;
    private final AtomicInteger count = new AtomicInteger();
    private final List<Duration> timeouts = new CopyOnWriteArrayList<>();
    private final List<Long> sentAt = new CopyOnWriteArrayList<>();
    private final Map<Integer, Exception> failures = new ConcurrentHashMap<>();
    private volatile Duration latency = Duration.ZERO;

    /** Records times relative to now on {@code clock}. */
    RecordingHttpClient(FakeTimer clock) {
        this.clock = clock;
        this.start = clock.nanoTime();
    }

    /** Makes request number {@code number} (1-based) fail with {@code error} instead of being sent. */
    RecordingHttpClient fail(int number, IOException error) {
        failures.put(number, error);
        return this;
    }

    /** Makes request number {@code number} (1-based) throw {@code error} instead of being sent. */
    RecordingHttpClient fail(int number, RuntimeException error) {
        failures.put(number, error);
        return this;
    }

    /** Moves the clock by {@code latency} during every request, as if each answer took that long. */
    RecordingHttpClient latency(Duration latency) {
        this.latency = latency;
        return this;
    }

    int requestCount() {
        return count.get();
    }

    /** The timeout of every request in milliseconds, in order. */
    List<Long> timeoutMillis() {
        List<Long> millis = new ArrayList<>();
        for (Duration timeout : timeouts) {
            millis.add(timeout.toMillis());
        }
        return millis;
    }

    /** When every request was sent, in milliseconds since this client was created, in order. */
    List<Long> sentAtMillis() {
        return new ArrayList<>(sentAt);
    }

    private Exception record(HttpRequest request) {
        int number = count.incrementAndGet();
        timeouts.add(request.timeout().orElse(Duration.ZERO));
        sentAt.add(Duration.ofNanos(clock.nanoTime() - start).toMillis());
        clock.advance(latency);
        return failures.get(number);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        Exception failure = record(request);
        if (failure instanceof IOException) {
            throw (IOException) failure;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
        return DELEGATE.send(request, handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        Exception failure = record(request);
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        }
        return DELEGATE.sendAsync(request, handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
        return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return DELEGATE.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return DELEGATE.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return DELEGATE.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return DELEGATE.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return DELEGATE.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return DELEGATE.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return DELEGATE.authenticator();
    }

    @Override
    public Version version() {
        return DELEGATE.version();
    }

    @Override
    public Optional<Executor> executor() {
        return DELEGATE.executor();
    }
}
