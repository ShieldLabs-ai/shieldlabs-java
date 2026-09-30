package ai.shieldlabs;

/**
 * The identifier a History API lookup searches by.
 *
 * <p>Pick the type by the question you are asking: {@link #REQUEST_ID} reads the verdict for one
 * protected action; {@link #USER_HID} lists what one account did; {@link #DEVICE_ID},
 * {@link #VISITOR_ID} and {@link #IP} show which accounts share a device, a visitor or an address
 * (useful for multi-accounting and account-sharing checks); {@link #SESSION_ID} and
 * {@link #COOKIE_ID} narrow down to one visit or one browser.
 */
public enum LookupType {
    /** Dotted IPv4 address (IPv6 addresses are not searchable). */
    IP("ip"),
    /**
     * User HID exactly as passed to the agent, including {@code "anonymous"}. Values that contain
     * {@code /}, and the values {@code .} and {@code ..}, cannot be searched.
     */
    USER_HID("user_hid"),
    /** Visitor ID (UUID). */
    VISITOR_ID("visitor_id"),
    /** Request ID (UUID) of one identification. */
    REQUEST_ID("request_id"),
    /** Device ID (UUID). */
    DEVICE_ID("device_id"),
    /** Session ID (UUID). */
    SESSION_ID("session_id"),
    /** Cookie ID (UUID). */
    COOKIE_ID("cookie_id");

    private final String value;

    LookupType(String value) {
        this.value = value;
    }

    /**
     * Returns the path value used by the History API.
     *
     * @return for example {@code "device_id"}
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns the lookup type for a path value. Use it when the type comes from configuration or user
     * input: the History API does not reject unknown types (it would return the latest rows of the
     * whole domain), so the SDK does.
     *
     * @param value one of {@code ip}, {@code user_hid}, {@code visitor_id}, {@code request_id},
     *     {@code device_id}, {@code session_id}, {@code cookie_id}
     * @return the lookup type
     * @throws ValidationException when the value is not one of the seven types
     */
    public static LookupType fromValue(String value) {
        for (LookupType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new ValidationException(
                "Unknown lookup type " + (value == null ? "null" : "'" + value + "'")
                        + ": use ip, user_hid, visitor_id, request_id, device_id, session_id or cookie_id");
    }

    @Override
    public String toString() {
        return value;
    }
}
