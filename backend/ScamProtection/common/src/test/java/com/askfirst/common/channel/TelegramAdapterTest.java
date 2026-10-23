package com.askfirst.common.channel;

import com.askfirst.common.channel.model.EntityRef;
import com.askfirst.common.channel.model.InboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramAdapterTest {

    private TelegramAdapter adapter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        adapter = new TelegramAdapter();
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("Parse regular text message")
    void testParseTextMessage() throws Exception {
        String json = """
            {
              "update_id": 10001,
              "message": {
                "message_id": 42,
                "from": { "id": 999, "is_bot": false, "first_name": "Nam" },
                "chat": { "id": 888, "type": "private" },
                "date": 1729000000,
                "text": "Chào bạn, đây là tin nhắn text"
              }
            }
            """;
        JsonNode update = mapper.readTree(json);
        Optional<InboundMessage> result = adapter.parseUpdate(update);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.channel()).isEqualTo("telegram");
        assertThat(msg.updateId()).isEqualTo("10001");
        assertThat(msg.messageId()).isEqualTo("42");
        assertThat(msg.chatId()).isEqualTo("888");
        assertThat(msg.userId()).isEqualTo("tg:999");
        assertThat(msg.kind()).isEqualTo("text");
        assertThat(msg.text()).isEqualTo("Chào bạn, đây là tin nhắn text");
        assertThat(msg.media()).isNull();
        assertThat(msg.entities()).isEmpty();
        assertThat(msg.receivedAt()).isNotNull();
    }

    @Test
    @DisplayName("Parse text message containing url and text_link entities")
    void testParseTextWithEntities() throws Exception {
        String json = """
            {
              "update_id": 10002,
              "message": {
                "message_id": 43,
                "from": { "id": 999, "is_bot": false },
                "chat": { "id": 888, "type": "private" },
                "date": 1729000000,
                "text": "Bấm vào đây để tra cứu phạt nguội",
                "entities": [
                  {
                    "offset": 0,
                    "length": 10,
                    "type": "text_link",
                    "url": "https://dichvucong-fake.com"
                  },
                  {
                    "offset": 20,
                    "length": 15,
                    "type": "bold"
                  }
                ]
              }
            }
            """;
        JsonNode update = mapper.readTree(json);
        Optional<InboundMessage> result = adapter.parseUpdate(update);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.entities()).hasSize(1);
        EntityRef entity = msg.entities().get(0);
        assertThat(entity.kind()).isEqualTo("text_link");
        assertThat(entity.offset()).isEqualTo(0);
        assertThat(entity.length()).isEqualTo(10);
        assertThat(entity.url()).isEqualTo("https://dichvucong-fake.com");
    }

    @Test
    @DisplayName("Parse photo message and pick the largest PhotoSize")
    void testParsePhotoMessage() throws Exception {
        String json = """
            {
              "update_id": 10003,
              "message": {
                "message_id": 44,
                "from": { "id": 999, "is_bot": false },
                "chat": { "id": 888, "type": "private" },
                "date": 1729000000,
                "caption": "Ảnh chụp màn hình tin nhắn phạt nguội",
                "photo": [
                  { "file_id": "small_id", "file_unique_id": "u_small", "file_size": 1500, "width": 100, "height": 100 },
                  { "file_id": "largest_id", "file_unique_id": "u_largest", "file_size": 85000, "width": 800, "height": 800 },
                  { "file_id": "medium_id", "file_unique_id": "u_medium", "file_size": 15000, "width": 320, "height": 320 }
                ]
              }
            }
            """;
        JsonNode update = mapper.readTree(json);
        Optional<InboundMessage> result = adapter.parseUpdate(update);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.kind()).isEqualTo("photo");
        assertThat(msg.text()).isEqualTo("Ảnh chụp màn hình tin nhắn phạt nguội");
        assertThat(msg.media()).isNotNull();
        assertThat(msg.media().fileId()).isEqualTo("largest_id");
        assertThat(msg.media().fileUniqueId()).isEqualTo("u_largest");
        assertThat(msg.media().mime()).isEqualTo("image/jpeg");
        assertThat(msg.media().size()).isEqualTo(85000L);
    }

    @Test
    @DisplayName("Parse voice message")
    void testParseVoiceMessage() throws Exception {
        String json = """
            {
              "update_id": 10004,
              "message": {
                "message_id": 45,
                "from": { "id": 999, "is_bot": false },
                "chat": { "id": 888, "type": "private" },
                "date": 1729000000,
                "voice": {
                  "file_id": "voice_file_id",
                  "file_unique_id": "u_voice",
                  "mime_type": "audio/ogg",
                  "file_size": 32000,
                  "duration": 15
                }
              }
            }
            """;
        JsonNode update = mapper.readTree(json);
        Optional<InboundMessage> result = adapter.parseUpdate(update);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.kind()).isEqualTo("voice");
        assertThat(msg.media()).isNotNull();
        assertThat(msg.media().fileId()).isEqualTo("voice_file_id");
        assertThat(msg.media().mime()).isEqualTo("audio/ogg");
        assertThat(msg.media().duration()).isEqualTo(15);
        assertThat(msg.media().size()).isEqualTo(32000L);
    }

    @Test
    @DisplayName("Parse forwarded message origin")
    void testParseForwardOrigin() throws Exception {
        String json = """
            {
              "update_id": 10005,
              "message": {
                "message_id": 46,
                "from": { "id": 999, "is_bot": false },
                "chat": { "id": 888, "type": "private" },
                "date": 1729000000,
                "text": "Tin nhắn chuyển tiếp từ kênh",
                "forward_origin": {
                  "type": "channel"
                }
              }
            }
            """;
        JsonNode update = mapper.readTree(json);
        Optional<InboundMessage> result = adapter.parseUpdate(update);

        assertThat(result).isPresent();
        assertThat(result.get().forwardOrigin()).isEqualTo("channel");
    }

    @Test
    @DisplayName("Ignore message from bot account")
    void testIgnoreBotMessage() throws Exception {
        String json = """
            {
              "update_id": 10006,
              "message": {
                "message_id": 47,
                "from": { "id": 777, "is_bot": true, "first_name": "SpamBot" },
                "chat": { "id": 888, "type": "private" },
                "date": 1729000000,
                "text": "Alo"
              }
            }
            """;
        JsonNode update = mapper.readTree(json);
        Optional<InboundMessage> result = adapter.parseUpdate(update);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Truncate text over 4000 characters")
    void testTruncateLongText() throws Exception {
        String longText = "a".repeat(5000);
        String json = """
            {
              "update_id": 10007,
              "message": {
                "message_id": 48,
                "from": { "id": 999, "is_bot": false },
                "chat": { "id": 888, "type": "private" },
                "date": 1729000000,
                "text": "%s"
              }
            }
            """.formatted(longText);
        JsonNode update = mapper.readTree(json);
        Optional<InboundMessage> result = adapter.parseUpdate(update);

        assertThat(result).isPresent();
        assertThat(result.get().text()).hasSize(4000);
    }

    @Test
    @DisplayName("Verify InboundMessage serializes to valid JSON matching schema")
    void testJsonRoundTripSerialization() throws Exception {
        String json = """
            {
              "update_id": 10008,
              "message": {
                "message_id": 49,
                "from": { "id": 999, "is_bot": false },
                "chat": { "id": 888, "type": "private" },
                "date": 1729000000,
                "text": "Kiểm tra serialization"
              }
            }
            """;
        JsonNode update = mapper.readTree(json);
        InboundMessage msg = adapter.parseUpdate(update).orElseThrow();

        String serialized = mapper.writeValueAsString(msg);
        JsonNode tree = mapper.readTree(serialized);

        assertThat(tree.path("channel").asText()).isEqualTo("telegram");
        assertThat(tree.path("user_id").asText()).isEqualTo("tg:999");
        assertThat(tree.path("chat_id").asText()).isEqualTo("888");
        assertThat(tree.path("message_id").asText()).isEqualTo("49");
        assertThat(tree.path("kind").asText()).isEqualTo("text");
        assertThat(tree.has("received_at")).isTrue();
    }
}
