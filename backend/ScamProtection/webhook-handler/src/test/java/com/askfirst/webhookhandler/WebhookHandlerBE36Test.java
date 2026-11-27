package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.CheckRecord;
import com.askfirst.common.store.CheckRepo;
import com.askfirst.common.store.GuardianRecord;
import com.askfirst.common.store.GuardianRepo;
import com.askfirst.common.store.InviteRecord;
import com.askfirst.common.store.InviteRepo;
import com.askfirst.common.store.UserRecord;
import com.askfirst.common.store.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho BE-36: Quản lý trong chat (/nguoithan, /lichsu) - ARCH §5.9, features FR11.
 *
 * Kiểm tra:
 * 1. Lệnh /lichsu: hiển thị đúng phân loại, không có nội dung tin nhắn cũ, phân trang qua editMessageText.
 * 2. Phân quyền /lichsu: Người thân gõ /lichsu tuyệt đối không xem được lịch sử của người cao tuổi (BR-030).
 * 3. Lệnh /nguoithan: hiển thị theo vai trò (người cao tuổi quản lý người thân, người thân xem người mình bảo vệ).
 * 4. Các nút inline: Đổi mức, Tạm dừng / Bật lại, Gỡ, Ngừng nhận cảnh báo.
 * 5. Độ dài callback_data <= 64 bytes UTF-8 và không chứa PII.
 */
class WebhookHandlerBE36Test {

    private static final String SECRET = "test-secret-123";
    private static final String POLICY_SHA256 = "d1a8f9b2c3e4567890abcdef1234567890abcdef1234567890abcdef12345678";
    private static final String ELDER_UID = "tg:111111";
    private static final long ELDER_CHAT_ID = 111111L;
    private static final String RELATIVE_UID = "tg:222222";
    private static final long RELATIVE_CHAT_ID = 222222L;

    private FakeGuardianRepo guardianRepo;
    private FakeInviteRepo inviteRepo;
    private FakeAuditRepo auditRepo;
    private FakeCheckRepo checkRepo;
    private FakeUserRepo userRepo;
    private FakeTelegramClient telegramClient;
    private WebhookHandler handler;

