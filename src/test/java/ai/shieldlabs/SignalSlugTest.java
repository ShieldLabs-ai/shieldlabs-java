package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SignalSlugTest {

    static Stream<Arguments> cases() {
        return Fixtures.cases("signal-slug-cases.json").stream()
                .map(item -> Arguments.of(item.get("description"), item.get("slug")));
    }

    @ParameterizedTest(name = "[{0}] -> {1}")
    @MethodSource("cases")
    void slugMatchesTheFixture(String description, String slug) {
        assertEquals(slug, Normalizer.signalSlug(description));
    }

    @ParameterizedTest(name = "history row [{0}] -> {1}")
    @MethodSource("cases")
    void historyRowsUseTheSameSlugs(String description, String slug) {
        String details = Fixtures.json(List.of(Map.of("Value", 5, "Description", description)));
        Identification id = Identification.fromHistoryRow(Map.of("request_id", "r", "score_details", details));
        assertEquals(1, id.getSignals().size());
        assertEquals(slug, id.getSignals().get(0).getName());
        assertEquals(5, id.getSignals().get(0).getWeight());
        assertEquals(description, id.getSignals().get(0).getDescription());
    }

    /** Extra inputs and their expected slugs. */
    private static final String[][] EXTRA_CASES = {
            {"\u2260 x", "neq__x"},
            {"Is tor ", "is_tor"},
            {"Antidetect browserX", "antidetect_browser"},
            {"Sticky verdict:  Is VPN (request 1)", "is_vpn"},
            {"Sticky verdict: ", "unknown"},
            {"---", "unknown"},
            {"a--b", "a_b"},
            {"(x) y", "unknown"},
            {"x (y) z", "x"},
            {"\u00c0\u00c9\u00ce", "\u00e0\u00e9\u00ee"},
            {"\u6570\u5b57 test", "\u6570\u5b57_test"},
            {"\u0661\u0662\u0663", "\u0661\u0662\u0663"},
            {"\u00bd ratio", "ratio"},
            {"\u216b roman", "roman"},
            {"\u01c5 title", "\u01c6_title"},
            {"\u03a3\u0391\u03a3", "\u03c3\u03b1\u03c3"},
            {"\u00df", "\u00df"},
            {"\u1e9e", "\u00df"},
            {"\u212a", "k"},
            {"\ud83d\ude42 smile", "smile"},
            {"a\u200bb", "ab"},
            {"\u2007x\u2007", "x"},
            {"\u0085x", "x"},
            {"x\u00a0y", "xy"},
            {"IP \u2260 leakIP", "ip__neq__leakip"},
            {"Browser timezone \u2260 IP-timezone (x)", "browser_timezone__neq__ip_timezone"},
            {"  / leading", "leading"},
            {"trailing - ", "trailing"},
            {"UA OS is not detected (extra)", "ua_os_is_not_detected"},
            {"JavaScript disabled", "javascript_disabled"},
            {"Os_mismatch", "os_mismatch"},
            {"OS mismatch2", "os_mismatch2"},
            {"is tor", "is_tor"},
            {"Is Tor", "is_tor"},
            {"A/B-C D", "a_b_c_d"}
    };

    static Stream<Arguments> extraCases() {
        return Stream.of(EXTRA_CASES).map(pair -> Arguments.of(pair[0], pair[1]));
    }

    @ParameterizedTest(name = "extra [{0}] -> {1}")
    @MethodSource("extraCases")
    void extraInputsGiveTheExpectedSlug(String description, String slug) {
        assertEquals(slug, Normalizer.signalSlug(description));
    }

    @Test
    void fixtureHasAllCases() {
        assertTrue(cases().count() >= 31);
    }

    @Test
    void fallbackHandlesUnicodeWhitespaceAndSeparators() {
        assertEquals("a_b_c", Normalizer.fallbackSlug("\u00a0A - B / C\u3000"), "Unicode whitespace is stripped");
        assertEquals("neq__x", Normalizer.fallbackSlug("\u2260 x"), "a separator after the not-equal sign is kept");
        assertEquals("ab", Normalizer.fallbackSlug("a\tb"), "tabs are dropped, not separators");
        assertEquals("i\u0307stanbul", Normalizer.fallbackSlug("\u0130stanbul"), "full lowercase mapping");
        assertEquals("sticky_verdict", Normalizer.signalSlug("Sticky verdict:"), "no text after the colon");
    }
}
