package com.askfirst.worker;

/** Lỗi tạm thời: để SQS giao lại job. Chỉ mang mã lỗi. */
public final class RetryableFailure extends RuntimeException {

    private final String code;

    public RetryableFailure(String code) {
        super(code, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
