package com.askfirst.common.store;

import java.time.Instant;
import java.util.Optional;

/** ALERT#<alert_id>: tra trực tiếp bằng alert_id (callback "ack:<alert_id>" chỉ mang alert_id). */
public interface AlertStore {

    /** Tạo alert pending nếu chưa có; nếu đã có thì trả về bản đang lưu (retry). */
    AlertRecord reserve(AlertRecord pending);

    Optional<AlertRecord> get(String alertId);

    /** Chỉ chuyển pending/failed sang sent; không hạ cấp acked. */
    void markSent(String alertId, int messageId);

    /** Chỉ chuyển pending sang failed. */
    void markFailed(String alertId);

    /** @return true nếu alert tồn tại (đã hoặc vừa được đánh dấu acked) */
    boolean markAcked(String alertId, Instant at);

    /** Nhắc lại tối đa một lần: true chỉ cho lần gọi đầu tiên đặt được reminded_at. */
    boolean markReminded(String alertId, Instant at);
}
