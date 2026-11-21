package com.askfirst.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.store.BufferStore;
import com.askfirst.common.store.CheckStore;
import com.askfirst.common.store.DynamoBufferStore;
import com.askfirst.common.store.DynamoCheckStore;
import com.askfirst.common.store.GuardianRepo;
import com.askfirst.common.store.UserRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Đọc jobs-dlq và báo "bận" cho người dùng (BE-07). Dùng chung jar với WorkerHandler.
 *
 * <p>Đây chỉ là lưới an toàn cho trường hợp {@link AnalyzeProcessor#notifyGaveUp} không chạy được (Lambda chết hoặc
 * hết giờ ở lần giao thứ 5). Nên nó chỉ báo khi chắc chắn chưa ai báo cho job này, và chỉ đụng vào tin "⏳" khi tin đó
 * thuộc về chính job này.
 */
public class DlqHandler implements RequestHandler<SQSEvent, Void> {

    /** Quá số lần này thì bỏ job: lỗi vĩnh viễn (vd. người dùng chặn bot) không được lặp mãi. */
    private static final int MAX_ATTEMPTS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TelegramClient telegram;
    private final BufferStore buffer;
    private final CheckStore checks;
    private final GuardianDirectory directory;

    public DlqHandler() {
        String table = System.getenv("DYNAMODB_TABLE_NAME");
        DynamoDbClient dynamo = DynamoDbClient.create();
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build();
        this.telegram = new TelegramClient(System.getenv("TELEGRAM_BOT_TOKEN"));
        this.buffer = new DynamoBufferStore(dynamo, table);
        this.checks = new DynamoCheckStore(dynamo, table);
        this.directory = new RepoGuardianDirectory(new UserRepo(enhanced, table), new GuardianRepo(enhanced, table));
    }

    DlqHandler(TelegramClient telegram, BufferStore buffer, CheckStore checks, GuardianDirectory directory) {
        this.telegram = telegram;
        this.buffer = buffer;
        this.checks = checks;
        this.directory = directory;
    }

    /** Giữ cho code cũ/test cũ: không có CHECK# và danh bạ thì bỏ qua bước kiểm tra tương ứng. */
    DlqHandler(TelegramClient telegram, BufferStore buffer) {
        this(telegram, buffer, null, null);
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
            // Chỉ AnalyzeJob có tin "⏳" và câu "chưa kiểm tra được tin này". FollowupJob (nhắc lại) không có seq nên
            // không thể biết tin "⏳" hiện có của chat có phải của nó không: không được báo hay sửa gì.
            if (!AnalyzeJob.TYPE.equals(job.path("type").asText())) {
                context.getLogger().log("Job DLQ không phải analyze, bỏ qua type=" + job.path("type").asText("?"));
                continue;
            }

            try {
                String outcome = notifyBusy(chatId, job);
                context.getLogger().log("DLQ job xử lý xong: " + outcome);
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

    /**
     * @return mã ngắn mô tả đã làm gì (để log): already_handled | superseded | edited | sent
     */
    String notifyBusy(long chatId, JsonNode job) {
        String chat = String.valueOf(chatId);
        String userId = job.path("user_id").asText(null);
        String jobId = job.path("job_id").asText(null);
        long seq = job.path("seq").asLong(0);

        // 1. Đã có CHECK# (kết quả thật, hoặc worker đã báo "bận"/bỏ qua ở lần giao thứ 5): không báo thêm.
        if (checks != null && userId != null && jobId != null && checks.exists(userId, jobId)) {
            return "already_handled";
        }

        // 2. Chỉ tin "⏳" của cụm hiện tại mới sửa được, và chỉ khi job này chính là job mới nhất của cụm.
        Optional<BufferStore.State> state = buffer.state(chat);
        boolean owner = false;
        if (state.isPresent() && seq > 0) {
            BufferStore.State s = state.get();
            if (seq < s.maxSeq() || seq <= s.doneSeq()) {
                // Đã có tin mới hơn (job mới nhất sẽ trả lời cả cụm) hoặc cụm này đã trả lời xong: không báo gì.
                return "superseded";
            }
            owner = seq == s.maxSeq();
        }

        String text = WorkerMessages.busy(addressForm(userId));
        Integer placeholder = owner ? state.get().placeholderId() : null;
        String outcome = "sent";
        if (placeholder != null) {
            try {
                telegram.editText(chatId, placeholder, text);
                outcome = "edited";
            } catch (Exception editFailed) {
                telegram.sendMessage(chatId, text);   // tin "⏳" đã mất hoặc không sửa được
            }
        } else {
            telegram.sendMessage(chatId, text);
        }

        // Dọn buffer: chỉ khi job này sở hữu cụm hiện tại (finish với seq của job khác sẽ xóa placeholder của cụm đó).
        // Best-effort và nằm SAU khi đã báo, để lỗi ở đây không làm retry gửi lại câu bận.
        if (owner) {
            try {
                buffer.finish(chat, seq);
            } catch (RuntimeException ignored) {
                // TTL 10 phút sẽ dọn
            }
        }
        return outcome;
    }

    /** Cách xưng hô đã lưu của người dùng; không tra được thì dùng mặc định "Bác" như worker. */
    private String addressForm(String userId) {
        if (directory == null || userId == null) {
            return "Bác";
        }
        try {
            return directory.elder(userId).map(GuardianDirectory.Elder::addressForm).orElse("Bác");
        } catch (RuntimeException e) {
            return "Bác";
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