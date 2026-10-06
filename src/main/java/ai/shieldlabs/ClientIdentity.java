package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stored server attribution: a verified provider does not verify a claimed agent or AI mode. */
public final class ClientIdentity {
    private final Map<String, Object> raw;
    private ClientIdentity(Map<String, Object> raw) { this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw)); }

    static ClientIdentity fromValue(Object value) {
        if (!(value instanceof Map)) return null;
        Map<?, ?> object = (Map<?, ?>) value;
        for (String key : new String[] {"schema_version", "availability", "registry_revision", "observed_at"}) {
            if (!(object.get(key) instanceof String)) return null;
        }
        if (!(object.get("classification_revision") instanceof Number) || ((Number) object.get("classification_revision")).longValue() < 1) return null;
        for (String key : new String[] {"claims", "verified", "assessments", "evidence"}) {
            if (!(object.get(key) instanceof List)) return null;
            for (Object entry : (List<?>) object.get(key)) if (!(entry instanceof Map)) return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        object.forEach((key, item) -> { if (key instanceof String) result.put((String) key, item); });
        return new ClientIdentity(result);
    }
    /** Original object, including unknown future values. */
    @JsonValue public Map<String, Object> raw() { return raw; }
    public String getSchemaVersion() { return (String) raw.get("schema_version"); }
    public long getClassificationRevision() { return ((Number) raw.get("classification_revision")).longValue(); }
    public String getClassifierVersion() { Object value = raw.get("classifier_version"); return value instanceof String ? (String) value : null; }
    public String getRegistryRevision() { return (String) raw.get("registry_revision"); }
    public String getAvailability() { return (String) raw.get("availability"); }
    public String getObservedAt() { return (String) raw.get("observed_at"); }
    public String getDecidedAt() { Object value = raw.get("decided_at"); return value instanceof String ? (String) value : null; }
    public List<Claim> getClaims() {
        List<Claim> result = new ArrayList<>();
        for (Object value : (List<?>) raw.get("claims")) result.add(new Claim((Map<?, ?>) value));
        return Collections.unmodifiableList(result);
    }
    public static final class Claim {
        private final Map<?, ?> raw;
        private Claim(Map<?, ?> raw) { this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw)); }
        @JsonValue public Map<?, ?> raw() { return raw; }
        public String getProfileId() { Object value=raw.get("profile_id"); return value instanceof String ? (String) value : null; }
        public String getProviderId() { Object value=raw.get("provider_id"); return value instanceof String ? (String) value : null; }
        public String getProviderName() { Object value=raw.get("provider_name"); return value instanceof String ? (String) value : null; }
        public String getAgentName() { Object value=raw.get("agent_name"); return value instanceof String ? (String) value : null; }
        public String getClientKind() { Object value=raw.get("client_kind"); return value instanceof String ? (String) value : null; }
        public String getPurpose() { Object value=raw.get("purpose"); return value instanceof String ? (String) value : null; }
        public String getSource() { Object value=raw.get("source"); return value instanceof String ? (String) value : null; }
    }
    public List<Verified> getVerified() {
        List<Verified> result = new ArrayList<>();
        for (Object value : (List<?>) raw.get("verified")) result.add(new Verified((Map<?, ?>) value));
        return Collections.unmodifiableList(result);
    }
    public static final class Verified {
        private final Map<?, ?> raw;
        private Verified(Map<?, ?> raw) { this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw)); }
        @JsonValue public Map<?, ?> raw() { return raw; }
        public String getSubject() { Object value=raw.get("subject"); return value instanceof String ? (String) value : null; }
        public String getValueId() { Object value=raw.get("value_id"); return value instanceof String ? (String) value : null; }
        public List<String> getEvidenceIds() {
            List<String> result = new ArrayList<>(); Object value = raw.get("evidence_ids");
            if (value instanceof List) for (Object item : (List<?>) value) if (item instanceof String) result.add((String) item);
            return Collections.unmodifiableList(result);
        }
    }
    public List<Assessment> getAssessments() {
        List<Assessment> result = new ArrayList<>();
        for (Object value : (List<?>) raw.get("assessments")) result.add(new Assessment((Map<?, ?>) value));
        return Collections.unmodifiableList(result);
    }
    public static final class Assessment {
        private final Map<?, ?> raw;
        private Assessment(Map<?, ?> raw) { this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw)); }
        @JsonValue public Map<?, ?> raw() { return raw; }
        public String getCandidateProfileId() { Object value=raw.get("candidate_profile_id"); return value instanceof String ? (String) value : null; }
        public String getStatus() { Object value=raw.get("status"); return value instanceof String ? (String) value : null; }
        public String getReason() { Object value=raw.get("reason"); return value instanceof String ? (String) value : null; }
    }
    public List<Evidence> getEvidence() {
        List<Evidence> result = new ArrayList<>();
        for (Object value : (List<?>) raw.get("evidence")) result.add(new Evidence((Map<?, ?>) value));
        return Collections.unmodifiableList(result);
    }
    public static final class Evidence {
        private final Map<?, ?> raw;
        private Evidence(Map<?, ?> raw) { this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw)); }
        @JsonValue public Map<?, ?> raw() { return raw; }
        public String getId() { Object value=raw.get("id"); return value instanceof String ? (String) value : null; }
        public String getMethod() { Object value=raw.get("method"); return value instanceof String ? (String) value : null; }
        public String getSourceId() { Object value=raw.get("source_id"); return value instanceof String ? (String) value : null; }
        public String getSourceRevision() { Object value=raw.get("source_revision"); return value instanceof String ? (String) value : null; }
        public String getCheckedAt() { Object value=raw.get("checked_at"); return value instanceof String ? (String) value : null; }
        public String getEvaluatedAt() { Object value=raw.get("evaluated_at"); return value instanceof String ? (String) value : null; }
        public List<String> getCoveredAttributes() {
            List<String> result = new ArrayList<>(); Object value = raw.get("covered_attributes");
            if (value instanceof List) for (Object item : (List<?>) value) if (item instanceof String) result.add((String) item);
            return Collections.unmodifiableList(result);
        }
        public List<String> getCoveredComponents() {
            List<String> result = new ArrayList<>(); Object value = raw.get("covered_components");
            if (value instanceof List) for (Object item : (List<?>) value) if (item instanceof String) result.add((String) item);
            return Collections.unmodifiableList(result);
        }
        public String getRequestBinding() { Object value=raw.get("request_binding"); return value instanceof String ? (String) value : null; }
        public String getReplayPolicy() { Object value=raw.get("replay_policy"); return value instanceof String ? (String) value : null; }
    }
}
