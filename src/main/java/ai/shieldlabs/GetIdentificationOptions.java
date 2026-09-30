package ai.shieldlabs;

import java.time.Duration;

/**
 * Options for {@link IdentificationService#get(String, GetIdentificationOptions)}. Immutable.
 *
 * <p>Scoring is asynchronous: the History row for a request ID appears about 1 to 3 seconds after the
 * browser call and can be refined for up to about 10 seconds while follow-up checks finish. By default
 * the SDK waits for the row within a total budget of 10 seconds ({@link Builder#waitFor(Duration)}):
 * it polls right away, then after waits of 250 ms, 500 ms, 1 s, 1.5 s and then every 2 s, and the last
 * poll runs at the deadline. Each poll is one HTTP attempt without retries, so a zero budget makes one
 * poll without waiting. To read the refined row later, make a single lookup ({@link Builder#noWait()})
 * after about 10 seconds; that lookup is retried like any History request.
 */
public final class GetIdentificationOptions {
    /** Default total time budget of the wait for the verdict. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);
    /** Default first wait between polls. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);

    private static final GetIdentificationOptions DEFAULTS = builder().build();

    private final boolean noWait;
    private final Duration waitFor;
    private final Duration pollInterval;

    private GetIdentificationOptions(Builder builder) {
        this.noWait = builder.noWait;
        this.waitFor = builder.waitFor;
        this.pollInterval = builder.pollInterval;
    }

    /**
     * Returns the default options: wait within a total budget of 10 seconds, first poll interval
     * 250 ms.
     *
     * @return the defaults
     */
    public static GetIdentificationOptions defaults() {
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
     * Returns whether a single lookup is made instead of waiting for the verdict.
     *
     * @return {@code true} after {@link Builder#noWait()}: one lookup, retried like any History request;
     *     {@code false} (the default) to poll within {@link #getWaitFor()}
     */
    public boolean isNoWait() {
        return noWait;
    }

    /**
     * Returns the total time budget of the wait for the verdict, counted from the call. It does not
     * apply when {@link #isNoWait()} is {@code true}.
     *
     * @return the budget; {@link Duration#ZERO} means one poll without waiting and without retries
     */
    public Duration getWaitFor() {
        return waitFor;
    }

    /**
     * Returns the base of the wait schedule. The waits between polls are this interval x1, x2, x4, x6,
     * x8 and then x8 again, each capped at 2 seconds, or at the interval itself when it is longer (1
     * second waits 1, 2, 2, 2 seconds; 3 seconds polls every 3 seconds).
     *
     * @return the poll interval
     */
    public Duration getPollInterval() {
        return pollInterval;
    }

    /** Builder for {@link GetIdentificationOptions}. */
    public static final class Builder {
        private boolean noWait;
        private Duration waitFor = DEFAULT_WAIT;
        private Duration pollInterval = DEFAULT_POLL_INTERVAL;

        private Builder() {
        }

        /**
         * Sets the total time budget of the wait for the verdict, 10 seconds by default. The last poll
         * runs at this deadline. Each poll is one HTTP attempt whose timeout is the client timeout,
         * shortened to the time left but never below one second, so the call can return up to one
         * second after the budget.
         *
         * <p>Zero makes one poll without waiting: a single HTTP attempt without the client's retries,
         * whose timeout is the client timeout but at most one second. A 429, a 5xx response, a
         * connection error or a timeout of that poll is thrown; a poll without a row returns empty.
         *
         * <p>This turns waiting back on after {@link #noWait()}: the later call wins.
         *
         * @param waitFor zero or more
         * @return this builder
         * @throws ValidationException when the value is null or negative
         */
        public Builder waitFor(Duration waitFor) {
            if (waitFor == null || waitFor.isNegative()) {
                throw new ValidationException("waitFor must be zero or positive");
            }
            this.waitFor = waitFor;
            this.noWait = false;
            return this;
        }

        /**
         * Makes a single lookup instead of waiting for the verdict. That lookup is retried like any
         * History request (connection errors, timeouts, 429 and 5xx, up to the client's
         * {@code maxRetries}), and the wait budget and the poll interval do not apply. Compare
         * {@code waitFor(Duration.ZERO)}, which makes one poll without retries.
         *
         * <p>A later {@link #waitFor(Duration)} turns waiting back on: the later call wins.
         *
         * @return this builder
         */
        public Builder noWait() {
            this.noWait = true;
            return this;
        }

        /**
         * Sets the first wait between polls. Later waits are 2, 4, 6 and 8 times this interval, then 8
         * times again, and every wait is capped at 2 seconds, or at this interval when it is longer.
         *
         * @param pollInterval a positive duration, 250 ms by default
         * @return this builder
         * @throws ValidationException when the value is null, zero or negative
         */
        public Builder pollInterval(Duration pollInterval) {
            if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
                throw new ValidationException("pollInterval must be positive");
            }
            this.pollInterval = pollInterval;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public GetIdentificationOptions build() {
            return new GetIdentificationOptions(this);
        }
    }
}
