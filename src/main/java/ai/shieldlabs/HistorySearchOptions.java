package ai.shieldlabs;

/** Options for {@link HistoryService#search(LookupType, String, HistorySearchOptions)}. Immutable. */
public final class HistorySearchOptions {
    /** Default page size. */
    public static final int DEFAULT_LIMIT = 20;
    /** Largest page size the History API accepts. */
    public static final int MAX_LIMIT = 100;

    private static final HistorySearchOptions DEFAULTS = builder().build();

    private final int limit;
    private final int offset;

    private HistorySearchOptions(Builder builder) {
        this.limit = builder.limit;
        this.offset = builder.offset;
    }

    /**
     * Returns the default options: {@code limit} 20, {@code offset} 0.
     *
     * @return the defaults
     */
    public static HistorySearchOptions defaults() {
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
     * Returns the page size.
     *
     * @return 1 to 100
     */
    public int getLimit() {
        return limit;
    }

    /**
     * Returns the number of rows to skip.
     *
     * @return 0 or more
     */
    public int getOffset() {
        return offset;
    }

    /** Builder for {@link HistorySearchOptions}. */
    public static final class Builder {
        private int limit = DEFAULT_LIMIT;
        private int offset;

        private Builder() {
        }

        /**
         * Sets the page size. The server silently replaces values outside 1-100 with 20, so the SDK
         * rejects them instead.
         *
         * @param limit 1 to 100
         * @return this builder
         * @throws ValidationException when the value is outside 1-100
         */
        public Builder limit(int limit) {
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new ValidationException("limit must be between 1 and 100, got " + limit);
            }
            this.limit = limit;
            return this;
        }

        /**
         * Sets the number of rows to skip.
         *
         * @param offset 0 or more
         * @return this builder
         * @throws ValidationException when the value is negative
         */
        public Builder offset(int offset) {
            if (offset < 0) {
                throw new ValidationException("offset must be 0 or more, got " + offset);
            }
            this.offset = offset;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public HistorySearchOptions build() {
            return new HistorySearchOptions(this);
        }
    }
}
