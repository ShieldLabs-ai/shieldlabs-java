package ai.shieldlabs;

/**
 * Options for {@link HistoryService#stream(LookupType, String, HistoryIterateOptions)} and
 * {@link HistoryService#iterate(LookupType, String, HistoryIterateOptions)}. Immutable.
 */
public final class HistoryIterateOptions {
    /** Default page size. */
    public static final int DEFAULT_PAGE_SIZE = 100;

    private static final HistoryIterateOptions DEFAULTS = builder().build();

    private final int pageSize;
    private final long maxItems;

    private HistoryIterateOptions(Builder builder) {
        this.pageSize = builder.pageSize;
        this.maxItems = builder.maxItems;
    }

    /**
     * Returns the default options: pages of 100 rows, no item limit.
     *
     * @return the defaults
     */
    public static HistoryIterateOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the number of rows requested per page.
     *
     * @return 1 to 100
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * Returns the maximum number of identifications to yield.
     *
     * @return the limit, or {@code -1} when there is none
     */
    public long getMaxItems() {
        return maxItems;
    }

    /** Builder for {@link HistoryIterateOptions}. */
    public static final class Builder {
        private int pageSize = DEFAULT_PAGE_SIZE;
        private long maxItems = -1;

        private Builder() {
        }

        /**
         * Sets the number of rows requested per page.
         *
         * @param pageSize 1 to 100
         * @return this builder
         * @throws ValidationException when the value is outside 1-100
         */
        public Builder pageSize(int pageSize) {
            if (pageSize < 1 || pageSize > HistorySearchOptions.MAX_LIMIT) {
                throw new ValidationException("pageSize must be between 1 and 100, got " + pageSize);
            }
            this.pageSize = pageSize;
            return this;
        }

        /**
         * Stops after this many identifications.
         *
         * @param maxItems 0 or more
         * @return this builder
         * @throws ValidationException when the value is negative
         */
        public Builder maxItems(long maxItems) {
            if (maxItems < 0) {
                throw new ValidationException("maxItems must be 0 or more, got " + maxItems);
            }
            this.maxItems = maxItems;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public HistoryIterateOptions build() {
            return new HistoryIterateOptions(this);
        }
    }
}
