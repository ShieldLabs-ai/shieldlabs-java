package ai.shieldlabs;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientIdentityTest {
    @Test void keepsScopedProofAndUnknownValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> fixture = mapper.readValue(getClass().getResourceAsStream("/client-identity.json"), Map.class);
        Identification history = Identification.fromHistoryRow(Map.of("score", 70, "client_identity", fixture));
        Identification webhook = Identification.fromWebhookData(Map.of("risk_score", 70, "client_identity", fixture));
        assertEquals(history.getClientIdentity().raw(), webhook.getClientIdentity().raw());
        assertEquals("provider", history.getClientIdentity().getVerified().get(0).getSubject());
        assertEquals("GPTBot", history.getClientIdentity().getClaims().get(0).getAgentName());
        assertEquals(70, history.getRiskScore());
        assertEquals(fixture, mapper.convertValue(history, Map.class).get("client_identity"));
        fixture = new LinkedHashMap<>(fixture); fixture.put("availability", "future_state"); fixture.put("extra", "kept");
        ClientIdentity future = Identification.fromHistoryRow(Map.of("client_identity", fixture)).getClientIdentity();
        assertEquals("future_state", future.getAvailability()); assertEquals("kept", future.raw().get("extra"));
        assertThrows(UnsupportedOperationException.class, () -> future.raw().put("bad", true));
        assertNull(Identification.fromHistoryRow(Map.of()).getClientIdentity());
        assertNull(Identification.fromHistoryRow(Map.of("client_identity", "bad")).getClientIdentity());
        assertFalse(mapper.convertValue(Identification.fromHistoryRow(Map.of()), Map.class).containsKey("client_identity"));
        assertNotNull(future.getSchemaVersion()); assertEquals(1, future.getClassificationRevision());
        assertNotNull(future.getClassifierVersion()); assertNotNull(future.getRegistryRevision()); assertNotNull(future.getObservedAt()); assertNotNull(future.getDecidedAt());
        future.getClaims().get(0).getProviderId(); future.getClaims().get(0).getProfileId(); future.getClaims().get(0).getProviderName(); future.getClaims().get(0).getClientKind(); future.getClaims().get(0).getPurpose(); future.getClaims().get(0).getSource();
        future.getVerified().get(0).getValueId(); future.getVerified().get(0).getEvidenceIds(); future.getVerified().get(0).raw();
        future.getAssessments().get(0).getStatus(); future.getAssessments().get(0).getReason(); future.getAssessments().get(0).getCandidateProfileId(); future.getAssessments().get(0).raw();
        ClientIdentity.Evidence evidence=future.getEvidence().get(0); evidence.getId(); evidence.getMethod(); evidence.getSourceId(); evidence.getSourceRevision(); evidence.getCheckedAt(); evidence.getEvaluatedAt(); evidence.getCoveredAttributes(); evidence.getCoveredComponents(); evidence.getRequestBinding(); evidence.getReplayPolicy(); evidence.raw();
    }
}
