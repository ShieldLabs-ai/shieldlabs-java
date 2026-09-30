package ai.shieldlabs;

/**
 * Known values of {@link Signal#getName()}.
 *
 * <p>Risk signal names are an open set: new names can appear at any time, so compare against these
 * constants but never treat the list as complete. Signal names are for display and logging; branch
 * on {@link DetectionFlags} and the Risk Score instead.
 */
public final class SignalName {
    /** Tor exit node. */
    public static final String TOR = "tor";
    /** JavaScript disabled. */
    public static final String JAVASCRIPT_DISABLED = "javascript_disabled";
    /** Operating system mismatch. */
    public static final String OS_MISMATCH = "os_mismatch";
    /** Anti-detect browser. */
    public static final String ANTIDETECT_BROWSER = "antidetect_browser";
    /** Network check routed through a proxy, a pattern of anti-detect browsers. */
    public static final String PROXY_ROUTED_ANTIDETECT = "proxy_routed_antidetect";
    /** Carried-forward variant of {@link #PROXY_ROUTED_ANTIDETECT}. */
    public static final String PORT_SCAN_ROUTED_VIA_PROXY = "port_scan_routed_via_proxy";
    /** Browser automation. */
    public static final String BROWSER_AUTOMATION = "browser_automation";
    /** Network check did not complete. */
    public static final String STUN_NOT_CHECKED = "stun_not_checked";
    /** Late network check that cancels {@link #STUN_NOT_CHECKED}; its weight is negative. */
    public static final String STUN_LATE_CORRECTION = "stun_late_correction";
    /** Operating system not detected. */
    public static final String OS_NOT_DETECTED = "os_not_detected";
    /** Browser VPN or proxy extension. */
    public static final String BROWSER_VPN_PROXY = "browser_vpn_proxy";
    /** VPN. */
    public static final String VPN = "vpn";
    /** Privacy relay. */
    public static final String PRIVACY_RELAY = "privacy_relay";
    /** Proxy. */
    public static final String PROXY = "proxy";
    /** Hosting or datacenter IP. */
    public static final String DATACENTER_IP = "datacenter_ip";
    /** IP with a record of abuse. */
    public static final String ABUSER = "abuser";
    /** Browser time zone differs from the IP location. */
    public static final String TIMEZONE_MISMATCH = "timezone_mismatch";
    /** Rate-limit marker; arrives alone with weight 999 and Risk Score 999. */
    public static final String RATE_LIMITED = "rate_limited";

    private SignalName() {
    }
}
