package com.askfirst.common.store;

/** CHECK#: dấu vết mỗi lần kiểm tra. Cũng là dấu "đã xong" để retry SQS không gửi lại kết quả. */
public interface CheckStore {

    boolean exists(String userId, String checkId);

    /** Ghi một lần; ghi lại cùng checkId là không làm gì. */
    void put(CheckRecord record);

    /** Cập nhật trường feedback (ví dụ "wrong") cho bản ghi kiểm tra. */
    default void updateFeedback(String userId, String checkId, String feedback) {
    }

    /**
     * Ghi feedback cho check ĐANG TỒN TẠI và CHƯA có feedback.
     *
     * @return true nếu vừa ghi; false nếu check không tồn tại (hết TTL, sai chủ) hoặc đã có feedback.
     *         Bản Dynamo kiểm bằng một conditional write nên bấm đôi song song chỉ một request thắng;
     *         bản mặc định này (cho fake/in-memory) chỉ kiểm sự tồn tại.
     */
    default boolean tryMarkFeedback(String userId, String checkId, String feedback) {
        if (!exists(userId, checkId)) {
            return false;
        }
        updateFeedback(userId, checkId, feedback);
        return true;
    }
}
