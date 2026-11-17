package com.askfirst.common.job;

import java.time.Instant;

/** Kiểm tra field dùng chung cho các record job. Chỉ nêu tên field, không nêu giá trị. */
final class JobChecks {

    private JobChecks() {
    }

    static void common(int v, String jobId, String chatId, Instant enqueuedAt) {
        if (v != Job.CURRENT_VERSION) {
            throw new InvalidJobException(InvalidJobException.UNSUPPORTED_VERSION);
        }
        text(jobId, "job_id");
        text(chatId, "chat_id");
        if (enqueuedAt == null) {
            throw InvalidJobException.invalidField("enqueued_at");
        }
    }

    static void text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw InvalidJobException.invalidField(field);
        }
    }
}
