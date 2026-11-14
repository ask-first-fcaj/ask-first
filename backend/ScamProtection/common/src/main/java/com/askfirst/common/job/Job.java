package com.askfirst.common.job;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;

/**
 * Envelope của message trong SQS {@code jobs} (implementation-plan §3.4).
 * Đây là contract nội bộ Java (webhook -> worker), KHÔNG phải contract Java <-> Python
 * và KHÔNG phải {@code InboundMessage} (cái đó nằm trong BUFFER#).
 *
 * <p>Wire format: snake_case, có {@code v} và {@code type}, thời gian UTC ISO-8601.
 * {@code chat_id} luôn là String.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AnalyzeJob.class, name = AnalyzeJob.TYPE),
        @JsonSubTypes.Type(value = AnswerJob.class, name = AnswerJob.TYPE),
        @JsonSubTypes.Type(value = FollowupJob.class, name = FollowupJob.TYPE)
})
public sealed interface Job permits AnalyzeJob, AnswerJob, FollowupJob {

    int CURRENT_VERSION = 1;

    int v();

    String jobId();

    String chatId();

    Instant enqueuedAt();
}
