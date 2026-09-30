package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class UserHidTest {

    @Test
    void hmacSha256AsLowercaseHex() {
        assertEquals(
                "a8476c9e84924094cb7cb6726feb934784fffafcfad255382a66a9f2b5b5b845",
                UserHid.fromUserId("user-42", "user_secret_for_tests"));
        assertEquals(
                "a8f3d138b73b2f20bad8fc1471f2be5fce40668ff354ede6bbc6412d6d8761f9",
                UserHid.fromUserId("\u03a9-user", "s\u00e9cret"));
        assertEquals(64, UserHid.fromUserId("1", "k").length());
    }

    @Test
    void emptyInputIsRejected() {
        assertThrows(ValidationException.class, () -> UserHid.fromUserId("", "secret"));
        assertThrows(ValidationException.class, () -> UserHid.fromUserId(null, "secret"));
        assertThrows(ValidationException.class, () -> UserHid.fromUserId("user-42", ""));
        assertThrows(ValidationException.class, () -> UserHid.fromUserId("user-42", null));
    }
}
