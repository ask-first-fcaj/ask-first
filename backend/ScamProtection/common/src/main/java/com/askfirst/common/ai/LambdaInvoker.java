package com.askfirst.common.ai;

/**
 * Tầng vận chuyển tối thiểu tới Lambda {@code ai}: gửi JSON, nhận JSON.
 * Bản thật nằm ở module worker (AWS SDK); test dùng lambda/fake nên không cần mock thư viện.
 */
@FunctionalInterface
public interface LambdaInvoker {

    /**
     * @return body phản hồi (JSON)
     * @throws LambdaInvokeException nếu FunctionError hoặc không gọi được
     */
    String invoke(String jsonPayload) throws LambdaInvokeException;
}
