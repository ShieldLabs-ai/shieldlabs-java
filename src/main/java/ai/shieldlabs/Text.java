package ai.shieldlabs;

/** String helpers with the Unicode whitespace set used for normalization. */
final class Text {
    private Text() {
    }

    /** True for the Unicode whitespace characters that normalization strips. */
    static boolean isWhitespace(int cp) {
        return (cp >= 0x09 && cp <= 0x0D)
                || (cp >= 0x1C && cp <= 0x20)
                || cp == 0x85
                || cp == 0xA0
                || cp == 0x1680
                || (cp >= 0x2000 && cp <= 0x200A)
                || cp == 0x2028
                || cp == 0x2029
                || cp == 0x202F
                || cp == 0x205F
                || cp == 0x3000;
    }

    /** Removes leading and trailing whitespace as defined by {@link #isWhitespace(int)}. */
    static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int cp = value.codePointAt(start);
            if (!isWhitespace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!isWhitespace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return value.substring(start, end);
    }
}
