package com.askfirst.common.store;

import java.util.List;

/**
 * Siêu dữ liệu của một lần kiểm tra, KHÔNG chứa nội dung tin nhắn, explanation hay evidence.
 * {@code outcome} là verdict | smalltalk | unreadable | degraded (AI lỗi, đã trả câu suy giảm).
 */
public record CheckRecord(
        String checkId,
        String userId,
        String outcome,
        String level,
        int score,
        String group,
        String scenario,
        String path,
        List<String> signalCodes,
        List<String> riskyActions,
        List<String> inputKinds,
        boolean alerted,
        String createdAt,
        long ttlEpochSeconds) {

    public CheckRecord {
        signalCodes = signalCodes == null ? List.of() : List.copyOf(signalCodes);
        riskyActions = riskyActions == null ? List.of() : List.copyOf(riskyActions);
        inputKinds = inputKinds == null ? List.of() : List.copyOf(inputKinds);
    }
}
