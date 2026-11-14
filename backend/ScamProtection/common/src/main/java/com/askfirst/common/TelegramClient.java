package com.askfirst.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard Telegram Bot API client using Java 21 HttpClient.
 * Implements message sending, editing, contact card, typing chat action,
 * HTML mode with escaping, and HTTP 429 rate limit backoff.
 */
public class TelegramClient {

    // Lambda webhook chỉ có 30s cho cả request: timeout phải ngắn để một lần Telegram chậm/429 không làm cả Lambda timeout
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_ATTEMPTS = 2;

    /** Mặc định cho fast path (webhook Lambda 30s): mỗi request tối đa 4s, chỉ chờ retry_after <= 2s. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(4);
    public static final int DEFAULT_MAX_RETRY_AFTER_SECONDS = 2;

    /** Tổng thời gian tối đa cho MỘT lời gọi API kể cả chờ retry (4s + 2s chờ + 4s thử lại vẫn nằm trong ngân sách). */
    private static final long TOTAL_BUDGET_MILLIS = 8_000L;

    private final String baseUrl;
    private final Duration requestTimeout;
    private final int maxRetryAfterSeconds;

    public TelegramClient(String botToken) {
        this(botToken, "https://api.telegram.org");
    }

    public TelegramClient(String botToken, String apiHost) {
        this(botToken, apiHost, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RETRY_AFTER_SECONDS);
    }

