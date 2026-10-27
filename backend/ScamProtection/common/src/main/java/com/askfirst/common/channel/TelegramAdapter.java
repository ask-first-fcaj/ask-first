package com.askfirst.common.channel;

import com.askfirst.common.channel.model.EntityRef;
import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.channel.model.MediaRef;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adapter converting raw Telegram webhook Update JSON into standard InboundMessage contract.
 * Implements architectural rules from system-architecture.md §4.4, §8.1 and implementation-plan.md
 * §3.3, §3.6.
 */
public class TelegramAdapter {

  public static final String CHANNEL_TELEGRAM = "telegram";
  public static final int MAX_TEXT_LENGTH = 4000;

  /**
   * Parses a Telegram update JSON. Returns Optional.empty() if the update is not a user message
   * (e.g., bot sender, callback query, unsupported media).
   */
  public Optional<InboundMessage> parseUpdate(JsonNode update) {
    if (update == null || !update.has("message")) {
      return Optional.empty();
    }

    JsonNode msg = update.path("message");

    // Ignore messages from bots (security & anti-loop rule: xlsx FRS-074)
    if (msg.path("from").path("is_bot").asBoolean(false)) {
      return Optional.empty();
    }

    String updateId = update.path("update_id").asText();
    String messageId = msg.path("message_id").asText();
    String chatId = msg.path("chat").path("id").asText();
    String fromId = msg.path("from").path("id").asText();
    if (updateId.isBlank() || messageId.isBlank() || chatId.isBlank() || fromId.isBlank()) {
      return Optional.empty();
    }

    String userId = "tg:" + fromId;

    // Received timestamp (Telegram returns unix epoch seconds in UTC)
    long dateEpochSec = msg.path("date").asLong(Instant.now().getEpochSecond());
    Instant receivedAt = Instant.ofEpochSecond(dateEpochSec);

    // 1. Extract text or caption (truncated to 4,000 characters)
    String rawText =
        msg.hasNonNull("text") ? msg.path("text").asText() : msg.path("caption").asText(null);
    String text = null;
    if (rawText != null) {
      text = rawText.length() > MAX_TEXT_LENGTH ? rawText.substring(0, MAX_TEXT_LENGTH) : rawText;
    }

    // 2. Identify kind and media reference (Java NEVER downloads media bytes: plan
    // §3.6)
    String kind = "text";
    MediaRef media = null;

    if (msg.has("photo") && msg.path("photo").isArray() && !msg.path("photo").isEmpty()) {
      kind = "photo";
      // In Telegram, photo array contains multiple sizes; pick the largest one (plan
      // §3.6)
      JsonNode largestPhoto = null;
      for (JsonNode p : msg.path("photo")) {
        if (largestPhoto == null
            || p.path("file_size").asLong(0) >= largestPhoto.path("file_size").asLong(0)) {
          largestPhoto = p;
        }
      }
      if (largestPhoto != null) {
        String fileId = largestPhoto.path("file_id").asText();
        String fileUniqueId = largestPhoto.path("file_unique_id").asText();
        Long size =
            largestPhoto.hasNonNull("file_size") ? largestPhoto.path("file_size").asLong() : null;
        media = new MediaRef(fileId, fileUniqueId, "image/jpeg", size, null, null);
      }
    } else if (msg.has("voice") && msg.path("voice").isObject()) {
      kind = "voice";
      JsonNode v = msg.path("voice");
      String fileId = v.path("file_id").asText();
      String fileUniqueId = v.path("file_unique_id").asText();
      String mime = v.path("mime_type").asText("audio/ogg");
      Long size = v.hasNonNull("file_size") ? v.path("file_size").asLong() : null;
      Integer duration = v.hasNonNull("duration") ? v.path("duration").asInt() : null;
      media = new MediaRef(fileId, fileUniqueId, mime, size, duration, null);
    } else if (msg.has("audio") && msg.path("audio").isObject()) {
      kind = "audio";
      JsonNode a = msg.path("audio");
      String fileId = a.path("file_id").asText();
      String fileUniqueId = a.path("file_unique_id").asText();
      String mime = a.path("mime_type").asText("audio/mpeg");
      Long size = a.hasNonNull("file_size") ? a.path("file_size").asLong() : null;
      Integer duration = a.hasNonNull("duration") ? a.path("duration").asInt() : null;
      String fileName = a.hasNonNull("file_name") ? a.path("file_name").asText() : null;
      media = new MediaRef(fileId, fileUniqueId, mime, size, duration, fileName);
    } else if (msg.has("document") && msg.path("document").isObject()) {
      kind = "document";
      JsonNode d = msg.path("document");
      String fileId = d.path("file_id").asText();
      String fileUniqueId = d.path("file_unique_id").asText();
      String mime = d.path("mime_type").asText("application/octet-stream");
      Long size = d.hasNonNull("file_size") ? d.path("file_size").asLong() : null;
      String fileName = d.hasNonNull("file_name") ? d.path("file_name").asText() : null;
      media = new MediaRef(fileId, fileUniqueId, mime, size, null, fileName);
    } else if (text == null) {
      // No text and no supported media -> ignore update
      return Optional.empty();
    }

    // 3. Extract entities (url, text_link) from either entities or caption_entities
    List<EntityRef> entities = new ArrayList<>();
    JsonNode entityNodes =
        msg.has("entities") ? msg.path("entities") : msg.path("caption_entities");
    if (entityNodes != null && entityNodes.isArray()) {
      for (JsonNode e : entityNodes) {
        String type = e.path("type").asText();
        if ("url".equals(type) || "text_link".equals(type)) {
          int offset = e.path("offset").asInt(0);
          int length = e.path("length").asInt(0);
          String url = e.hasNonNull("url") ? e.path("url").asText() : null;
          if ("text_link".equals(type) && (url == null || url.isBlank())) {
            continue;
          }
          entities.add(new EntityRef(type, offset, length, url));
        }
      }
    }

    // 4. Forward origin: support Telegram Bot API 7.0+ forward_origin or legacy
    // forward fields
    String forwardOrigin = null;
    if (msg.has("forward_origin") && msg.path("forward_origin").isObject()) {
      forwardOrigin = msg.path("forward_origin").path("type").asText(null);
    } else if (msg.has("forward_from") || msg.has("forward_sender_name")) {
      forwardOrigin = msg.has("forward_from") ? "user" : "hidden_user";
    } else if (msg.has("forward_from_chat")) {
      String chatType = msg.path("forward_from_chat").path("type").asText();
      forwardOrigin = "channel".equals(chatType) ? "channel" : "chat";
    }

    // 5. Media group ID (album of photos/videos sent together: plan §5.2)
    String mediaGroupId =
        msg.hasNonNull("media_group_id") ? msg.path("media_group_id").asText() : null;

    return Optional.of(
        new InboundMessage(
            CHANNEL_TELEGRAM,
            updateId,
            chatId,
            userId,
            messageId,
            kind,
            text,
            entities,
            media,
            forwardOrigin,
            mediaGroupId,
            receivedAt));
  }
}
