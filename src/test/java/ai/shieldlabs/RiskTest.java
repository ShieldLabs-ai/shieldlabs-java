package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RiskTest {
    private static final Instant OBSERVED = Instant.parse("2026-09-30T12:34:57.482913041Z");

    static Stream<Arguments> bands() {
        return Fixtures.cases("risk-band-cases.json").stream()
                .map(item -> Arguments.of(((Number) item.get("score")).intValue(), item.get("band")));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("bands")
    void bandMatchesTheFixture(int score, String band) {
        assertEquals(band, Risk.band(score).getValue());
        assertEquals(RiskBand.fromValue(band), Risk.band(score));
        assertEquals(score > 100, Risk.isRateLimited(score));
    }

    @Test
    void negativeScoresAreTrustedAndUnknownBandNamesAreRejected() {
        assertEquals(RiskBand.TRUSTED, Risk.band(-5));
        assertEquals(RiskBand.RATE_LIMITED, Risk.band(101));
        assertThrows(ValidationException.class, () -> RiskBand.fromValue("high"));
        assertEquals("dangerous", RiskBand.DANGEROUS.toString());
    }

    /** The scored webhook fixture with a few fields replaced. */
    static Identification identification(Map<String, Object> changes) {
        Map<String, Object> data = Fixtures.copy(Fixtures.map(Fixtures.normalizationCase("webhook_scored").get("input")));
        data.putAll(changes);
        return Identification.fromWebhookData(data);
    }

    static Map<String, Object> flags(String... set) {
        Map<String, Object> flags = new java.util.LinkedHashMap<>();
        for (DetectionFlag flag : DetectionFlag.values()) {
            flags.put(flag.getValue(), false);
        }
        for (String name : set) {
            flags.put(name, true);
        }
        return flags;
    }

    private static EvaluateOptions at(Instant now) {
        return EvaluateOptions.builder().now(now).build();
    }

    @Test
    void missingIdentificationIsNeverClean() {
        Evaluation evaluation = Risk.evaluate(null);
        assertFalse(evaluation.isOk());
        assertEquals(Optional.of(Evaluation.Reason.MISSING), evaluation.getReason());
        assertEquals(Optional.empty(), evaluation.getBand());
        assertEquals(Optional.empty(), evaluation.getFlag());
    }

    @Test
    void replayIsCheckedBeforeFreshness() {
        Set<String> used = new HashSet<>();
        EvaluateOptions options =
                EvaluateOptions.builder().now(OBSERVED.plus(Duration.ofHours(1))).replayCheck(id -> !used.add(id)).build();
        Identification id = identification(Map.of("risk_score", 10, "detection_flags", flags()));
        assertEquals(Optional.of(Evaluation.Reason.STALE), Risk.evaluate(id, options).getReason());
        Evaluation second = Risk.evaluate(id, options);
        assertEquals(Optional.of(Evaluation.Reason.REPLAYED), second.getReason());
        assertEquals(Optional.of(RiskBand.TRUSTED), second.getBand());
    }

    @Test
    void staleWhenOlderThanMaxAgeOrWithoutTimestamp() {
        Identification id = identification(Map.of("risk_score", 10, "detection_flags", flags()));
        assertTrue(Risk.evaluate(id, at(OBSERVED.plus(Duration.ofMinutes(5)))).isOk(), "exactly maxAge is fresh");
        assertEquals(
                Optional.of(Evaluation.Reason.STALE),
                Risk.evaluate(id, at(OBSERVED.plus(Duration.ofMinutes(5)).plusMillis(1))).getReason());
        Identification undated = identification(Map.of("risk_score", 10, "detection_flags", flags(), "observed_at", "bad"));
        assertEquals(Optional.of(Evaluation.Reason.STALE), Risk.evaluate(undated, at(OBSERVED)).getReason());
        EvaluateOptions noFreshness = EvaluateOptions.builder().maxAge(null).build();
        assertTrue(Risk.evaluate(undated, noFreshness).isOk());
        EvaluateOptions custom = EvaluateOptions.builder().now(OBSERVED.plusSeconds(90)).maxAge(Duration.ofMinutes(1)).build();
        assertEquals(Optional.of(Evaluation.Reason.STALE), Risk.evaluate(id, custom).getReason());
    }

    @Test
    void rateLimitMarkerIsRefusedBeforeDeviceAndFlags() {
        Identification id =
                Identification.fromWebhookData(Fixtures.map(Fixtures.object("webhook-rate-limited.json").get("data")));
        Evaluation evaluation = Risk.evaluate(id, at(Instant.parse("2026-09-30T13:11:00Z")));
        assertEquals(Optional.of(Evaluation.Reason.RATE_LIMITED), evaluation.getReason());
        assertEquals(Optional.of(RiskBand.RATE_LIMITED), evaluation.getBand());
    }

    @Test
    void nilDeviceIdMeansNoDeviceSignals() {
        Identification id =
                identification(Map.of("risk_score", 10, "detection_flags", flags(), "device_id", Risk.NIL_DEVICE_ID));
        assertEquals(Optional.of(Evaluation.Reason.NO_DEVICE_SIGNALS), Risk.evaluate(id, at(OBSERVED)).getReason());
    }

    @Test
    void blockingFlagsAreReportedInContractOrder() {
        Identification id =
                identification(
                        Map.of("risk_score", 10, "detection_flags", flags("javascript_disabled", "browser_automation")));
        Evaluation evaluation = Risk.evaluate(id, at(OBSERVED));
        assertEquals(Optional.of(Evaluation.Reason.BLOCKED_FLAG), evaluation.getReason());
        assertEquals(Optional.of(DetectionFlag.BROWSER_AUTOMATION), evaluation.getFlag());
        EvaluateOptions noFlags = EvaluateOptions.builder().now(OBSERVED).blockFlags().build();
        assertTrue(Risk.evaluate(id, noFlags).isOk());
        EvaluateOptions vpn =
                EvaluateOptions.builder().now(OBSERVED).blockFlags(java.util.List.of(DetectionFlag.VPN)).build();
        Identification vpnId = identification(Map.of("risk_score", 10, "detection_flags", flags("vpn")));
        assertEquals(Optional.of(DetectionFlag.VPN), Risk.evaluate(vpnId, vpn).getFlag());
    }

    @Test
    void bandsBlockAfterFlags() {
        Identification dangerous = identification(Map.of("risk_score", 80, "detection_flags", flags("proxy")));
        Evaluation evaluation = Risk.evaluate(dangerous, at(OBSERVED));
        assertEquals(Optional.of(Evaluation.Reason.BLOCKED_BAND), evaluation.getReason());
        assertEquals(Optional.of(RiskBand.DANGEROUS), evaluation.getBand());

        Identification suspicious = identification(Map.of("risk_score", 45, "detection_flags", flags()));
        assertTrue(Risk.evaluate(suspicious, at(OBSERVED)).isOk());
        EvaluateOptions strict =
                EvaluateOptions.builder().now(OBSERVED).blockBands(RiskBand.SUSPICIOUS, RiskBand.DANGEROUS).build();
        assertEquals(Optional.of(Evaluation.Reason.BLOCKED_BAND), Risk.evaluate(suspicious, strict).getReason());
        EvaluateOptions none = EvaluateOptions.builder().now(OBSERVED).blockBands().build();
        assertTrue(Risk.evaluate(dangerous, none).isOk());
    }

    @Test
    void allowedIdentificationReportsItsBand() {
        Identification id = identification(Map.of("risk_score", 15, "detection_flags", flags()));
        Evaluation evaluation = Risk.evaluate(id, at(OBSERVED));
        assertTrue(evaluation.isOk());
        assertEquals(Optional.empty(), evaluation.getReason());
        assertEquals(Optional.of(RiskBand.TRUSTED), evaluation.getBand());
        assertEquals(new Evaluation(true, null, RiskBand.TRUSTED, null), evaluation);
        assertEquals(evaluation.hashCode(), new Evaluation(true, null, RiskBand.TRUSTED, null).hashCode());
        assertTrue(evaluation.toString().contains("ok=true"));
        assertEquals(
                Map.of("ok", true, "band", "trusted"),
                withoutNulls(Fixtures.serialized(evaluation)));
    }

    @Test
    void defaultsAndValidation() {
        EvaluateOptions defaults = EvaluateOptions.defaults();
        assertEquals(EvaluateOptions.DEFAULT_MAX_AGE, defaults.getMaxAge());
        assertEquals(Set.of(RiskBand.DANGEROUS), defaults.getBlockBands());
        assertEquals(Set.of(DetectionFlag.BROWSER_AUTOMATION, DetectionFlag.JAVASCRIPT_DISABLED), defaults.getBlockFlags());
        assertEquals(null, defaults.getReplayCheck());
        assertEquals(null, defaults.getNow());
        assertThrows(ValidationException.class, () -> EvaluateOptions.builder().maxAge(Duration.ZERO));
        assertThrows(ValidationException.class, () -> EvaluateOptions.builder().maxAge(Duration.ofSeconds(-1)));
        assertThrows(ValidationException.class, () -> EvaluateOptions.builder().blockBands((RiskBand) null));
        assertThrows(ValidationException.class, () -> EvaluateOptions.builder().blockFlags((DetectionFlag) null));
        assertTrue(EvaluateOptions.builder().blockBands((RiskBand[]) null).build().getBlockBands().isEmpty());
        assertTrue(EvaluateOptions.builder().blockFlags((DetectionFlag[]) null).build().getBlockFlags().isEmpty());
        assertEquals("blocked_flag", Evaluation.Reason.BLOCKED_FLAG.toString());
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> map) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (v != null) {
                copy.put(k, v);
            }
        });
        return copy;
    }
}
