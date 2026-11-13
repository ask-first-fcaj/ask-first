package com.askfirst.common.channel;

import com.askfirst.common.channel.model.EntityRef;
import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.channel.model.TelegramCallbackQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
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

    private JsonNode loadFixture(String fixtureName) throws Exception {
        String path = "/telegram/" + fixtureName;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalArgumentException("Fixture not found on classpath: " + path);
            }
            return mapper.readTree(is);
        }
    }

    @Test
    @DisplayName("Fixture 01: Parse regular text message")
    void test01_TextSimple() throws Exception {
        JsonNode fixture = loadFixture("01_text_simple.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.channel()).isEqualTo("telegram");
        assertThat(msg.updateId()).isEqualTo("10001");
        assertThat(msg.messageId()).isEqualTo("101");
        assertThat(msg.chatId()).isEqualTo("222222");
        assertThat(msg.userId()).isEqualTo("tg:111111");
        assertThat(msg.kind()).isEqualTo("text");
        assertThat(msg.text()).isEqualTo("Chào bạn, cho mình hỏi số tài khoản này có an toàn không?");
        assertThat(msg.media()).isNull();
        assertThat(msg.entities()).isEmpty();
        assertThat(msg.forwardOrigin()).isNull();
        assertThat(msg.mediaGroupId()).isNull();
        assertThat(msg.receivedAt()).isNotNull();
    }

    @Test
    @DisplayName("Fixture 02: Parse text with url and text_link entities")
    void test02_TextUrlEntities() throws Exception {
        JsonNode fixture = loadFixture("02_text_url_entities.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.entities()).hasSize(2);

        EntityRef urlRef = msg.entities().get(0);
        assertThat(urlRef.kind()).isEqualTo("url");
        assertThat(urlRef.offset()).isEqualTo(18);
        assertThat(urlRef.length()).isEqualTo(24);

        EntityRef linkRef = msg.entities().get(1);
        assertThat(linkRef.kind()).isEqualTo("text_link");
        assertThat(linkRef.offset()).isEqualTo(52);
        assertThat(linkRef.length()).isEqualTo(6);
        assertThat(linkRef.url()).isEqualTo("https://malicious-site.vn");
    }

    @Test
    @DisplayName("Fixture 03: Parse command /start")
    void test03_TextCommandStart() throws Exception {
        JsonNode fixture = loadFixture("03_text_command_start.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.kind()).isEqualTo("text");
        assertThat(msg.text()).isEqualTo("/start");
        assertThat(TelegramAdapter.isCommand(msg)).isTrue();
    }

    @Test
    @DisplayName("Fixture 04: Parse command /huongdan")
    void test04_TextCommandHuongdan() throws Exception {
        JsonNode fixture = loadFixture("04_text_command_huongdan.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.kind()).isEqualTo("text");
        assertThat(msg.text()).isEqualTo("/huongdan");
        assertThat(TelegramAdapter.isCommand(msg)).isTrue();
    }

    @Test
    @DisplayName("Fixture 05: Parse photo message and select largest PhotoSize")
    void test05_PhotoSingle() throws Exception {
        JsonNode fixture = loadFixture("05_photo_single.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.kind()).isEqualTo("photo");
        assertThat(msg.text()).isEqualTo("Ảnh chụp màn hình tin nhắn trúng thưởng");
        assertThat(msg.media()).isNotNull();
        assertThat(msg.media().fileId()).isEqualTo("photo_lg_105");
        assertThat(msg.media().fileUniqueId()).isEqualTo("u_lg_105");
        assertThat(msg.media().mime()).isEqualTo("image/jpeg");
        assertThat(msg.media().size()).isEqualTo(95000L);
    }

    @Test
    @DisplayName("Fixture 06: Parse photo album preserving media_group_id")
    void test06_PhotoAlbum() throws Exception {
        JsonNode fixture = loadFixture("06_photo_album.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.kind()).isEqualTo("photo");
        assertThat(msg.mediaGroupId()).isEqualTo("album_987654321");
        assertThat(msg.media().fileId()).isEqualTo("photo_alb_106");
    }

    @Test
    @DisplayName("Fixture 07: Parse voice message")
    void test07_VoiceOgg() throws Exception {
        JsonNode fixture = loadFixture("07_voice_ogg.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.kind()).isEqualTo("voice");
        assertThat(msg.media()).isNotNull();
        assertThat(msg.media().fileId()).isEqualTo("voice_107_id");
        assertThat(msg.media().mime()).isEqualTo("audio/ogg");
        assertThat(msg.media().duration()).isEqualTo(22);
        assertThat(msg.media().size()).isEqualTo(45000L);
    }

    @Test
    @DisplayName("Fixture 08: Parse audio message")
    void test08_AudioMp3() throws Exception {
        JsonNode fixture = loadFixture("08_audio_mp3.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.kind()).isEqualTo("audio");
        assertThat(msg.media()).isNotNull();
        assertThat(msg.media().fileId()).isEqualTo("audio_108_id");
        assertThat(msg.media().mime()).isEqualTo("audio/mpeg");
        assertThat(msg.media().duration()).isEqualTo(65);
        assertThat(msg.media().fileName()).isEqualTo("ghi_am_cuoc_goi.mp3");
    }

    @Test
    @DisplayName("Fixture 09: Parse document message")
    void test09_DocumentPdf() throws Exception {
        JsonNode fixture = loadFixture("09_document_pdf.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        InboundMessage msg = result.get();
        assertThat(msg.kind()).isEqualTo("document");
        assertThat(msg.media()).isNotNull();
        assertThat(msg.media().fileId()).isEqualTo("doc_109_id");
        assertThat(msg.media().mime()).isEqualTo("application/pdf");
        assertThat(msg.media().fileName()).isEqualTo("thong_bao_phat_nguoi.pdf");
        assertThat(msg.media().size()).isEqualTo(120500L);
    }

    @Test
    @DisplayName("Fixture 10: Parse forwarded message from channel")
    void test10_ForwardChannel() throws Exception {
        JsonNode fixture = loadFixture("10_forward_channel.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        assertThat(result.get().forwardOrigin()).isEqualTo("channel");
    }

    @Test
    @DisplayName("Fixture 11: Parse forwarded message from user")
    void test11_ForwardUser() throws Exception {
        JsonNode fixture = loadFixture("11_forward_user.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        assertThat(result.get().forwardOrigin()).isEqualTo("user");
    }

    @Test
    @DisplayName("Fixture 12: Ignore message from bot account")
    void test12_BotSenderIgnored() throws Exception {
        JsonNode fixture = loadFixture("12_bot_sender.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Fixture 13: Parse callback query for Fast Path button clicks")
    void test13_CallbackQuery() throws Exception {
        JsonNode fixture = loadFixture("13_callback_query.json");

        // parseUpdate returns empty for callback_query (slow path ignores it)
        assertThat(adapter.parseUpdate(fixture)).isEmpty();

        // parseCallbackQuery extracts callback details for fast path
        Optional<TelegramCallbackQuery> cbOpt = adapter.parseCallbackQuery(fixture);
        assertThat(cbOpt).isPresent();
        TelegramCallbackQuery cb = cbOpt.get();
        assertThat(cb.updateId()).isEqualTo("10013");
        assertThat(cb.queryId()).isEqualTo("cb_query_10013");
        assertThat(cb.userId()).isEqualTo("tg:111111");
        assertThat(cb.chatId()).isEqualTo("222222");
        assertThat(cb.messageId()).isEqualTo("101");
        assertThat(cb.data()).isEqualTo("action:confirm_report");
    }

    @Test
    @DisplayName("Fixture 14: Truncate message text over 4000 characters")
    void test14_TextLongTruncated() throws Exception {
        JsonNode fixture = loadFixture("14_text_long_truncated.json");
        Optional<InboundMessage> result = adapter.parseUpdate(fixture);

        assertThat(result).isPresent();
        assertThat(result.get().text()).hasSize(4000);
    }

    @Test
    @DisplayName("Verify round-trip JSON serialization matches InboundMessage contract")
    void testJsonRoundTripContract() throws Exception {
        JsonNode fixture = loadFixture("01_text_simple.json");
        InboundMessage msg = adapter.parseUpdate(fixture).orElseThrow();

        String serialized = mapper.writeValueAsString(msg);
        JsonNode tree = mapper.readTree(serialized);

        assertThat(tree.path("channel").asText()).isEqualTo("telegram");
        assertThat(tree.path("update_id").asText()).isEqualTo("10001");
        assertThat(tree.path("chat_id").asText()).isEqualTo("222222");
        assertThat(tree.path("user_id").asText()).isEqualTo("tg:111111");
        assertThat(tree.path("message_id").asText()).isEqualTo("101");
        assertThat(tree.path("kind").asText()).isEqualTo("text");
        assertThat(tree.has("received_at")).isTrue();
    }

    @Test
    @DisplayName("Handles empty update, null, or unsupported update gracefully")
    void testEmptyAndUnsupportedUpdates() throws Exception {
        assertThat(adapter.parseUpdate(null)).isEmpty();
        assertThat(adapter.parseCallbackQuery(null)).isEmpty();

        JsonNode emptyNode = mapper.readTree("{}");
        assertThat(adapter.parseUpdate(emptyNode)).isEmpty();
        assertThat(adapter.parseCallbackQuery(emptyNode)).isEmpty();

        // Unsupported sticker / video without text
        String stickerUpdate = """
            {
              "update_id": 99999,
              "message": {
                "message_id": 555,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "sticker": { "file_id": "sticker_id" }
              }
            }
            """;
        assertThat(adapter.parseUpdate(mapper.readTree(stickerUpdate))).isEmpty();

        // Missing required fields (e.g. chat id is missing)
        String missingChatUpdate = """
            {
              "update_id": 99998,
              "message": {
                "message_id": 556,
                "from": { "id": 111111, "is_bot": false },
                "date": 1729000000,
                "text": "Hello"
              }
            }
            """;
        assertThat(adapter.parseUpdate(mapper.readTree(missingChatUpdate))).isEmpty();
    }

    @Test
    @DisplayName("Parse legacy forward fields (forward_from, forward_sender_name, forward_from_chat)")
    void testLegacyForwardFields() throws Exception {
        // forward_from
        String forwardFrom = """
            {
              "update_id": 8801,
              "message": {
                "message_id": 601,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "text": "Fwd",
                "forward_from": { "id": 222, "first_name": "Bob" }
              }
            }
            """;
        assertThat(adapter.parseUpdate(mapper.readTree(forwardFrom)).get().forwardOrigin()).isEqualTo("user");

        // forward_sender_name
        String forwardSenderName = """
            {
              "update_id": 8802,
              "message": {
                "message_id": 602,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "text": "Fwd hidden",
                "forward_sender_name": "Hidden User"
              }
            }
            """;
        assertThat(adapter.parseUpdate(mapper.readTree(forwardSenderName)).get().forwardOrigin()).isEqualTo("hidden_user");

        // forward_from_chat non-channel
        String forwardFromGroup = """
            {
              "update_id": 8803,
              "message": {
                "message_id": 603,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "text": "Fwd group",
                "forward_from_chat": { "id": -555, "type": "supergroup" }
              }
            }
            """;
        assertThat(adapter.parseUpdate(mapper.readTree(forwardFromGroup)).get().forwardOrigin()).isEqualTo("chat");
    }

    @Test
    @DisplayName("BE-35: Detects .apk file by extension as kind='apk'")
    void testApkDocumentExtension() throws Exception {
        String apkUpdate = """
            {
              "update_id": 9001,
              "message": {
                "message_id": 701,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "document": {
                  "file_id": "apk_file_1",
                  "file_unique_id": "u_apk_1",
                  "file_name": "DichVuCong_Fake.apk",
                  "mime_type": "application/octet-stream",
                  "file_size": 15000000
                }
              }
            }
            """;
        InboundMessage msg = adapter.parseUpdate(mapper.readTree(apkUpdate)).orElseThrow();
        assertThat(msg.kind()).isEqualTo("apk");
        assertThat(msg.media()).isNotNull();
        assertThat(msg.media().fileName()).isEqualTo("DichVuCong_Fake.apk");

        // Biến thể bundle Android (.xapk, .aab)
        String xapkUpdate = apkUpdate.replace("DichVuCong_Fake.apk", "DichVuCong_Fake.XAPK");
        assertThat(adapter.parseUpdate(mapper.readTree(xapkUpdate)).orElseThrow().kind()).isEqualTo("apk");
    }

    @Test
    @DisplayName("BE-35: Detects .apk file by MIME application/vnd.android.package-archive as kind='apk'")
    void testApkDocumentMime() throws Exception {
        String apkUpdate = """
            {
              "update_id": 9002,
              "message": {
                "message_id": 702,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "document": {
                  "file_id": "apk_file_2",
                  "file_unique_id": "u_apk_2",
                  "file_name": "app_update",
                  "mime_type": "application/vnd.android.package-archive",
                  "file_size": 12000000
                }
              }
            }
            """;
        InboundMessage msg = adapter.parseUpdate(mapper.readTree(apkUpdate)).orElseThrow();
        assertThat(msg.kind()).isEqualTo("apk");
    }

    @Test
    @DisplayName("BE-35: Document with image MIME or image extension is mapped to kind='photo'")
    void testImageDocumentMappedToPhoto() throws Exception {
        String imgMimeDoc = """
            {
              "update_id": 9003,
              "message": {
                "message_id": 703,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "document": {
                  "file_id": "img_doc_1",
                  "file_unique_id": "u_img_1",
                  "file_name": "uncompressed_evidence.png",
                  "mime_type": "image/png",
                  "file_size": 2500000
                }
              }
            }
            """;
        InboundMessage msg1 = adapter.parseUpdate(mapper.readTree(imgMimeDoc)).orElseThrow();
        assertThat(msg1.kind()).isEqualTo("photo");

        String imgExtDoc = """
            {
              "update_id": 9004,
              "message": {
                "message_id": 704,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "document": {
                  "file_id": "img_doc_2",
                  "file_unique_id": "u_img_2",
                  "file_name": "chup_man_hinh.JPG",
                  "mime_type": "application/octet-stream",
                  "file_size": 1800000
                }
              }
            }
            """;
        InboundMessage msg2 = adapter.parseUpdate(mapper.readTree(imgExtDoc)).orElseThrow();
        assertThat(msg2.kind()).isEqualTo("photo");
    }

    @Test
    @DisplayName("BE-35: Detects video, location, contact, animation as kind='unsupported_media'")
    void testUnsupportedMediaTypes() throws Exception {
        String videoUpdate = """
            {
              "update_id": 9005,
              "message": {
                "message_id": 705,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "video": { "file_id": "v1", "file_unique_id": "uv1", "duration": 15 }
              }
            }
            """;
        InboundMessage msg1 = adapter.parseUpdate(mapper.readTree(videoUpdate)).orElseThrow();
        assertThat(msg1.kind()).isEqualTo("unsupported_media");

        String locUpdate = """
            {
              "update_id": 9006,
              "message": {
                "message_id": 706,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "location": { "latitude": 21.0285, "longitude": 105.8542 }
              }
            }
            """;
        InboundMessage msg2 = adapter.parseUpdate(mapper.readTree(locUpdate)).orElseThrow();
        assertThat(msg2.kind()).isEqualTo("unsupported_media");

        String contactUpdate = """
            {
              "update_id": 9007,
              "message": {
                "message_id": 707,
                "from": { "id": 111111, "is_bot": false },
                "chat": { "id": 222222, "type": "private" },
                "date": 1729000000,
                "contact": { "phone_number": "0912345678", "first_name": "Test" }
              }
            }
            """;
        InboundMessage msg3 = adapter.parseUpdate(mapper.readTree(contactUpdate)).orElseThrow();
        assertThat(msg3.kind()).isEqualTo("unsupported_media");
    }
}
