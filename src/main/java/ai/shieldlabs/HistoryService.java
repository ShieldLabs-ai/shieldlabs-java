package ai.shieldlabs;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Reads identifications from the History API ({@code GET /api/v1/history/{type}/{value}}). Obtain it
 * from {@link ShieldLabsClient#history()}. Thread-safe.
 *
 * <p>Every lookup is validated before it is sent: the type must be one of the seven
 * {@link LookupType} values, UUID types need a UUID (sent lowercase), {@code ip} needs a dotted IPv4
 * address and {@code user_hid} a non-empty string. A User HID is sent exactly as given, as one URL
 * path segment in the canonical form the History API matches ({@code $ & + , : ; = @} stay as they
 * are, everything outside letters, digits and {@code - . _ ~} is percent-encoded). A value that
 * contains {@code /}, and the values {@code .} and {@code ..}, cannot be searched and throw
 * {@link ValidationException}.
 */
public final class HistoryService {
    private final Transport transport;
    private final String origin;

    HistoryService(Transport transport, String origin) {
        this.transport = transport;
        this.origin = origin;
    }

    /**
     * Searches by one identifier with the default options ({@code limit} 20, {@code offset} 0).
     *
     * @param type the identifier type
     * @param value the identifier value
     * @return the page
     * @throws ValidationException when the type or value is invalid (nothing is sent)
     * @throws ShieldLabsException when the request fails
     */
    public HistoryPage search(LookupType type, String value) {
        return search(type, value, null);
    }

    /**
     * Searches by one identifier.
     *
     * @param type the identifier type
     * @param value the identifier value
     * @param options page size and offset, or {@code null} for the defaults
     * @return the page
     * @throws ValidationException when the type or value is invalid (nothing is sent)
     * @throws ShieldLabsException when the request fails
     */
    public HistoryPage search(LookupType type, String value, HistorySearchOptions options) {
        HistorySearchOptions opts = options == null ? HistorySearchOptions.defaults() : options;
        URI uri = uri(type, value, opts.getLimit(), opts.getOffset());
        return parsePage(transport.get(uri));
    }

    /**
     * Asynchronous {@link #search(LookupType, String)}.
     *
     * @param type the identifier type
     * @param value the identifier value
     * @return a future with the page; it fails with a {@link ShieldLabsException} (including
     *     {@link ValidationException}, in which case nothing is sent)
     */
    public CompletableFuture<HistoryPage> searchAsync(LookupType type, String value) {
        return searchAsync(type, value, null);
    }

    /**
     * Asynchronous {@link #search(LookupType, String, HistorySearchOptions)}.
     *
     * @param type the identifier type
     * @param value the identifier value
     * @param options page size and offset, or {@code null} for the defaults
     * @return a future with the page; it fails with a {@link ShieldLabsException} (including
     *     {@link ValidationException}, in which case nothing is sent)
     */
    public CompletableFuture<HistoryPage> searchAsync(
            LookupType type, String value, HistorySearchOptions options) {
        HistorySearchOptions opts = options == null ? HistorySearchOptions.defaults() : options;
        URI uri;
        try {
            uri = uri(type, value, opts.getLimit(), opts.getOffset());
        } catch (ValidationException e) {
            return CompletableFuture.failedFuture(e);
        }
        return transport.getAsync(uri).thenApply(HistoryService::parsePage);
    }

    /**
     * Lazily pages through every identification for one identifier, newest first, with the default
     * options (pages of 100, no limit).
     *
     * @param type the identifier type
     * @param value the identifier value
     * @return a sequential, lazy stream; no request is sent until it is consumed
     * @throws ValidationException when the type or value is invalid
     * @see #stream(LookupType, String, HistoryIterateOptions)
     */
    public Stream<Identification> stream(LookupType type, String value) {
        return stream(type, value, null);
    }

    /**
     * Lazily pages through every identification for one identifier, newest first.
     *
     * <p>Rows are ordered by time without a tie-breaker, so paging while new identifications arrive can
     * return a row twice: the stream skips request IDs it has already yielded. It stops at the reported
     * total, at an empty page, or after {@code maxItems}. Request failures surface as unchecked
     * {@link ShieldLabsException}s from the terminal operation.
     *
     * @param type the identifier type
     * @param value the identifier value
     * @param options page size and item limit, or {@code null} for the defaults
     * @return a sequential, lazy stream; no request is sent until it is consumed
     * @throws ValidationException when the type or value is invalid
     */
    public Stream<Identification> stream(LookupType type, String value, HistoryIterateOptions options) {
        Iterator<Identification> iterator = iterator(type, value, options);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /**
     * Same as {@link #stream(LookupType, String)}, as an {@link Iterable} for for-each loops.
     *
     * @param type the identifier type
     * @param value the identifier value
     * @return an iterable; each call to {@code iterator()} starts again from the newest row
     * @throws ValidationException when the type or value is invalid
     */
    public Iterable<Identification> iterate(LookupType type, String value) {
        return iterate(type, value, null);
    }

    /**
     * Same as {@link #stream(LookupType, String, HistoryIterateOptions)}, as an {@link Iterable}.
     *
     * @param type the identifier type
     * @param value the identifier value
     * @param options page size and item limit, or {@code null} for the defaults
     * @return an iterable; each call to {@code iterator()} starts again from the newest row
     * @throws ValidationException when the type or value is invalid
     */
    public Iterable<Identification> iterate(LookupType type, String value, HistoryIterateOptions options) {
        Validation.lookupValue(type, value);
        return () -> iterator(type, value, options);
    }

    private Iterator<Identification> iterator(LookupType type, String value, HistoryIterateOptions options) {
        HistoryIterateOptions opts = options == null ? HistoryIterateOptions.defaults() : options;
        String checked = Validation.lookupValue(type, value);
        return new PagingIterator(type, checked, opts.getPageSize(), opts.getMaxItems());
    }

    URI uri(LookupType type, String value, int limit, long offset) {
        String checked = Validation.lookupValue(type, value);
        return URI.create(origin + "/api/v1/history/" + type.getValue() + "/" + Urls.encodePathSegment(checked)
                + "?limit=" + limit + "&offset=" + offset);
    }

    static HistoryPage parsePage(JsonResponse response) {
        Map<?, ?> body = Json.object(response.value);
        if (body == null) {
            throw response.unexpected("expected a JSON object with data and total");
        }
        Object data = body.get("data");
        if (data != null && !(data instanceof List)) {
            throw response.unexpected("data is not an array");
        }
        List<?> rows = data == null ? List.of() : (List<?>) data;
        List<Identification> items = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Map<?, ?> object = Json.object(row);
            if (object != null) {
                items.add(Normalizer.fromHistoryRow(object));
            }
        }
        Long total = Json.longValue(body.get("total"));
        return new HistoryPage(items, total == null ? rows.size() : total, rows.size());
    }

    /** Offset paging with request ID de-duplication. Not thread-safe (like any iterator). */
    private final class PagingIterator implements Iterator<Identification> {
        private final LookupType type;
        private final String value;
        private final int pageSize;
        private final long maxItems;
        private final Deque<Identification> buffer = new ArrayDeque<>();
        private final Set<String> seen = new HashSet<>();
        private long offset;
        private long emitted;
        private boolean exhausted;

        PagingIterator(LookupType type, String value, int pageSize, long maxItems) {
            this.type = type;
            this.value = value;
            this.pageSize = pageSize;
            this.maxItems = maxItems;
        }

        @Override
        public boolean hasNext() {
            while (buffer.isEmpty() && !exhausted) {
                fetch();
            }
            return !buffer.isEmpty();
        }

        @Override
        public Identification next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Identification item = buffer.removeFirst();
            emitted++;
            if (maxItems >= 0 && emitted >= maxItems) {
                exhausted = true;
                buffer.clear();
            }
            return item;
        }

        private void fetch() {
            if (maxItems >= 0 && emitted >= maxItems) {
                exhausted = true;
                return;
            }
            HistoryPage page = parsePage(transport.get(uri(type, value, pageSize, offset)));
            if (page.rowCount() == 0) {
                exhausted = true;
                return;
            }
            offset += page.rowCount();
            for (Identification item : page.getIdentifications()) {
                String requestId = item.getRequestId();
                if (requestId.isEmpty() || seen.add(requestId)) {
                    buffer.addLast(item);
                }
            }
            if (offset >= page.getTotal()) {
                exhausted = true;
            }
        }
    }
}
