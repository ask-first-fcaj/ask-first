package com.askfirst.common.ai;

/** Invoke Lambda thất bại ở tầng vận chuyển (FunctionError, timeout, throttle...). Chỉ mang mã lỗi. */
public final class LambdaInvokeException extends RuntimeException {

    private final String code;

    public LambdaInvokeException(String code) {
        super(code, null, false, false);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
