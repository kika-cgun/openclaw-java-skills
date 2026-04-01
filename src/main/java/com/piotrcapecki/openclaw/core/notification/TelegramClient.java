package com.piotrcapecki.openclaw.core.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.util.Map;

@Component
@Slf4j
public class TelegramClient {

    private final ObjectMapper objectMapper;
    private final String botToken;
    private final String chatId;

    public TelegramClient(
            ObjectMapper objectMapper,
            @Value("${telegram.bot-token}") String botToken,
            @Value("${telegram.chat-id}") String chatId) {
        this.objectMapper = objectMapper;
        this.botToken = botToken;
        this.chatId = chatId;
    }

    /**
     * Sends an HTML-formatted message to the configured Telegram chat.
     * Used by all skills on the platform.
     */
    public void send(String htmlMessage) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", chatId,
                    "text", htmlMessage,
                    "parse_mode", "HTML",
                    "disable_web_page_preview", true
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Telegram API error: HTTP " + response.statusCode() + " — " + response.body());
            }
            log.info("Telegram message sent ({} chars)", htmlMessage.length());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Telegram send failed: " + e.getMessage(), e);
        }
    }

    /**
     * Helper to build a titled message block.
     * Skills use this to compose consistent messages.
     */
    public String formatMessage(String title, String body) {
        return "<b>" + escapeHtml(title) + "</b>\n\n" + body;
    }

    public String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
