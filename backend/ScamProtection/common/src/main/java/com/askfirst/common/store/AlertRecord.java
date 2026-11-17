package com.askfirst.common.store;

/**
 * Một cảnh báo gửi cho một người thân. {@code alertId} xác định theo (check, guardian) nên retry
 * dùng lại đúng alert cũ. status: pending | sent | failed | acked.
 */
public record AlertRecord(
        String alertId,
        String elderUid,
        String elderChatId,
        String checkId,
        String guardianUid,
        String guardianChatId,
        String guardianName,
        int shareLevel,
        String status,
        Integer messageId,
        String ackedAt,
        String remindedAt,
        long ttlEpochSeconds) {

    public static final String PENDING = "pending";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    public static final String ACKED = "acked";

    public boolean delivered() {
        return SENT.equals(status) || ACKED.equals(status);
    }
}
