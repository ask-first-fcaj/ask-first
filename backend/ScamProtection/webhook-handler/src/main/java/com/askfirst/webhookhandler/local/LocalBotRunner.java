package com.askfirst.webhookhandler.local;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.webhookhandler.WebhookHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long Polling runner for local development and testing (BE-20).
 * Continuously polls getUpdates from Telegram and forwards to WebhookHandler.
 * Allows dev to test /start and bot features without configuring ngrok.
 *
 * Đây là nơi DUY NHẤT được bỏ qua kiểm tra secret của webhook (qua WebhookHandler.forLocalDevelopment),
 * vì long polling không đi qua Function URL nên không có header X-Telegram-Bot-Api-Secret-Token.
 */
public class LocalBotRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);

    public static void main(String[] args) {
        Map<String, String> env = loadEnv();
        String botToken = env.get("TELEGRAM_BOT_TOKEN");
        if (botToken == null || botToken.isBlank()) {
            System.err.println("❌ LỖI: Không tìm thấy TELEGRAM_BOT_TOKEN trong biến môi trường hoặc file .env!");
            System.exit(1);
        }

        // 1. Kiểm tra trạng thái webhook hiện tại
        try {
            JsonNode webhookInfo = callTelegram(botToken, "getWebhookInfo", Map.of());
            String existingUrl = webhookInfo.path("result").path("url").asText("");
            if (!existingUrl.isBlank()) {
                System.out.println("⚠️ Webhook đang được trỏ tới: " + existingUrl);
                System.out.println("🔄 Đang gỡ Webhook để cho phép chế độ Long Polling (getUpdates)...");
                callTelegram(botToken, "deleteWebhook", Map.of("drop_pending_updates", false));
                System.out.println("✅ Đã gỡ Webhook thành công.");
            }

            JsonNode me = callTelegram(botToken, "getMe", Map.of());
            String username = me.path("result").path("username").asText("unknown_bot");
            String firstName = me.path("result").path("first_name").asText("AskFirst Bot");
            long botId = me.path("result").path("id").asLong();

            System.out.println("=================================================================");
            System.out.println("🤖 AskFirst Local Bot Runner (Long Polling Mode - BE-20)");
            System.out.println("Bot: @" + username + " (" + firstName + ") [ID: " + botId + "]");
            System.out.println("Chế độ: Đang lắng nghe tin nhắn trực tiếp từ Telegram...");
            System.out.println("👉 Hãy mở Telegram chat với @" + username + " và gửi /start để kiểm tra!");
            System.out.println("Nhấn Ctrl+C để dừng.");
            System.out.println("=================================================================");

        } catch (Exception e) {
            System.err.println("❌ Lỗi khi khởi tạo kết nối tới Telegram: " + e.getMessage());
            System.exit(1);
        }

        TelegramClient telegramClient = new TelegramClient(botToken);
        // Chế độ local: không có SQS, không có header secret -> dùng factory dành riêng cho local
        WebhookHandler handler = WebhookHandler.forLocalDevelopment(telegramClient);
        Context mockContext = createMockContext();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Đang dừng Local Bot Runner...");
            RUNNING.set(false);
        }));

        long offset = 0;

        while (RUNNING.get()) {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("offset", offset);
                params.put("timeout", 25);
                params.put("allowed_updates", new String[]{"message", "callback_query"});

                JsonNode updatesResp = callTelegram(botToken, "getUpdates", params);
                if (!updatesResp.path("ok").asBoolean(false)) {
                    System.err.println("Lỗi getUpdates: " + updatesResp.path("description").asText("không rõ nguyên nhân"));
                    Thread.sleep(2000);
                    continue;
                }

                JsonNode updates = updatesResp.path("result");
                if (updates.isArray() && !updates.isEmpty()) {
                    for (JsonNode update : updates) {
                        long updateId = update.path("update_id").asLong();
                        offset = Math.max(offset, updateId + 1);

                        // Chỉ log loại update, KHÔNG in tên người dùng hay nội dung tin nhắn (dữ liệu cá nhân)
                        System.out.println("\n📥 [Update #" + updateId + "] Loại: " + describeUpdate(update));

                        // Giả lập sự kiện API Gateway gửi tới WebhookHandler
                        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent()
                                .withHttpMethod("POST")
                                .withPath("/webhook")
                                .withHeaders(Map.of("Content-Type", "application/json"))
                                .withBody(MAPPER.writeValueAsString(update));

                        APIGatewayProxyResponseEvent response = handler.handleRequest(request, mockContext);
                        System.out.println("📤 [Update #" + updateId + "] Kết quả WebhookHandler: HTTP "
                                + response.getStatusCode() + " (" + response.getBody() + ")");
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("⚠️ Lỗi khi nhận tin: " + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }

        System.out.println("Local Bot Runner đã kết thúc.");
    }

    /** Mô tả update không lộ dữ liệu cá nhân: chỉ loại và độ dài văn bản. */
    private static String describeUpdate(JsonNode update) {
        if (update.has("callback_query")) {
            return "callback_query";
        }
        JsonNode msg = update.path("message");
        if (msg.isMissingNode() || msg.isNull()) {
            return "khác";
        }
        if (msg.has("voice")) {
            return "voice";
        }
        if (msg.has("photo")) {
            return "ảnh";
        }
        if (msg.has("text")) {
            return "văn bản (" + msg.path("text").asText("").length() + " ký tự)";
        }
        return "tin nhắn khác";
    }

    private static JsonNode callTelegram(String token, String method, Map<String, Object> payload) throws Exception {
        String url = "https://api.telegram.org/bot" + token + "/" + method;
        String json = MAPPER.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(35))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(response.body());
    }

    private static Map<String, String> loadEnv() {
        Map<String, String> map = new HashMap<>();

        File[] candidates = new File[]{
                new File(".env"),
                new File("backend/ScamProtection/.env"),
                new File("../.env"),
                new File("../../.env")
        };

        for (File f : candidates) {
            if (f.exists() && f.isFile()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.startsWith("#") && line.contains("=")) {
                            String[] parts = line.split("=", 2);
                            String k = parts[0].trim();
                            String v = parts[1].trim().replaceAll("^[\"']|[\"']$", "");
                            if (!map.containsKey(k) && !v.isBlank()) {
                                map.put(k, v);
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }

        System.getenv().forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                map.put(k, v);
            }
        });

        return map;
    }

    private static Context createMockContext() {
        return new Context() {
            @Override
            public String getAwsRequestId() { return "local-mock-req-id"; }
            @Override
            public String getLogGroupName() { return "local-log-group"; }
            @Override
            public String getLogStreamName() { return "local-log-stream"; }
            @Override
            public String getFunctionName() { return "local-webhook-handler"; }
            @Override
            public String getFunctionVersion() { return "1.0"; }
            @Override
            public String getInvokedFunctionArn() { return "arn:aws:lambda:local:0:function:webhook"; }
            @Override
            public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() { return null; }
            @Override
            public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() { return null; }
            @Override
            public int getRemainingTimeInMillis() { return 30000; }
            @Override
            public int getMemoryLimitInMB() { return 512; }
            @Override
            public LambdaLogger getLogger() {
                return new LambdaLogger() {
                    @Override
                    public void log(String message) {
                        System.out.println("[LambdaLog] " + message);
                    }
                    @Override
                    public void log(byte[] message) {
                        System.out.println("[LambdaLog] " + new String(message, StandardCharsets.UTF_8));
                    }
                };
            }
        };
    }
}
