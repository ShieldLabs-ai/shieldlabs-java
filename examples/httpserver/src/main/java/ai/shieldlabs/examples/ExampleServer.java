package ai.shieldlabs.examples;

import ai.shieldlabs.EvaluateOptions;
import ai.shieldlabs.Evaluation;
import ai.shieldlabs.Identification;
import ai.shieldlabs.IdentificationScoredEvent;
import ai.shieldlabs.Risk;
import ai.shieldlabs.RiskBand;
import ai.shieldlabs.ShieldLabsClient;
import ai.shieldlabs.ShieldLabsException;
import ai.shieldlabs.SignatureVerificationException;
import ai.shieldlabs.ValidationException;
import ai.shieldlabs.WebhookEvent;
import ai.shieldlabs.WebhookParseException;
import ai.shieldlabs.WebhookPingEvent;
import ai.shieldlabs.Webhooks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * A minimal backend on the JDK HTTP server that does both halves of a ShieldLabs integration.
 *
 * <ul>
 *   <li>{@code POST /signup} reads the {@code requestId} the browser SDK returned, waits for the
 *       verdict with {@code identifications().get}, and refuses the signup when the identification is
 *       missing, reused, stale, rate limited, shows browser automation (or JavaScript disabled) or
 *       falls in the dangerous band.
 *   <li>{@code POST /webhooks/shieldlabs} verifies the signature of each delivery, answers fast and
 *       logs each event once per request ID.
 * </ul>
 *
 * <p>Configuration: {@code SHIELDLABS_API_KEY}, {@code SHIELDLABS_WEBHOOK_SECRET}, optional
 * {@code SHIELDLABS_API_BASE_URL} and {@code PORT} (default 8080).
 */
