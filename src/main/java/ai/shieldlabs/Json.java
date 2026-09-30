package ai.shieldlabs;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON helpers. Values are parsed into plain Java objects ({@code Map}, {@code List}, {@code String},
 * {@code Integer}/{@code Long}/{@code BigInteger}, {@code Double}, {@code Boolean}, {@code null}) so
 * the normalization rules can be applied exactly as specified, including tolerant handling of
 * unexpected types.
 */
final class Json {
    /** Shared, thread-safe mapper. Accepts NaN/Infinity literals and rejects trailing garbage. */
    static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    private Json() {
    }

    static Object parse(String text) throws IOException {
        return MAPPER.readValue(text, Object.class);
    }

    static Object parse(byte[] bytes) throws IOException {
        return MAPPER.readValue(bytes, Object.class);
    }

    /** Returns a deep, unmodifiable copy of a parsed JSON value. */
    static Object freeze(Object value) {
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                copy.add(freeze(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /** Returns a deep, unmodifiable copy of a JSON object. */
    static Map<String, Object> freezeObject(Map<?, ?> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /** The value as a JSON object, or {@code null} when it is not one. */
    static Map<?, ?> object(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    /** The value as a JSON object, or an empty map when it is not one. */
    static Map<?, ?> objectOrEmpty(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap();
    }

    /** Truthiness for normalization: null, false, 0, "" and empty containers are false. */
    static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof BigInteger) {
            return ((BigInteger) value).signum() != 0;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).signum() != 0;
        }
        if (value instanceof Double || value instanceof Float) {
            return ((Number) value).doubleValue() != 0.0;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue() != 0L;
        }
        if (value instanceof Map) {
            return !((Map<?, ?>) value).isEmpty();
        }
        if (value instanceof Collection) {
            return !((Collection<?>) value).isEmpty();
        }
        return true;
    }

    /** The value when it is a JSON integer (never a boolean or a float), else {@code null}. */
    static BigInteger integer(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        return null;
    }

    /** The value when it is a JSON integer that fits in an {@code int}, else {@code null}. */
    static Integer intValue(Object value) {
        BigInteger number = integer(value);
        return number != null && number.bitLength() < Integer.SIZE ? Integer.valueOf(number.intValue()) : null;
    }

    /** The value when it is a JSON integer that fits in a {@code long}, else {@code null}. */
    static Long longValue(Object value) {
        BigInteger number = integer(value);
        return number != null && number.bitLength() < Long.SIZE ? Long.valueOf(number.longValue()) : null;
    }

    /** A string field read with a default of {@code ""}: {@code null} becomes empty. */
    static String text(Object value) {
        return value == null ? "" : scalarText(value);
    }

    /** A string field where every falsy value ({@code null}, {@code ""}, {@code 0}, {@code false}) becomes empty. */
    static String orEmpty(Object value) {
        return truthy(value) ? scalarText(value) : "";
    }

    private static String scalarText(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "";
    }
}
