package com.askfirst.common.job;

/**
 * Job SQS sai contract (JSON hỏng, version/type lạ, thiếu field).
 * Chỉ mang mã lỗi ngắn, KHÔNG mang body hay giá trị field và KHÔNG chain cause:
 * message của Jackson có thể chứa đoạn nội dung gốc và sẽ lọt vào log.
 */
public final class InvalidJobException extends RuntimeException {

    public static final String MALFORMED_JSON = "malformed_json";
    public static final String UNSUPPORTED_VERSION = "unsupported_version";
    public static final String UNKNOWN_TYPE = "unknown_type";
    public static final String INVALID_FIELD = "invalid_field";

    private final String code;

    public InvalidJobException(String code) {
        super(code, null, false, false);
        this.code = code;
    }

    /** Mã lỗi an toàn để log/metric. */
    public String code() {
        return code;
    }

    static InvalidJobException invalidField(String field) {
        return new InvalidJobException(INVALID_FIELD + ":" + field);
    }
}