    @BeforeEach
    void setUp() {
        guardianRepo = new FakeGuardianRepo();
        inviteRepo = new FakeInviteRepo();
        auditRepo = new FakeAuditRepo();
        checkRepo = new FakeCheckRepo();
        userRepo = new FakeUserRepo();
        telegramClient = new FakeTelegramClient();

        handler = new WebhookHandler(
                telegramClient, null, null, SECRET,
                userRepo, auditRepo, null, guardianRepo, inviteRepo,
                POLICY_SHA256, null, false, "AskFirstBot", checkRepo, null, null);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 1. Kiểm tra lệnh /lichsu
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BE-36: /lichsu khi chưa có lượt kiểm tra nào → thông báo chưa có lịch sử")
    void testLichSuEmpty() {
        APIGatewayProxyRequestEvent req = buildMessageRequest(ELDER_CHAT_ID, "/lichsu");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("HISTORY_EMPTY", res.getBody());
        assertFalse(telegramClient.sentMessages.isEmpty());
        String text = telegramClient.sentMessages.get(0).text;
        assertTrue(text.contains("Lịch sử kiểm tra tin nhắn"));
        assertTrue(text.contains("chưa có lượt kiểm tra tin nhắn nào"));
    }

    @Test
    @DisplayName("BE-36: /lichsu hiển thị đúng các mức rủi ro (🟢/🟡/🔴), kịch bản, và không chứa nội dung tin nhắn")
    void testLichSuWithRecordsAndFormat() {
        String sensitiveOriginalText = "Bác ơi chuyển gấp 50 triệu vào số tài khoản 12345678";

        checkRepo.put(new CheckRecord(
                "01CHK01", ELDER_UID, "verdict", "SAFE", 10,
                "ban_be", "Hỏi thăm thông thường", "full",
                List.of(), List.of(), List.of("text"), false,
                "2026-11-18T10:15:00Z", 0L));

        checkRepo.put(new CheckRecord(
                "01CHK02", ELDER_UID, "verdict", "SUSPICIOUS", 60,
                "gia_mao", "Giả danh nhân viên giao hàng", "full",
                List.of(), List.of(), List.of("text"), false,
                "2026-11-18T11:20:00Z", 0L));

        checkRepo.put(new CheckRecord(
                "01CHK03", ELDER_UID, "verdict", "DANGEROUS", 95,
                "ngan_hang", "Giả mạo ngân hàng đòi OTP", "full",
                List.of(), List.of(), List.of("text"), true,
                "2026-11-18T14:30:00Z", 0L));

        APIGatewayProxyRequestEvent req = buildMessageRequest(ELDER_CHAT_ID, "/lichsu");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("HISTORY_SENT", res.getBody());

        assertFalse(telegramClient.sentMessages.isEmpty());
        String text = telegramClient.sentMessages.get(0).text;

        // Kiểm tra biểu tượng mức độ rủi ro
        assertTrue(text.contains("🟢 Ít dấu hiệu"), "Phải có nhãn mức an toàn");
        assertTrue(text.contains("🟡 Cần cảnh giác"), "Phải có nhãn mức nghi ngờ");
        assertTrue(text.contains("🔴 Nguy hiểm"), "Phải có nhãn mức nguy hiểm");

        // Kiểm tra kịch bản
        assertTrue(text.contains("Hỏi thăm thông thường"));
        assertTrue(text.contains("Giả danh nhân viên giao hàng"));
        assertTrue(text.contains("Giả mạo ngân hàng đòi OTP"));

        // TUYỆT ĐỐI không chứa nội dung tin nhắn gốc
        assertFalse(text.contains(sensitiveOriginalText), "Lịch sử không được lưu hay hiển thị nội dung tin gốc");
        assertTrue(text.contains("tuyệt đối không lưu nội dung tin nhắn gốc"), "Phải có cam kết bảo mật");
    }

    @Test
    @DisplayName("/lichsu: mức LOW_RISK của AI hiện 'Chưa thấy dấu hiệu', kh rơi vào 'Cần cảnh giác'")
    void testLichSuLowRiskLabel() {
        checkRepo.put(new CheckRecord(
                "01CHK09", ELDER_UID, "verdict", "LOW_RISK", 5,
                "N0", "Tin bình thường", "full",
                List.of(), List.of(), List.of("text"), false,
                "2026-11-27T03:08:00Z", 0L));

        handler.handleRequest(buildMessageRequest(ELDER_CHAT_ID, "/lichsu"), fakeContext());

        String text = telegramClient.sentMessages.get(0).text;
        assertTrue(text.contains("⚪ Chưa thấy dấu hiệu"));
        assertFalse(text.contains("Cần cảnh giác"));
    }

    @Test
    @DisplayName("BE-36: /lichsu phân trang khi có nhiều hơn 10 bản ghi và cập nhật bằng editMessageText")
    void testLichSuPagination() {
        // Tạo 12 bản ghi kiểm tra
        for (int i = 1; i <= 12; i++) {
            String checkId = String.format("01CHK%02d", i);
            checkRepo.put(new CheckRecord(
                    checkId, ELDER_UID, "verdict", (i % 2 == 0 ? "SAFE" : "DANGEROUS"), 50,
                    "group", "Kịch bản số " + i, "full",
                    List.of(), List.of(), List.of("text"), false,
                    "2026-11-18T10:00:00Z", 0L));
        }

        // Lượt gõ lệnh /lichsu đầu tiên
        APIGatewayProxyRequestEvent req = buildMessageRequest(ELDER_CHAT_ID, "/lichsu");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());
        assertEquals(200, res.getStatusCode());

        FakeTelegramClient.SentMsg firstMsg = telegramClient.sentMessages.get(0);
        assertNotNull(firstMsg.replyMarkup, "Phải có inline keyboard khi có trang tiếp theo");

        // Trích xuất nút Xem thêm
        String callbackData = extractCallbackData(firstMsg.replyMarkup);
        assertNotNull(callbackData);
        assertTrue(callbackData.startsWith("h:"), "Callback data phân trang phải bắt đầu bằng h:");
        assertTrue(callbackData.length() <= 64, "callback_data phải <= 64 byte");

        // Bấm nút Xem thêm
        APIGatewayProxyRequestEvent cbReq = buildCallbackRequest(ELDER_CHAT_ID, 999, callbackData);
        APIGatewayProxyResponseEvent cbRes = handler.handleRequest(cbReq, fakeContext());
        assertEquals(200, cbRes.getStatusCode());
        assertEquals("HISTORY_PAGE_LOADED", cbRes.getBody());

        // Phải cập nhật bằng editText (editMessageText) vào messageId 999
        assertFalse(telegramClient.editedMessages.isEmpty(), "Phải gọi editMessageText để sang trang");
        FakeTelegramClient.EditedMsg edited = telegramClient.editedMessages.get(0);
        assertEquals(999, edited.messageId);
        assertTrue(edited.text.contains("tiếp theo"), "Phải là nội dung trang tiếp theo");
        assertFalse(telegramClient.answeredCallbacks.isEmpty(), "Phải gọi answerCallbackQuery");
    }

