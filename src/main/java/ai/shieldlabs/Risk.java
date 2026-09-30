package ai.shieldlabs;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

/**
 * Risk helpers: risk bands, the rate-limit marker and a reusable guard for protected actions.
 *
 * <p>Bands are computed on the client from the Risk Score: trusted 0-29, suspicious 30-59,
 * dangerous 60-100. A score above 100 (999) is the rate-limit marker, never a score.
 */
public final class Risk {
    /** The all-zero device ID: no usable device signals reached ShieldLabs. */
    public static final String NIL_DEVICE_ID = "00000000-0000-0000-0000-000000000000";

    private Risk() {
    }

    /**
     * Returns the risk band for a Risk Score.
     *
     * @param score the Risk Score
     * @return {@link RiskBand#TRUSTED} (up to 29), {@link RiskBand#SUSPICIOUS} (30-59),
     *     {@link RiskBand#DANGEROUS} (60-100) or {@link RiskBand#RATE_LIMITED} (above 100)
     */
    public static RiskBand band(int score) {
        if (score > 100) {
            return RiskBand.RATE_LIMITED;
        }
        if (score >= 60) {
            return RiskBand.DANGEROUS;
        }
        if (score >= 30) {
            return RiskBand.SUSPICIOUS;
        }
        return RiskBand.TRUSTED;
    }

    /**
     * Returns whether a Risk Score is the rate-limit marker (above 100, sent as 999). When a visitor's
     * IP goes over the per-IP limit of identifications, ShieldLabs records the block once as a separate
     * identification with this marker and its own request ID. It carries no verdict. The request IDs a
     * page receives during the block have no identification at all.
     *
     * @param score the Risk Score
     * @return {@code true} when the score is above 100
     */
    public static boolean isRateLimited(int score) {
        return score > 100;
    }

    /**
     * Evaluates an identification with the default policy (see {@link EvaluateOptions}).
     *
     * @param identification the identification, or {@code null} when none was found
     * @return the evaluation
     */
    public static Evaluation evaluate(Identification identification) {
        return evaluate(identification, null);
    }

    /**
     * Evaluates an identification against a policy. Checks run in this order and the first failure
     * wins: missing identification, replayed request ID (when a replay check is set), older than the
     * freshness window, rate-limit marker, nil device ID, blocking flag, blocking band.
     *
     * @param identification the identification, or {@code null} when none was found
     * @param options the policy, or {@code null} for the defaults
     * @return the evaluation
     */
    public static Evaluation evaluate(Identification identification, EvaluateOptions options) {
        EvaluateOptions opts = options == null ? EvaluateOptions.defaults() : options;
        if (identification == null) {
            return new Evaluation(false, Evaluation.Reason.MISSING, null, null);
        }
        RiskBand band = band(identification.getRiskScore());
        Predicate<String> replayCheck = opts.getReplayCheck();
        if (replayCheck != null && replayCheck.test(identification.getRequestId())) {
            return new Evaluation(false, Evaluation.Reason.REPLAYED, band, null);
        }
        Duration maxAge = opts.getMaxAge();
        if (maxAge != null) {
            Instant now = opts.getNow() != null ? opts.getNow() : Instant.now();
            Instant observedAt = identification.getObservedAt();
            if (observedAt == null || Duration.between(observedAt, now).compareTo(maxAge) > 0) {
                return new Evaluation(false, Evaluation.Reason.STALE, band, null);
            }
        }
        if (isRateLimited(identification.getRiskScore())) {
            return new Evaluation(false, Evaluation.Reason.RATE_LIMITED, band, null);
        }
        if (NIL_DEVICE_ID.equals(identification.getDeviceId())) {
            return new Evaluation(false, Evaluation.Reason.NO_DEVICE_SIGNALS, band, null);
        }
        for (DetectionFlag flag : DetectionFlag.values()) {
            if (opts.getBlockFlags().contains(flag) && identification.getDetectionFlags().get(flag)) {
                return new Evaluation(false, Evaluation.Reason.BLOCKED_FLAG, band, flag);
            }
        }
        if (opts.getBlockBands().contains(band)) {
            return new Evaluation(false, Evaluation.Reason.BLOCKED_BAND, band, null);
        }
        return new Evaluation(true, null, band, null);
    }
}
