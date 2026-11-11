package com.askfirst.common.ai;

/**
 * Lỗi khi gọi Lambda {@code ai}. Chỉ mang cờ retryable và mã lỗi ngắn, không mang payload hay cause
 * (cause của SDK/Jackson có thể chứa nội dung người dùng).
 * <ul>
 *   <li>retryable = true: worker để SQS retry (throw / batchItemFailure).</li>
 *   <li>retryable = false: lỗi chắc chắn lặp lại (input sai, vi phạm contract): trả câu suy giảm, không retry.</li>
 * </ul>
 */
public final class AiException extends RuntimeException {

    private final boolean retryable;
    private final String code;

    private AiException(boolean retryable, String code) {
        super((retryable ? "retryable:" : "fatal:") + code, null, false, false);
        this.retryable = retryable;
        this.code = code;
    }

    public static AiException retryable(String code) {
        return new AiException(true, code);
    }

    public static AiException fatal(String code) {
        return new AiException(false, code);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String code() {
        return code;
    }
}
