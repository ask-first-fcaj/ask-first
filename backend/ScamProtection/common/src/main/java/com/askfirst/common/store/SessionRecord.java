package com.askfirst.common.store;

/**
 * Ngữ cảnh phiên kiểm tra tạm thời (SESSION#{chat_id}), TTL 30 phút.
 * Chứa bản text đã mask (masked_text) phục vụ hỏi thêm hoặc đóng góp mẫu (Donation).
 * Tuyệt đối không lưu text gốc hay PII.
 */
public record SessionRecord(
        String chatId,
        String checkId,
        String userId,
        String maskedText,
        String followupQuestion,
        int turn,
        long ttlEpochSeconds) {

    public SessionRecord {
        maskedText = maskedText == null ? "" : maskedText;
    }
}
