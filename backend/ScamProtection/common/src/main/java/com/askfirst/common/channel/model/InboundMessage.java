package com.askfirst.common.channel.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Built by the adapter from one update. Kept temporarily in BUFFER# (<= 10 minutes, deleted right after processing).
 * Matches contracts/schema/InboundMessage.json
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InboundMessage(
    @JsonProperty("channel") String channel,
    @JsonProperty("update_id") String updateId,
    @JsonProperty("chat_id") String chatId,
    @JsonProperty("user_id") String userId,
    @JsonProperty("message_id") String messageId,
    @JsonProperty("kind") String kind,
    @JsonProperty("text") String text,
    @JsonProperty("entities") List<EntityRef> entities,
    @JsonProperty("media") MediaRef media,
    @JsonProperty("forward_origin") String forwardOrigin,
    @JsonProperty("media_group_id") String mediaGroupId,
    @JsonProperty("received_at")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss[.SSS]X", timezone = "UTC")
    Instant receivedAt
) {
    public InboundMessage {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel is required");
        }
        if (updateId == null || updateId.isBlank()) {
            throw new IllegalArgumentException("update_id is required");
        }
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chat_id is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id is required");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("message_id is required");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind is required");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("received_at is required");
        }
        if (entities == null) {
            entities = Collections.emptyList();
        } else {
            entities = List.copyOf(entities);
        }
    }
}
