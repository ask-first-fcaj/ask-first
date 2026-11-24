package com.askfirst.common.job;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Câu trả lời của người dùng cho câu hỏi làm rõ (multi-turn).
 * Multi-turn đang hoãn khỏi MVP: class có sẵn để contract đủ, worker chưa cần xử lý thật.
 */
public record AnswerJob(
        @JsonProperty("v") int v,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("chat_id") String chatId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("prev_check_id") String prevCheckId,
        @JsonProperty("answer") String answer,
        @JsonProperty("placeholder_message_id") String placeholderMessageId,
        @JsonProperty("enqueued_at") Instant enqueuedAt) implements Job {

    public static final String TYPE = "answer";

    public AnswerJob {
        JobChecks.common(v, jobId, chatId, enqueuedAt);
        JobChecks.text(userId, "user_id");
        JobChecks.text(prevCheckId, "prev_check_id");
        JobChecks.text(answer, "answer");
        // placeholder_message_id có thể null nếu chưa có placeholder
    }
}
