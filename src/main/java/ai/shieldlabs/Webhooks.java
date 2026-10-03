package ai.shieldlabs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies and parses ShieldLabs webhook deliveries. No client is needed.
 *
 * <p>Every delivery is a {@code POST} with the header
 * {@code X-Shield-Signature: sha256=<hex HMAC-SHA256(key, raw body)>}. The key is the endpoint's
 * signing secret string exactly as shown in the analytics dashboard, including its {@code whsec_}
 * prefix, used as UTF-8 bytes. Always verify the raw request body: re-serializing parsed JSON changes
 * the bytes and breaks the signature.
 *
 * <p>Both methods accept several secrets and succeed when any of them matches, so you can rotate a
 * secret without downtime: configure the new secret next to the old one, rotate in the analytics
 * dashboard, then remove the old one.
 *
 * <p>Deliveries carry no timestamp or delivery ID header. Today each identification is delivered once
 * per endpoint, with a one-second timeout and no retries; a later server release adds retries that
 * resend identical bytes. Respond with a 2xx status within one second, make your handler idempotent on
 * {@code data.request_id} (the request ID of the identification), and use the History API for
 * guaranteed reads.
 */
public final class Webhooks {
    /** Name of the signature header. */
    public static final String SIGNATURE_HEADER = "X-Shield-Signature";

    private static final String PREFIX = "sha256=";
    private static final int DIGEST_HEX_LENGTH = 64;
    private static final int MAX_WARNED_SCHEMA_VERSIONS = 16;
    private static final System.Logger LOGGER = System.getLogger("ai.shieldlabs");
    private static final Set<String> WARNED_SCHEMA_VERSIONS = ConcurrentHashMap.newKeySet();

    private Webhooks() {
    }

    /**
     * Checks the signature of a delivery.
     *
     * @param payload the raw request body, exactly as received
     * @param signatureHeader the value of the {@code X-Shield-Signature} header
     * @param secrets one or more endpoint signing secrets ({@code whsec_...}); valid when any matches
     * @return {@code true} when the signature is valid; {@code false} for a mismatch, a missing or
     *     malformed header, or when no non-empty secret was given
     */
    public static boolean verifySignature(byte[] payload, String signatureHeader, String... secrets) {
        return check(payload, signatureHeader, secrets == null ? null : Arrays.asList(secrets)) == Check.VALID;
    }

    /**
     * Checks the signature of a delivery whose body you hold as a string. The string is encoded as
     * UTF-8; prefer the {@code byte[]} overload when you have the raw bytes.
     *
     * @param payload the raw request body
     * @param signatureHeader the value of the {@code X-Shield-Signature} header
     * @param secrets one or more endpoint signing secrets; valid when any matches
     * @return {@code true} when the signature is valid
     */
    public static boolean verifySignature(String payload, String signatureHeader, String... secrets) {
        return verifySignature(bytes(payload), signatureHeader, secrets);
    }

    /**
     * Checks the signature of a delivery against a list of secrets.
     *
     * @param payload the raw request body, exactly as received
     * @param signatureHeader the value of the {@code X-Shield-Signature} header
     * @param secrets endpoint signing secrets; valid when any matches
     * @return {@code true} when the signature is valid
     */
    public static boolean verifySignature(byte[] payload, String signatureHeader, Collection<String> secrets) {
        return check(payload, signatureHeader, secrets) == Check.VALID;
    }

    /**
     * Verifies a delivery and parses it into a typed event.
     *
     * @param payload the raw request body, exactly as received
     * @param signatureHeader the value of the {@code X-Shield-Signature} header
     * @param secrets one or more endpoint signing secrets; valid when any matches
     * @return the event: {@link IdentificationScoredEvent}, {@link WebhookPingEvent} or
     *     {@link UnknownWebhookEvent}
     * @throws SignatureVerificationException when the signature is missing, malformed or does not
     *     match, or no secret was given; answer with HTTP 401
     * @throws WebhookParseException when the verified body is not a valid event; answer with HTTP 400
     */
    public static WebhookEvent constructEvent(byte[] payload, String signatureHeader, String... secrets) {
        return constructEvent(payload, signatureHeader, secrets == null ? null : Arrays.asList(secrets));
    }

    /**
     * Verifies a delivery held as a string and parses it into a typed event.
     *
     * @param payload the raw request body
     * @param signatureHeader the value of the {@code X-Shield-Signature} header
     * @param secrets one or more endpoint signing secrets; valid when any matches
     * @return the event
     * @throws SignatureVerificationException when verification fails
     * @throws WebhookParseException when the verified body is not a valid event
     */
    public static WebhookEvent constructEvent(String payload, String signatureHeader, String... secrets) {
        return constructEvent(bytes(payload), signatureHeader, secrets);
    }

