package com.askfirst.common.job;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Webhook gửi cho mỗi tin cần phân tích, {@code DelaySeconds = 3} để debounce.
 * Job có {@code seq < BUFFER.max_seq} sẽ bị worker bỏ qua; job mới nhất xử lý cả nhóm tin chưa xong.
 */
public record AnalyzeJob(
        @JsonProperty("v") int v,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("chat_id") String chatId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("seq") long seq,
        @JsonProperty("enqueued_at") Instant enqueuedAt) implements Job {

    public static final String TYPE = "analyze";
    public static final int DELAY_SECONDS = 3;

    public AnalyzeJob {
        JobChecks.common(v, jobId, chatId, enqueuedAt);
        JobChecks.text(userId, "user_id");
        if (seq < 1) {
            throw InvalidJobException.invalidField("seq");
        }
    }

    public static AnalyzeJob create(String jobId, String chatId, String userId, long seq, Instant now) {
        return new AnalyzeJob(CURRENT_VERSION, jobId, chatId, userId, seq, now);
    }
}
