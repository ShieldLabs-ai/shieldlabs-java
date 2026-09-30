package ai.shieldlabs;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Arrays;

/**
 * Where the visit came from: attribution channel, referrer, landing URL, click ID type and UTM
 * parameters. Every field is a string and is {@code ""} when absent.
 */
@JsonAutoDetect(
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        fieldVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({
    "channel",
    "referrer_domain",
    "landing_url",
    "click_id_type",
    "utm_source",
    "utm_medium",
    "utm_campaign",
    "utm_content",
    "utm_term"
})
public final class TrafficSource {
    private final String channel;
    private final String referrerDomain;
    private final String landingUrl;
    private final String clickIdType;
    private final String utmSource;
    private final String utmMedium;
    private final String utmCampaign;
    private final String utmContent;
    private final String utmTerm;

    TrafficSource(
            String channel,
            String referrerDomain,
            String landingUrl,
            String clickIdType,
            String utmSource,
            String utmMedium,
            String utmCampaign,
            String utmContent,
            String utmTerm) {
        this.channel = channel;
        this.referrerDomain = referrerDomain;
        this.landingUrl = landingUrl;
        this.clickIdType = clickIdType;
        this.utmSource = utmSource;
        this.utmMedium = utmMedium;
        this.utmCampaign = utmCampaign;
        this.utmContent = utmContent;
        this.utmTerm = utmTerm;
    }

    /**
     * Returns the attribution channel, for example {@code "Google Ads"}, {@code "Organic Search"},
     * {@code "Referral"} or {@code "Direct"}.
     *
     * @return the channel, or {@code ""}
     */
    @JsonProperty("channel")
    public String getChannel() {
        return channel;
    }

    /**
     * Returns the referring site (registrable domain without {@code www.}).
     *
     * @return the referrer domain, or {@code ""}
     */
    @JsonProperty("referrer_domain")
    public String getReferrerDomain() {
        return referrerDomain;
    }

    /**
     * Returns the landing page URL without its fragment. It can contain query parameters.
     *
     * @return the landing URL, or {@code ""}
     */
    @JsonProperty("landing_url")
    public String getLandingUrl() {
        return landingUrl;
    }

    /**
     * Returns the type of ad click ID found on the landing URL, for example {@code "gclid"}.
     *
     * @return the click ID type, or {@code ""}
     */
    @JsonProperty("click_id_type")
    public String getClickIdType() {
        return clickIdType;
    }

    /**
     * Returns {@code utm_source} (lowercased).
     *
     * @return the value, or {@code ""}
     */
    @JsonProperty("utm_source")
    public String getUtmSource() {
        return utmSource;
    }

    /**
     * Returns {@code utm_medium} (lowercased).
     *
     * @return the value, or {@code ""}
     */
    @JsonProperty("utm_medium")
    public String getUtmMedium() {
        return utmMedium;
    }

    /**
     * Returns {@code utm_campaign}.
     *
     * @return the value, or {@code ""}
     */
    @JsonProperty("utm_campaign")
    public String getUtmCampaign() {
        return utmCampaign;
    }

    /**
     * Returns {@code utm_content}.
     *
     * @return the value, or {@code ""}
     */
    @JsonProperty("utm_content")
    public String getUtmContent() {
        return utmContent;
    }

    /**
     * Returns {@code utm_term}.
     *
     * @return the value, or {@code ""}
     */
    @JsonProperty("utm_term")
    public String getUtmTerm() {
        return utmTerm;
    }

    private String[] fields() {
        return new String[] {
            channel, referrerDomain, landingUrl, clickIdType, utmSource, utmMedium, utmCampaign, utmContent, utmTerm
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof TrafficSource && Arrays.equals(fields(), ((TrafficSource) o).fields());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(fields());
    }

    @Override
    public String toString() {
        return "TrafficSource{channel=" + channel + ", referrerDomain=" + referrerDomain + "}";
    }
}
