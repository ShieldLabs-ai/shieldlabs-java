package ai.shieldlabs;

/**
 * Known values of {@link Identification#getConnectionType()}. The field is a plain string so that a
 * value added later is kept as is; compare against these constants.
 */
public final class ConnectionType {
    /** Direct connection. */
    public static final String DIRECT = "direct";
    /** Mobile carrier network. */
    public static final String MOBILE = "mobile";
    /** VPN. */
    public static final String VPN = "vpn";
    /** Proxy or hosting network. */
    public static final String PROXY = "proxy";
    /** Tor. */
    public static final String TOR = "tor";
    /** Privacy relay. */
    public static final String PRIVACY_RELAY = "privacy_relay";
    /** Browser VPN or proxy extension. */
    public static final String BROWSER_VPN_PROXY = "browser_vpn_proxy";
    /** Not determined. */
    public static final String UNKNOWN = "unknown";

    private ConnectionType() {
    }
}
