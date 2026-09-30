package ai.shieldlabs;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Client for the ShieldLabs History API: read the verdict for a request ID and search identifications
 * by device, visitor, user, IP, session or cookie.
 *
 * <p>Authenticate with the Private API Key of one domain ({@code sec_...}, from the analytics
 * dashboard). The key must stay on your server: never ship it to a browser or other client-side code.
 *
 * <pre>{@code
 * ShieldLabsClient client = ShieldLabsClient.builder()
 *         .apiKey(System.getenv("SHIELDLABS_API_KEY"))
 *         .build();
 * Optional<Identification> identification = client.identifications().get(requestId);
 * }</pre>
 *
 * <p>Instances are immutable and safe for concurrent use; create one per domain and share it.
 */
public final class ShieldLabsClient {
    /** Version of this SDK, sent in the {@code User-Agent} header. */
    public static final String VERSION = "1.0.0";
    /** Default History API origin. */
    public static final URI DEFAULT_BASE_URL = URI.create("https://account.shieldlabs.ai");
    /** Default timeout of one HTTP attempt. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    /** Default number of retries for transient failures. */
    public static final int DEFAULT_MAX_RETRIES = 2;

    private static final System.Logger LOGGER = System.getLogger("ai.shieldlabs");
    private static final Pattern PRIVATE_KEY = Pattern.compile("sec_[a-z0-9]{8}-[a-z0-9]{8}-[a-z0-9]{8}");

    private final URI baseUrl;
    private final HistoryService history;
    private final IdentificationService identifications;

    private ShieldLabsClient(Builder builder) {
        String apiKey = Validation.credential(builder.apiKey, "apiKey");
        if (!PRIVATE_KEY.matcher(apiKey).matches()) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "The ShieldLabs API key does not look like a Private API Key (sec_xxxxxxxx-xxxxxxxx-xxxxxxxx). "
                            + "The History API accepts only Private API Keys.");
        }
        String base = Validation.baseUrl(builder.baseUrl, "baseUrl", builder.allowInsecureHttp);
        String origin = Urls.historyOrigin(base);
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
            "Authorization", "Bearer " + apiKey,
        };
        Transport transport =
                new Transport(http, builder.timeout, builder.maxRetries, true, headers, builder.timer, builder.random);
        this.baseUrl = URI.create(origin);
        this.history = new HistoryService(transport, origin);
        this.identifications = new IdentificationService(transport, history);
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
     * Creates a client from environment variables: {@code SHIELDLABS_API_KEY} (required) and
     * {@code SHIELDLABS_API_BASE_URL} (optional).
     *
     * @return the client
     * @throws ValidationException when {@code SHIELDLABS_API_KEY} is not set
     */
    public static ShieldLabsClient fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    static ShieldLabsClient fromEnvironment(Function<String, String> env) {
        String apiKey = env.apply("SHIELDLABS_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new ValidationException("SHIELDLABS_API_KEY is not set");
        }
        Builder builder = builder().apiKey(apiKey);
        String baseUrl = env.apply("SHIELDLABS_API_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /**
     * Returns the verdict reader for request IDs.
     *
     * @return the identification service
     */
    public IdentificationService identifications() {
        return identifications;
    }

    /**
     * Returns the History API search service.
     *
     * @return the history service
     */
    public HistoryService history() {
        return history;
    }

    /**
     * Returns the History API origin in use, after normalization (a trailing {@code /api} is removed).
     *
     * @return the origin, for example {@code https://account.shieldlabs.ai}
     */
    public URI getBaseUrl() {
        return baseUrl;
    }

    @Override
    public String toString() {
        return "ShieldLabsClient{baseUrl=" + baseUrl + "}";
    }

    /** Builder for {@link ShieldLabsClient}. Not thread-safe. */
    public static final class Builder {
        private String apiKey;
        private URI baseUrl = DEFAULT_BASE_URL;
        private boolean allowInsecureHttp;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private HttpClient httpClient;
        private Timer timer = Timer.SYSTEM;
        private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

        private Builder() {
        }

        /**
         * Sets the Private API Key ({@code sec_...}). Required. Surrounding whitespace, such as the line
         * break at the end of a secrets file, is removed. The key is never logged.
         *
         * @param apiKey the key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the History API origin, {@code https://account.shieldlabs.ai} by default. A trailing
         * {@code /api} is removed, so request paths never become {@code /api/api/...}. The development
         * host is {@code https://dev.account.shieldlabs.ai}. The URL must use https, because every
         * request carries the key; plain http is accepted for {@code localhost}, {@code 127.0.0.1} and
         * {@code [::1]} (see {@link #allowInsecureHttp(boolean)} for other test hosts).
         *
         * @param baseUrl an absolute https URL, or http for a loopback host
         * @return this builder
         */
        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the History API origin from a string.
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
         * {@code [::1]}, for example a test server in a container network. The key then travels
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
         * Sets the timeout of one HTTP attempt, 10 seconds by default. While
         * {@link IdentificationService#get(String, GetIdentificationOptions)} waits for a verdict, each
         * poll uses this timeout shortened to the time left, but never below one second.
         *
         * @param timeout a positive duration
         * @return this builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets how many times a failed GET is retried after connection errors, timeouts, 429 and 5xx
         * responses; 2 by default. Other errors are never retried. The polls of
         * {@link IdentificationService#get(String, GetIdentificationOptions)} are single attempts and do
         * not use these retries: a failed poll is followed by the next poll until the deadline.
         *
         * @param maxRetries 0 or more
         * @return this builder
         */
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Uses your own {@link HttpClient}, for example to configure a proxy, an executor or TLS. By
         * default the SDK creates one with a connect timeout equal to {@link #timeout(Duration)}.
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
         * @throws ValidationException when the key is missing or an option is invalid
         */
        public ShieldLabsClient build() {
            return new ShieldLabsClient(this);
        }
    }
}
