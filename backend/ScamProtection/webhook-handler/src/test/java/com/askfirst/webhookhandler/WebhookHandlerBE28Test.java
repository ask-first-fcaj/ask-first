package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.GuardianRecord;
import com.askfirst.common.store.GuardianRepo;
import com.askfirst.common.store.InviteRecord;
import com.askfirst.common.store.InviteRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho BE-28: Luồng mời người thân 2 chiều (double opt-in) - ARCH
 * §5.4.
 *
 * Dùng fake in-memory repos và fake TelegramClient để test hoàn toàn độc lập,
 * không cần DynamoDB thật, không cần bot token thật.
 */
class WebhookHandlerBE28Test {

    private static final String SECRET = "test-secret-123";
    private static final String POLICY_SHA256 = "aabbccdd";
    private static final String ELDER_UID = "tg:111";
    private static final long ELDER_CHAT_ID = 111L;
    private static final String RELATIVE_UID = "tg:222";
    private static final long RELATIVE_CHAT_ID = 222L;

    // ── Fake in-memory repos ──────────────────────────────────────────────────
    private FakeGuardianRepo guardianRepo;
    private FakeInviteRepo inviteRepo;
    private FakeAuditRepo auditRepo;
    private FakeTelegramClient telegramClient;
    private WebhookHandler handler;

    @BeforeEach
    void setUp() {
        guardianRepo = new FakeGuardianRepo();
        inviteRepo = new FakeInviteRepo();
        auditRepo = new FakeAuditRepo();
        telegramClient = new FakeTelegramClient();

        handler = new WebhookHandler(
                telegramClient, null, null,
                SECRET, guardianRepo, inviteRepo, auditRepo, POLICY_SHA256);
    }

