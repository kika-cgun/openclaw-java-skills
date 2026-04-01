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

    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public OpenRouterClient(
            OkHttpClient httpClient,
            @Value("${openrouter.api-key}") String apiKey,
            @Value("${openrouter.model}") String model) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * Sends a single user prompt and returns the model's text response.
     * Used by all skills on the platform.
     */
    public String complete(String userPrompt) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt))
            ));

            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(RequestBody.create(body, JSON))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new RuntimeException("OpenRouter error: HTTP " + response.code());
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                return root.path("choices").get(0).path("message").path("content").asText();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenRouter call failed: " + e.getMessage(), e);
        }
    }
}
