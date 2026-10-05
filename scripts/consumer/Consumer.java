import ai.shieldlabs.DomainProfile;
import ai.shieldlabs.HistoryPage;
import ai.shieldlabs.Identification;
import ai.shieldlabs.IdentificationScoredEvent;
import ai.shieldlabs.LookupType;
import ai.shieldlabs.ManagementClient;
import ai.shieldlabs.ShieldLabsClient;
import ai.shieldlabs.Webhooks;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Compiled and run using only the packaged jar and its published runtime dependencies. */
public final class Consumer {
    private Consumer() {}

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = exchange.getRequestURI().getPath().startsWith("/v1/profile")
                    ? "{\"Domain\":\"example.com\",\"Weight\":123,\"PublicKey\":\"****abcd\",\"extra\":42}"
                    : "{\"data\":[{\"request_id\":\"00000000-0000-0000-0000-000000000001\","
                      + "\"score\":999,\"connection_type\":\"future\",\"future_field\":42}],\"total\":1}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            ShieldLabsClient client = ShieldLabsClient.builder().apiKey("sec_fixture_only").baseUrl(origin).build();
            HistoryPage page = client.history().search(LookupType.USER_HID, "anonymous");
            Identification row = page.getIdentifications().get(0);
            require(row.getRiskScore() == 999 && row.getConnectionType().equals("future"), "History");
            require(row.raw().containsKey("future_field"), "unknown History field");
            DomainProfile profile = ManagementClient.builder().secretKey("fixture_only")
                    .domain("example.com").baseUrl(origin).build().getProfile();
            require(profile.getRemainingIdentifications() == 123 && profile.raw().containsKey("extra"), "profile");
            String body = "{\"event_type\":\"identification.scored\",\"schema_version\":\"2026-06-01\","
                    + "\"data\":{\"risk_score\":30,\"connection_type\":\"future\",\"extra\":42}}";
            String secret = "whsec_fixture_only";
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder signature = new StringBuilder("sha256=");
            for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) {
                signature.append(String.format("%02x", b & 255));
            }
            IdentificationScoredEvent event = (IdentificationScoredEvent) Webhooks.constructEvent(body, signature.toString(), secret);
            require(event.getIdentification().getRiskScore() == 30
                    && event.getIdentification().raw().containsKey("extra"), "webhook");
            System.out.println("Packaged consumer: History, profile, verified webhook and unknown fields passed");
        } finally {
            server.stop(0);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