    @Test
    @DisplayName("BE-36: [BẢO MẬT & BR-030] Người thân gõ /lichsu không thể thấy lịch sử của người cao tuổi")
    void testRelativeCannotSeeElderHistory() {
        // Người cao tuổi có các lượt kiểm tra
        checkRepo.put(new CheckRecord(
                "01CHK99", ELDER_UID, "verdict", "DANGEROUS", 90,
                "ngan_hang", "Tin mật của người cao tuổi", "full",
                List.of(), List.of(), List.of("text"), true,
                "2026-11-18T10:00:00Z", 0L));

        // Người thân liên kết bảo vệ người cao tuổi
        GuardianRecord link = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        link.setName("Bác Nam");
        link.setStatus("active");
        guardianRepo.save(link);

        // Người thân (RELATIVE_CHAT_ID) gõ lệnh /lichsu
        APIGatewayProxyRequestEvent req = buildMessageRequest(RELATIVE_CHAT_ID, "/lichsu");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("HISTORY_EMPTY", res.getBody());

        assertFalse(telegramClient.sentMessages.isEmpty());
        String msg = telegramClient.sentMessages.get(0).text;

        // Tuyệt đối không có kịch bản của người cao tuổi
        assertFalse(msg.contains("Tin mật của người cao tuổi"), "Người thân không được thấy lịch sử của người cao tuổi");
        assertTrue(msg.contains("không thể xem lịch sử kiểm tra tin nhắn của người thân mà mình đang bảo vệ"),
                "Phải nhắc nhở quy định bảo vệ quyền riêng tư");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. Kiểm tra lệnh /nguoithan theo vai trò & các nút điều khiển
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BE-36: /nguoithan cho người cao tuổi hiển thị trạng thái hoạt động/tạm dừng và các nút [Tạm dừng], [Bật lại], [Đổi mức], [Gỡ]")
    void testNguoiThanElderView() {
        GuardianRecord activeGuardian = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        activeGuardian.setName("Con gái");
        activeGuardian.setStatus("active");
        activeGuardian.setShareLevel(1);
        guardianRepo.save(activeGuardian);

        GuardianRecord pausedGuardian = new GuardianRecord(ELDER_UID, "tg:333333");
        pausedGuardian.setName("Con trai");
        pausedGuardian.setStatus("paused");
        pausedGuardian.setShareLevel(2);
        guardianRepo.save(pausedGuardian);

        APIGatewayProxyRequestEvent req = buildMessageRequest(ELDER_CHAT_ID, "/nguoithan");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());
        assertEquals(200, res.getStatusCode());

        String text = telegramClient.sentMessages.get(0).text;
        assertTrue(text.contains("Con gái"));
        assertTrue(text.contains("✅ Đang hoạt động (Mức 1)"));
        assertTrue(text.contains("Con trai"));
        assertTrue(text.contains("⏸ Tạm dừng (Mức 2)"));

        Map<String, Object> markup = telegramClient.sentMessages.get(0).replyMarkup;
        assertNotNull(markup);

        List<String> callbacks = extractAllCallbacks(markup);
        assertTrue(callbacks.contains("g:tog:" + RELATIVE_UID), "Phải có nút tạm dừng cho Con gái đang active");
        assertTrue(callbacks.contains("g:tog:tg:333333"), "Phải có nút bật lại cho Con trai đang paused");
        assertTrue(callbacks.contains("g:lvl:" + RELATIVE_UID), "Phải có nút đổi mức");
        assertTrue(callbacks.contains("g:rm:" + RELATIVE_UID), "Phải có nút gỡ");
        assertTrue(callbacks.contains("guardian:create_invite"), "Phải có nút tạo mã kết nối mới");
    }

