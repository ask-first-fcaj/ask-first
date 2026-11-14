package com.askfirst.common.store;

/**
 * ALERTLOCK#<uid>#<scenario>: chặn gửi trùng cảnh báo cùng kịch bản trong 30 phút.
 * Chủ khóa là check_id nên SQS retry cùng job vào lại được, job khác bị chặn.
 */
public interface AlertLockStore {

    /**
     * @return true nếu khóa chưa tồn tại, đã hết hạn (TTL của DynamoDB xóa trễ, nên so thời gian thật),
     *         hoặc đang do chính {@code owner} giữ
     */
    boolean acquire(String userId, String scenario, String owner, long nowEpochSeconds, long ttlEpochSeconds);
}
