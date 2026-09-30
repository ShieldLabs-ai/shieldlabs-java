package ai.shieldlabs;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Client for the ShieldLabs Management API ({@code https://api.shieldlabs.ai}): read the profile of a
 * registered domain.
 *
 * <p>Authenticate with the domain's Secret Key and the registered domain. The server matches the
 * domain exactly, so the client normalizes it first: it trims it, lowercases it and strips a scheme,
 * a path, a trailing slash and a leading {@code www.}.
 *
 * <p>The Management API allows about 15 requests per minute per caller IP, and the request that goes
 * over the limit blocks that IP for 10 minutes. Call it sparingly and cache the profile. A 429 is
 * therefore never retried (connection errors, timeouts and 5xx responses are).
 *
 * <p>Instances are immutable and safe for concurrent use.
 */
public final class ManagementClient {
    /** Default Management API origin. */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.shieldlabs.ai");

    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://");

    private final URI baseUrl;
    private final String domain;
    private final Transport transport;

    private ManagementClient(Builder builder) {
        String secretKey = Validation.credential(builder.secretKey, "secretKey");
        if (builder.domain == null) {
            throw new ValidationException("domain is required");
        }
        String normalized = normalizeDomain(builder.domain);
        if (normalized.isEmpty()) {
            throw new ValidationException("domain is required");
        }
        Validation.domain(normalized);
        String origin = Validation.baseUrl(builder.baseUrl, "baseUrl", builder.allowInsecureHttp);
        if (builder.timeout == null || builder.timeout.isNegative() || builder.timeout.isZero()) {
            throw new ValidationException("timeout must be positive");
        }
        if (builder.maxRetries < 0) {
            throw new ValidationException("maxRetries must be 0 or more");
        }
        HttpClient http =
                builder.httpClient != null
                        ? builder.httpClient
                        : HttpClient.newBuilder().connectTimeout(builder.timeout).build();
        String[] headers = {
            "Accept", "application/json",
            "User-Agent", Transport.userAgent(),
            "X-Shield-Domain", normalized,
            "Authorization", "Bearer " + secretKey,
        };
        this.baseUrl = URI.create(origin);
        this.domain = normalized;
        this.transport =
                new Transport(http, builder.timeout, builder.maxRetries, false, headers, builder.timer, builder.random);
    }

    /**
     * Returns a new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a client from environment variables: {@code SHIELDLABS_SECRET_KEY} and
     * {@code SHIELDLABS_DOMAIN} (required), {@code SHIELDLABS_MANAGEMENT_BASE_URL} (optional).
     *
     * @return the client
     * @throws ValidationException when a required variable is not set
     */
    public static ManagementClient fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    static ManagementClient fromEnvironment(Function<String, String> env) {
        String secretKey = env.apply("SHIELDLABS_SECRET_KEY");
        String domain = env.apply("SHIELDLABS_DOMAIN");
        if (secretKey == null || secretKey.isBlank()) {
            throw new ValidationException("SHIELDLABS_SECRET_KEY is not set");
        }
        if (domain == null || domain.isBlank()) {
            throw new ValidationException("SHIELDLABS_DOMAIN is not set");
        }
        Builder builder = builder().secretKey(secretKey).domain(domain);
        String baseUrl = env.apply("SHIELDLABS_MANAGEMENT_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /**
     * Normalizes a domain the way the server stores it: trimmed, lowercased, without scheme, path
     * (which also removes a trailing slash), query, fragment or leading {@code www.}.
     */
    static String normalizeDomain(String value) {
        String text = value.strip().toLowerCase(Locale.ROOT);
        text = SCHEME.matcher(text).replaceFirst("");
        int end = text.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int index = text.indexOf(c);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        text = text.substring(0, end);
        if (text.startsWith("www.")) {
            text = text.substring("www.".length());
        }
        return text.strip();
    }

    /**
     * Reads the profile of the domain ({@code GET /v1/profile}). Cache the result: the Management API
     * allows about 15 requests per minute per IP.
     *
     * @return the profile
     * @throws AuthenticationException when the Secret Key or domain is rejected
     * @throws RateLimitException when the IP is rate limited (never retried)
     * @throws ShieldLabsException when the request fails
     */
    public DomainProfile getProfile() {
        return parseProfile(transport.get(profileUri()));
    }

    /**
     * Asynchronous {@link #getProfile()}.
     *
     * @return a future with the profile; it fails with a {@link ShieldLabsException}
     */
    public CompletableFuture<DomainProfile> getProfileAsync() {
        return transport.getAsync(profileUri()).thenApply(ManagementClient::parseProfile);
    }

    /**
     * Returns the normalized domain sent in {@code X-Shield-Domain}.
     *
     * @return the domain, for example {@code example.com}
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Returns the Management API origin in use.
     *
     * @return the origin, for example {@code https://api.shieldlabs.ai}
     */
    public URI getBaseUrl() {
        return baseUrl;
    }

    private URI profileUri() {
        return URI.create(baseUrl + "/v1/profile");
    }

    private static DomainProfile parseProfile(JsonResponse response) {
        Map<?, ?> body = Json.object(response.value);
        if (body == null) {
            throw response.unexpected("expected a JSON object");
        }
        return DomainProfile.fromJson(body);
    }

    @Override
    public String toString() {
        return "ManagementClient{baseUrl=" + baseUrl + ", domain=" + domain + "}";
    }

    /** Builder for {@link ManagementClient}. Not thread-safe. */
    public static final class Builder {
        private String secretKey;
        private String domain;
        private URI baseUrl = DEFAULT_BASE_URL;
        private boolean allowInsecureHttp;
        private Duration timeout = ShieldLabsClient.DEFAULT_TIMEOUT;
        private int maxRetries = ShieldLabsClient.DEFAULT_MAX_RETRIES;
        private HttpClient httpClient;
        private Timer timer = Timer.SYSTEM;
        private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

        private Builder() {
        }

        /**
         * Sets the Secret Key of the domain. Required. Surrounding whitespace, such as the line break at
         * the end of a secrets file, is removed. The key is never logged.
         *
         * @param secretKey the Secret Key
         * @return this builder
         */
        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        /**
         * Sets the registered domain, for example {@code example.com}. Required. A scheme, path,
         * trailing slash or leading {@code www.} is removed and the value is lowercased.
         *
         * @param domain the registered domain
         * @return this builder
         */
        public Builder domain(String domain) {
            this.domain = domain;
            return this;
        }

        /**
         * Sets the Management API origin, {@code https://api.shieldlabs.ai} by default. The URL must use
         * https, because every request carries the Secret Key; plain http is accepted for
         * {@code localhost}, {@code 127.0.0.1} and {@code [::1]} (see
         * {@link #allowInsecureHttp(boolean)} for other test hosts).
         *
         * @param baseUrl an absolute https URL, or http for a loopback host
         * @return this builder
         */
        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the Management API origin from a string.
         *
         * @param baseUrl an absolute https URL, or http for a loopback host
         * @return this builder
         * @throws ValidationException when the value is not a valid URL
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Validation.parseUri(baseUrl, "baseUrl");
            return this;
        }

        /**
         * Accepts a plain http base URL on a host other than {@code localhost}, {@code 127.0.0.1} or
         * {@code [::1]}, for example a test server in a container network. The Secret Key then travels
         * unencrypted, so the client logs a warning. Off by default; never enable it for production.
         *
         * @param allowInsecureHttp {@code true} to accept plain http on any host
         * @return this builder
         */
        public Builder allowInsecureHttp(boolean allowInsecureHttp) {
            this.allowInsecureHttp = allowInsecureHttp;
            return this;
        }

        /**
         * Sets the timeout of one HTTP attempt, 10 seconds by default.
         *
         * @param timeout a positive duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets how many times a failed request is retried after connection errors, timeouts and 5xx
         * responses; 2 by default. A 429 is never retried.
         *
         * @param maxRetries 0 or more
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Uses your own {@link HttpClient}.
         *
         * @param httpClient the client
         * @return this builder
         */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        Builder timer(Timer timer) {
            this.timer = timer;
            return this;
        }

        Builder random(DoubleSupplier random) {
            this.random = random;
            return this;
        }

        /**
         * Builds the client.
         *
         * @return the client
         * @throws ValidationException when a credential is missing or an option is invalid
         */
        public ManagementClient build() {
            return new ManagementClient(this);
        }
    }
}
