package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Client-side label for a Risk Score. The bands are fixed: trusted 0-29, suspicious 30-59,
 * dangerous 60-100. A score above 100 (999) is the rate-limit marker, not a score, and maps to
 * {@link #RATE_LIMITED}. No band field exists on the wire; use {@link Risk#band(int)}.
 */
public enum RiskBand {
    /** Risk Score 0-29. */
    TRUSTED("trusted"),
    /** Risk Score 30-59. */
    SUSPICIOUS("suspicious"),
    /** Risk Score 60-100. */
    DANGEROUS("dangerous"),
    /** Score above 100: the rate-limit marker (999). The identification carries no verdict. */
    RATE_LIMITED("rate_limited");

    private final String value;

    RiskBand(String value) {
        this.value = value;
    }

    /**
     * Returns the lowercase name used in logs and JSON.
     *
     * @return {@code "trusted"}, {@code "suspicious"}, {@code "dangerous"} or {@code "rate_limited"}
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Returns the band with the given lowercase name.
     *
     * @param value {@code "trusted"}, {@code "suspicious"}, {@code "dangerous"} or {@code "rate_limited"}
     * @return the band
     * @throws ValidationException when the name is unknown
     */
    public static RiskBand fromValue(String value) {
        for (RiskBand band : values()) {
            if (band.value.equals(value)) {
                return band;
            }
        }
        throw new ValidationException("Unknown risk band: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
