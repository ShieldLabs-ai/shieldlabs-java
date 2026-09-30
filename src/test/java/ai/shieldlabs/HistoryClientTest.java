package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HistoryClientTest {

    @Test
    void searchParsesTheFixturePage() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Fixtures.text("history-page.json"));
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            HistoryPage page =
                    client.history()
                            .search(
                                    LookupType.DEVICE_ID,
                                    "AC7C303D-971B-41D1-8E25-CD5B46B46AED",
                                    HistorySearchOptions.builder().limit(50).offset(10).build());
            assertEquals(37, page.getTotal());
            assertEquals(5, page.getIdentifications().size());
            assertEquals("02f1d973-84db-4156-a7f7-e799e6bf389b", page.getIdentifications().get(0).getRequestId());
            assertEquals(Identification.Source.HISTORY, page.getIdentifications().get(0).getSource());
            assertTrue(page.toString().contains("total=37"));

            TestServer.Recorded request = server.requests().get(0);
            assertEquals("GET", request.method);
            assertEquals("/api/v1/history/device_id/ac7c303d-971b-41d1-8e25-cd5b46b46aed", request.rawPath);
            assertEquals("limit=50&offset=10", request.rawQuery);
            assertEquals("Bearer " + Clients.API_KEY, request.header("Authorization"));
            assertEquals("application/json", request.header("Accept"));
            assertTrue(request.header("User-Agent").startsWith("shieldlabs-java/" + ShieldLabsClient.VERSION + " (Java "));
        }
    }

    @Test
    void emptyHistoryAndDefaults() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Fixtures.text("history-empty.json"));
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            HistoryPage page = client.history().search(LookupType.IP, "192.0.2.10");
            assertEquals(0, page.getTotal());
            assertTrue(page.getIdentifications().isEmpty());
            assertEquals("limit=20&offset=0", server.requests().get(0).rawQuery);
            assertEquals("/api/v1/history/ip/192.0.2.10", server.requests().get(0).rawPath);
        }
    }

    @Test
    void userHidIsSentExactlyAsGivenInCanonicalPathForm() {
        try (TestServer server = TestServer.start()) {
            server.always(200, Fixtures.text("history-empty.json"), "application/json");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            String[][] cases = {
                {"Ab C d?e#f%g\u00e9~._-", "Ab%20C%20d%3Fe%23f%25g%C3%A9~._-"},
                {"anonymous", "anonymous"},
                // $ & + , : ; = @ stay as they are, so an email address or a base64 value matches.
                {"user+tag@example.com", "user+tag@example.com"},
                {"x$y&z", "x$y&z"},
                {"a,b;c:d=e", "a,b;c:d=e"},
                {"dGVzdA==", "dGVzdA=="},
                // ! ' ( ) * are escaped.
                {"a!b'c(d)e*f", "a%21b%27c%28d%29e%2Af"},
                // Text that looks escaped is escaped once more and matches literally.
                {"a%2Fb", "a%252Fb"},
                {"\ud83d\ude42 x", "%F0%9F%99%82%20x"},
                {"...", "..."},
                {".a", ".a"},
            };
            for (int i = 0; i < cases.length; i++) {
                client.history().search(LookupType.USER_HID, cases[i][0]);
                assertEquals("/api/v1/history/user_hid/" + cases[i][1], server.requests().get(i).rawPath, cases[i][0]);
            }
        }
    }

    @Test
    void pathEncodingKeepsOnlyCanonicalCharactersPlain() {
        String plain = "-._~$&+,:;=@";
        String hex = "0123456789ABCDEF";
        for (char c = 0; c < 128; c++) {
            String encoded = Urls.encodePathSegment(String.valueOf(c));
            boolean expectPlain =
                    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || plain.indexOf(c) >= 0;
            String expected = expectPlain ? String.valueOf(c) : "%" + hex.charAt(c >> 4) + hex.charAt(c & 0x0F);
            assertEquals(expected, encoded, "character " + (int) c);
        }
        // Everything outside ASCII is percent-encoded byte by byte in UTF-8, with uppercase hex.
        for (char c = 0x80; c <= 0xFF; c++) {
            String expected = String.format(java.util.Locale.ROOT, "%%%02X%%%02X", 0xC0 | (c >> 6), 0x80 | (c & 0x3F));
            assertEquals(expected, Urls.encodePathSegment(String.valueOf(c)), "character " + (int) c);
        }
        assertEquals("%C3%BC", Urls.encodePathSegment("\u00fc"));
        assertEquals("%E4%B8%AD%E6%96%87", Urls.encodePathSegment("\u4e2d\u6587"));
        assertEquals("%F0%9F%99%82", Urls.encodePathSegment("\ud83d\ude42"));
    }

    @ParameterizedTest(name = "rejected user_hid {index}")
    @ValueSource(strings = {"a/b", "/", "/leading", "trailing/", ".", "..", "\ud800", "a\udc00b", "x\ud83d"})
    void userHidsThatNoPathSegmentCanCarryAreRejected(String value) {
        assertNoRequest(c -> c.history().search(LookupType.USER_HID, value));
        assertNoRequest(c -> c.history().iterate(LookupType.USER_HID, value));
        assertNoRequest(c -> c.history().stream(LookupType.USER_HID, value));
        try (TestServer server = TestServer.start()) {
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            CompletableFuture<HistoryPage> future = client.history().searchAsync(LookupType.USER_HID, value);
            CompletionException thrown = assertThrows(CompletionException.class, future::join);
            assertInstanceOf(ValidationException.class, thrown.getCause());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void userHidRejectionsExplainTheReason() {
        assertTrue(
                assertThrows(ValidationException.class, () -> Validation.lookupValue(LookupType.USER_HID, "a/b"))
                        .getMessage()
                        .contains("\"/\""));
        assertTrue(
                assertThrows(ValidationException.class, () -> Validation.lookupValue(LookupType.USER_HID, ".."))
                        .getMessage()
                        .contains("\"..\""));
        assertTrue(
                assertThrows(ValidationException.class, () -> Validation.lookupValue(LookupType.USER_HID, "\ud800"))
                        .getMessage()
                        .contains("surrogate"));
        assertEquals("a\ud83d\ude42b", Validation.lookupValue(LookupType.USER_HID, "a\ud83d\ude42b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/", "/", "", "/api//", "//api"})
    void apiSuffixIsStrippedFromTheBaseUrl(String suffix) {
        try (TestServer server = TestServer.start()) {
            server.json(200, Fixtures.text("history-empty.json"));
            ShieldLabsClient client =
                    Clients.history(server, new FakeTimer()).baseUrl(server.uri() + suffix).build();
            assertEquals(server.uri(), client.getBaseUrl());
            client.history().search(LookupType.REQUEST_ID, Clients.REQUEST_ID);
            assertEquals("/api/v1/history/request_id/" + Clients.REQUEST_ID, server.requests().get(0).rawPath);
        }
    }

    @Test
    void baseUrlPathPrefixIsKept() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Fixtures.text("history-empty.json"));
            ShieldLabsClient client =
                    Clients.history(server, new FakeTimer()).baseUrl(URI.create(server.uri() + "/proxy/api")).build();
            client.history().search(LookupType.REQUEST_ID, Clients.REQUEST_ID);
            assertEquals("/proxy/api/v1/history/request_id/" + Clients.REQUEST_ID, server.requests().get(0).rawPath);
        }
    }

    private static void assertNoRequest(Consumer<ShieldLabsClient> call) {
        try (TestServer server = TestServer.start()) {
            server.always(200, Fixtures.text("history-page.json"), "application/json");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            assertThrows(ValidationException.class, () -> call.accept(client));
            assertEquals(0, server.requestCount(), "no request may be sent");
        }
    }

    @Test
    void unknownTypeIsRejected() {
        assertNoRequest(c -> c.history().search(LookupType.fromValue("auto"), "x"));
        assertNoRequest(c -> c.history().search(LookupType.fromValue(null), "x"));
        assertNoRequest(c -> c.history().search(null, "x"));
        assertEquals(LookupType.SESSION_ID, LookupType.fromValue("session_id"));
        assertEquals("cookie_id", LookupType.COOKIE_ID.toString());
    }

    @Test
    void badUuidsAreRejected() {
        assertNoRequest(c -> c.history().search(LookupType.DEVICE_ID, "abc"));
        assertNoRequest(c -> c.history().search(LookupType.VISITOR_ID, "3f2b8c1e9d4a4e6b8a7c2d1e0f9b6a53"));
        assertNoRequest(c -> c.history().search(LookupType.REQUEST_ID, " " + Clients.REQUEST_ID));
        assertNoRequest(c -> c.history().search(LookupType.SESSION_ID, "{" + Clients.REQUEST_ID + "}"));
        assertNoRequest(c -> c.history().search(LookupType.COOKIE_ID, null));
        assertNoRequest(c -> c.identifications().get("not-a-uuid"));
        assertNoRequest(c -> c.identifications().get(null));
    }

    @Test
    void nilUuidIsAccepted() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Fixtures.text("history-empty.json"));
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            client.history().search(LookupType.DEVICE_ID, Risk.NIL_DEVICE_ID);
            assertEquals("/api/v1/history/device_id/" + Risk.NIL_DEVICE_ID, server.requests().get(0).rawPath);
        }
    }

    @Test
    void ipv6AndInvalidIpv4AreRejected() {
        assertNoRequest(c -> c.history().search(LookupType.IP, "2001:db8::1"));
        assertNoRequest(c -> c.history().search(LookupType.IP, "256.1.1.1"));
        assertNoRequest(c -> c.history().search(LookupType.IP, "192.0.2"));
        assertNoRequest(c -> c.history().search(LookupType.IP, "192.0.2.010"));
        ValidationException ipv6 =
                assertThrows(
                        ValidationException.class,
                        () -> Validation.lookupValue(LookupType.IP, "2001:db8::1"));
        assertTrue(ipv6.getMessage().contains("IPv6"));
    }

    @Test
    void emptyUserHidIsRejected() {
        assertNoRequest(c -> c.history().search(LookupType.USER_HID, ""));
    }

    @Test
    void limitAndOffsetOutOfRangeAreRejected() {
        assertThrows(ValidationException.class, () -> HistorySearchOptions.builder().limit(0));
        assertThrows(ValidationException.class, () -> HistorySearchOptions.builder().limit(101));
        assertThrows(ValidationException.class, () -> HistorySearchOptions.builder().offset(-1));
        assertEquals(100, HistorySearchOptions.builder().limit(100).build().getLimit());
        assertEquals(1, HistorySearchOptions.builder().limit(1).build().getLimit());
        assertEquals(20, HistorySearchOptions.defaults().getLimit());
        assertEquals(0, HistorySearchOptions.defaults().getOffset());
        assertNoRequest(
                c -> c.history().search(LookupType.IP, "192.0.2.1", HistorySearchOptions.builder().limit(0).build()));
    }

    @Test
    void asyncSearchReturnsTheSamePage() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Fixtures.text("history-page.json"));
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            HistoryPage page = client.history().searchAsync(LookupType.USER_HID, "anonymous").join();
            assertEquals(5, page.getIdentifications().size());
        }
    }

    @Test
    void asyncValidationFailsTheFutureWithoutRequest() {
        try (TestServer server = TestServer.start()) {
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            CompletableFuture<HistoryPage> future = client.history().searchAsync(LookupType.IP, "2001:db8::1");
            CompletionException error = assertThrows(CompletionException.class, future::join);
            assertInstanceOf(ValidationException.class, error.getCause());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void unexpectedSuccessBodiesAreApiErrors() {
        try (TestServer server = TestServer.start()) {
            server.json(200, "[]");
            server.json(200, "{\"data\":{},\"total\":1}");
            server.enqueue(200, "<html>ok</html>", "text/html", 0);
            server.json(200, "{\"total\":3}");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            ApiException notObject =
                    assertThrows(ApiException.class, () -> client.history().search(LookupType.USER_HID, "u"));
            assertTrue(notObject.getMessage().contains("Unexpected response body"));
            assertThrows(ApiException.class, () -> client.history().search(LookupType.USER_HID, "u"));
            ApiException html = assertThrows(ApiException.class, () -> client.history().search(LookupType.USER_HID, "u"));
            assertEquals("The response body is not valid JSON", html.getMessage());
            assertEquals(200, html.getStatusCode());
            HistoryPage noData = client.history().search(LookupType.USER_HID, "u");
            assertEquals(3, noData.getTotal());
            assertTrue(noData.getIdentifications().isEmpty());
        }
    }

    @Test
    void nonObjectRowsAreSkippedAndMissingTotalFallsBack() {
        try (TestServer server = TestServer.start()) {
            server.json(200, "{\"data\":[1," + Clients.row(Clients.REQUEST_ID) + "]}");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            HistoryPage page = client.history().search(LookupType.USER_HID, "u");
            assertEquals(1, page.getIdentifications().size());
            assertEquals(2, page.getTotal());
        }
    }
}
