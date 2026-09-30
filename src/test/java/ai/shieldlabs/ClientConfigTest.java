package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ClientConfigTest {

    @Test
    void apiKeyIsRequired() {
        assertThrows(ValidationException.class, () -> ShieldLabsClient.builder().build());
        assertThrows(ValidationException.class, () -> ShieldLabsClient.builder().apiKey("").build());
        assertThrows(ValidationException.class, () -> ShieldLabsClient.builder().apiKey("   ").build());
        assertThrows(ValidationException.class, () -> ShieldLabsClient.builder().apiKey("sec_a\r\nb").build());
        assertThrows(ValidationException.class, () -> ShieldLabsClient.builder().apiKey("sec_\u00e9").build());
        assertThrows(ValidationException.class, () -> ShieldLabsClient.builder().apiKey("sec_\u007f").build());
    }

    @Test
    void surroundingWhitespaceIsRemovedFromTheKey() {
        try (TestServer server = TestServer.start()) {
            server.always(200, Fixtures.text("history-empty.json"), "application/json");
            for (String suffix : new String[] {"\n", "\r\n", " \t"}) {
                ShieldLabsClient client =
                        Clients.history(server, new FakeTimer()).apiKey(Clients.API_KEY + suffix).build();
                client.history().search(LookupType.USER_HID, "u");
            }
            Map<String, String> env = new LinkedHashMap<>();
            env.put("SHIELDLABS_API_KEY", Clients.API_KEY + "\r\n");
            env.put("SHIELDLABS_API_BASE_URL", server.uri().toString());
            ShieldLabsClient.fromEnvironment(env::get).history().search(LookupType.USER_HID, "u");
            assertEquals(4, server.requestCount());
            for (TestServer.Recorded request : server.requests()) {
                assertEquals("Bearer " + Clients.API_KEY, request.header("Authorization"));
            }
        }
    }

    @Test
    void keyErrorsDoNotMentionDomains() {
        ValidationException nonAscii =
                assertThrows(ValidationException.class, () -> ShieldLabsClient.builder().apiKey("sec_\u00e9").build());
        assertEquals("apiKey must contain printable ASCII characters only", nonAscii.getMessage());
        ValidationException lineBreakInside =
                assertThrows(ValidationException.class, () -> ShieldLabsClient.builder().apiKey("sec_a\r\nb").build());
        assertEquals("apiKey must contain printable ASCII characters only", lineBreakInside.getMessage());
    }

    @Test
    void unusualKeysOnlyWarn() {
        ShieldLabsClient client = ShieldLabsClient.builder().apiKey("sec_your_private_key").build();
        assertEquals(ShieldLabsClient.DEFAULT_BASE_URL, client.getBaseUrl());
        assertTrue(client.toString().contains("account.shieldlabs.ai"));
        assertTrue(!client.toString().contains("sec_"), "the key never appears in toString");
    }

    @Test
    void optionsAreValidated() {
        assertThrows(
                ValidationException.class,
                () -> ShieldLabsClient.builder().apiKey(Clients.API_KEY).timeout(Duration.ZERO).build());
        assertThrows(
                ValidationException.class,
                () -> ShieldLabsClient.builder().apiKey(Clients.API_KEY).timeout(Duration.ofSeconds(-1)).build());
        assertThrows(
                ValidationException.class, () -> ShieldLabsClient.builder().apiKey(Clients.API_KEY).timeout(null).build());
        assertThrows(
                ValidationException.class, () -> ShieldLabsClient.builder().apiKey(Clients.API_KEY).maxRetries(-1).build());
    }

    @Test
    void baseUrlsAreValidated() {
        String[] invalid = {
            "ftp://account.example.com",
            "account.example.com",
            "/relative/path",
            "https://account.example.com/?x=1",
            "https://account.example.com/#frag",
            "https://user:pass@account.example.com",
            "mailto:someone@example.com",
            "https://exa mple.com",
            " "
        };
        for (String value : invalid) {
            assertThrows(
                    ValidationException.class,
                    () -> ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl(value).build(),
                    value);
        }
        assertThrows(
                ValidationException.class,
                () -> ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl((URI) null).build());
        assertThrows(
                ValidationException.class,
                () -> ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl((String) null).build());
        ShieldLabsClient dev =
                ShieldLabsClient.builder()
                        .apiKey(Clients.API_KEY)
                        .baseUrl("https://dev.account.example.com/api/")
                        .httpClient(HttpClient.newHttpClient())
                        .build();
        assertEquals("https://dev.account.example.com", dev.getBaseUrl().toString());
    }

    @Test
    void plainHttpIsAcceptedOnlyForLoopbackHosts() {
        String[] loopback = {
            "http://localhost:8080", "HTTP://LOCALHOST", "http://api.localhost", "http://127.0.0.1:9",
            "http://127.10.0.1", "http://[::1]:8080"
        };
        for (String value : loopback) {
            ShieldLabsClient client = ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl(value).build();
            assertTrue(client.getBaseUrl().toString().regionMatches(true, 0, "http://", 0, 7), value);
        }
        ValidationException insecure =
                assertThrows(
                        ValidationException.class,
                        () -> ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl("http://account.example.com").build());
        assertTrue(insecure.getMessage().contains("https"), insecure.getMessage());
        assertTrue(insecure.getMessage().contains("allowInsecureHttp"), insecure.getMessage());
        String[] remote = {
            "http://10.0.0.5:8080", "http://128.0.0.1", "http://[::2]", "http://localhost.example.com", "http://mock:8080"
        };
        for (String value : remote) {
            assertThrows(
                    ValidationException.class,
                    () -> ShieldLabsClient.builder().apiKey(Clients.API_KEY).baseUrl(value).build(),
                    value);
        }
        ShieldLabsClient optedIn =
                ShieldLabsClient.builder()
                        .apiKey(Clients.API_KEY)
                        .baseUrl("http://mock:8080/api")
                        .allowInsecureHttp(true)
                        .build();
        assertEquals("http://mock:8080", optedIn.getBaseUrl().toString());
        ShieldLabsClient secure =
                ShieldLabsClient.builder()
                        .apiKey(Clients.API_KEY)
                        .baseUrl("https://account.example.com")
                        .allowInsecureHttp(false)
                        .build();
        assertEquals("https://account.example.com", secure.getBaseUrl().toString());
    }

    @Test
    void loopbackHostsAreRecognizedWithoutLookups() {
        String[] loopback = {
            "localhost", "LOCALHOST", "localhost.", "api.localhost", "127.0.0.1", "127.255.0.9", "[::1]",
            "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]"
        };
        for (String host : loopback) {
            assertTrue(Validation.isLoopbackHost(host), host);
        }
        String[] other = {
            "example.com", "localhost.example.com", "mylocalhost", "10.0.0.1", "128.0.0.1", "127.0.0", "[::2]",
            "[fe80::1]", "[not-an-address]", "[127.0.0.1]", "[g::1]", "[::1::2]"
        };
        for (String host : other) {
            assertFalse(Validation.isLoopbackHost(host), host);
        }
    }

    @Test
    void fromEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        assertThrows(ValidationException.class, () -> ShieldLabsClient.fromEnvironment(env::get));
        env.put("SHIELDLABS_API_KEY", " ");
        assertThrows(ValidationException.class, () -> ShieldLabsClient.fromEnvironment(env::get));
        env.put("SHIELDLABS_API_KEY", Clients.API_KEY);
        assertEquals(ShieldLabsClient.DEFAULT_BASE_URL, ShieldLabsClient.fromEnvironment(env::get).getBaseUrl());
        env.put("SHIELDLABS_API_BASE_URL", "https://dev.account.example.com/api");
        assertEquals(
                "https://dev.account.example.com", ShieldLabsClient.fromEnvironment(env::get).getBaseUrl().toString());
        env.put("SHIELDLABS_API_BASE_URL", "");
        assertEquals(ShieldLabsClient.DEFAULT_BASE_URL, ShieldLabsClient.fromEnvironment(env::get).getBaseUrl());
        env.put("SHIELDLABS_API_BASE_URL", "http://account.example.com");
        assertThrows(ValidationException.class, () -> ShieldLabsClient.fromEnvironment(env::get));
        env.put("SHIELDLABS_API_BASE_URL", "http://127.0.0.1:8080/api");
        assertEquals("http://127.0.0.1:8080", ShieldLabsClient.fromEnvironment(env::get).getBaseUrl().toString());
    }

    @Test
    void userAgentNamesTheSdkAndRuntime() {
        String userAgent = Transport.userAgent();
        assertTrue(userAgent.startsWith("shieldlabs-java/1.0.0 (Java "), userAgent);
        assertTrue(userAgent.endsWith(")"), userAgent);
        assertTrue(userAgent.matches("[ -~]+"), "printable ASCII only");
    }

    @Test
    void versionConstantMatchesTheBuild() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ClientConfigTest.class.getResourceAsStream("/version.properties")) {
            properties.load(in);
        }
        assertEquals(properties.getProperty("version"), ShieldLabsClient.VERSION);
    }
}
