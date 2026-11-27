package com.askfirst.common.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

    /** alert_id xác định theo (check, guardian): retry hay bấm lại dùng đúng alert cũ, không gửi trùng. */
    public static String idFor(String checkId, String guardianUid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(guardianUid.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return checkId + "-" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng");
        }
    }
}