    @Test
    @DisplayName("BE-36: Callback [Tạm dừng / Bật lại] chuyển đổi trạng thái active <-> paused")
    void testToggleStatusCallback() {
        GuardianRecord rec = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        rec.setStatus("active");
        guardianRepo.save(rec);

        // Bấm tạm dừng
        APIGatewayProxyRequestEvent req1 = buildCallbackRequest(ELDER_CHAT_ID, 100, "g:tog:" + RELATIVE_UID);
        APIGatewayProxyResponseEvent res1 = handler.handleRequest(req1, fakeContext());
        assertEquals(200, res1.getStatusCode());
        assertEquals("GUARDIAN_STATUS_TOGGLED", res1.getBody());
        assertEquals("paused", guardianRepo.getGuardian(ELDER_UID, RELATIVE_UID).get().getStatus());

        // Bấm bật lại
        APIGatewayProxyRequestEvent req2 = buildCallbackRequest(ELDER_CHAT_ID, 101, "g:tog:" + RELATIVE_UID);
        APIGatewayProxyResponseEvent res2 = handler.handleRequest(req2, fakeContext());
        assertEquals(200, res2.getStatusCode());
        assertEquals("GUARDIAN_STATUS_TOGGLED", res2.getBody());
        assertEquals("active", guardianRepo.getGuardian(ELDER_UID, RELATIVE_UID).get().getStatus());
    }

    @Test
    @DisplayName("BE-36: Callback [Đổi mức] gửi picker và lưu mức mới thành công")
    void testChangeShareLevelFlow() {
        GuardianRecord rec = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        rec.setStatus("active");
        rec.setShareLevel(1);
        guardianRepo.save(rec);

        // Bấm nút đổi mức
        APIGatewayProxyRequestEvent req1 = buildCallbackRequest(ELDER_CHAT_ID, 200, "g:lvl:" + RELATIVE_UID);
        APIGatewayProxyResponseEvent res1 = handler.handleRequest(req1, fakeContext());
        assertEquals(200, res1.getStatusCode());
        assertEquals("LEVEL_PICKER_SENT", res1.getBody());

        // Bấm chọn Mức 3
        APIGatewayProxyRequestEvent req2 = buildCallbackRequest(ELDER_CHAT_ID, 201, "g:set:" + RELATIVE_UID + ":3");
        APIGatewayProxyResponseEvent res2 = handler.handleRequest(req2, fakeContext());
        assertEquals(200, res2.getStatusCode());
        assertEquals("SHARE_LEVEL_UPDATED", res2.getBody());

        // Kiểm tra trong repo
        assertEquals(3, guardianRepo.getGuardian(ELDER_UID, RELATIVE_UID).get().getShareLevel());
    }

    @Test
    @DisplayName("BE-36: /nguoithan cho vai trò Người thân hiển thị danh sách người mình bảo vệ và nút [Ngừng nhận cảnh báo]")
    void testNguoiThanWardViewAndStop() {
        GuardianRecord ward = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        ward.setName("Bác Ba (Ba của tôi)");
        ward.setStatus("active");
        guardianRepo.save(ward);

        // Người thân gọi /nguoithan
        APIGatewayProxyRequestEvent req = buildMessageRequest(RELATIVE_CHAT_ID, "/nguoithan");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());
        assertEquals(200, res.getStatusCode());

        String text = telegramClient.sentMessages.get(0).text;
        assertTrue(text.contains("Người cao tuổi bạn đang bảo vệ"), "Phải có tiêu đề cho người thân");
        assertTrue(text.contains("Bác Ba (Ba của tôi)"));

        Map<String, Object> markup = telegramClient.sentMessages.get(0).replyMarkup;
        List<String> callbacks = extractAllCallbacks(markup);
        assertTrue(callbacks.contains("g:stop:" + ELDER_UID), "Phải có callback ngừng nhận cảnh báo");

        // Người thân bấm [Ngừng nhận cảnh báo]
        APIGatewayProxyRequestEvent stopReq = buildCallbackRequest(RELATIVE_CHAT_ID, 300, "g:stop:" + ELDER_UID);
        APIGatewayProxyResponseEvent stopRes = handler.handleRequest(stopReq, fakeContext());
        assertEquals(200, stopRes.getStatusCode());
        assertEquals("GUARDIAN_STOPPED", stopRes.getBody());

