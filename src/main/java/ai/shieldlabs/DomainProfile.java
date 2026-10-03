package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The profile of a registered domain, returned by {@link ManagementClient#getProfile()}. Immutable.
 *
 * <p>Serialized with Jackson it produces {@code domain}, {@code remaining_identifications},
 * {@code public_key_masked}, {@code secret_key_masked} and {@code created_at}. The original response
 * (including legacy fields) stays available through {@link #raw()}.
 */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({"domain", "remaining_identifications", "public_key_masked", "secret_key_masked", "created_at"})
public final class DomainProfile {
    private final String domain;
    private final long remainingIdentifications;
    private final String publicKeyMasked;
    private final String secretKeyMasked;
    private final Instant createdAt;
    private final Map<String, Object> raw;

    DomainProfile(
            String domain,
            long remainingIdentifications,
            String publicKeyMasked,
            String secretKeyMasked,
            Instant createdAt,
            Map<String, Object> raw) {
        this.domain = domain;
        this.remainingIdentifications = remainingIdentifications;
        this.publicKeyMasked = publicKeyMasked;
        this.secretKeyMasked = secretKeyMasked;
        this.createdAt = createdAt;
        this.raw = raw;
    }

    static DomainProfile fromJson(Map<?, ?> body) {
        WireModels.DomainProfile bodyWire = new WireModels.DomainProfile(body);
        Long weight = Json.longValue(WireValue.integer(bodyWire.Weight()));
        return new DomainProfile(
                Json.text(WireValue.string(bodyWire.Domain())),
                weight == null ? 0L : weight,
                Json.text(WireValue.string(bodyWire.PublicKey())),
                Json.text(WireValue.string(bodyWire.Secret())),
                Timestamps.parseRfc3339(WireValue.string(bodyWire.CreatedAt())),
                Json.freezeObject(body));
    }

    /**
     * Returns the registered domain.
     *
     * @return the domain, for example {@code "example.com"}
     */
    @JsonProperty("domain")
    public String getDomain() {
        return domain;
    }

    /**
     * Returns the included identifications left on the account. The value can be negative when the
     * account is over its included volume.
     *
     * @return the remaining identifications
     */
    @JsonProperty("remaining_identifications")
    public long getRemainingIdentifications() {
        return remainingIdentifications;
    }

    /**
     * Returns the Public Key with every character except the last 4 replaced by {@code *}.
     *
     * @return the masked Public Key
     */
    @JsonProperty("public_key_masked")
    public String getPublicKeyMasked() {
        return publicKeyMasked;
    }

    /**
     * Returns the Secret Key with every character except the last 4 replaced by {@code *}.
     *
     * @return the masked Secret Key
     */
    @JsonProperty("secret_key_masked")
    public String getSecretKeyMasked() {
        return secretKeyMasked;
    }

    /**
     * Returns when the domain was registered.
     *
     * @return the timestamp, or {@code null} when it is missing or cannot be parsed
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    @JsonProperty("created_at")
    private String createdAtJson() {
        return createdAt == null ? null : Timestamps.format(createdAt);
    }

    /**
     * Returns the original response object, including fields the model does not expose.
     *
     * @return an unmodifiable map
     */
    public Map<String, Object> raw() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DomainProfile)) {
            return false;
        }
        DomainProfile other = (DomainProfile) o;
        return remainingIdentifications == other.remainingIdentifications
                && domain.equals(other.domain)
                && publicKeyMasked.equals(other.publicKeyMasked)
                && secretKeyMasked.equals(other.secretKeyMasked)
                && Objects.equals(createdAt, other.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(domain, remainingIdentifications, publicKeyMasked, secretKeyMasked, createdAt);
    }

    @Override
    public String toString() {
        return "DomainProfile{domain=" + domain + ", remainingIdentifications=" + remainingIdentifications + "}";
    }
}
