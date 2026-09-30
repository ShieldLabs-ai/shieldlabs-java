package ai.shieldlabs;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A scripted HTTP server on the loopback interface, built on {@code com.sun.net.httpserver}. */
final class TestServer implements AutoCloseable {
    /** One scripted response. */
    static final class Reply {
        final int status;
        final String body;
        final Map<String, String> headers;
        final long delayMillis;

        Reply(int status, String body, Map<String, String> headers, long delayMillis) {
            this.status = status;
            this.body = body;
            this.headers = headers;
            this.delayMillis = delayMillis;
        }
    }

    /** One received request. */
    static final class Recorded {
        final String method;
        final String rawPath;
        final String rawQuery;
        final Headers headers;

        Recorded(String method, String rawPath, String rawQuery, Headers headers) {
            this.method = method;
            this.rawPath = rawPath;
            this.rawQuery = rawQuery;
            this.headers = headers;
        }

        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Reply fallback;

    private TestServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static TestServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            TestServer testServer = new TestServer(server, executor);
            server.createContext("/", testServer::handle);
            server.setExecutor(executor);
            server.start();
            return testServer;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** Queues a JSON response. */
    TestServer json(int status, String body) {
        return enqueue(status, body, "application/json", 0);
    }

    /** Queues a response; {@code contentType} may be {@code null}. */
    TestServer enqueue(int status, String body, String contentType, long delayMillis, String... extraHeaders) {
        replies.addLast(reply(status, body, contentType, delayMillis, extraHeaders));
        return this;
    }

    /** Response used when the queue is empty. */
    TestServer always(int status, String body, String contentType, String... extraHeaders) {
        fallback = reply(status, body, contentType, 0, extraHeaders);
        return this;
    }

    private static Reply reply(int status, String body, String contentType, long delayMillis, String... extraHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            headers.put(extraHeaders[i], extraHeaders[i + 1]);
        }
        return new Reply(status, body == null ? "" : body, headers, delayMillis);
    }

    List<Recorded> requests() {
        return Collections.unmodifiableList(new ArrayList<>(requests));
    }

    int requestCount() {
        return requests.size();
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        Headers copy = new Headers();
        copy.putAll(exchange.getRequestHeaders());
        requests.add(new Recorded(exchange.getRequestMethod(), uri.getRawPath(), uri.getRawQuery(), copy));
        exchange.getRequestBody().readAllBytes();
        Reply reply = replies.pollFirst();
        if (reply == null) {
            reply = fallback;
        }
        if (reply == null) {
            reply = new Reply(599, "no response scripted", Map.of(), 0);
        }
        try {
            if (reply.delayMillis > 0) {
                Thread.sleep(reply.delayMillis);
            }
            for (Map.Entry<String, String> header : reply.headers.entrySet()) {
                exchange.getResponseHeaders().add(header.getKey(), header.getValue());
            }
            byte[] body = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // The client went away (for example after a timeout).
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
