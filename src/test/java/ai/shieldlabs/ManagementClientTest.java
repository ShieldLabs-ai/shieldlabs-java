package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ManagementClientTest {

    @Test
    void readsTheProfileFixture() {
        try (TestServer server = TestServer.start()) {
            server.enqueue(200, Fixtures.text("management-profile.json"), "application/json; charset=utf-8", 0);
            ManagementClient client = Clients.management(server, new FakeTimer()).build();
            DomainProfile profile = client.getProfile();

            Map<String, Object> expected = new LinkedHashMap<>(Fixtures.object("management-profile-expected.json"));
            Map<String, Object> actual = new LinkedHashMap<>(Fixtures.serialized(profile));
            assertEquals(
                    Instant.parse((String) expected.remove("created_at")),
                    profile.getCreatedAt().truncatedTo(ChronoUnit.MILLIS));
            assertEquals("2026-01-15T09:00:00.000Z", actual.remove("created_at"));
            assertEquals(expected, actual);

            assertEquals("example.com", profile.getDomain());
            assertEquals(148230L, profile.getRemainingIdentifications());
            assertEquals("****************************a3f8", profile.getPublicKeyMasked());
            assertEquals("****************************9c2d", profile.getSecretKeyMasked());
            assertEquals("", profile.raw().get("Callback"), "the legacy Callback field stays in raw");
            assertTrue(profile.toString().contains("example.com"));

            TestServer.Recorded request = server.requests().get(0);
            assertEquals("GET", request.method);
            assertEquals("/v1/profile", request.rawPath);
            assertEquals("example.com", request.header("X-Shield-Domain"));
            assertEquals("Bearer " + Clients.SECRET_KEY, request.header("Authorization"));
            assertEquals("application/json", request.header("Accept"));
            assertTrue(request.header("User-Agent").startsWith("shieldlabs-java/"));
        }
    }

    @Test
    void negativeRemainingAndOddValuesAreTolerated() {
        try (TestServer server = TestServer.start()) {
            server.json(200, "{\"Domain\":\"example.com\",\"Weight\":-120,\"CreatedAt\":\"0001-01-01T00:00:00Z\"}");
            server.json(200, "{\"Domain\":\"example.com\",\"Weight\":1.5,\"CreatedAt\":\"yesterday\"}");
            ManagementClient client = Clients.management(server, new FakeTimer()).build();
            DomainProfile debt = client.getProfile();
            assertEquals(-120L, debt.getRemainingIdentifications());
            assertEquals(Instant.parse("0001-01-01T00:00:00Z"), debt.getCreatedAt());
            assertEquals("", debt.getPublicKeyMasked());
            DomainProfile odd = client.getProfile();
            assertEquals(0L, odd.getRemainingIdentifications());
            assertNull(odd.getCreatedAt());
            assertNull(Fixtures.serialized(odd).get("created_at"));
        }
    }

    @Test
    void rateLimitIsNeverRetried() {
        try (TestServer server = TestServer.start()) {
            server.always(429, "{\"error\":\"too many requests\"}", "application/json; charset=utf-8", "Retry-After", "600");
            FakeTimer timer = new FakeTimer();
            ManagementClient client = Clients.management(server, timer).maxRetries(5).build();
            RateLimitException error = assertThrows(RateLimitException.class, client::getProfile);
            assertEquals(1, server.requestCount());
            assertTrue(timer.waits().isEmpty());
            assertEquals(Optional.of(Duration.ofMinutes(10)), error.getRetryAfter());

            CompletionException asyncError = assertThrows(CompletionException.class, () -> client.getProfileAsync().join());
            assertInstanceOf(RateLimitException.class, asyncError.getCause());
            assertEquals(2, server.requestCount());
        }
    }

    @Test
    void busyServerIsRetried() {
        try (TestServer server = TestServer.start()) {
            server.json(503, "{\"error\":\"server is busy\"}").json(200, Fixtures.text("management-profile.json"));
            FakeTimer timer = new FakeTimer();
            ManagementClient client = Clients.management(server, timer).build();
            assertEquals("example.com", client.getProfileAsync().join().getDomain());
            assertEquals(List.of(500L), timer.waitMillis());
        }
    }

    @Test
    void emptyAuthenticationErrorBody() {
        try (TestServer server = TestServer.start()) {
            server.always(401, "", null);
            ManagementClient client = Clients.management(server, new FakeTimer()).build();
            AuthenticationException error = assertThrows(AuthenticationException.class, client::getProfile);
            assertEquals("", error.getBody());
            assertEquals(Optional.empty(), error.getErrorMessage());
            assertEquals("HTTP 401", error.getMessage());
        }
    }

    @Test
    void unexpectedBodyIsAnApiError() {
        try (TestServer server = TestServer.start()) {
            server.json(200, "\"ok\"");
            ManagementClient client = Clients.management(server, new FakeTimer()).build();
            ApiException error = assertThrows(ApiException.class, client::getProfile);
            assertTrue(error.getMessage().contains("Unexpected response body"));
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "example.com|example.com",
                "'  Example.COM  '|example.com",
                "https://example.com|example.com",
                "http://www.example.com/|example.com",
                "HTTPS://WWW.Example.com/path/to?x=1#top|example.com",
                "www.shop.example.com|shop.example.com",
                "shop.example.com/|shop.example.com",
                "example.com?utm=1|example.com",
                "example.com#frag|example.com",
                "wwwexample.com|wwwexample.com",
                "example.com:8443|example.com:8443"
            })
    void normalizesTheDomain(String input, String expected) {
        assertEquals(expected, ManagementClient.normalizeDomain(input));
        ManagementClient client = ManagementClient.builder().secretKey(Clients.SECRET_KEY).domain(input).build();
        assertEquals(expected, client.getDomain());
    }

    @Test
    void validation() {
        assertThrows(ValidationException.class, () -> ManagementClient.builder().domain("example.com").build());
        assertThrows(ValidationException.class, () -> ManagementClient.builder().secretKey("").domain("example.com").build());
        assertThrows(ValidationException.class, () -> ManagementClient.builder().secretKey("k").build());
        assertThrows(ValidationException.class, () -> ManagementClient.builder().secretKey("k").domain("https://").build());
        assertThrows(ValidationException.class, () -> ManagementClient.builder().secretKey("k").domain("   ").build());
        assertThrows(
                ValidationException.class, () -> ManagementClient.builder().secretKey("a\nb").domain("example.com").build());
        assertThrows(
                ValidationException.class,
                () -> ManagementClient.builder().secretKey("k").domain("\u043f\u0440\u0438\u043c\u0435\u0440.example").build());
        assertEquals(
                "xn--e1afmkfd.example",
                ManagementClient.builder().secretKey("k").domain("xn--e1afmkfd.example").build().getDomain());
        assertThrows(
                ValidationException.class,
                () -> ManagementClient.builder().secretKey("k").domain("example.com").timeout(Duration.ZERO).build());
        assertThrows(
                ValidationException.class,
                () -> ManagementClient.builder().secretKey("k").domain("example.com").timeout(null).build());
        assertThrows(
                ValidationException.class,
                () -> ManagementClient.builder().secretKey("k").domain("example.com").maxRetries(-1).build());
        assertThrows(
                ValidationException.class,
                () -> ManagementClient.builder().secretKey("k").domain("example.com").baseUrl("ftp://example.com").build());
        ManagementClient client = ManagementClient.builder().secretKey("k").domain("example.com").build();
        assertEquals(ManagementClient.DEFAULT_BASE_URL, client.getBaseUrl());
        assertTrue(client.toString().contains("example.com"));
        ManagementClient custom =
                ManagementClient.builder()
                        .secretKey("k")
                        .domain("example.com")
                        .baseUrl("https://dev.api.example.com/")
                        .httpClient(java.net.http.HttpClient.newHttpClient())
                        .build();
        assertEquals("https://dev.api.example.com", custom.getBaseUrl().toString());
    }

    @Test
    void plainHttpBaseUrlsNeedALoopbackHostOrAnOptIn() {
        ValidationException insecure =
                assertThrows(
                        ValidationException.class,
                        () -> ManagementClient.builder()
                                .secretKey("k")
                                .domain("example.com")
                                .baseUrl("http://api.example.com")
                                .build());
        assertTrue(insecure.getMessage().contains("https"), insecure.getMessage());
        ManagementClient local =
                ManagementClient.builder().secretKey("k").domain("example.com").baseUrl("http://localhost:8081/").build();
        assertEquals("http://localhost:8081", local.getBaseUrl().toString());
        ManagementClient optedIn =
                ManagementClient.builder()
                        .secretKey("k")
                        .domain("example.com")
                        .baseUrl("http://mock-management:8081")
                        .allowInsecureHttp(true)
                        .build();
        assertEquals("http://mock-management:8081", optedIn.getBaseUrl().toString());
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SHIELDLABS_SECRET_KEY", Clients.SECRET_KEY);
        env.put("SHIELDLABS_DOMAIN", "example.com");
        env.put("SHIELDLABS_MANAGEMENT_BASE_URL", "http://api.example.com");
        assertThrows(ValidationException.class, () -> ManagementClient.fromEnvironment(env::get));
    }

    @Test
    void surroundingWhitespaceIsRemovedFromTheSecretKey() {
        try (TestServer server = TestServer.start()) {
            server.always(200, Fixtures.text("management-profile.json"), "application/json; charset=utf-8");
            for (String suffix : new String[] {"\n", "\r\n"}) {
                Clients.management(server, new FakeTimer()).secretKey(Clients.SECRET_KEY + suffix).build().getProfile();
            }
            Map<String, String> env = new LinkedHashMap<>();
            env.put("SHIELDLABS_SECRET_KEY", Clients.SECRET_KEY + "\r\n");
            env.put("SHIELDLABS_DOMAIN", "example.com\r\n");
            env.put("SHIELDLABS_MANAGEMENT_BASE_URL", server.uri().toString());
            ManagementClient.fromEnvironment(env::get).getProfile();
            assertEquals(3, server.requestCount());
            for (TestServer.Recorded request : server.requests()) {
                assertEquals("Bearer " + Clients.SECRET_KEY, request.header("Authorization"));
                assertEquals("example.com", request.header("X-Shield-Domain"));
            }
        }
    }

    @Test
    void onlyTheDomainErrorMentionsPunycode() {
        ValidationException domain =
                assertThrows(
                        ValidationException.class,
                        () -> ManagementClient.builder().secretKey("k").domain("\u043f\u0440\u0438\u043c\u0435\u0440.example").build());
        assertTrue(domain.getMessage().contains("punycode"), domain.getMessage());
        ValidationException secret =
                assertThrows(
                        ValidationException.class,
                        () -> ManagementClient.builder().secretKey("k\u00e9").domain("example.com").build());
        assertEquals("secretKey must contain printable ASCII characters only", secret.getMessage());
    }

    @Test
    void fromEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        assertThrows(ValidationException.class, () -> ManagementClient.fromEnvironment(env::get));
        env.put("SHIELDLABS_SECRET_KEY", Clients.SECRET_KEY);
        assertThrows(ValidationException.class, () -> ManagementClient.fromEnvironment(env::get));
        env.put("SHIELDLABS_DOMAIN", "www.example.com");
        ManagementClient client = ManagementClient.fromEnvironment(env::get);
        assertEquals("example.com", client.getDomain());
        assertEquals(ManagementClient.DEFAULT_BASE_URL, client.getBaseUrl());
        env.put("SHIELDLABS_MANAGEMENT_BASE_URL", "http://127.0.0.1:9");
        assertEquals("http://127.0.0.1:9", ManagementClient.fromEnvironment(env::get).getBaseUrl().toString());
        env.put("SHIELDLABS_MANAGEMENT_BASE_URL", " ");
        assertEquals(ManagementClient.DEFAULT_BASE_URL, ManagementClient.fromEnvironment(env::get).getBaseUrl());
        env.put("SHIELDLABS_SECRET_KEY", " ");
        assertThrows(ValidationException.class, () -> ManagementClient.fromEnvironment(env::get));
    }
}