    // ── Test 1: /nguoithan khi chưa có người thân nào ────────────────────────
    @Test
    @DisplayName("BE-28: /nguoithan khi chưa có người thân → gửi nút [Tạo mã kết nối]")
    void testNguoiThanEmpty() {
        APIGatewayProxyRequestEvent req = buildMessageRequest(ELDER_CHAT_ID, "/nguoithan");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("COMMAND_HANDLED", res.getBody());
        // TelegramClient phải đã được gọi để hiển thị tin nhắn chào
        assertFalse(telegramClient.sentMessages.isEmpty(), "Bot phải gửi ít nhất 1 tin nhắn");
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("Danh sách người thân"), "Phải hiện tiêu đề danh sách");
    }

    // ── Test 2: Callback tạo mã mời ──────────────────────────────────────────
    @Test
    @DisplayName("BE-28: Bấm [Tạo mã kết nối] → sinh mã 6 chữ số và lưu InviteRepo")
    void testCreateInvite() {
        APIGatewayProxyRequestEvent req = buildCallbackRequest(ELDER_CHAT_ID, "guardian:create_invite");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("INVITE_CREATED", res.getBody());

        // Phải có đúng 1 mã trong InviteRepo
        assertEquals(1, inviteRepo.store.size(), "Phải có 1 mã mời được lưu");
        InviteRecord invite = inviteRepo.store.values().iterator().next();
        assertEquals(ELDER_UID, invite.getElderUid());
        assertEquals("pending", invite.getStatus());
        assertNotNull(invite.getCode(), "Mã không được null");
        assertTrue(invite.getCode().matches("\\d{6}"), "Mã phải đúng 6 chữ số");

        // Bot phải gửi tin nhắn chứa mã
        String msgText = telegramClient.sentMessages.get(0).text;
        assertTrue(msgText.contains(invite.getCode()), "Tin nhắn phải hiện mã mời");
        assertTrue(msgText.contains("/ketnoi"), "Tin nhắn phải hướng dẫn gõ /ketnoi");
    }

    // ── Test 3: /ketnoi mã không hợp lệ ─────────────────────────────────────
    @Test
    @DisplayName("BE-28: /ketnoi mã không tồn tại → báo không hợp lệ")
    void testKetNoiInvalidCode() {
        APIGatewayProxyRequestEvent req = buildMessageRequest(RELATIVE_CHAT_ID, "/ketnoi 999999");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("INVALID_INVITE", res.getBody());
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("không hợp lệ"), "Phải thông báo mã không hợp lệ");
    }

    // ── Test 4: /ketnoi mã đã hết hạn ────────────────────────────────────────
    @Test
    @DisplayName("BE-28: /ketnoi mã đã hết hạn → báo hết hạn")
    void testKetNoiExpiredCode() {
        // Tạo mã đã hết hạn (expiresAt = 1 giây trong quá khứ)
        InviteRecord expired = new InviteRecord("123456", ELDER_UID,
                Instant.now().getEpochSecond() - 1);
        expired.setStatus("pending");
        inviteRepo.store.put("INVITE#123456", expired);

        APIGatewayProxyRequestEvent req = buildMessageRequest(RELATIVE_CHAT_ID, "/ketnoi 123456");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("INVITE_EXPIRED", res.getBody());
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("hết hạn"), "Phải thông báo mã hết hạn");
    }

    // ── Test 5: /ketnoi mã đã dùng ───────────────────────────────────────────
    @Test
    @DisplayName("BE-28: /ketnoi mã đã được dùng → báo đã dùng")
    void testKetNoiAlreadyUsedCode() {
        InviteRecord used = new InviteRecord("123456", ELDER_UID,
                Instant.now().getEpochSecond() + 86400);
        used.setStatus("used");
        inviteRepo.store.put("INVITE#123456", used);

        APIGatewayProxyRequestEvent req = buildMessageRequest(RELATIVE_CHAT_ID, "/ketnoi 123456");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("INVITE_ALREADY_USED", res.getBody());
    }

    // ── Test 6: /ketnoi hợp lệ → gửi yêu cầu về người cao tuổi ─────────────
    @Test
    @DisplayName("BE-28: /ketnoi mã hợp lệ → đánh dấu used + tạo pending guardian + gửi yêu cầu cho người cao tuổi")
    void testKetNoiValidCode() {
        InviteRecord invite = new InviteRecord("123456", ELDER_UID,
                Instant.now().getEpochSecond() + 86400);
        invite.setStatus("pending");
        inviteRepo.store.put("INVITE#123456", invite);

        APIGatewayProxyRequestEvent req = buildMessageRequest(RELATIVE_CHAT_ID, "/ketnoi 123456");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("INVITE_REDEEMED", res.getBody());

        // Mã phải được đánh dấu là đã dùng
        assertEquals("used", inviteRepo.store.get("INVITE#123456").getStatus());
        assertEquals(RELATIVE_UID, inviteRepo.store.get("INVITE#123456").getUsedByUid());

        // Phải có bản ghi pending trong guardianRepo (issue #64)
        Optional<GuardianRecord> pendingGuardian = guardianRepo.getGuardian(ELDER_UID, RELATIVE_UID);
        assertTrue(pendingGuardian.isPresent(), "Phải tạo bản ghi guardian pending");
        assertEquals("pending", pendingGuardian.get().getStatus());

        // Phải có tin nhắn gửi đến người thân (xác nhận đã gửi yêu cầu)
        boolean relativeMsgSent = telegramClient.sentMessages.stream()
                .anyMatch(m -> m.chatId == RELATIVE_CHAT_ID);
        assertTrue(relativeMsgSent, "Bot phải gửi xác nhận về cho người thân");

        // Phải có tin nhắn gửi đến người cao tuổi (yêu cầu chọn mức) chứa tên con cháu (tránh duyệt mù)
        Optional<FakeTelegramClient.SentMsg> elderMsgOpt = telegramClient.sentMessages.stream()
                .filter(m -> m.chatId == ELDER_CHAT_ID)
                .findFirst();
        assertTrue(elderMsgOpt.isPresent(), "Bot phải gửi yêu cầu xác nhận đến người cao tuổi");
        String elderMsg = elderMsgOpt.get().text;
        assertTrue(elderMsg.contains("muốn kết nối với Bác"), "Tin nhắn phải thông báo tài khoản muốn kết nối");
        assertTrue(elderMsg.contains("Test"), "Tin nhắn phải chứa tên người gửi mã");

        // Phải có audit
        assertFalse(auditRepo.events.isEmpty(), "Phải có audit event");
        assertEquals("INVITE_REDEEMED", auditRepo.events.get(0).event);

        // Người thân tự gọi mã của chính elder không được
        assertNotEquals(ELDER_UID, RELATIVE_UID);
    }

    // ── Test 7: Người cao tuổi chấp nhận → lưu guardian + thông báo 2 chiều ─
    @Test
    @DisplayName("BE-28: Người cao tuổi chấp nhận Mức 1 → lưu guardian active + thông báo 2 chiều")
    void testGuardianAcceptLevel1() {
        // Cần có bản ghi pending trước đó
        GuardianRecord pending = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        pending.setStatus("pending");
        guardianRepo.save(pending);

        String data = "guardian:accept:" + RELATIVE_UID + ":1";
        APIGatewayProxyRequestEvent req = buildCallbackRequest(ELDER_CHAT_ID, data);
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("GUARDIAN_LINKED", res.getBody());

        // Phải lưu được guardian với đúng share_level và status active
        Optional<GuardianRecord> savedOpt = guardianRepo.getGuardian(ELDER_UID, RELATIVE_UID);
        assertTrue(savedOpt.isPresent(), "Phải có guardian record");
        GuardianRecord saved = savedOpt.get();
        assertEquals("active", saved.getStatus());
        assertEquals(1, saved.getShareLevel());

        // Phải gọi answerCallbackQuery
        assertFalse(telegramClient.answeredCallbacks.isEmpty(), "Phải trả lời callback query");

        // Thông báo đến cả 2 bên
        boolean elderNotified = telegramClient.sentMessages.stream()
                .anyMatch(m -> m.chatId == ELDER_CHAT_ID);
        boolean relativeNotified = telegramClient.sentMessages.stream()
                .anyMatch(m -> m.chatId == RELATIVE_CHAT_ID);
        assertTrue(elderNotified, "Người cao tuổi phải nhận thông báo kết nối thành công");
        assertTrue(relativeNotified, "Người thân phải nhận thông báo kết nối thành công");

        // Audit
        assertFalse(auditRepo.events.isEmpty());
        assertEquals("GUARDIAN_LINKED", auditRepo.events.get(0).event);
    }

    // ── Test 8: Người cao tuổi từ chối ───────────────────────────────────────
    @Test
    @DisplayName("BE-28: Người cao tuổi từ chối → xoá bản ghi pending, thông báo từ chối")
    void testGuardianReject() {
        // Cần có bản ghi pending trước đó
        GuardianRecord pending = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        pending.setStatus("pending");
        guardianRepo.save(pending);

        String data = "guardian:reject:" + RELATIVE_UID;
        APIGatewayProxyRequestEvent req = buildCallbackRequest(ELDER_CHAT_ID, data);
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("GUARDIAN_REJECTED", res.getBody());
        assertTrue(guardianRepo.store.isEmpty(), "Bản ghi pending phải bị xoá khi từ chối");
        assertFalse(telegramClient.answeredCallbacks.isEmpty(), "Phải trả lời callback query");
    }

    @Test
    @DisplayName("BE-28: Chấp nhận khi không có bản ghi pending → từ chối xử lý")
    void testGuardianAcceptWithoutPending() {
        String data = "guardian:accept:" + RELATIVE_UID + ":1";
        APIGatewayProxyRequestEvent req = buildCallbackRequest(ELDER_CHAT_ID, data);
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("GUARDIAN_NOT_PENDING", res.getBody());
        assertTrue(guardianRepo.store.isEmpty());
    }

    @Test
    @DisplayName("BE-28: Từ chối khi không có bản ghi pending → từ chối xử lý")
    void testGuardianRejectWithoutPending() {
        String data = "guardian:reject:" + RELATIVE_UID;
        APIGatewayProxyRequestEvent req = buildCallbackRequest(ELDER_CHAT_ID, data);
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("GUARDIAN_NOT_PENDING", res.getBody());
    }

    @Test
    @DisplayName("BE-28: Nút cũ bấm accept sau khi đã từ chối → không cho phép kết nối")
    void testGuardianAcceptAfterReject() {
        GuardianRecord pending = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        pending.setStatus("pending");
        guardianRepo.save(pending);

        // 1. Người cao tuổi từ chối
        APIGatewayProxyResponseEvent res1 = handler.handleRequest(
                buildCallbackRequest(ELDER_CHAT_ID, "guardian:reject:" + RELATIVE_UID), fakeContext());
        assertEquals("GUARDIAN_REJECTED", res1.getBody());

        // 2. Bấm nút chấp nhận cũ
        APIGatewayProxyResponseEvent res2 = handler.handleRequest(
                buildCallbackRequest(ELDER_CHAT_ID, "guardian:accept:" + RELATIVE_UID + ":1"), fakeContext());
        assertEquals("GUARDIAN_NOT_PENDING", res2.getBody());
        assertTrue(guardianRepo.store.isEmpty());
    }

    @Test
    @DisplayName("BE-28: Tất cả callback guardian:* phải gọi answerCallbackQuery để tránh xoay loading")
    void testAllGuardianCallbacksAnswerCallbackQuery() {
        // create_invite
        handler.handleRequest(buildCallbackRequest(ELDER_CHAT_ID, "guardian:create_invite"), fakeContext());
        assertFalse(telegramClient.answeredCallbacks.isEmpty(), "create_invite phải gọi answerCallbackQuery");
        telegramClient.answeredCallbacks.clear();

        // level picker
        handler.handleRequest(buildCallbackRequest(ELDER_CHAT_ID, "guardian:level:" + RELATIVE_UID), fakeContext());
        assertFalse(telegramClient.answeredCallbacks.isEmpty(), "level picker phải gọi answerCallbackQuery");
        telegramClient.answeredCallbacks.clear();

        // setlevel
        handler.handleRequest(buildCallbackRequest(ELDER_CHAT_ID, "guardian:setlevel:" + RELATIVE_UID + ":2"), fakeContext());
        assertFalse(telegramClient.answeredCallbacks.isEmpty(), "setlevel phải gọi answerCallbackQuery");
        telegramClient.answeredCallbacks.clear();

        // remove
        handler.handleRequest(buildCallbackRequest(ELDER_CHAT_ID, "guardian:remove:" + RELATIVE_UID), fakeContext());
        assertFalse(telegramClient.answeredCallbacks.isEmpty(), "remove phải gọi answerCallbackQuery");
    }

    // ── Test 9: Gỡ liên kết ──────────────────────────────────────────────────
    @Test
    @DisplayName("BE-28: Gỡ liên kết → xoá guardian record + audit")
    void testGuardianRemove() {
        // Tạo sẵn guardian
        GuardianRecord rec = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        rec.setStatus("active");
        guardianRepo.store.put(ELDER_UID + "|" + RELATIVE_UID, rec);

        String data = "guardian:remove:" + RELATIVE_UID;
        APIGatewayProxyRequestEvent req = buildCallbackRequest(ELDER_CHAT_ID, data);
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("GUARDIAN_REMOVED", res.getBody());
        assertTrue(guardianRepo.store.isEmpty(), "Guardian phải bị xoá");
        assertFalse(auditRepo.events.isEmpty());
        assertEquals("GUARDIAN_REMOVED", auditRepo.events.get(0).event);
    }

    // ── Test 10: /ketnoi không có mã → báo thiếu mã ─────────────────────────
    @Test
    @DisplayName("BE-28: /ketnoi thiếu mã → yêu cầu nhập mã")
    void testKetNoiMissingCode() {
        APIGatewayProxyRequestEvent req = buildMessageRequest(RELATIVE_CHAT_ID, "/ketnoi");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("CONNECT_NO_CODE", res.getBody());
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("/ketnoi"), "Phải hướng dẫn cú pháp");
    }

    // ── Test 11: /start g_<code> → tự động kết nối (deep link flow) ──────────
    @Test
    @DisplayName("BE-28: /start g_123456 → tự động xử lý như /ketnoi 123456")
    void testDeepLinkStartValid() {
        // Chuẩn bị mã mời hợp lệ
        InviteRecord invite = new InviteRecord("123456", ELDER_UID,
                Instant.now().getEpochSecond() + 86400);
        invite.setStatus("pending");
        inviteRepo.store.put("INVITE#123456", invite);

        // Người thân bấm deep link → Telegram gửi /start g_123456
        APIGatewayProxyRequestEvent req = buildMessageRequest(RELATIVE_CHAT_ID, "/start g_123456");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        // Phải xử lý y hệt /ketnoi 123456
        assertEquals(200, res.getStatusCode());
        assertEquals("INVITE_REDEEMED", res.getBody());
        assertEquals("used", inviteRepo.store.get("INVITE#123456").getStatus());
        assertEquals(RELATIVE_UID, inviteRepo.store.get("INVITE#123456").getUsedByUid());
    }

    // ── Test 12: /start g_<code> mã không hợp lệ ─────────────────────────────
    @Test
    @DisplayName("BE-28: /start g_999999 mã không tồn tại → báo không hợp lệ")
    void testDeepLinkStartInvalid() {
        APIGatewayProxyRequestEvent req = buildMessageRequest(RELATIVE_CHAT_ID, "/start g_999999");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("INVALID_INVITE", res.getBody());
    }

    // ── Test 13: Tạo mã khi có botUsername → nút share thay thế hướng dẫn thủ công
    // ──
    @Test
    @DisplayName("BE-28: Tạo mã với botUsername cấu hình → tin nhắn có deep link, không cần /ketnoi text")
    void testCreateInviteWithDeepLink() {
        // Tạo handler với botUsername
        WebhookHandler handlerWithBot = new WebhookHandler(
                telegramClient, null, null,
                SECRET, guardianRepo, inviteRepo, auditRepo, POLICY_SHA256, "AskFirstBot"
        );

        APIGatewayProxyRequestEvent req = buildCallbackRequest(ELDER_CHAT_ID, "guardian:create_invite");
        APIGatewayProxyResponseEvent res = handlerWithBot.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("INVITE_CREATED", res.getBody());

        // Mã vẫn được lưu
        assertEquals(1, inviteRepo.store.size());
        InviteRecord invite = inviteRepo.store.values().iterator().next();
        assertTrue(invite.getCode().matches("\\d{6}"), "Mã phải đúng 6 chữ số");

        // Tin nhắn phải chứa mã và mention đến deep link / share
        String msgText = telegramClient.sentMessages.get(0).text;
        assertTrue(msgText.contains(invite.getCode()), "Tin nhắn phải hiện mã mời");
        assertTrue(msgText.contains("bấm nút"), "Tin nhắn phải hướng dẫn bấm nút share");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private APIGatewayProxyRequestEvent buildMessageRequest(long chatId, String text) {
        String body = """
                {"update_id":1,"message":{"message_id":1,"date":1,"chat":{"id":%d,"type":"private"},
                "from":{"id":%d,"is_bot":false,"first_name":"Test"},"text":"%s"}}
                """.formatted(chatId, chatId, text);
        return new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", SECRET))
                .withBody(body);
    }

    private APIGatewayProxyRequestEvent buildCallbackRequest(long chatId, String data) {
        String body = """
                {"update_id":2,"callback_query":{"id":"cq1","from":{"id":%d,"is_bot":false,"first_name":"Test"},
                "message":{"message_id":10,"date":1,"chat":{"id":%d,"type":"private"},"text":"menu"},
                "data":"%s"}}
                """.formatted(chatId, chatId, data);
        return new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", SECRET))
                .withBody(body);
    }

    private Context fakeContext() {
        return new Context() {
            @Override
            public String getAwsRequestId() {
                return "test-req";
            }

            @Override
            public String getLogGroupName() {
                return "test-group";
            }

            @Override
            public String getLogStreamName() {
                return "test-stream";
            }

            @Override
            public String getFunctionName() {
                return "test-fn";
            }

            @Override
            public String getFunctionVersion() {
                return "1";
            }

            @Override
            public String getInvokedFunctionArn() {
                return "arn:test";
            }

            @Override
            public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() {
                return null;
            }

            @Override
            public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() {
                return null;
            }

            @Override
            public int getRemainingTimeInMillis() {
                return 30_000;
            }

            @Override
            public int getMemoryLimitInMB() {
                return 512;
            }

            @Override
            public com.amazonaws.services.lambda.runtime.LambdaLogger getLogger() {
                return new com.amazonaws.services.lambda.runtime.LambdaLogger() {
                    @Override
                    public void log(String message) {
                        System.out.print(message);
                    }

                    @Override
                    public void log(byte[] message) {
                        System.out.print(new String(message));
                    }
                };
            }
        };
    }

    // ══ Fake Repos ════════════════════════════════════════════════════════════
    static class FakeGuardianRepo extends GuardianRepo {
        final Map<String, GuardianRecord> store = new ConcurrentHashMap<>();

        @Override
        public void save(GuardianRecord r) {
            store.put(r.getPk() + "|" + r.getSk(), r);
        }

        @Override
        public Optional<GuardianRecord> getGuardian(String elderUid, String gUid) {
            return store.values().stream()
                    .filter(r -> (elderUid.equals(r.getElderUid()) || elderUid.equals(r.getPk())
                            || ("USER#" + elderUid).equals(r.getPk()))
                            && (gUid.equals(r.getGuardianUid()) || gUid.equals(r.getSk())
                                    || ("GUARDIAN#" + gUid).equals(r.getSk())))
                    .findFirst();
        }

        @Override
        public List<GuardianRecord> listGuardians(String elderUid) {
            return store.values().stream()
                    .filter(r -> elderUid.equals(r.getElderUid())
                            || ("USER#" + elderUid).equals(r.getPk())
                            || elderUid.equals(r.getPk()))
                    .toList();
        }

        @Override
        public void delete(String elderUid, String gUid) {
            store.entrySet()
                    .removeIf(e -> (elderUid.equals(e.getValue().getElderUid())
                            || ("USER#" + elderUid).equals(e.getValue().getPk()))
                            && (gUid.equals(e.getValue().getGuardianUid())
                                    || ("GUARDIAN#" + gUid).equals(e.getValue().getSk())));
        }

        @Override
        public void updateShareLevel(String elderUid, String gUid, int level) {
            getGuardian(elderUid, gUid).ifPresent(r -> r.setShareLevel(level));
        }
    }

    static class FakeInviteRepo extends InviteRepo {
        final Map<String, InviteRecord> store = new ConcurrentHashMap<>();

        @Override
        public void createInvite(String code, String elderUid, long ttl) {
            InviteRecord r = new InviteRecord(code, elderUid, ttl);
            r.setStatus("pending");
            store.put("INVITE#" + code, r);
        }

        @Override
        public Optional<InviteRecord> getInvite(String code) {
            String key = code.startsWith("INVITE#") ? code : "INVITE#" + code;
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public boolean markUsed(String code, String usedByUid) {
            String key = code.startsWith("INVITE#") ? code : "INVITE#" + code;
            InviteRecord r = store.get(key);
            if (r == null || !"pending".equals(r.getStatus()))
                return false;
            r.setStatus("used");
            r.setUsedByUid(usedByUid);
            return true;
        }
    }

    static class FakeAuditRepo extends AuditRepo {
        record AuditEntry(String userId, String event, String details) {
        }

        final List<AuditEntry> events = new ArrayList<>();

        @Override
        public void recordEvent(String userId, String event, String ver, String sha, String details) {
            events.add(new AuditEntry(userId, event, details));
        }
    }

    static class FakeTelegramClient extends TelegramClient {
        record SentMsg(long chatId, String text) {
        }

        final List<SentMsg> sentMessages = new ArrayList<>();
        final List<String> answeredCallbacks = new ArrayList<>();

        FakeTelegramClient() {
            super("fake:token");
        }

        @Override
        public void answerCallbackQuery(String callbackQueryId, String text) {
            answeredCallbacks.add(callbackQueryId + ":" + text);
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
}
