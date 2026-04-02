package com.piotrcapecki.openclaw.core.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramClientTest {

    @Test
    void formatMessageFormatsCorrectly() {
        TelegramClient client = new TelegramClient(
                new ObjectMapper(), "fake-token", "123456"
        );

        String text = client.formatMessage("My Title", "Body line 1\nBody line 2");

        assertThat(text).isEqualTo("<b>My Title</b>\n\nBody line 1\nBody line 2");
    }

    @Test
    void formatPlainMessageEscapesBothTitleAndBody() {
        TelegramClient client = new TelegramClient(new ObjectMapper(), "fake-token", "123456");
        String text = client.formatPlainMessage("Title <ok>", "Body & stuff");
        assertThat(text).isEqualTo("<b>Title &lt;ok&gt;</b>\n\nBody &amp; stuff");
    }

    @Test
    void escapeHtmlEscapesSpecialChars() {
        TelegramClient client = new TelegramClient(
                new ObjectMapper(), "fake-token", "123456"
        );

        assertThat(client.escapeHtml("Hello <World> & Co")).isEqualTo("Hello &lt;World&gt; &amp; Co");
        assertThat(client.escapeHtml(null)).isEqualTo("");
    }
}
