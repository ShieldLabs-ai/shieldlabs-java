package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HistoryIteratorTest {
    private static final String A = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String B = "bbbbbbbb-0000-4000-8000-000000000002";
    private static final String C = "cccccccc-0000-4000-8000-000000000003";
    private static final String D = "dddddddd-0000-4000-8000-000000000004";
    private static final String DEVICE = "d8e0f2a4-b6c8-4d0e-bf2a-4b6c8d0e2f4a";

    private static List<String> ids(Stream<Identification> stream) {
        return stream.map(Identification::getRequestId).collect(Collectors.toList());
    }

    @Test
    void pagesWithOffsetAndSkipsRepeatedRequestIds() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Clients.page(5, Clients.row(A), Clients.row(B)))
                    .json(200, Clients.page(5, Clients.row(B), Clients.row(C)))
                    .json(200, Clients.page(5, Clients.row(D)));
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            Stream<Identification> stream =
                    client.history()
                            .stream(LookupType.DEVICE_ID, DEVICE, HistoryIterateOptions.builder().pageSize(2).build());
            assertEquals(0, server.requestCount(), "streams are lazy");
            assertEquals(List.of(A, B, C, D), ids(stream));
            assertEquals(3, server.requestCount(), "stops once offset reaches total");
            assertEquals("limit=2&offset=0", server.requests().get(0).rawQuery);
            assertEquals("limit=2&offset=2", server.requests().get(1).rawQuery);
            assertEquals("limit=2&offset=4", server.requests().get(2).rawQuery);
            assertEquals("/api/v1/history/device_id/" + DEVICE, server.requests().get(2).rawPath);
        }
    }

    @Test
    void stopsAtAnEmptyPage() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Clients.page(100, Clients.row(A), Clients.row(B)))
                    .json(200, Clients.page(100));
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            assertEquals(
                    List.of(A, B),
                    ids(client.history().stream(LookupType.USER_HID, "user-1", HistoryIterateOptions.builder().pageSize(2).build())));
            assertEquals(2, server.requestCount());
        }
    }

    @Test
    void stopsAfterMaxItems() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Clients.page(10, Clients.row(A), Clients.row(B)))
                    .json(200, Clients.page(10, Clients.row(C), Clients.row(D)));
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            HistoryIterateOptions options = HistoryIterateOptions.builder().pageSize(2).maxItems(3).build();
            assertEquals(List.of(A, B, C), ids(client.history().stream(LookupType.IP, "203.0.113.24", options)));
            assertEquals(2, server.requestCount());
        }
    }

    @Test
    void maxItemsZeroSendsNothing() {
        try (TestServer server = TestServer.start()) {
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            HistoryIterateOptions options = HistoryIterateOptions.builder().maxItems(0).build();
            assertEquals(List.of(), ids(client.history().stream(LookupType.IP, "203.0.113.24", options)));
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void iterableWorksWithForEachAndDefaults() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Fixtures.text("history-page.json")).json(200, Clients.page(37));
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            List<String> seen = new ArrayList<>();
            for (Identification identification : client.history().iterate(LookupType.USER_HID, "anonymous")) {
                seen.add(identification.getRequestId());
            }
            assertEquals(5, seen.size());
            assertEquals("limit=100&offset=0", server.requests().get(0).rawQuery);
            assertEquals("limit=100&offset=5", server.requests().get(1).rawQuery);
        }
    }

    @Test
    void exhaustedIteratorThrowsNoSuchElement() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Clients.page(1, Clients.row(A)));
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            Iterator<Identification> iterator = client.history().iterate(LookupType.USER_HID, "u").iterator();
            assertTrue(iterator.hasNext());
            assertEquals(A, iterator.next().getRequestId());
            assertFalse(iterator.hasNext());
            assertThrows(NoSuchElementException.class, iterator::next);
            assertEquals(1, server.requestCount());
        }
    }

    @Test
    void rowsWithoutRequestIdAreNotDeduplicated() {
        try (TestServer server = TestServer.start()) {
            server.json(200, "{\"data\":[{\"score\":5},{\"score\":6}],\"total\":2}");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            assertEquals(2, client.history().stream(LookupType.USER_HID, "u").count());
        }
    }

    @Test
    void validationIsEagerAndOptionsAreChecked() {
        try (TestServer server = TestServer.start()) {
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            assertThrows(ValidationException.class, () -> client.history().stream(LookupType.DEVICE_ID, "nope"));
            assertThrows(ValidationException.class, () -> client.history().iterate(LookupType.IP, "::1"));
            assertThrows(ValidationException.class, () -> HistoryIterateOptions.builder().pageSize(0));
            assertThrows(ValidationException.class, () -> HistoryIterateOptions.builder().pageSize(101));
            assertThrows(ValidationException.class, () -> HistoryIterateOptions.builder().maxItems(-1));
            assertEquals(100, HistoryIterateOptions.defaults().getPageSize());
            assertEquals(-1, HistoryIterateOptions.defaults().getMaxItems());
            assertEquals(0, server.requestCount());
        }
    }

    @Test
    void errorsSurfaceFromTheTerminalOperation() {
        try (TestServer server = TestServer.start()) {
            server.json(200, Clients.page(4, Clients.row(A), Clients.row(B)))
                    .always(401, "{\"error\":\"invalid api key\"}\n", "text/plain; charset=utf-8");
            ShieldLabsClient client = Clients.history(server, new FakeTimer()).build();
            List<String> seen = new ArrayList<>();
            Stream<Identification> stream =
                    client.history().stream(LookupType.USER_HID, "u", HistoryIterateOptions.builder().pageSize(2).build());
            assertThrows(AuthenticationException.class, () -> stream.forEach(i -> seen.add(i.getRequestId())));
            assertEquals(List.of(A, B), seen);
        }
    }
}
