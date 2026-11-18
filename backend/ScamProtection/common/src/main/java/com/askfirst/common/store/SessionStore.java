package com.askfirst.common.store;

import java.util.Optional;

/**
 * SESSION#{chat_id}: ngữ cảnh phiên kiểm tra tạm thời (TTL 30 phút).
 */
public interface SessionStore {

    /** Lấy phiên theo chatId. */
    Optional<SessionRecord> get(String chatId);

    /** Lưu hoặc cập nhật phiên. */
    void put(SessionRecord record);

    /** Xóa phiên theo chatId. */
    void delete(String chatId);
}

