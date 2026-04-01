package com.piotrcapecki.openclaw.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class OpenRouterClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public OpenRouterClient(
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            @Value("${openrouter.api-key}") String apiKey,
            @Value("${openrouter.model}") String model,
            @Value("${openrouter.base-url}") String baseUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = baseUrl + "/chat/completions";
    }

    /**
     * Sends a single user prompt and returns the model's text response.
     * Used by all skills on the platform.
     */
    public String complete(String userPrompt) {
        try {
            log.debug("Calling OpenRouter model={} promptLength={}", model, userPrompt.length());

            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt))
            ));

            Request request = new Request.Builder()
                    .url(apiUrl)
                    .post(RequestBody.create(body, JSON))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "(no body)";
                    log.error("OpenRouter HTTP error {}: {}", response.code(), errorBody);
                    throw new RuntimeException("OpenRouter error: HTTP " + response.code() + " — " + errorBody);
                }
                if (response.body() == null) {
                    throw new RuntimeException("OpenRouter returned HTTP 200 with no response body");
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    throw new RuntimeException("OpenRouter returned no choices. Full response: " + root);
                }
                return choices.get(0).path("message").path("content").asText();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenRouter call failed: " + e.getMessage(), e);
        }
    }
}