    /**
     * Verifies a delivery against a list of secrets and parses it into a typed event.
     *
     * @param payload the raw request body, exactly as received
     * @param signatureHeader the value of the {@code X-Shield-Signature} header
     * @param secrets endpoint signing secrets; valid when any matches
     * @return the event
     * @throws SignatureVerificationException when verification fails
     * @throws WebhookParseException when the verified body is not a valid event
     */
    public static WebhookEvent constructEvent(byte[] payload, String signatureHeader, Collection<String> secrets) {
        switch (check(payload, signatureHeader, secrets)) {
            case VALID:
                return parse(payload);
            case NO_SECRET:
                throw new SignatureVerificationException("No webhook signing secret was provided");
            case MALFORMED:
                throw new SignatureVerificationException(
                        "Missing or malformed " + SIGNATURE_HEADER + " header: expected sha256=<64 hex characters>");
            default:
                throw new SignatureVerificationException("The webhook signature does not match the payload");
        }
    }

    private enum Check {
        VALID,
        NO_SECRET,
        MALFORMED,
        MISMATCH
    }

    private static Check check(byte[] payload, String signatureHeader, Collection<String> secrets) {
        List<String> keys = secrets == null ? Collections.emptyList() : nonEmpty(secrets);
        if (keys.isEmpty()) {
            return Check.NO_SECRET;
        }
        byte[] expected = parseHeader(signatureHeader);
        if (expected == null) {
            return Check.MALFORMED;
        }
        if (payload == null) {
            return Check.MISMATCH;
        }
        boolean match = false;
        for (String secret : keys) {
            byte[] actual = hmacSha256(secret.getBytes(StandardCharsets.UTF_8), payload);
            match |= MessageDigest.isEqual(actual, expected);
        }
        return match ? Check.VALID : Check.MISMATCH;
    }

    private static List<String> nonEmpty(Collection<String> secrets) {
        return secrets.stream()
                .filter(secret -> secret != null && !secret.isEmpty())
                .collect(Collectors.toList());
    }

    /** Returns the 32 digest bytes of a well-formed header, or {@code null}. Hex digits may be upper case. */
    private static byte[] parseHeader(String header) {
        if (header == null) {
            return null;
        }
        String value = header.strip();
        if (!value.startsWith(PREFIX) || value.length() != PREFIX.length() + DIGEST_HEX_LENGTH) {
            return null;
        }
        byte[] digest = new byte[DIGEST_HEX_LENGTH / 2];
        for (int i = 0; i < digest.length; i++) {
            int high = hexValue(value.charAt(PREFIX.length() + 2 * i));
            int low = hexValue(value.charAt(PREFIX.length() + 2 * i + 1));
            if (high < 0 || low < 0) {
                return null;
            }
            digest[i] = (byte) ((high << 4) | low);
        }
        return digest;
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = digits[(bytes[i] >> 4) & 0x0F];
            out[2 * i + 1] = digits[bytes[i] & 0x0F];
        }
        return new String(out);
    }

    private static byte[] bytes(String payload) {
        return payload == null ? null : payload.getBytes(StandardCharsets.UTF_8);
    }

    private static WebhookEvent parse(byte[] payload) {
        Object parsed;
        try {
            parsed = Json.parse(payload);
        } catch (IOException | RuntimeException e) {
            throw new WebhookParseException("The webhook body is not valid JSON", e);
        }
        Map<?, ?> envelope = Json.object(parsed);
        if (envelope == null) {
            throw new WebhookParseException("The webhook body is not a JSON object");
        }
        WireModels.IdentificationScoredEvent envelopeWire = new WireModels.IdentificationScoredEvent(envelope);
        Object type = WireValue.string(envelopeWire.event_type());
        if (!(type instanceof String) || ((String) type).isEmpty()) {
            throw new WebhookParseException("The webhook body has no event_type");
        }
        Object version = WireValue.string(envelopeWire.schema_version());
        String schemaVersion = version instanceof String ? (String) version : null;
        warnOnUnknownSchemaVersion(schemaVersion);
        Instant createdAt = Timestamps.parseRfc3339(WireValue.string(envelopeWire.created_at()));
        Map<String, Object> raw = Json.freezeObject(envelope);
        switch ((String) type) {
            case WebhookEvent.IDENTIFICATION_SCORED:
                Map<?, ?> data = Json.object(WireValue.object(envelopeWire.data()));
                if (data == null) {
                    throw new WebhookParseException("The identification.scored event has no data object");
                }
                return new IdentificationScoredEvent(schemaVersion, createdAt, Normalizer.fromWebhookData(data), raw);
            case WebhookEvent.WEBHOOK_PING:
                return new WebhookPingEvent(schemaVersion, createdAt, raw);
            default:
                return new UnknownWebhookEvent((String) type, schemaVersion, createdAt, raw);
        }
    }

    private static void warnOnUnknownSchemaVersion(String schemaVersion) {
        if (schemaVersion == null || WebhookEvent.SCHEMA_VERSION.equals(schemaVersion)) {
            return;
        }
        if (WARNED_SCHEMA_VERSIONS.size() < MAX_WARNED_SCHEMA_VERSIONS && WARNED_SCHEMA_VERSIONS.add(schemaVersion)) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Received a ShieldLabs webhook with schema_version {0}; this SDK was written for {1}. "
                            + "The event was parsed; consider upgrading the SDK.",
                    schemaVersion,
                    WebhookEvent.SCHEMA_VERSION);
        }
    }
}
