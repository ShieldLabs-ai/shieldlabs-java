package ai.shieldlabs;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** A successful (2xx) response with its body parsed as JSON. */
final class JsonResponse {
    final int status;
    final String body;
    final Map<String, List<String>> headers;
    final Object value;

    private JsonResponse(int status, String body, Map<String, List<String>> headers, Object value) {
        this.status = status;
        this.body = body;
        this.headers = headers;
        this.value = value;
    }

    /** Parses a response; error statuses and invalid JSON become exceptions. */
    static JsonResponse from(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        byte[] bytes = response.body() == null ? new byte[0] : response.body();
        Map<String, List<String>> headers = response.headers().map();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (status < 200 || status > 299) {
            throw HttpErrors.forStatus(status, text, headers);
        }
        try {
            return new JsonResponse(status, text, headers, Json.parse(bytes));
        } catch (IOException | RuntimeException e) {
            throw new ApiException("The response body is not valid JSON", status, text, headers);
        }
    }

    /** An exception for a 2xx response whose JSON does not have the expected shape. */
    ApiException unexpected(String detail) {
        return new ApiException("Unexpected response body: " + detail, status, body, headers);
    }
}