        // Kiểm tra liên kết đã được xoá
        assertTrue(guardianRepo.getGuardian(ELDER_UID, RELATIVE_UID).isEmpty());
    }

    @Test
    @DisplayName("BE-36: Người dùng đóng cả 2 vai trò (role=both) thấy cả 2 phần trong cùng một tin nhắn")
    void testBothRolesView() {
        // ELDER_UID vừa có người bảo vệ mình (RELATIVE_UID)
        GuardianRecord g = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        g.setName("Con");
        guardianRepo.save(g);

        // ELDER_UID đồng thời cũng bảo vệ một người cao tuổi khác (tg:999999)
        GuardianRecord w = new GuardianRecord("tg:999999", ELDER_UID);
        w.setName("Bác Hàng Xóm");
        guardianRepo.save(w);

        APIGatewayProxyRequestEvent req = buildMessageRequest(ELDER_CHAT_ID, "/nguoithan");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());
        assertEquals(200, res.getStatusCode());

        String text = telegramClient.sentMessages.get(0).text;
        assertTrue(text.contains("Người thân bảo vệ Bác"), "Phải có phần người thân bảo vệ");
        assertTrue(text.contains("Người cao tuổi bạn đang bảo vệ"), "Phải có phần người mình đang bảo vệ");
        assertTrue(text.contains("Con"));
        assertTrue(text.contains("Bác Hàng Xóm"));
    }

    @Test
    @DisplayName("BE-36: Toàn bộ callback_data sinh ra đều <= 64 bytes UTF-8 và không chứa PII")
    void testCallbackDataConstraints() {
        GuardianRecord g = new GuardianRecord(ELDER_UID, RELATIVE_UID);
        g.setName("Nguyễn Văn A");
        guardianRepo.save(g);

        GuardianRecord w = new GuardianRecord("tg:888888", ELDER_UID);
        w.setName("Trần Thị B");
        guardianRepo.save(w);

        APIGatewayProxyRequestEvent req = buildMessageRequest(ELDER_CHAT_ID, "/nguoithan");
        handler.handleRequest(req, fakeContext());

        Map<String, Object> markup = telegramClient.sentMessages.get(0).replyMarkup;
        List<String> callbacks = extractAllCallbacks(markup);

        for (String cb : callbacks) {
            byte[] bytes = cb.getBytes(StandardCharsets.UTF_8);
            assertTrue(bytes.length <= 64, "Callback data '" + cb + "' vượt quá 64 bytes (" + bytes.length + ")");
            assertFalse(cb.contains("Nguyễn Văn A"), "Callback data không được chứa tên người");
            assertFalse(cb.contains("Trần Thị B"), "Callback data không được chứa tên người");
            assertFalse(cb.contains("@"), "Callback data không được chứa email");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static APIGatewayProxyRequestEvent buildMessageRequest(long chatId, String text) {
        APIGatewayProxyRequestEvent req = new APIGatewayProxyRequestEvent();
        req.setHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", SECRET));
        String body = """
                {
                  "update_id": %d,
                  "message": {
                    "message_id": 100,
                    "from": {"id": %d, "is_bot": false, "first_name": "Test"},
                    "chat": {"id": %d, "type": "private"},
                    "text": "%s"
                  }
                }
                """.formatted(System.currentTimeMillis() % 1000000, chatId, chatId, text);
        req.setBody(body);
        return req;
    }

    private static APIGatewayProxyRequestEvent buildCallbackRequest(long chatId, int messageId, String data) {
        APIGatewayProxyRequestEvent req = new APIGatewayProxyRequestEvent();
        req.setHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", SECRET));
        String body = """
                {
                  "update_id": %d,
                  "callback_query": {
                    "id": "cb_%d",
                    "from": {"id": %d, "is_bot": false, "first_name": "Test"},
                    "message": {
                      "message_id": %d,
                      "chat": {"id": %d, "type": "private"}
                    },
                    "data": "%s"
                  }
                }
                """.formatted(System.currentTimeMillis() % 1000000, System.currentTimeMillis() % 1000, chatId, messageId, chatId, data);
        req.setBody(body);
        return req;
    }

    @SuppressWarnings("unchecked")
    private static String extractCallbackData(Map<String, Object> replyMarkup) {
        List<List<Map<String, String>>> keyboard = (List<List<Map<String, String>>>) replyMarkup.get("inline_keyboard");
        if (keyboard == null || keyboard.isEmpty()) return null;
        for (List<Map<String, String>> row : keyboard) {
            for (Map<String, String> button : row) {
                if (button.containsKey("callback_data")) {
                    return button.get("callback_data");
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> extractAllCallbacks(Map<String, Object> replyMarkup) {
        List<String> list = new ArrayList<>();
        List<List<Map<String, String>>> keyboard = (List<List<Map<String, String>>>) replyMarkup.get("inline_keyboard");
        if (keyboard != null) {
            for (List<Map<String, String>> row : keyboard) {
                for (Map<String, String> button : row) {
                    if (button.containsKey("callback_data")) {
                        list.add(button.get("callback_data"));
                    }
                }
            }
        }
        return list;
    }

    private static Context fakeContext() {
        return new Context() {
            @Override public String getAwsRequestId() { return "test-id"; }
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

    // ── Fake Repos ────────────────────────────────────────────────────────────

    static class FakeCheckRepo implements CheckRepo {
        final Map<String, CheckRecord> store = new ConcurrentHashMap<>();

        @Override
        public boolean exists(String userId, String checkId) {
            return store.containsKey(userId + "#" + checkId);
        }

        @Override
        public void put(CheckRecord record) {
            store.put(record.userId() + "#" + record.checkId(), record);
        }

        @Override
        public Optional<CheckRecord> get(String userId, String checkId) {
            return Optional.ofNullable(store.get(userId + "#" + checkId));
        }

        @Override
        public void updateFeedback(String userId, String checkId, String feedback) {
            CheckRecord old = store.get(userId + "#" + checkId);
            if (old != null) {
                store.put(userId + "#" + checkId, new CheckRecord(
                        old.checkId(), old.userId(), old.outcome(), old.level(), old.score(),
                        old.group(), old.scenario(), old.path(), old.signalCodes(),
                        old.riskyActions(), old.inputKinds(), old.alerted(), feedback,
                        old.createdAt(), old.ttlEpochSeconds()));
            }
        }

        @Override
        public List<CheckRecord> listRecentChecks(String userId, int limit, String startSortKey) {
            String normUserId = userId.startsWith("USER#") ? userId.substring(5) : userId;
            List<CheckRecord> list = store.values().stream()
                    .filter(r -> {
                        String rUid = r.userId().startsWith("USER#") ? r.userId().substring(5) : r.userId();
                        return rUid.equals(normUserId);
                    })
                    .sorted((a, b) -> b.checkId().compareTo(a.checkId()))
                    .toList();

            if (startSortKey != null) {
                String startId = startSortKey.replace("CHECK#", "");
                int index = -1;
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).checkId().equals(startId)) {
                        index = i;
                        break;
                    }
                }
                if (index >= 0 && index + 1 < list.size()) {
                    list = list.subList(index + 1, list.size());
                } else if (index >= 0) {
                    return List.of();
                }
            }

            return list.stream().limit(limit).toList();
        }
    }

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
        public List<GuardianRecord> listWards(String guardianUid) {
            return store.values().stream()
                    .filter(r -> guardianUid.equals(r.getGuardianUid())
                            || ("GUARDIAN#" + guardianUid).equals(r.getGuardianUid())
                            || guardianUid.equals(r.getSk())
                            || ("GUARDIAN#" + guardianUid).equals(r.getSk())
                            || (r.getGsi1Pk() != null && r.getGsi1Pk().endsWith(guardianUid)))
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

        @Override
        public void toggleStatus(String elderUid, String gUid) {
            getGuardian(elderUid, gUid).ifPresent(r -> {
                String current = r.getStatus();
                if ("active".equalsIgnoreCase(current)) {
                    r.setStatus("paused");
                } else {
                    r.setStatus("active");
                }
            });
        }
    }

    static class FakeUserRepo extends UserRepo {
        final Map<String, UserRecord> users = new ConcurrentHashMap<>();

        @Override
        public Optional<UserRecord> getUser(String userId) {
            return Optional.ofNullable(users.get(userId));
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
    }

    static class FakeAuditRepo extends AuditRepo {
        record AuditEntry(String userId, String event, String details) {}
        final List<AuditEntry> events = new ArrayList<>();

        @Override
        public void recordEvent(String userId, String event, String ver, String sha, String details) {
            events.add(new AuditEntry(userId, event, details));
        }
    }

    static class FakeTelegramClient extends TelegramClient {
        record SentMsg(long chatId, String text, Map<String, Object> replyMarkup) {}
        record EditedMsg(long chatId, int messageId, String text, Map<String, Object> replyMarkup) {}

        final List<SentMsg> sentMessages = new ArrayList<>();
        final List<EditedMsg> editedMessages = new ArrayList<>();
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
            sentMessages.add(new SentMsg(chatId, text, null));
            return 1;
        }

        @Override
        public int sendText(long chatId, String text, Map<String, Object> replyMarkup) {
            sentMessages.add(new SentMsg(chatId, text, replyMarkup));
            return 1;
        }

        @Override
        public void editText(long chatId, int messageId, String text, Map<String, Object> replyMarkup) {
            editedMessages.add(new EditedMsg(chatId, messageId, text, replyMarkup));
        }
    }
}
