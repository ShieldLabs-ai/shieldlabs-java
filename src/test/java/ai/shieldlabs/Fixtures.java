package ai.shieldlabs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the shared test fixtures from {@code src/test/resources/fixtures}. */
final class Fixtures {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    static Map<String, Object> object(String name) {
        return map(parse(text(name)));
    }

    static Object parse(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Serializes a model with a plain mapper and reads it back as a map (the JSON mapping of the model). */
    static Map<String, Object> serialized(Object model) {
        return map(parse(json(model)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    static List<Map<String, Object>> cases(String name) {
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Object item : list(object(name).get("cases"))) {
            cases.add(map(item));
        }
        return cases;
    }

    /** Deep copy of a JSON object so a test can modify it. */
    static Map<String, Object> copy(Map<String, Object> value) {
        return new LinkedHashMap<>(map(parse(json(value))));
    }

    /** A normalization case by name. */
    static Map<String, Object> normalizationCase(String name) {
        for (Map<String, Object> item : cases("normalization-cases.json")) {
            if (name.equals(item.get("name"))) {
                return item;
            }
        }
        throw new IllegalArgumentException("No normalization case " + name);
    }

    /** HMAC signature header for a body. */
    static String sign(byte[] body, String secret) {
        return "sha256=" + Webhooks.hex(Webhooks.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), body));
    }
}
