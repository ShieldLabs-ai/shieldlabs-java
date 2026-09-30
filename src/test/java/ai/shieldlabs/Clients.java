package ai.shieldlabs;

import java.time.Duration;

/** Test clients wired to a {@link TestServer} and a {@link FakeTimer}. */
final class Clients {
    static final String API_KEY = "sec_abcd1234-efgh5678-ijkl9012";
    static final String SECRET_KEY = "0123456789abcdef0123456789abcdef";
    static final String REQUEST_ID = "3f2b8c1e-9d4a-4e6b-8a7c-2d1e0f9b6a53";

    private Clients() {
    }

    static ShieldLabsClient.Builder history(TestServer server, FakeTimer timer) {
        return ShieldLabsClient.builder()
                .apiKey(API_KEY)
                .baseUrl(server.uri())
                .timeout(Duration.ofSeconds(5))
                .timer(timer)
                .random(() -> 1.0);
    }

    static ManagementClient.Builder management(TestServer server, FakeTimer timer) {
        return ManagementClient.builder()
                .secretKey(SECRET_KEY)
                .domain("https://WWW.Example.com/")
                .baseUrl(server.uri())
                .timeout(Duration.ofSeconds(5))
                .timer(timer)
                .random(() -> 1.0);
    }

    /** A History body with the given rows (row JSON objects) and total. */
    static String page(long total, String... rows) {
        return "{\"data\":[" + String.join(",", rows) + "],\"total\":" + total + "}";
    }

    /** A minimal History row. */
    static String row(String requestId) {
        return "{\"request_id\":\"" + requestId + "\",\"score\":10,\"created_at\":\"2026-09-30 12:00:00.000\"}";
    }
}