public final class ExampleServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    // Webhook deliveries and signup posts are a few KB. Both endpoints accept requests from anyone,
    // so read at most this much and answer 413 for anything larger.
    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final ShieldLabsClient shieldlabs;
    private final String webhookSecret;
    // In production keep these sets in a shared store with an atomic "add if absent" and an expiry
    // (for example a Redis SET with NX and EX), so every instance of your backend sees them.
    private final Set<String> usedRequestIds = ConcurrentHashMap.newKeySet();
    private final Set<String> processedDeliveries = ConcurrentHashMap.newKeySet();

    ExampleServer(ShieldLabsClient shieldlabs, String webhookSecret) {
        this.shieldlabs = shieldlabs;
        this.webhookSecret = webhookSecret;
    }

    public static void main(String[] args) throws IOException {
        String apiKey = System.getenv("SHIELDLABS_API_KEY");
        String webhookSecret = System.getenv("SHIELDLABS_WEBHOOK_SECRET");
        if (apiKey == null || apiKey.isBlank() || webhookSecret == null || webhookSecret.isBlank()) {
            System.err.println("Set SHIELDLABS_API_KEY and SHIELDLABS_WEBHOOK_SECRET first.");
            System.exit(1);
            return;
        }
        ExampleServer app = new ExampleServer(ShieldLabsClient.fromEnvironment(), webhookSecret);
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/signup", exchange -> app.handle(exchange, app::signup));
        server.createContext("/webhooks/shieldlabs", exchange -> app.handle(exchange, app::webhook));
        // Waiting for a verdict can block a thread for up to 10 seconds, so use a pool.
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("Listening on http://localhost:" + port);
    }

    /** A request handler that may throw. */
    interface Route {
        void serve(HttpExchange exchange) throws IOException;
    }

    void handle(HttpExchange exchange, Route route) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, json("ok", false, "reason", "method_not_allowed"));
                return;
            }
            route.serve(exchange);
        } finally {
            exchange.close();
        }
    }

    /** POST /signup with a JSON body {"requestId": "..."} or a form field requestId. */
    void signup(HttpExchange exchange) throws IOException {
        byte[] requestBody = readBody(exchange);
        if (requestBody == null) {
            respond(exchange, 413, json("ok", false, "reason", "body_too_large"));
            return;
        }
        String requestId = requestIdFrom(requestBody, exchange.getRequestHeaders().getFirst("Content-Type"));
        if (requestId == null || requestId.isEmpty()) {
            respond(exchange, 400, json("ok", false, "reason", "missing_request_id"));
            return;
        }

        Optional<Identification> identification;
        try {
            identification = shieldlabs.identifications().get(requestId);
        } catch (ValidationException e) {
            respond(exchange, 400, json("ok", false, "reason", "invalid_request_id"));
            return;
        } catch (ShieldLabsException e) {
            // The verdict could not be read: treat the signup as unverified, never as clean.
            System.out.println("signup: verdict unavailable: " + e.getMessage());
            respond(exchange, 503, json("ok", false, "reason", "verification_unavailable"));
            return;
        }

        // Missing, reused, stale (older than 5 minutes), rate-limited, no device signals, browser
        // automation or JavaScript disabled, and the dangerous band are all refused.
        EvaluateOptions policy =
                EvaluateOptions.builder()
                        .maxAge(Duration.ofMinutes(5))
                        .replayCheck(id -> !usedRequestIds.add(id))
                        .build();
        Evaluation evaluation = Risk.evaluate(identification.orElse(null), policy);
        if (!evaluation.isOk()) {
            Map<String, Object> body =
                    json("ok", false, "reason", evaluation.getReason().map(Evaluation.Reason::getValue).orElse("refused"));
            evaluation.getFlag().ifPresent(flag -> body.put("flag", flag.getValue()));
            respond(exchange, 403, body);
            return;
        }

        // Create the account here. Store the request ID and the device ID with it: they let you look
        // up related accounts later with client.history().
        respond(exchange, 201, json("ok", true, "band", evaluation.getBand().map(RiskBand::getValue).orElse("")));
    }

    /** POST /webhooks/shieldlabs: verify, acknowledge fast, process once per request ID. */
    void webhook(HttpExchange exchange) throws IOException {
        byte[] body = readBody(exchange);
        if (body == null) {
            respond(exchange, 413, json("ok", false, "reason", "body_too_large"));
            return;
        }
        String signature = exchange.getRequestHeaders().getFirst(Webhooks.SIGNATURE_HEADER);
        WebhookEvent event;
        try {
            // While rotating the secret, pass both: Webhooks.constructEvent(body, signature, newSecret, oldSecret).
            event = Webhooks.constructEvent(body, signature, webhookSecret);
        } catch (SignatureVerificationException e) {
            respond(exchange, 401, json("ok", false));
            return;
        } catch (WebhookParseException e) {
            respond(exchange, 400, json("ok", false));
            return;
        }

        if (event instanceof IdentificationScoredEvent) {
            Identification identification = ((IdentificationScoredEvent) event).getIdentification();
            if (processedDeliveries.add(identification.getRequestId())) {
                // Hand longer work to a queue: ShieldLabs waits one second for the response.
                System.out.println(
                        "identification.scored request_id=" + identification.getRequestId()
                                + " risk_score=" + identification.getRiskScore()
                                + " band=" + Risk.band(identification.getRiskScore())
                                + " flags=" + identification.getDetectionFlags().active());
            } else {
                System.out.println("duplicate delivery ignored: " + identification.getRequestId());
            }
        } else if (event instanceof WebhookPingEvent) {
            System.out.println("webhook.ping received");
        } else {
            System.out.println("ignored event type " + event.getType());
        }
        respond(exchange, 200, json("ok", true));
    }

    /** Reads the request body, or returns {@code null} when it is larger than {@link #MAX_BODY_BYTES}. */
    private static byte[] readBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        return body.length > MAX_BODY_BYTES ? null : body;
    }

    private static String requestIdFrom(byte[] body, String contentType) {
        if (contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "requestId".equals(formDecode(pair.substring(0, eq)))) {
                    return formDecode(pair.substring(eq + 1));
                }
            }
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode requestId = node == null ? null : node.get("requestId");
            return requestId != null && requestId.isTextual() ? requestId.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Decodes one form field. Text with a malformed percent escape (such as {@code %zz}) is returned
     * as sent: it can never be a valid request ID, so the SDK's validation rejects it with a
     * {@link ValidationException} and the signup gets {@code 400 invalid_request_id}.
     */
    private static String formDecode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    /** An ordered JSON object from alternating keys and values. */
    private static Map<String, Object> json(Object... keysAndValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            body.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return body;
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
