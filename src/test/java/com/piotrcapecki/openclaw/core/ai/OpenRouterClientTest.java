package com.piotrcapecki.openclaw.core.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenRouterClientTest {

    @Mock OkHttpClient httpClient;

    @Test
    void returnsResponseTextFromOpenRouter() throws Exception {
        String responseJson = """
            {
              "choices": [{
                "message": { "content": "Hello from Claude" }
              }]
            }
            """;

        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(responseJson, MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        OpenRouterClient client = new OpenRouterClient(httpClient, new ObjectMapper(), "test-key", "anthropic/claude-sonnet-4-5", "https://openrouter.ai/api/v1");
        String result = client.complete("Say hello");

        assertThat(result).isEqualTo("Hello from Claude");
    }

    @Test
    void throwsWhenApiReturnsError() throws Exception {
        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                .body(ResponseBody.create("{}", MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        OpenRouterClient client = new OpenRouterClient(httpClient, new ObjectMapper(), "test-key", "anthropic/claude-sonnet-4-5", "https://openrouter.ai/api/v1");

        RuntimeException ex = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> client.complete("Say hello")
        );
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("429");
    }
}
