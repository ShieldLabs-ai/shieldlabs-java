package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Collections;
import java.util.List;

/** One page of History API results, newest first. Immutable. */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({"data", "total"})
public final class HistoryPage {
    private final List<Identification> data;
    private final long total;
    private final int rowCount;

    HistoryPage(List<Identification> data, long total, int rowCount) {
        this.data = Collections.unmodifiableList(data);
        this.total = total;
        this.rowCount = rowCount;
    }

    /**
     * Returns the identifications on this page, newest first.
     *
     * @return an unmodifiable list, possibly empty
     */
    @JsonProperty("data")
    public List<Identification> getIdentifications() {
        return data;
    }

    /**
     * Returns the total number of rows that match the lookup (for all pages).
     *
     * @return the total
     */
    @JsonProperty("total")
    public long getTotal() {
        return total;
    }

    /** Number of rows the server returned on this page, used to advance the offset. */
    int rowCount() {
        return rowCount;
    }

    @Override
    public String toString() {
        return "HistoryPage{size=" + data.size() + ", total=" + total + "}";
    }
}
