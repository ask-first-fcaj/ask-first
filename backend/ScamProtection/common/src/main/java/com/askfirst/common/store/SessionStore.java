package com.askfirst.common.store;

import java.util.Optional;

/**
 * SESSION#{chat_id}: ngữ cảnh phiên kiểm tra tạm thời (TTL 30 phút).
 */
public interface SessionStore {

    Optional<SessionRecord> get(String chatId);

    void put(SessionRecord record);

    void delete(String chatId);
}
