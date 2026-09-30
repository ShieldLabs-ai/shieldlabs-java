package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WebhookSignatureVectorsTest {

    static Stream<Arguments> vectors() {
        List<Object> vectors = Fixtures.list(Fixtures.object("webhook-signature-vectors.json").get("vectors"));
        return vectors.stream().map(Fixtures::map).map(v -> Arguments.of(v.get("name"), v));
    }

    private static List<String> secrets(Map<String, Object> vector) {
        List<String> secrets = new ArrayList<>();
        if (vector.containsKey("secrets")) {
            for (Object secret : Fixtures.list(vector.get("secrets"))) {
                secrets.add((String) secret);
            }
        } else {
            secrets.add((String) vector.get("secret"));
        }
        return secrets;
    }

    @Test
    void fixtureHasAllVectors() {
        assertEquals(21, vectors().count());
        assertEquals("X-Shield-Signature", Fixtures.object("webhook-signature-vectors.json").get("header_name"));
        assertEquals(Webhooks.SIGNATURE_HEADER, Fixtures.object("webhook-signature-vectors.json").get("header_name"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void verifySignatureMatchesTheVector(String name, Map<String, Object> vector) {
        byte[] body = Base64.getDecoder().decode((String) vector.get("body_base64"));
        assertTrue(Arrays.equals(body, ((String) vector.get("body")).getBytes(StandardCharsets.UTF_8)));
        String header = (String) vector.get("signature_header");
        boolean valid = (Boolean) vector.get("valid");
        List<String> secrets = secrets(vector);
        String[] secretArray = secrets.toArray(new String[0]);

        assertEquals(valid, Webhooks.verifySignature(body, header, secretArray), "byte[] + varargs");
        assertEquals(valid, Webhooks.verifySignature(body, header, secrets), "byte[] + list");
        assertEquals(valid, Webhooks.verifySignature((String) vector.get("body"), header, secretArray), "String");

        if (valid) {
            assertNotNull(Webhooks.constructEvent(body, header, secretArray));
            assertNotNull(Webhooks.constructEvent((String) vector.get("body"), header, secretArray));
            assertNotNull(Webhooks.constructEvent(body, header, secrets));
        } else {
            assertThrows(SignatureVerificationException.class, () -> Webhooks.constructEvent(body, header, secretArray));
        }
    }

    @Test
    void rotationListAcceptsTheSecondSecretOnly() {
        byte[] body = Fixtures.bytes("webhook-ping.raw.txt");
        String header = "sha256=ea2685733d254f7028fb031c4214583b0650de01e6c8c93131236024edd9fdd8";
        assertTrue(Webhooks.verifySignature(body, header, "whsec_old", "whsec_00112233445566778899aabbccddeeff"));
        assertTrue(Webhooks.verifySignature(body, header, "", null, "whsec_00112233445566778899aabbccddeeff"));
        assertFalse(Webhooks.verifySignature(body, header, "whsec_old"));
    }

    @Test
    void missingInputsNeverVerify() {
        byte[] body = Fixtures.bytes("webhook-ping.raw.txt");
        String header = "sha256=ea2685733d254f7028fb031c4214583b0650de01e6c8c93131236024edd9fdd8";
        String secret = "whsec_00112233445566778899aabbccddeeff";
        assertFalse(Webhooks.verifySignature(body, header));
        assertFalse(Webhooks.verifySignature(body, header, (String[]) null));
        assertFalse(Webhooks.verifySignature(body, header, (List<String>) null));
        assertFalse(Webhooks.verifySignature(body, null, secret));
        assertFalse(Webhooks.verifySignature((byte[]) null, header, secret));
        assertFalse(Webhooks.verifySignature((String) null, header, secret));
        assertFalse(Webhooks.verifySignature(body, "sha256= ea2685733d254f7028fb031c4214583b0650de01e6c8c93131236024edd9fdd8", secret));
        assertFalse(Webhooks.verifySignature(body, "SHA256=ea2685733d254f7028fb031c4214583b0650de01e6c8c93131236024edd9fdd8", secret));
        assertFalse(Webhooks.verifySignature(body, "sha256=ea2685733d254f7028fb031c4214583b0650de01e6c8c93131236024edd9fdd\u0664", secret));

        SignatureVerificationException noSecret =
                assertThrows(SignatureVerificationException.class, () -> Webhooks.constructEvent(body, header));
        assertTrue(noSecret.getMessage().contains("secret"));
        SignatureVerificationException malformed =
                assertThrows(SignatureVerificationException.class, () -> Webhooks.constructEvent(body, "sha1=abc", secret));
        assertTrue(malformed.getMessage().contains("X-Shield-Signature"));
        SignatureVerificationException mismatch =
                assertThrows(
                        SignatureVerificationException.class,
                        () -> Webhooks.constructEvent((byte[]) null, header, secret));
        assertTrue(mismatch.getMessage().contains("does not match"));
        assertThrows(
                SignatureVerificationException.class,
                () -> Webhooks.constructEvent(body, header, (List<String>) null));
    }
}
