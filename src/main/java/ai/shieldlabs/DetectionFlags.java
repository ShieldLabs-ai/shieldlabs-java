package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The 19 detection flags of an identification. Every flag is always present; a flag missing from the
 * source JSON reads as {@code false} (the test delivery from the analytics dashboard, for example, sends
 * only 17 flags).
 */
public final class DetectionFlags {
    private final Set<DetectionFlag> active;

    DetectionFlags(Set<DetectionFlag> active) {
        EnumSet<DetectionFlag> copy = EnumSet.noneOf(DetectionFlag.class);
        copy.addAll(active);
        this.active = Collections.unmodifiableSet(copy);
    }

    /**
     * Returns the value of one flag.
     *
     * @param flag the flag
     * @return {@code true} when the flag is set
     */
    public boolean get(DetectionFlag flag) {
        return flag != null && active.contains(flag);
    }

    /**
     * Returns the flags that are set, in contract order.
     *
     * @return an unmodifiable set
     */
    public Set<DetectionFlag> active() {
        return active;
    }

    /**
     * Returns all 19 flags keyed by their wire names, in contract order.
     *
     * @return an unmodifiable map such as {@code {"vpn": false, "privacy_relay": false, ...}}
     */
    @JsonValue
    public Map<String, Boolean> asMap() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (DetectionFlag flag : DetectionFlag.values()) {
            map.put(flag.getValue(), active.contains(flag));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Returns {@code vpn}.
     *
     * @return whether a VPN was detected
     */
    public boolean isVpn() {
        return get(DetectionFlag.VPN);
    }

    /**
     * Returns {@code privacy_relay}.
     *
     * @return whether a privacy relay was detected
     */
    public boolean isPrivacyRelay() {
        return get(DetectionFlag.PRIVACY_RELAY);
    }

    /**
     * Returns {@code browser_vpn_proxy}.
     *
     * @return whether a browser VPN or proxy extension was detected
     */
    public boolean isBrowserVpnProxy() {
        return get(DetectionFlag.BROWSER_VPN_PROXY);
    }

    /**
     * Returns {@code tor}.
     *
     * @return whether the request came through Tor
     */
    public boolean isTor() {
        return get(DetectionFlag.TOR);
    }

    /**
     * Returns {@code proxy}.
     *
     * @return whether a proxy was detected
     */
    public boolean isProxy() {
        return get(DetectionFlag.PROXY);
    }

    /**
     * Returns {@code datacenter_ip}.
     *
     * @return whether the public IP belongs to a hosting or datacenter network
     */
    public boolean isDatacenterIp() {
        return get(DetectionFlag.DATACENTER_IP);
    }

    /**
     * Returns {@code abuser}.
     *
     * @return whether the public IP has a record of abuse
     */
    public boolean isAbuser() {
        return get(DetectionFlag.ABUSER);
    }

    /**
     * Returns {@code os_mismatch}.
     *
     * @return whether the reported operating system contradicts the network evidence
     */
    public boolean isOsMismatch() {
        return get(DetectionFlag.OS_MISMATCH);
    }

    /**
     * Returns {@code os_not_detected}.
     *
     * @return whether the operating system could not be detected
     */
    public boolean isOsNotDetected() {
        return get(DetectionFlag.OS_NOT_DETECTED);
    }

    /**
     * Returns {@code timezone_mismatch}.
     *
     * @return whether the browser time zone differs from the IP location
     */
    public boolean isTimezoneMismatch() {
        return get(DetectionFlag.TIMEZONE_MISMATCH);
    }

    /**
     * Returns {@code anti_detect_browser}.
     *
     * @return whether an anti-detect browser was detected
     */
    public boolean isAntiDetectBrowser() {
        return get(DetectionFlag.ANTI_DETECT_BROWSER);
    }

    /**
     * Returns {@code browser_automation}.
     *
     * @return whether browser automation was detected
     */
    public boolean isBrowserAutomation() {
        return get(DetectionFlag.BROWSER_AUTOMATION);
    }

    /**
     * Returns {@code ip_mismatch}. Informational.
     *
     * @return whether the public IP differs from the local network IP
     */
    public boolean isIpMismatch() {
        return get(DetectionFlag.IP_MISMATCH);
    }

    /**
     * Returns {@code incognito}.
     *
     * @return whether the browser runs in a private window
     */
    public boolean isIncognito() {
        return get(DetectionFlag.INCOGNITO);
    }

    /**
     * Returns {@code search_bot}.
     *
     * @return whether the visit is a search engine crawler
     */
    public boolean isSearchBot() {
        return get(DetectionFlag.SEARCH_BOT);
    }

    /**
     * Returns {@code suspicious_paid_click}.
     *
     * @return whether a paid click came with a high Risk Score
     */
    public boolean isSuspiciousPaidClick() {
        return get(DetectionFlag.SUSPICIOUS_PAID_CLICK);
    }

    /**
     * Returns {@code javascript_disabled}.
     *
     * @return whether JavaScript was disabled
     */
    public boolean isJavascriptDisabled() {
        return get(DetectionFlag.JAVASCRIPT_DISABLED);
    }

    /**
     * Returns {@code stun_not_checked}.
     *
     * @return whether the network check did not complete
     */
    public boolean isStunNotChecked() {
        return get(DetectionFlag.STUN_NOT_CHECKED);
    }

    /**
     * Returns {@code check_incomplete}. Informational.
     *
     * @return whether a browser check timed out
     */
    public boolean isCheckIncomplete() {
        return get(DetectionFlag.CHECK_INCOMPLETE);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof DetectionFlags && active.equals(((DetectionFlags) o).active));
    }

    @Override
    public int hashCode() {
        return active.hashCode();
    }

    @Override
    public String toString() {
        return active.toString();
    }
}
