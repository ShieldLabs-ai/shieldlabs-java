package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireModelsTest {
    @Test
    void everyGeneratedAccessorPreservesRawMissingNullAndUnexpectedValues() throws Exception {
        for (Class<?> model : WireModels.class.getDeclaredClasses()) {
            if (model.isEnum()) {
                continue;
            }
            Map<String, Object> raw = new HashMap<>();
            Object view = model.getDeclaredConstructor(Map.class).newInstance(raw);
            for (Method getter : model.getDeclaredMethods()) {
                if (getter.isSynthetic()) {
                    continue;
                }
                Method reader = null;
                for (Method candidate : WireValue.class.getDeclaredMethods()) {
                    if (candidate.getParameterCount() == 1 && candidate.getParameterTypes()[0] == getter.getReturnType()) {
                        reader = candidate;
                    }
                }
                assertTrue(reader != null, getter.toString());
                assertNull(reader.invoke(null, getter.invoke(view)));
                raw.put(getter.getName(), null);
                assertNull(reader.invoke(null, getter.invoke(view)));
                Object oddValue = new Object();
                raw.put(getter.getName(), oddValue);
                assertSame(oddValue, reader.invoke(null, getter.invoke(view)));
                raw.put(getter.getName(), "future-value");
                assertEquals("future-value", reader.invoke(null, getter.invoke(view)));
                raw.clear();
            }
        }
    }

    @Test
    void generatedContractDoesNotMakeNormalizationStrict() {
        Map<String, Object> row = new HashMap<>();
        row.put("request_id", 123);
        row.put("score", "999");
        row.put("connection_type", "future-connection");
        row.put("future_column", Map.of("answer", 42));
        Identification result = Identification.fromHistoryRow(row);
        assertEquals("123", result.getRequestId());
        assertEquals(0, result.getRiskScore());
        assertEquals("future-connection", result.getConnectionType());
        assertEquals(row.get("future_column"), result.raw().get("future_column"));
        DomainProfile profile = DomainProfile.fromJson(Map.of("Domain", true, "Weight", "12", "extra", 7));
        assertEquals("true", profile.getDomain());
        assertEquals(0, profile.getRemainingIdentifications());
        assertEquals(7, profile.raw().get("extra"));
    }
}
