package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of {@link Risk#evaluate(Identification, EvaluateOptions)}: whether the protected action
 * should proceed and, if not, why. Immutable.
 */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({"ok", "reason", "band", "flag"})
public final class Evaluation {
    /** Why an identification did not pass. Checked in this order. */
    public enum Reason {
        /** No identification was found: treat it as unverified. */
        MISSING("missing"),
        /** The request ID was already used for another action. */
        REPLAYED("replayed"),
        /** The identification is older than the freshness window. */
        STALE("stale"),
        /** The Risk Score is the rate-limit marker (999): the identification carries no verdict. */
        RATE_LIMITED("rate_limited"),
        /** The device ID is the nil UUID: no usable device signals. */
        NO_DEVICE_SIGNALS("no_device_signals"),
        /** A blocking detection flag is set (see {@link Evaluation#getFlag()}). */
        BLOCKED_FLAG("blocked_flag"),
        /** The risk band is one of the blocking bands. */
        BLOCKED_BAND("blocked_band");

        private final String value;

        Reason(String value) {
            this.value = value;
        }

        /**
         * Returns the lowercase name used in logs and JSON.
         *
         * @return for example {@code "blocked_band"}
         */
        @JsonValue
        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private final boolean ok;
    private final Reason reason;
    private final RiskBand band;
    private final DetectionFlag flag;

    Evaluation(boolean ok, Reason reason, RiskBand band, DetectionFlag flag) {
        this.ok = ok;
        this.reason = reason;
        this.band = band;
        this.flag = flag;
    }

    /**
     * Returns whether every check passed.
     *
     * @return {@code true} when the action may proceed
     */
    @JsonProperty("ok")
    public boolean isOk() {
        return ok;
    }

    /**
     * Returns why the identification did not pass.
     *
     * @return the reason, or empty when {@link #isOk()} is {@code true}
     */
    public Optional<Reason> getReason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Returns the risk band of the identification.
     *
     * @return the band, or empty when the identification is missing
     */
    public Optional<RiskBand> getBand() {
        return Optional.ofNullable(band);
    }

    /**
     * Returns the first blocking flag that was set, in contract order.
     *
     * @return the flag, or empty unless the reason is {@link Reason#BLOCKED_FLAG}
     */
    public Optional<DetectionFlag> getFlag() {
        return Optional.ofNullable(flag);
    }

    @JsonProperty("reason")
    private Reason reasonJson() {
        return reason;
    }

    @JsonProperty("band")
    private RiskBand bandJson() {
        return band;
    }

    @JsonProperty("flag")
    private DetectionFlag flagJson() {
        return flag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Evaluation)) {
            return false;
        }
        Evaluation other = (Evaluation) o;
        return ok == other.ok && reason == other.reason && band == other.band && flag == other.flag;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ok, reason, band, flag);
    }

    @Override
    public String toString() {
        return "Evaluation{ok=" + ok + ", reason=" + reason + ", band=" + band + ", flag=" + flag + "}";
    }
}
