package com.ishee.shortener.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ModelGateway {
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ModelGateway(ObjectMapper json) {
        this.json = json;
    }

    public JsonNode ask(String instructions, Object context) throws Exception {
        String endpoint = System.getenv("MODEL_API_URL");
        String key = System.getenv("MODEL_API_KEY");
        String model = System.getenv("MODEL_NAME");
        if (endpoint == null || key == null || model == null || endpoint.isBlank() || key.isBlank() || model.isBlank()) {
            throw new IllegalStateException("MODEL_API_URL, MODEL_API_KEY, and MODEL_NAME are required in model mode");
        }
        if (!endpoint.startsWith("https://")) throw new IllegalArgumentException("Model URL must use HTTPS");
        Map<String, Object> payload = Map.of(
                "model", model,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", "Return exactly one JSON object. " + instructions),
                        Map.of("role", "user", "content", json.writeValueAsString(context))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(35))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Model request returned HTTP " + response.statusCode());
        if (response.body().length() > 150_000) throw new IllegalStateException("Model response exceeded size limit");
        String content = json.readTree(response.body()).path("choices").path(0).path("message").path("content").asText();
        JsonNode result = json.readTree(content);
        if (result == null || !result.isObject()) throw new IllegalStateException("Expected model JSON object");
        return result;
    }
}
