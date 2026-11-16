package com.askfirst.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.askfirst.common.TelegramClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Worker xử lý tin nhắn từ SQS và phản hồi qua Telegram.
 * Câu cảnh báo xưng hô lễ phép, tôn trọng theo tôn chỉ SH-08.
 */
public class WorkerHandler implements RequestHandler<SQSEvent, Void> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    public static final String DEFAULT_WARNING_TEXT =
            "⚠️ Tin này có nguy cơ lừa đảo cao. Bác tuyệt đối không làm theo, không chuyển tiền và không cung cấp mã OTP nhé!";

    private final TelegramClient telegram;

    public WorkerHandler() {
        this(new TelegramClient(System.getenv("TELEGRAM_BOT_TOKEN")));
    }

    public WorkerHandler(TelegramClient telegram) {
        this.telegram = telegram;
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        if (event == null || event.getRecords() == null) {
            return null;
        }

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                JsonNode body = MAPPER.readTree(message.getBody());
                long chatId = body.has("chat_id") ? body.path("chat_id").asLong() : body.path("chatId").asLong();
                if (telegram != null) {
                    telegram.sendText(chatId, DEFAULT_WARNING_TEXT);
                }
            } catch (Exception e) {
                if (context != null && context.getLogger() != null) {
                    context.getLogger().log("Lỗi worker: " + e);
                }
                throw new RuntimeException(e); // để SQS retry (batch-size = 1)
            }
        }
        return null;
    }
}
