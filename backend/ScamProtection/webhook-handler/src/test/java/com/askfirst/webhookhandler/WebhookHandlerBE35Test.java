package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.GuardianRecord;
import com.askfirst.common.store.GuardianRepo;
import com.askfirst.common.store.RateLimitRepo;
import com.askfirst.common.store.UserRecord;
import com.askfirst.common.store.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho BE-35: Đầu vào không hỗ trợ và tệp .apk (ARCH §5.7, FRS-022, docs/features.md FR15).
 *
 * Kiểm tra các tiêu chí:
 * 1. Chặn file .apk độc hại ngay lập tức (zero download, zero SQS enqueue).
 * 2. Phát cảnh báo đỏ khẩn cấp cho người cao tuổi.
 * 3. Kích hoạt cảnh báo khẩn cấp cho con cháu nếu đã kết nối người thân.
 * 4. Xử lý các định dạng tệp không hỗ trợ (PDF, ZIP, Video, Location, Contact, Audio, Sticker)
 *    với thông điệp hướng dẫn chụp màn hình hoặc gửi lại.
 * 5. Tài liệu dạng ảnh (image/*) được chuyển tiếp xử lý như ảnh bình thường.
 */
class WebhookHandlerBE35Test {

    private static final String SECRET = "test-secret-be35";
    private static final String POLICY_SHA256 = "sha256-policy-be35";
    private static final String ELDER_UID = "tg:111111";
    private static final long ELDER_CHAT_ID = 111111L;
    private static final String GUARDIAN_1_UID = "tg:222222";
    private static final long GUARDIAN_1_CHAT_ID = 222222L;
    private static final String GUARDIAN_2_UID = "tg:333333";
    private static final long GUARDIAN_2_CHAT_ID = 333333L;

    private FakeTelegramClient telegramClient;
    private FakeSqsClient sqsClient;
    private FakeUserRepo userRepo;
    private FakeGuardianRepo guardianRepo;
    private FakeAuditRepo auditRepo;
    private FakeRateLimitRepo rateLimitRepo;
    private WebhookHandler handler;

    @BeforeEach
    void setUp() {
        telegramClient = new FakeTelegramClient();
        sqsClient = new FakeSqsClient();
        userRepo = new FakeUserRepo();
        guardianRepo = new FakeGuardianRepo();
        auditRepo = new FakeAuditRepo();
        rateLimitRepo = new FakeRateLimitRepo();

        handler = new WebhookHandler(
                telegramClient,
                sqsClient,
                "https://sqs.mock.queue/jobs",
                SECRET,
                userRepo,
                auditRepo,
                rateLimitRepo,
                guardianRepo,
                null,
                POLICY_SHA256,
                null,
                false,
                "AskFirstBot");
    }

    // ── Test 1: Tệp .apk khi chưa có người thân kết nối ────────────────────────
    @Test
    @DisplayName("BE-35: Gửi file .apk khi chưa có người thân -> Cảnh báo đỏ cho người cao tuổi, không SQS")
    void testApkWithoutGuardians() {
        String updateJson = buildDocumentUpdateJson(ELDER_CHAT_ID, "DichVuCong_Gia.apk", "application/vnd.android.package-archive");
        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("APK_BLOCKED", res.getBody());

        // Zero SQS messages
        assertEquals(0, sqsClient.enqueuedMessages.size(), "Tệp APK tuyệt đối không được đưa vào SQS");

        // Tin nhắn cảnh báo đỏ cho người cao tuổi
        assertEquals(1, telegramClient.sentMessages.size());
        FakeTelegramClient.SentMsg sent = telegramClient.sentMessages.get(0);
        assertEquals(ELDER_CHAT_ID, sent.chatId());
        assertTrue(sent.text().contains("CẢNH BÁO NGUY HIỂM"), "Phải có tiêu đề CẢNH BÁO NGUY HIỂM");
        assertTrue(sent.text().contains("TUYỆT ĐỐI KHÔNG BẤM CÀI ĐẶT"), "Phải có hướng dẫn tuyệt đối không cài đặt");
        assertTrue(sent.text().contains("Bác"), "Mặc định xưng hô Bác");

        // Ghi nhận Audit Log
        assertEquals(1, auditRepo.events.size());
        assertEquals("APK_BLOCKED", auditRepo.events.get(0).event());
        assertTrue(auditRepo.events.get(0).details().contains("DichVuCong_Gia.apk"));
    }

    // ── Test 2: Tệp .apk khi đã kết nối người thân (active & revoked) ──────────
    @Test
    @DisplayName("BE-35: Gửi file .apk khi đã liên kết người thân -> Cảnh báo khẩn cấp cho cả người thân active")
    void testApkWithGuardians() {
        // Gắn 2 người thân: 1 active, 1 revoked
        GuardianRecord activeGuardian = new GuardianRecord(ELDER_UID, GUARDIAN_1_UID);
        activeGuardian.setStatus("active");
        guardianRepo.save(activeGuardian);

        GuardianRecord revokedGuardian = new GuardianRecord(ELDER_UID, GUARDIAN_2_UID);
        revokedGuardian.setStatus("revoked");
        guardianRepo.save(revokedGuardian);

        String updateJson = buildDocumentUpdateJson(ELDER_CHAT_ID, "VNeID_CapNhat.apk", "application/octet-stream");
        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("APK_BLOCKED", res.getBody());

        // Zero SQS messages
        assertEquals(0, sqsClient.enqueuedMessages.size());

        // Phải có 2 tin nhắn: 1 cho người cao tuổi, 1 cho người thân active
        assertEquals(2, telegramClient.sentMessages.size(), "Phải gửi 1 tin cho người già và 1 tin cho người thân active");

        // Tin 1: Người già
        FakeTelegramClient.SentMsg elderMsg = telegramClient.sentMessages.stream()
                .filter(m -> m.chatId() == ELDER_CHAT_ID)
                .findFirst().orElseThrow();
        assertTrue(elderMsg.text().contains("CẢNH BÁO NGUY HIỂM"));

        // Tin 2: Người thân active
        FakeTelegramClient.SentMsg guardianMsg = telegramClient.sentMessages.stream()
                .filter(m -> m.chatId() == GUARDIAN_1_CHAT_ID)
                .findFirst().orElseThrow();
        assertTrue(guardianMsg.text().contains("CẢNH BÁO KHẨN CẤP TỪ ASKFIRST"));
        assertTrue(guardianMsg.text().contains("Hãy liên hệ ngay với người thân"));

        // Người thân revoked không nhận được tin nào
        boolean revokedReceived = telegramClient.sentMessages.stream().anyMatch(m -> m.chatId() == GUARDIAN_2_CHAT_ID);
        assertFalse(revokedReceived, "Người thân đã huỷ kết nối không được nhận cảnh báo");
    }

    // ── Test 3: Tệp .apk với xưng hô tùy chỉnh ─────────────────────────────────
    @Test
    @DisplayName("BE-35: Gửi file .apk với xưng hô tuỳ chỉnh (Cô) -> cảnh báo dùng đúng xưng hô")
    void testApkWithCustomAddressForm() {
        userRepo.updateAddressForm(ELDER_UID, "Cô");

        GuardianRecord guardian = new GuardianRecord(ELDER_UID, GUARDIAN_1_UID);
        guardian.setStatus("active");
        guardianRepo.save(guardian);

        String updateJson = buildDocumentUpdateJson(ELDER_CHAT_ID, "ThueDienTu.apk", "application/octet-stream");
        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("APK_BLOCKED", res.getBody());

        // Tin nhắn cho người cao tuổi dùng "Cô"
        FakeTelegramClient.SentMsg elderMsg = telegramClient.sentMessages.stream()
                .filter(m -> m.chatId() == ELDER_CHAT_ID)
                .findFirst().orElseThrow();
        assertTrue(elderMsg.text().contains("Cô <b>TUYỆT ĐỐI KHÔNG BẤM CÀI ĐẶT</b>"));

        // Tin nhắn cho người thân ghi rõ "Người thân của bạn (Cô)"
        FakeTelegramClient.SentMsg guardianMsg = telegramClient.sentMessages.stream()
                .filter(m -> m.chatId() == GUARDIAN_1_CHAT_ID)
                .findFirst().orElseThrow();
        assertTrue(guardianMsg.text().contains("Người thân của bạn (Cô)"));
    }

    // ── Test 4: Tệp .apk từ người dùng CHƯA đồng ý điều khoản ──────────────────
    @Test
    @DisplayName("BE-35: Người dùng chưa đồng ý điều khoản gửi .apk -> Vẫn phát cảnh báo đỏ ngay lập tức")
    void testApkUnconsentedUser() {
        // Chưa có consent trong userRepo
        String updateJson = buildDocumentUpdateJson(ELDER_CHAT_ID, "TroCapXaHoi.apk", "application/octet-stream");
        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        // Vẫn phải chặn APK và gửi cảnh báo đỏ khẩn cấp thay vì chặn consent
        assertEquals(200, res.getStatusCode());
        assertEquals("APK_BLOCKED", res.getBody());
        assertEquals(1, telegramClient.sentMessages.size());
        assertTrue(telegramClient.sentMessages.get(0).text().contains("CẢNH BÁO NGUY HIỂM"));
        assertEquals(0, sqsClient.enqueuedMessages.size());
    }

    // ── Test 5: Tài liệu PDF không hỗ trợ ─────────────────────────────────────
    @Test
    @DisplayName("BE-35: Gửi file PDF -> Phản hồi hướng dẫn mở file và chụp màn hình, không SQS")
    void testUnsupportedDocumentPdf() {
        String updateJson = buildDocumentUpdateJson(ELDER_CHAT_ID, "ThongBaoTienDien.pdf", "application/pdf");
        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("UNSUPPORTED_DOCUMENT", res.getBody());
        assertEquals(0, sqsClient.enqueuedMessages.size(), "PDF không được đẩy vào SQS");

        assertEquals(1, telegramClient.sentMessages.size());
        String reply = telegramClient.sentMessages.get(0).text();
        assertTrue(reply.contains("chưa hỗ trợ đọc trực tiếp định dạng tệp này"));
        assertTrue(reply.contains("CHỤP ẢNH MÀN HÌNH"));
    }

    // ── Test 6: Tài liệu ZIP không hỗ trợ ─────────────────────────────────────
    @Test
    @DisplayName("BE-35: Gửi file ZIP -> Phản hồi hướng dẫn chụp màn hình, không SQS")
    void testUnsupportedDocumentZip() {
        String updateJson = buildDocumentUpdateJson(ELDER_CHAT_ID, "HoSoBoSung.zip", "application/zip");
        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("UNSUPPORTED_DOCUMENT", res.getBody());
        assertEquals(0, sqsClient.enqueuedMessages.size());
        assertTrue(telegramClient.sentMessages.get(0).text().contains("CHỤP ẢNH MÀN HÌNH"));
    }

    // ── Test 7: Tài liệu dạng ẢNH (image/png) được xử lý như ảnh thường ───────
    @Test
    @DisplayName("BE-35: Gửi ảnh dưới dạng document (image/png) -> Được nhận dạng là photo và xử lý bình thường")
    void testImageDocumentProcessedAsPhoto() {
        // Cho người dùng đã consent để xem luồng enqueue
        userRepo.updateConsent(ELDER_UID, POLICY_SHA256, "1.0", Map.of("analyze", true));

        String updateJson = buildDocumentUpdateJson(ELDER_CHAT_ID, "chung_tu_ngan_hang.png", "image/png");
        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("OK", res.getBody());

        // Đã được đưa vào SQS như ảnh bình thường
        assertEquals(1, sqsClient.enqueuedMessages.size(), "Ảnh gửi dạng file phải được đưa vào SQS để phân tích");
        assertTrue(telegramClient.sentMessages.get(0).text().contains("Cháu đang kiểm tra"));
    }

    // ── Test 8: Video không hỗ trợ ───────────────────────────────────────────
    @Test
    @DisplayName("BE-35: Gửi video -> Phản hồi hướng dẫn chụp màn hình hoặc gõ lại nội dung, không SQS")
    void testUnsupportedMediaVideo() {
        String updateJson = """
            {
              "update_id": 1008,
              "message": {
                "message_id": 8,
                "date": 1729000000,
                "chat": { "id": %d, "type": "private" },
                "from": { "id": %d, "is_bot": false, "first_name": "Nam" },
                "video": { "file_id": "vid_123", "file_unique_id": "uvid_123", "duration": 10 }
              }
            }
            """.formatted(ELDER_CHAT_ID, ELDER_CHAT_ID);

        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA", res.getBody());
        assertEquals(0, sqsClient.enqueuedMessages.size());

        String reply = telegramClient.sentMessages.get(0).text();
        assertTrue(reply.contains("chưa hỗ trợ kiểm tra trực tiếp video, vị trí hoặc danh bạ"));
        assertTrue(reply.contains("CHỤP ẢNH MÀN HÌNH"));
    }

    // ── Test 9: Vị trí (Location) không hỗ trợ ────────────────────────────────
    @Test
    @DisplayName("BE-35: Gửi location -> Phản hồi hướng dẫn thay thế, không SQS")
    void testUnsupportedMediaLocation() {
        String updateJson = """
            {
              "update_id": 1009,
              "message": {
                "message_id": 9,
                "date": 1729000000,
                "chat": { "id": %d, "type": "private" },
                "from": { "id": %d, "is_bot": false, "first_name": "Nam" },
                "location": { "latitude": 10.7769, "longitude": 106.7009 }
              }
            }
            """.formatted(ELDER_CHAT_ID, ELDER_CHAT_ID);

        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA", res.getBody());
        assertEquals(0, sqsClient.enqueuedMessages.size());
    }

    // ── Test 10: Danh bạ (Contact) không hỗ trợ ──────────────────────────────
    @Test
    @DisplayName("BE-35: Gửi contact -> Phản hồi hướng dẫn thay thế, không SQS")
    void testUnsupportedMediaContact() {
        String updateJson = """
            {
              "update_id": 1010,
              "message": {
                "message_id": 10,
                "date": 1729000000,
                "chat": { "id": %d, "type": "private" },
                "from": { "id": %d, "is_bot": false, "first_name": "Nam" },
                "contact": { "phone_number": "+84901234567", "first_name": "Cong An Gia" }
              }
            }
            """.formatted(ELDER_CHAT_ID, ELDER_CHAT_ID);

        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA", res.getBody());
        assertEquals(0, sqsClient.enqueuedMessages.size());
    }

    // ── Test 11: File âm thanh (Audio - MP3) không hỗ trợ ─────────────────────
    @Test
    @DisplayName("BE-35: Gửi file audio MP3 -> Phản hồi chưa hỗ trợ tệp âm thanh, không SQS")
    void testUnsupportedAudio() {
        String updateJson = """
            {
              "update_id": 1011,
              "message": {
                "message_id": 11,
                "date": 1729000000,
                "chat": { "id": %d, "type": "private" },
                "from": { "id": %d, "is_bot": false, "first_name": "Nam" },
                "audio": { "file_id": "aud_1", "file_unique_id": "uaud_1", "duration": 45, "file_name": "cuoc_goi.mp3" }
              }
            }
            """.formatted(ELDER_CHAT_ID, ELDER_CHAT_ID);

        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("UNSUPPORTED_AUDIO", res.getBody());
        assertEquals(0, sqsClient.enqueuedMessages.size());
        assertTrue(telegramClient.sentMessages.get(0).text().contains("chưa hỗ trợ kiểm tra tệp âm thanh"));
    }

    // ── Test 12: Sticker ─────────────────────────────────────────────────────
    @Test
    @DisplayName("BE-35: Gửi sticker -> Phản hồi lời chào thân mật và hướng dẫn gửi tin nhắn, không SQS")
    void testStickerReplied() {
        String updateJson = """
            {
              "update_id": 1012,
              "message": {
                "message_id": 12,
                "date": 1729000000,
                "chat": { "id": %d, "type": "private" },
                "from": { "id": %d, "is_bot": false, "first_name": "Nam" },
                "sticker": { "file_id": "stk_1", "file_unique_id": "ustk_1", "width": 512, "height": 512 }
              }
            }
            """.formatted(ELDER_CHAT_ID, ELDER_CHAT_ID);

        APIGatewayProxyResponseEvent res = handler.handleRequest(buildRequest(updateJson), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("STICKER_REPLIED", res.getBody());
        assertEquals(0, sqsClient.enqueuedMessages.size());
        assertTrue(telegramClient.sentMessages.get(0).text().contains("Cháu là trợ lý phòng chống lừa đảo"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String buildDocumentUpdateJson(long chatId, String fileName, String mimeType) {
        return """
            {
              "update_id": 2001,
              "message": {
                "message_id": 201,
                "date": 1729000000,
                "chat": { "id": %d, "type": "private" },
                "from": { "id": %d, "is_bot": false, "first_name": "Nam" },
                "document": {
                  "file_id": "doc_file_99",
                  "file_unique_id": "udoc_99",
                  "file_name": "%s",
                  "mime_type": "%s",
                  "file_size": 1024000
                }
              }
            }
            """.formatted(chatId, chatId, fileName, mimeType);
    }

    private APIGatewayProxyRequestEvent buildRequest(String body) {
        return new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", SECRET))
                .withBody(body);
    }

    private Context fakeContext() {
        return new Context() {
            @Override public String getAwsRequestId() { return "test-req"; }
            @Override public String getLogGroupName() { return "test-group"; }
            @Override public String getLogStreamName() { return "test-stream"; }
            @Override public String getFunctionName() { return "test-fn"; }
            @Override public String getFunctionVersion() { return "1"; }
            @Override public String getInvokedFunctionArn() { return "arn:test"; }
            @Override public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() { return null; }
            @Override public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() { return null; }
            @Override public int getRemainingTimeInMillis() { return 30_000; }
            @Override public int getMemoryLimitInMB() { return 512; }
            @Override public com.amazonaws.services.lambda.runtime.LambdaLogger getLogger() {
                return new com.amazonaws.services.lambda.runtime.LambdaLogger() {
                    @Override public void log(String message) { System.out.print(message); }
                    @Override public void log(byte[] message) { System.out.print(new String(message)); }
                };
            }
        };
    }

    // ── Mocks & In-memory Repos ───────────────────────────────────────────────
    static class FakeTelegramClient extends TelegramClient {
        record SentMsg(long chatId, String text) {}
        final List<SentMsg> sentMessages = new ArrayList<>();

        FakeTelegramClient() {
            super("fake:token");
        }

        @Override
        public int sendText(long chatId, String text) {
            sentMessages.add(new SentMsg(chatId, text));
            return 1;
        }

        @Override
        public int sendText(long chatId, String text, Map<String, Object> replyMarkup) {
            sentMessages.add(new SentMsg(chatId, text));
            return 1;
        }

        @Override
        public void sendMessage(long chatId, String text) {
            sentMessages.add(new SentMsg(chatId, text));
        }
    }

    static class FakeSqsClient implements SqsClient {
        final List<SendMessageRequest> enqueuedMessages = new ArrayList<>();

        @Override
        public SendMessageResponse sendMessage(SendMessageRequest req) {
            enqueuedMessages.add(req);
            return SendMessageResponse.builder().messageId("msg-" + UUID.randomUUID()).build();
        }

        @Override public String serviceName() { return "sqs"; }
        @Override public void close() {}
    }

    static class FakeUserRepo extends UserRepo {
        final Map<String, UserRecord> store = new ConcurrentHashMap<>();

        @Override
        public Optional<UserRecord> getUser(String userId) {
            return Optional.ofNullable(store.get(userId));
        }

        @Override
        public void updateConsent(String userId, String sha256, String version, Map<String, Boolean> purposes) {
            UserRecord u = store.computeIfAbsent(userId, id -> new UserRecord(id, "123"));
            u.setPolicySha256(sha256);
            u.setConsentVersion(version);
            u.setPurposes(purposes);
        }

        @Override
        public void updateAddressForm(String userId, String form) {
            UserRecord u = store.computeIfAbsent(userId, id -> new UserRecord(id, "123"));
            u.setAddressForm(form);
        }

        @Override
        public boolean hasValidConsent(String userId, String currentSha256) {
            UserRecord u = store.get(userId);
            return u != null && currentSha256.equals(u.getPolicySha256())
                    && u.getPurposes() != null
                    && Boolean.TRUE.equals(u.getPurposes().get("analyze"));
        }
    }

    static class FakeGuardianRepo extends GuardianRepo {
        final Map<String, GuardianRecord> store = new ConcurrentHashMap<>();

        @Override
        public void save(GuardianRecord record) {
            store.put(record.getElderUid() + "#" + record.getGuardianUid(), record);
        }

        @Override
        public List<GuardianRecord> listGuardians(String elderUid) {
            return store.values().stream()
                    .filter(r -> elderUid.equals(r.getElderUid()))
                    .toList();
        }
    }

    static class FakeAuditRepo extends AuditRepo {
        record AuditEntry(String userId, String event, String details) {}
        final List<AuditEntry> events = new ArrayList<>();

        @Override
        public void recordEvent(String userId, String event, String ver, String sha, String details) {
            events.add(new AuditEntry(userId, event, details));
        }
    }

    static class FakeRateLimitRepo extends RateLimitRepo {
        @Override
        public RateLimitResult checkAndIncrement(String userId) {
            return new RateLimitResult(true, 1L, 1L, "OK");
        }

        @Override
        public RateLimitResult checkAndIncrement(String userId, int maxPerHour, int maxPerDay) {
            return new RateLimitResult(true, 1L, 1L, "OK");
        }
    }
}
