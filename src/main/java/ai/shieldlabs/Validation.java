package ai.shieldlabs;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Client-side argument checks. Every failure is a {@link ValidationException}. */
final class Validation {
    private static final System.Logger LOGGER = System.getLogger("ai.shieldlabs");
    static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final String OCTET = "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");

    private Validation() {
    }

    /** Checks a lookup value against its type and returns the value to send. */
    static String lookupValue(LookupType type, String value) {
        if (type == null) {
            throw new ValidationException("type is required");
        }
        if (value == null) {
            throw new ValidationException("value is required");
        }
        switch (type) {
            case IP:
                if (!IPV4.matcher(value).matches()) {
                    if (value.indexOf(':') >= 0) {
                        throw new ValidationException(
                                "ip must be a dotted IPv4 address: IPv6 addresses are not searchable");
                    }
                    throw new ValidationException("ip must be a dotted IPv4 address, for example 192.0.2.10");
                }
                return value;
            case USER_HID:
                return userHid(value);
            default:
                return uuid(value, type.getValue());
        }
    }

    /**
     * Checks a User HID for a History lookup. It travels as one URL path segment, so a value that
     * contains {@code /}, and the values {@code .} and {@code ..} (removed as dot segments by URL
     * handling), cannot be searched: they are refused instead of returning an empty page.
     */
    private static String userHid(String value) {
        if (value.isEmpty()) {
            throw new ValidationException("user_hid must be a non-empty string");
        }
        if (value.equals(".") || value.equals("..")) {
            throw new ValidationException(
                    "user_hid cannot be \".\" or \"..\": URL handling removes such path segments, so the lookup"
                            + " would request a different path");
        }
        if (value.indexOf('/') >= 0) {
            throw new ValidationException(
                    "user_hid cannot contain \"/\": the History API cannot search a value with a slash. Use"
                            + " URL-safe User HIDs, such as the 64 hex characters of UserHid.fromUserId");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean pair = Character.isHighSurrogate(c)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1));
            if (pair) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new ValidationException("user_hid must be valid Unicode text (it has an unpaired surrogate)");
            }
        }
        return value;
    }

    /** Checks a UUID (any version, nil allowed) and returns it lowercased. */
    static String uuid(String value, String name) {
        if (value == null || !UUID.matcher(value).matches()) {
            throw new ValidationException(
                    name + " must be a UUID such as 3f2b8c1e-9d4a-4e6b-8a7c-2d1e0f9b6a53");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Checks a credential and returns it without surrounding whitespace (a key read from a secrets
     * file or a CRLF {@code .env} file often ends in a line break): required, printable ASCII only.
     */
    static String credential(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " is required");
        }
        return printableAscii(value.strip(), name, "");
    }

    /** Checks a normalized, non-empty domain for the {@code X-Shield-Domain} header: printable ASCII only. */
    static String domain(String value) {
        return printableAscii(value, "domain", " (use the punycode form of an internationalized domain)");
    }

    private static String printableAscii(String value, String name, String hint) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new ValidationException(name + " must contain printable ASCII characters only" + hint);
            }
        }
        return value;
    }

    /**
     * Checks a base URL and returns it without trailing slashes. Every request carries a key, so the
     * URL must use https; plain http is accepted for loopback hosts (local test servers) and, when
     * {@code allowInsecureHttp} is set, for any host, with a logged warning.
     */
    static String baseUrl(URI uri, String name, boolean allowInsecureHttp) {
        if (uri == null) {
            throw new ValidationException(name + " is required");
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute()
                || uri.isOpaque()
                || uri.getHost() == null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new ValidationException(name + " must be an absolute https URL, for example https://example.com");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new ValidationException(name + " must not contain a query, a fragment or credentials");
        }
        if ("http".equalsIgnoreCase(scheme) && !isLoopbackHost(uri.getHost())) {
            if (!allowInsecureHttp) {
                throw new ValidationException(
                        name + " must use https: with plain http the key would travel unencrypted. Plain http is"
                                + " accepted for localhost, 127.0.0.1 and [::1]; for a test server on another host,"
                                + " call allowInsecureHttp(true) on the builder");
            }
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "The ShieldLabs " + name + " uses plain http, so the key travels unencrypted."
                            + " Use https outside local tests.");
        }
        String text = uri.toString();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * True for {@code localhost}, names under {@code .localhost} and loopback IP literals
     * ({@code 127.0.0.0/8}, {@code [::1]}). Never resolves a name.
     */
    static boolean isLoopbackHost(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.equals("localhost") || name.endsWith(".localhost")) {
            return true;
        }
        if (IPV4.matcher(name).matches()) {
            return name.startsWith("127.");
        }
        if (name.startsWith("[") && name.endsWith("]") && name.indexOf(':') > 0) {
            try {
                // A bracketed IPv6 literal: only its format is checked, no lookup happens.
                return InetAddress.getByName(name).isLoopbackAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
        return false;
    }

    /** Parses a base URL given as a string. */
    static URI parseUri(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(name + " is required");
        }
        try {
            return new URI(value.strip());
        } catch (java.net.URISyntaxException e) {
            throw new ValidationException(name + " is not a valid URL");
        }
    }
}
