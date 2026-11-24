package com.askfirst.common;

/**
 * Telegram trả HTTP khác 200 hoặc ok=false. Là {@link IllegalStateException} để code cũ bắt
 * {@code IllegalStateException} vẫn chạy. Message giữ nguyên định dạng cũ (có body của Telegram),
 * nên caller KHÔNG được log {@code getMessage()} nguyên văn: dùng {@link #status()} và các hàm phân loại.
 */
public class TelegramApiException extends IllegalStateException {

    private final int status;

    public TelegramApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** 400/403/404: gửi lại cũng không khá hơn (chat bị chặn bot, tin đã xóa, request sai). */
    public boolean isPermanent() {
        return status == 400 || status == 403 || status == 404;
    }

    /** Edit với nội dung giống hệt tin hiện tại (xảy ra khi SQS retry sau khi đã edit xong). */
    public boolean isMessageNotModified() {
        String m = getMessage();
        return status == 400 && m != null && m.contains("message is not modified");
    }
}