    /**
     * Cho phép worker nền (timeout dài hơn, không bị giới hạn 30s) truyền giá trị lớn hơn mặc định của fast path.
     */
    public TelegramClient(String botToken, String apiHost, Duration requestTimeout, int maxRetryAfterSeconds) {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("TELEGRAM_BOT_TOKEN chưa được cấu hình");
        }
        String host = (apiHost == null || apiHost.isBlank()) ? "https://api.telegram.org" : apiHost.replaceAll("/+$", "");
        this.baseUrl = host + "/bot" + botToken;
        this.requestTimeout = requestTimeout != null ? requestTimeout : DEFAULT_REQUEST_TIMEOUT;
        this.maxRetryAfterSeconds = Math.max(0, maxRetryAfterSeconds);
    }

    /**
     * Escapes special characters for Telegram HTML parse mode (&, <, >, ").
     */
    public static String escapeHtml(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    public record InlineButton(String text, String callbackData) {}

    /**
     * Nút URL (mở trình duyệt hoặc deep link Telegram) thay vì gửi callback_data.
     * Dùng kèm {@link #inlineKeyboardRaw(java.util.List)} khi cần trộn loại nút.
     */
    public record UrlButton(String text, String url) {}

    public static Map<String, Object> inlineKeyboard(java.util.List<java.util.List<InlineButton>> rows) {
        java.util.List<java.util.List<Map<String, String>>> keyboard = new java.util.ArrayList<>();
        for (java.util.List<InlineButton> row : rows) {
            java.util.List<Map<String, String>> rowMap = new java.util.ArrayList<>();
            for (InlineButton btn : row) {
                rowMap.add(Map.of("text", btn.text(), "callback_data", btn.callbackData()));
            }
            keyboard.add(rowMap);
        }
        return Map.of("inline_keyboard", keyboard);
    }

    /**
     * Tạo inline keyboard từ danh sách rows đã được serialize thành Map (hỗ trợ cả callback_data và url).
     * Mỗi phần tử trong row là Map {"text": "...", "callback_data": "..."} hoặc {"text": "...", "url": "..."}.
     */
    public static Map<String, Object> inlineKeyboardRaw(
            java.util.List<java.util.List<Map<String, String>>> rows) {
        return Map.of("inline_keyboard", rows);
    }

    /** Tạo Map nút URL để dùng với {@link #inlineKeyboardRaw(java.util.List)}. */
    public static Map<String, String> urlButtonMap(UrlButton btn) {
        return Map.of("text", btn.text(), "url", btn.url());
    }

    /** Tạo Map nút callback để dùng với {@link #inlineKeyboardRaw(java.util.List)}. */
    public static Map<String, String> callbackButtonMap(InlineButton btn) {
        return Map.of("text", btn.text(), "callback_data", btn.callbackData());
    }

    /**
     * Sends a text message with parse_mode=HTML and disable_web_page_preview=true.
     *
     * @return the created Telegram message_id.
     */
    public int sendText(long chatId, String text) {
        return sendText(chatId, text, null);
    }

    /**
     * Sends a text message with optional reply markup (e.g. inline keyboard).
     */
    public int sendText(long chatId, String text, Map<String, Object> replyMarkup) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (replyMarkup != null && !replyMarkup.isEmpty()) {
            body.put("reply_markup", replyMarkup);
        }
        JsonNode result = executePost("/sendMessage", body);
        return result.path("message_id").asInt();
    }

    /**
     * Answers a callback query to acknowledge an inline button click.
     */
    public void answerCallbackQuery(String callbackQueryId, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callback_query_id", callbackQueryId);
        if (text != null && !text.isBlank()) {
            body.put("text", text);
        }
        executePost("/answerCallbackQuery", body);
    }

    /**
     * Backward-compatible convenience wrapper for sendText.
     */
    public void sendMessage(long chatId, String text) {
        sendText(chatId, text);
    }

    /**
     * Edits an existing message text (e.g. replacing placeholder '⏳' with verdict).
     */
    public void editText(long chatId, int messageId, String text) {
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "message_id", messageId,
                "text", text,
                "parse_mode", "HTML",
                "disable_web_page_preview", true
        );
        executePost("/editMessageText", body);
    }

    /**
     * Sends a contact card (e.g. for emergency contact sharing).
     */
    public void sendContact(long chatId, String phoneNumber, String firstName, String lastName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("phone_number", phoneNumber);
        body.put("first_name", firstName);
        if (lastName != null && !lastName.isBlank()) {
            body.put("last_name", lastName);
        }
        executePost("/sendContact", body);
    }

    /**
     * Sends chat action indicator (e.g. 'typing').
     */
    public void sendChatAction(long chatId, String action) {
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "action", action
        );
        executePost("/sendChatAction", body);
    }

    /**
     * Lấy username của bot từ Telegram API (gọi /getMe).
     *
     * @return username không có ký tự '@' (ví dụ: "AskFirstBot"), hoặc null nếu không lấy được.
     */
    public String getMeUsername() {
        try {
            JsonNode result = executePost("/getMe", Map.of());
            if (result != null) {
                String u = result.path("username").asText(null);
                if (u != null && !u.isBlank()) {
                    return u;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Executes POST request to Telegram API with automatic retry on connect timeout
     * and HTTP 429 rate limit backoff.
     */
    private JsonNode executePost(String method, Map<String, Object> payload) {
        try {
            String json = MAPPER.writeValueAsString(payload);
            String targetUrl = baseUrl + method;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            final long deadlineNanos = System.nanoTime() + TOTAL_BUDGET_MILLIS * 1_000_000L;

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 429) {
                        int retryAfter = extractRetryAfter(response);
                        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                        // Chỉ ngủ khi chờ ngắn VÀ còn đủ ngân sách để thử lại; ngược lại báo lỗi ngay thay vì treo Lambda
                        boolean canWait = retryAfter > 0
                                && retryAfter <= maxRetryAfterSeconds
                                && attempt < MAX_ATTEMPTS
                                && remainingMillis > retryAfter * 1000L + requestTimeout.toMillis();
                        if (canWait) {
                            Thread.sleep(retryAfter * 1000L);
                            continue;
                        } else {
                            throw new TelegramRateLimitException(
                                    retryAfter,
                                    "Telegram rate limit (HTTP 429): retry after " + retryAfter + "s"
                            );
                        }
                    }

                    if (response.statusCode() != 200) {
                        throw new IllegalStateException(
                                "Telegram trả về HTTP " + response.statusCode() + ": " + response.body()
                        );
                    }

                    JsonNode root = MAPPER.readTree(response.body());
                    if (!root.path("ok").asBoolean(false)) {
                        throw new IllegalStateException(
                                "Telegram API returned ok=false: " + response.body()
                        );
                    }
                    return root.path("result");

                } catch (HttpConnectTimeoutException e) {
                    if (attempt == MAX_ATTEMPTS) {
                        throw e;
                    }
                    Thread.sleep(300L * attempt);
                }
            }

            throw new IllegalStateException("Hết số lần thử gửi tới Telegram");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Gửi Telegram bị gián đoạn", e);
        } catch (IOException e) {
            throw new RuntimeException("Lỗi kết nối khi gửi tới Telegram", e);
        }
    }

    private int extractRetryAfter(HttpResponse<String> response) {
        try {
            JsonNode bodyNode = MAPPER.readTree(response.body());
            int seconds = bodyNode.path("parameters").path("retry_after").asInt(0);
            if (seconds > 0) {
                return seconds;
            }
        } catch (Exception ignored) {
        }

        return response.headers().firstValue("Retry-After")
                .map(s -> {
                    try {
                        return Integer.parseInt(s.trim());
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                })
                .orElse(1);
    }
}
