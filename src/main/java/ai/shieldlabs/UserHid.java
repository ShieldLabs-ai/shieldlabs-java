package ai.shieldlabs;

import java.nio.charset.StandardCharsets;

/**
 * Creates a User HID on your server. Pass it to the browser SDK instead of a raw email address or
 * account ID.
 */
public final class UserHid {
    private UserHid() {
    }

    /**
     * Computes a stable, irreversible User HID: HMAC-SHA256 of the user ID keyed with your secret, as
     * 64 lowercase hex characters. The same inputs always give the same value, so History lookups by
     * {@code user_hid} keep working across sessions. Keep the secret on the server and never change it
     * once in use.
     *
     * @param userId your account ID, for example a database key
     * @param secret a server-side secret used only for this purpose
     * @return the User HID
     * @throws ValidationException when either argument is null or empty
     */
    public static String fromUserId(String userId, String secret) {
        if (userId == null || userId.isEmpty()) {
            throw new ValidationException("userId must be a non-empty string");
        }
        if (secret == null || secret.isEmpty()) {
            throw new ValidationException("secret must be a non-empty string");
        }
        byte[] digest =
                Webhooks.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), userId.getBytes(StandardCharsets.UTF_8));
        return Webhooks.hex(digest);
    }
}
