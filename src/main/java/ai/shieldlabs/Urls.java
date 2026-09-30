package ai.shieldlabs;

import java.nio.charset.StandardCharsets;

/** URL building helpers. */
final class Urls {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Urls() {
    }

    /**
     * Percent-encodes a value as one path segment in canonical form: {@code A-Z a-z 0-9 - . _ ~} and
     * {@code $ & + , : ; = @} stay as they are, every other byte of the UTF-8 encoding becomes
     * {@code %XX} with uppercase hex (including {@code ! ' ( ) *}). The History API decodes the value
     * only when the path is escaped exactly this way; any other spelling is compared as escaped text
     * and finds nothing. The caller rejects values containing {@code /} and the values {@code .} and
     * {@code ..}, which no path segment can carry.
     */
    static String encodePathSegment(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isPlain(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return out.toString();
    }

    private static boolean isPlain(int c) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            return true;
        }
        switch (c) {
            case '-':
            case '.':
            case '_':
            case '~':
            case '$':
            case '&':
            case '+':
            case ',':
            case ':':
            case ';':
            case '=':
            case '@':
                return true;
            default:
                return false;
        }
    }

    /**
     * Normalizes a History API base URL (already without trailing slashes) to an origin: a trailing
     * {@code /api} segment is stripped, so request paths never become {@code /api/api/...}.
     */
    static String historyOrigin(String base) {
        String text = base.endsWith("/api") ? base.substring(0, base.length() - "/api".length()) : base;
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
