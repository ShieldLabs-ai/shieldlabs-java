package ai.shieldlabs;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Policy for {@link Risk#evaluate(Identification, EvaluateOptions)}. Immutable.
 *
 * <p>The defaults are a starting point to tune for your traffic: identifications older than 5
 * minutes are stale, the dangerous band is blocked, and the {@code browser_automation} and
 * {@code javascript_disabled} flags are blocked.
 */
public final class EvaluateOptions {
    /** Default freshness window. */
    public static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(5);

    private static final EvaluateOptions DEFAULTS = builder().build();

    private final Duration maxAge;
    private final Instant now;
    private final Set<RiskBand> blockBands;
    private final Set<DetectionFlag> blockFlags;
    private final Predicate<String> replayCheck;

    private EvaluateOptions(Builder builder) {
        this.maxAge = builder.maxAge;
        this.now = builder.now;
        this.blockBands = Collections.unmodifiableSet(EnumSet.copyOf(builder.blockBands));
        this.blockFlags = Collections.unmodifiableSet(EnumSet.copyOf(builder.blockFlags));
        this.replayCheck = builder.replayCheck;
    }

    /**
     * Returns the default policy.
     *
     * @return the defaults
     */
    public static EvaluateOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a new builder with the default policy.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the freshness window.
     *
     * @return the maximum age, or {@code null} when freshness is not checked
     */
    public Duration getMaxAge() {
        return maxAge;
    }

    /**
     * Returns the fixed evaluation time.
     *
     * @return the time, or {@code null} to use the current time
     */
    public Instant getNow() {
        return now;
    }

    /**
     * Returns the blocking bands.
     *
     * @return an unmodifiable set
     */
    public Set<RiskBand> getBlockBands() {
        return blockBands;
    }

    /**
     * Returns the blocking flags.
     *
     * @return an unmodifiable set
     */
    public Set<DetectionFlag> getBlockFlags() {
        return blockFlags;
    }

    /**
     * Returns the replay check.
     *
     * @return the check, or {@code null} when none is configured
     */
    public Predicate<String> getReplayCheck() {
        return replayCheck;
    }

    /** Builder for {@link EvaluateOptions}. Starts from the defaults. */
    public static final class Builder {
        private Duration maxAge = DEFAULT_MAX_AGE;
        private Instant now;
        private Set<RiskBand> blockBands = EnumSet.of(RiskBand.DANGEROUS);
        private Set<DetectionFlag> blockFlags =
                EnumSet.of(DetectionFlag.BROWSER_AUTOMATION, DetectionFlag.JAVASCRIPT_DISABLED);
        private Predicate<String> replayCheck;

        private Builder() {
        }

        /**
         * Sets the freshness window, measured from {@link Identification#getObservedAt()}. An
         * identification without a timestamp counts as stale.
         *
         * @param maxAge a positive duration, or {@code null} to skip the freshness check (for example
         *     when reviewing older history)
         * @return this builder
         * @throws ValidationException when the value is zero or negative
         */
        public Builder maxAge(Duration maxAge) {
            if (maxAge != null && (maxAge.isNegative() || maxAge.isZero())) {
                throw new ValidationException("maxAge must be positive");
            }
            this.maxAge = maxAge;
            return this;
        }

        /**
         * Fixes the evaluation time, for tests. By default the current time is used.
         *
         * @param now the time, or {@code null} for the current time
         * @return this builder
         */
        public Builder now(Instant now) {
            this.now = now;
            return this;
        }

        /**
         * Sets the bands that block the action, {@code DANGEROUS} by default.
         *
         * @param bands the bands; none to block no band
         * @return this builder
         */
        public Builder blockBands(RiskBand... bands) {
            return blockBands(bands == null ? Collections.<RiskBand>emptyList() : Arrays.asList(bands));
        }

        /**
         * Sets the bands that block the action.
         *
         * @param bands the bands; empty to block no band
         * @return this builder
         */
        public Builder blockBands(Collection<RiskBand> bands) {
            EnumSet<RiskBand> set = EnumSet.noneOf(RiskBand.class);
            if (bands != null) {
                for (RiskBand band : bands) {
                    if (band == null) {
                        throw new ValidationException("blockBands must not contain null");
                    }
                    set.add(band);
                }
            }
            this.blockBands = set;
            return this;
        }

        /**
         * Sets the flags that block the action, {@code BROWSER_AUTOMATION} and
         * {@code JAVASCRIPT_DISABLED} by default.
         *
         * @param flags the flags; none to block no flag
         * @return this builder
         */
        public Builder blockFlags(DetectionFlag... flags) {
            return blockFlags(flags == null ? Collections.<DetectionFlag>emptyList() : Arrays.asList(flags));
        }

        /**
         * Sets the flags that block the action.
         *
         * @param flags the flags; empty to block no flag
         * @return this builder
         */
        public Builder blockFlags(Collection<DetectionFlag> flags) {
            EnumSet<DetectionFlag> set = EnumSet.noneOf(DetectionFlag.class);
            if (flags != null) {
                for (DetectionFlag flag : flags) {
                    if (flag == null) {
                        throw new ValidationException("blockFlags must not contain null");
                    }
                    set.add(flag);
                }
            }
            this.blockFlags = set;
            return this;
        }

        /**
         * Sets a replay check: it receives the request ID and returns {@code true} when that ID was
         * already used for a protected action. The SDK stores no state; keep used request IDs in your
         * own store (for example with an atomic "add if absent").
         *
         * @param replayCheck the check, or {@code null} for none
         * @return this builder
         */
        public Builder replayCheck(Predicate<String> replayCheck) {
            this.replayCheck = replayCheck;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         */
        public EvaluateOptions build() {
            return new EvaluateOptions(this);
        }
    }
}
