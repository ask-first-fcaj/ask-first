package com.askfirst.common.job;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * Worker tự tạo sau khi có ít nhất một guardian alert gửi thành công, {@code DelaySeconds = 900}.
 * Không gọi AI: chỉ kiểm tra ACK và nhắc.
 */
public record FollowupJob(
        @JsonProperty("v") int v,
        @JsonProperty("job_id") String jobId,
        @JsonProperty("chat_id") String chatId,
        @JsonProperty("elder_uid") String elderUid,
        @JsonProperty("check_id") String checkId,
        @JsonProperty("alert_ids") List<String> alertIds,
        @JsonProperty("enqueued_at") Instant enqueuedAt) implements Job {

    public static final String TYPE = "followup";
    public static final int DELAY_SECONDS = 900;

    public FollowupJob {
        JobChecks.common(v, jobId, chatId, enqueuedAt);
        JobChecks.text(elderUid, "elder_uid");
        JobChecks.text(checkId, "check_id");
        if (alertIds == null || alertIds.isEmpty()) {
            throw InvalidJobException.invalidField("alert_ids");
        }
        alertIds = List.copyOf(alertIds);
    }

    public static FollowupJob create(String jobId, String chatId, String elderUid, String checkId,
                                     List<String> alertIds, Instant now) {
        return new FollowupJob(CURRENT_VERSION, jobId, chatId, elderUid, checkId, alertIds, now);
    }
}
