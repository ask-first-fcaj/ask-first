package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import com.askfirst.common.channel.TelegramAdapter;
import com.askfirst.common.channel.model.InboundMessage;

import java.util.Map;
import java.util.Optional;

public class WebhookHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private final String queueUrl = System.getenv("WEBHOOK_QUEUE_URL");
    private final String secret = System.getenv("TELEGRAM_WEBHOOK_SECRET");
    private final TelegramClient telegram = new TelegramClient(System.getenv("TELEGRAM_BOT_TOKEN"));
    private final SqsClient sqs = SqsClient.create(); // tự đọc AWS_ENDPOINT_URL / AWS_REGION

    // @Override
    // public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent
    // input, Context context) {
    // if (!isFromTelegram(input)) return response(403, "FORBIDDEN");
    //
    // long chatId;
    // try {
    // JsonNode message = MAPPER.readTree(input.getBody()).path("message");
    // if (!message.path("text").isTextual()) return response(200, "IGNORED");
    //
    // chatId = message.path("chat").path("id").asLong();
    // String payload = MAPPER.writeValueAsString(
    // Map.of("chatId", chatId, "text", message.path("text").asText()));
    // sqs.sendMessage(SendMessageRequest.builder()
    // .queueUrl(queueUrl).messageBody(payload).build());
    // } catch (Exception e) {
    // context.getLogger().log("Lỗi webhook/SQS: " + e);
    // return response(500, "ERROR"); // chưa vào queue -> để Telegram gửi lại
    // }
    //
    // try {
    // telegram.sendMessage(chatId, "Vui lòng chờ, hệ thống đang kiểm tra nội dung
    // bạn gửi...");
    // } catch (Exception e) {
    // context.getLogger().log("Lỗi gửi 'vui lòng chờ': " + e); // không được làm
    // Telegram retry
    // }
    // return response(200, "OK");
    // }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input,
            Context context) {
        if (!isFromTelegram(input)) {
            return response(403, "FORBIDDEN");
        }

        final long chatId;
        final String payload;

        try {
            JsonNode root = MAPPER.readTree(input.getBody());

            // Phần code này là của Trí, để khớp với telegarmpAdaper thì t viết lại
            // Có gì comment lại thui nha

            // JsonNode message = root.path("message");
            // if (!message.path("text").isTextual()) {
            // return response(200, "IGNORED");
            // }
            // chatId = message.path("chat").path("id").asLong();
            // payload = MAPPER.writeValueAsString(
            // Map.of(
            // "chatId", chatId,
            // "text", message.path("text").asText()
            // )
            // );

            // Code mới dùng TelegramAdapter (hỗ trợ text, photo, voice, document theo hợp
            // đồng):
            TelegramAdapter adapter = new TelegramAdapter();
            Optional<InboundMessage> inboundOpt = adapter.parseUpdate(root);

            if (inboundOpt.isEmpty()) {
                return response(200, "IGNORED");
            }

            InboundMessage inbound = inboundOpt.get();
            chatId = Long.parseLong(inbound.chatId());
            payload = MAPPER.writeValueAsString(inbound);
        } catch (Exception e) {
            context.getLogger().log("Lỗi parse Telegram update: " + e);
            return response(400, "INVALID_UPDATE");
        }

        // Bước 1: Gửi tin nhắn chờ và đợi Telegram phản hồi thành công.
        try {
            telegram.sendMessage(
                    chatId,
                    "Vui lòng chờ, hệ thống đang kiểm tra nội dung bạn gửi...");
        } catch (Exception e) {
            context.getLogger().log(
                    "Không gửi được tin nhắn chờ: " + e);

            // Chưa enqueue nên worker chưa thể gửi cảnh báo trước.
            return response(500, "WAIT_MESSAGE_FAILED");
        }

        // Bước 2: Chỉ enqueue sau khi gửi tin nhắn chờ thành công.
        try {
            sqs.sendMessage(
                    SendMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .messageBody(payload)
                            .build());
        } catch (Exception e) {
            context.getLogger().log("Lỗi gửi message vào SQS: " + e);
            return response(500, "SQS_FAILED");
        }

        return response(200, "OK");
    }

    private boolean isFromTelegram(APIGatewayProxyRequestEvent input) {
        Map<String, String> headers = input.getHeaders();
        return secret != null && headers != null && headers.entrySet().stream().anyMatch(
                e -> "X-Telegram-Bot-Api-Secret-Token".equalsIgnoreCase(e.getKey()) && secret.equals(e.getValue()));
    }

    private APIGatewayProxyResponseEvent response(int status, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(status).withBody(body);
    }
}