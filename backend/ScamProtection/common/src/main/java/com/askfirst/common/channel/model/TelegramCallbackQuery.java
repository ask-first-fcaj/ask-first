package com.askfirst.common.channel.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parsed callback query from an inline keyboard button press (Telegram Fast Path).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramCallbackQuery(
        @JsonProperty("update_id") String updateId,
        @JsonProperty("query_id") String queryId,
        @JsonProperty("user_id") String userId,
        @JsonProperty("chat_id") String chatId,
        @JsonProperty("message_id") String messageId,
        @JsonProperty("data") String data
) {
    public TelegramCallbackQuery {
        if (queryId == null || queryId.isBlank()) {
            throw new IllegalArgumentException("query_id is required");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chat_id is required");
        }
    }
}

