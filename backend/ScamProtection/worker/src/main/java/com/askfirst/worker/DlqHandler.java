package com.askfirst.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.store.BufferStore;
import com.askfirst.common.store.DynamoBufferStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Đọc jobs-dlq và báo "bận" cho người dùng (BE-07). Dùng chung jar với WorkerHandler. */
public class DlqHandler implements RequestHandler<SQSEvent, Void> {

    static final String BUSY_TEXT =
            "Hệ thống đang quá tải hoặc gặp sự cố, bác/cô vui lòng thử lại sau ít phút nhé!";

    /** Quá số lần này thì bỏ job: lỗi vĩnh viễn (vd. người dùng chặn bot) không được lặp mãi. */
    private static final int MAX_ATTEMPTS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TelegramClient telegram;
    private final BufferStore buffer;

    public DlqHandler() {
        this(new TelegramClient(System.getenv("TELEGRAM_BOT_TOKEN")),
                new DynamoBufferStore(DynamoDbClient.create(), System.getenv("DYNAMODB_TABLE_NAME")));
    }

    DlqHandler(TelegramClient telegram, BufferStore buffer) {
        this.telegram = telegram;
        this.buffer = buffer;
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            // KHÔNG log body: đó là InboundMessage có text của người dùng.
            int attempt = receiveCount(message);
            context.getLogger().log("DLQ job sqsMessageId=" + message.getMessageId() + " attempt=" + attempt);

            JsonNode job;
            long chatId;
            try {
                job = MAPPER.readTree(message.getBody());
                chatId = job.has("chat_id") ? job.path("chat_id").asLong() : job.path("chatId").asLong();
            } catch (Exception e) {
                context.getLogger().log("Job DLQ không đọc được, bỏ qua: " + e.getClass().getSimpleName());
                continue;
            }
            if (chatId == 0) {
                context.getLogger().log("Job DLQ không có chat_id, bỏ qua");
                continue;
            }

            try {
                notifyBusy(chatId, job);
            } catch (Exception e) {
                if (attempt >= MAX_ATTEMPTS) {
                    context.getLogger().log("Bỏ job sau " + attempt + " lần: " + e.getClass().getSimpleName());
                    continue;
                }
                context.getLogger().log("Lỗi tạm thời, để SQS giao lại: " + e.getClass().getSimpleName());
                throw new RuntimeException(e);   // batch size 1 nên chỉ giao lại đúng tin này
            }
        }
        return null;
    }

    void notifyBusy(long chatId, JsonNode job) {
        String chat = String.valueOf(chatId);
        Integer placeholder = buffer.state(chat).map(BufferStore.State::placeholderId).orElse(null);

        if (placeholder != null) {
            try {
                telegram.editText(chatId, placeholder, BUSY_TEXT);   // xem ghi chú bên dưới
            } catch (Exception editFailed) {
                telegram.sendMessage(chatId, BUSY_TEXT);   // tin "⏳" đã mất hoặc không sửa được
            }
        } else {
            telegram.sendMessage(chatId, BUSY_TEXT);
        }

        // Dọn buffer: best-effort và nằm SAU khi đã báo, để lỗi ở đây không làm retry gửi lại câu bận.
        if (job.hasNonNull("seq")) {
            try {
                buffer.finish(chat, job.path("seq").asLong());
            } catch (RuntimeException ignored) {
                // TTL 10 phút sẽ dọn
            }
        }
    }

    private static int receiveCount(SQSEvent.SQSMessage message) {
        try {
            return Integer.parseInt(message.getAttributes().get("ApproximateReceiveCount"));
        } catch (RuntimeException e) {
            return 1;
        }
    }
}