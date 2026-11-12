package com.askfirst.common.store;

/** CHECK#: dấu vết mỗi lần kiểm tra. Cũng là dấu "đã xong" để retry SQS không gửi lại kết quả. */
public interface CheckStore {

    boolean exists(String userId, String checkId);

    /** Ghi một lần; ghi lại cùng checkId là không làm gì. */
    void put(CheckRecord record);
}
