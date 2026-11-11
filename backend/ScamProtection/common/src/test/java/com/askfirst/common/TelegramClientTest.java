package com.askfirst.common;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramClientTest {

    private HttpServer mockServer;
    private int port;
    private String baseUrl;
    private TelegramClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = mockServer.getAddress().getPort();
        baseUrl = "http://localhost:" + port;
        mockServer.start();

        client = new TelegramClient("dummy_token", baseUrl);
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    @DisplayName("sendText successfully sends HTML parse_mode and returns message_id")
    void testSendTextSuccess() {
        mockServer.createContext("/botdummy_token/sendMessage", exchange -> {
            String body = readBody(exchange);
            assertThat(body).contains("\"chat_id\":12345");
            assertThat(body).contains("\"text\":\"<b>Xin chào</b>\"");
            assertThat(body).contains("\"parse_mode\":\"HTML\"");
            assertThat(body).contains("\"disable_web_page_preview\":true");

            String response = """
                {
                  "ok": true,
                  "result": {
                    "message_id": 9988,
                    "date": 1729000000,
                    "text": "Xin chào"
                  }
                }
                """;
            sendResponse(exchange, 200, response);
        });

        int messageId = client.sendText(12345L, "<b>Xin chào</b>");
        assertThat(messageId).isEqualTo(9988);
    }

    @Test
    @DisplayName("editText successfully sends message_id and updated text")
    void testEditTextSuccess() {
        mockServer.createContext("/botdummy_token/editMessageText", exchange -> {
            String body = readBody(exchange);
            assertThat(body).contains("\"chat_id\":12345");
            assertThat(body).contains("\"message_id\":9988");
            assertThat(body).contains("\"text\":\"✅ Đã kiểm tra xong\"");
            assertThat(body).contains("\"parse_mode\":\"HTML\"");

            String response = """
                {
                  "ok": true,
                  "result": {
                    "message_id": 9988,
                    "text": "✅ Đã kiểm tra xong"
                  }
                }
                """;
            sendResponse(exchange, 200, response);
        });

        client.editText(12345L, 9988, "✅ Đã kiểm tra xong");
    }

    @Test
    @DisplayName("sendContact successfully sends contact details")
    void testSendContactSuccess() {
        mockServer.createContext("/botdummy_token/sendContact", exchange -> {
            String body = readBody(exchange);
            assertThat(body).contains("\"chat_id\":12345");
            assertThat(body).contains("\"phone_number\":\"0912345678\"");
            assertThat(body).contains("\"first_name\":\"Nguyễn\"");
            assertThat(body).contains("\"last_name\":\"Văn A\"");

            String response = """
                {
                  "ok": true,
                  "result": {
                    "message_id": 9989
                  }
                }
                """;
            sendResponse(exchange, 200, response);
        });

        client.sendContact(12345L, "0912345678", "Nguyễn", "Văn A");
    }

    @Test
    @DisplayName("sendChatAction successfully sends typing action")
    void testSendChatActionSuccess() {
        mockServer.createContext("/botdummy_token/sendChatAction", exchange -> {
            String body = readBody(exchange);
            assertThat(body).contains("\"chat_id\":12345");
            assertThat(body).contains("\"action\":\"typing\"");

            String response = """
                {
                  "ok": true,
                  "result": true
                }
                """;
            sendResponse(exchange, 200, response);
        });

        client.sendChatAction(12345L, "typing");
    }

    @Test
    @DisplayName("Handles HTTP 429 rate limit with retry_after and succeeds on retry")
    void testRateLimitRetrySuccess() {
        AtomicInteger attempts = new AtomicInteger(0);

        mockServer.createContext("/botdummy_token/sendMessage", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                String rateLimitResp = """
                    {
                      "ok": false,
                      "error_code": 429,
                      "description": "Too Many Requests: retry after 1",
                      "parameters": {
                        "retry_after": 1
                      }
                    }
                    """;
                sendResponse(exchange, 429, rateLimitResp);
            } else {
                String successResp = """
                    {
                      "ok": true,
                      "result": {
                        "message_id": 1001
                      }
                    }
                    """;
                sendResponse(exchange, 200, successResp);
            }
        });

        int msgId = client.sendText(12345L, "Thử lại sau 429");
        assertThat(msgId).isEqualTo(1001);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Throws TelegramRateLimitException when retry_after exceeds max wait")
    void testRateLimitExceedsMaxWaitThrowsException() {
        mockServer.createContext("/botdummy_token/sendMessage", exchange -> {
            String rateLimitResp = """
                {
                  "ok": false,
                  "error_code": 429,
                  "description": "Too Many Requests: retry after 60",
                  "parameters": {
                    "retry_after": 60
                  }
                }
                """;
            sendResponse(exchange, 429, rateLimitResp);
        });

        assertThatThrownBy(() -> client.sendText(12345L, "Test rate limit"))
                .isInstanceOf(TelegramRateLimitException.class)
                .hasMessageContaining("retry after 60s");
    }

    @Test
    @DisplayName("escapeHtml correctly escapes special HTML characters")
    void testEscapeHtml() {
        assertThat(TelegramClient.escapeHtml(null)).isNull();
        assertThat(TelegramClient.escapeHtml("")).isEqualTo("");
        assertThat(TelegramClient.escapeHtml("Normal text")).isEqualTo("Normal text");
        assertThat(TelegramClient.escapeHtml("Tom & Jerry")).isEqualTo("Tom &amp; Jerry");
        assertThat(TelegramClient.escapeHtml("<script>alert(1)</script>"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(TelegramClient.escapeHtml("Ký tự: <, >, &, \""))
                .isEqualTo("Ký tự: &lt;, &gt;, &amp;, &quot;");
    }

    @Test
    @DisplayName("Throws IllegalStateException when Telegram returns HTTP 500")
    void testServerErrorThrowsException() {
        mockServer.createContext("/botdummy_token/sendMessage", exchange -> {
            sendResponse(exchange, 500, "Internal Server Error");
        });

        assertThatThrownBy(() -> client.sendText(12345L, "Error test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Telegram trả về HTTP 500");
    }

    @Test
    @DisplayName("Throws IllegalArgumentException if botToken is empty")
    void testEmptyTokenThrowsException() {
        assertThatThrownBy(() -> new TelegramClient(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

