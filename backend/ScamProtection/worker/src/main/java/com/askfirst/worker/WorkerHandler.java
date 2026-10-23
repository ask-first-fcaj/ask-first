package com.askfirst.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.askfirst.common.TelegramClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class WorkerHandler implements RequestHandler<SQSEvent, Void> {
    // sửa lại xíu nha
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private static final String WARNING_TEXT = "Nội dung bạn vừa gửi có nguy cơ dính đến lừa đảo chuyển tiền. Vui lòng không thực hiện giao dịch.";
    private final TelegramClient telegram = new TelegramClient(System.getenv("TELEGRAM_BOT_TOKEN"));

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                // Code cũ:
                // long chatId = MAPPER.readTree(message.getBody()).path("chatId").asLong();

                // Code mới hỗ trợ cả chat_id (InboundMessage) và chatId (cũ):
                JsonNode body = MAPPER.readTree(message.getBody());
                long chatId = body.has("chat_id") ? body.path("chat_id").asLong() : body.path("chatId").asLong();
                telegram.sendMessage(chatId, WARNING_TEXT);
            } catch (Exception e) {
                context.getLogger().log("Lỗi worker: " + e);
                throw new RuntimeException(e); // để SQS retry (batch-size = 1)
            }
        }
        return null;
    }
}
