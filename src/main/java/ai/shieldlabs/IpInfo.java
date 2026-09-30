package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * An IP address with its country, as seen for the public address of the request or for the local
 * network address.
 */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({"ip", "country"})
public final class IpInfo {
    private final String ip;
    private final String country;

    IpInfo(String ip, String country) {
        this.ip = ip;
        this.country = country;
    }

    /**
     * Returns the dotted IPv4 address.
     *
     * @return the address, or {@code ""} when none is known (for example an IPv6 visitor)
     */
    @JsonProperty("ip")
    public String getIp() {
        return ip;
    }

    /**
     * Returns the country as an English country name, for example {@code "Germany"}.
     *
     * @return the country name, or {@code ""} when unknown
     */
    @JsonProperty("country")
    public String getCountry() {
        return country;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IpInfo)) {
            return false;
        }
        IpInfo other = (IpInfo) o;
        return ip.equals(other.ip) && country.equals(other.country);
    }

    @Override
    public int hashCode() {
        return 31 * ip.hashCode() + country.hashCode();
    }

    @Override
    public String toString() {
        return country.isEmpty() ? ip : ip + " (" + country + ")";
    }
}
