package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.function.Function;

/**
 * The 19 detection flags of an identification, in the order of the webhook contract.
 *
 * <p>Detection flags are stable booleans. Branch on them (and on the Risk Score), not on signal names.
 */
public enum DetectionFlag {
    /** A VPN was detected. */
    VPN("vpn", "is_vpn", WireModels.DetectionFlags::vpn, WireModels.HistoryRow::is_vpn),
    /** A privacy relay (such as a platform relay service) was detected. */
    PRIVACY_RELAY("privacy_relay", "is_privacy_relay", WireModels.DetectionFlags::privacy_relay, WireModels.HistoryRow::is_privacy_relay),
    /** A browser VPN or proxy extension was detected ({@code connection_type} {@code browser_vpn_proxy}). */
    BROWSER_VPN_PROXY("browser_vpn_proxy", null, WireModels.DetectionFlags::browser_vpn_proxy, null),
    /** The request came through Tor. */
    TOR("tor", "is_tor", WireModels.DetectionFlags::tor, WireModels.HistoryRow::is_tor),
    /** A proxy was detected. */
    PROXY("proxy", "is_proxy", WireModels.DetectionFlags::proxy, WireModels.HistoryRow::is_proxy),
    /** The public IP belongs to a hosting or datacenter network. */
    DATACENTER_IP("datacenter_ip", "is_datacenter", WireModels.DetectionFlags::datacenter_ip, WireModels.HistoryRow::is_datacenter),
    /** The public IP has a record of abuse. */
    ABUSER("abuser", "is_abuser", WireModels.DetectionFlags::abuser, WireModels.HistoryRow::is_abuser),
    /** The operating system reported by the browser does not match the network evidence. */
    OS_MISMATCH("os_mismatch", "is_os_mismatch", WireModels.DetectionFlags::os_mismatch, WireModels.HistoryRow::is_os_mismatch),
    /** The operating system could not be detected. */
    OS_NOT_DETECTED("os_not_detected", "is_os_not_detected", WireModels.DetectionFlags::os_not_detected, WireModels.HistoryRow::is_os_not_detected),
    /** The browser time zone does not match the IP location. */
    TIMEZONE_MISMATCH("timezone_mismatch", "is_timezone_mismatch", WireModels.DetectionFlags::timezone_mismatch, WireModels.HistoryRow::is_timezone_mismatch),
    /** An anti-detect browser was detected. */
    ANTI_DETECT_BROWSER("anti_detect_browser", "is_antidetect", WireModels.DetectionFlags::anti_detect_browser, WireModels.HistoryRow::is_antidetect),
    /** Browser automation was detected. */
    BROWSER_AUTOMATION("browser_automation", "is_browser_automation", WireModels.DetectionFlags::browser_automation, WireModels.HistoryRow::is_browser_automation),
    /** The public IP differs from the local network IP. Informational. */
    IP_MISMATCH("ip_mismatch", null, WireModels.DetectionFlags::ip_mismatch, null),
    /** The browser runs in a private window. */
    INCOGNITO("incognito", "is_incognito", WireModels.DetectionFlags::incognito, WireModels.HistoryRow::is_incognito),
    /** The visit is a search engine crawler (its Risk Score is forced to 0). */
    SEARCH_BOT("search_bot", "is_search_bot", WireModels.DetectionFlags::search_bot, WireModels.HistoryRow::is_search_bot),
    /** A paid click with a high Risk Score. */
    SUSPICIOUS_PAID_CLICK("suspicious_paid_click", "is_suspicious_paid_click", WireModels.DetectionFlags::suspicious_paid_click, WireModels.HistoryRow::is_suspicious_paid_click),
    /** JavaScript was disabled. */
    JAVASCRIPT_DISABLED("javascript_disabled", "is_js_disabled", WireModels.DetectionFlags::javascript_disabled, WireModels.HistoryRow::is_js_disabled),
    /** The network check did not complete. */
    STUN_NOT_CHECKED("stun_not_checked", "is_stun_not_checked", WireModels.DetectionFlags::stun_not_checked, WireModels.HistoryRow::is_stun_not_checked),
    /** A browser check timed out. Informational. */
    CHECK_INCOMPLETE("check_incomplete", "check_incomplete", WireModels.DetectionFlags::check_incomplete, WireModels.HistoryRow::check_incomplete);

    private final String value;
    private final String historyKey;

    private final Function<WireModels.DetectionFlags, WireValue.BooleanValue> webhookReader;
    private final Function<WireModels.HistoryRow, WireValue.BooleanValue> historyReader;

    DetectionFlag(String value, String historyKey,
            Function<WireModels.DetectionFlags, WireValue.BooleanValue> webhookReader,
            Function<WireModels.HistoryRow, WireValue.BooleanValue> historyReader) {
        this.webhookReader = webhookReader;
        this.historyReader = historyReader;
        this.value = value;
        this.historyKey = historyKey;
    }

    /**
     * Returns the key of this flag in {@code detection_flags}.
     *
     * @return the wire name, for example {@code "browser_automation"}
     */
    @JsonValue
    public String getValue() {
        return value;
    }

    Object historyValue(WireModels.HistoryRow row) {
        return historyReader == null ? null : WireValue.bool(historyReader.apply(row));
    }

    Object webhookValue(WireModels.DetectionFlags data) {
        return WireValue.bool(webhookReader.apply(data));
    }

    /** The History API column for this flag, or {@code null} when the flag is derived. */
    String historyKey() {
        return historyKey;
    }

    /**
     * Returns the flag with the given wire name.
     *
     * @param value a key of {@code detection_flags}, for example {@code "tor"}
     * @return the flag
     * @throws ValidationException when the name is not one of the 19 flags
     */
    public static DetectionFlag fromValue(String value) {
        for (DetectionFlag flag : values()) {
            if (flag.value.equals(value)) {
                return flag;
            }
        }
        throw new ValidationException("Unknown detection flag: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
