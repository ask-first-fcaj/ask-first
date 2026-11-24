package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.store.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests cho BE-31: Quyền lực tối cao của người dùng đối với dữ liệu
 * (Nghị định 356) — Rút đồng ý (/rutdongy) và Xoá dữ liệu (/xoadulieu).
 * Bao gồm đầy đủ các kịch bản chuẩn BE31-10 đến BE31-22.
 */
class WebhookHandlerBE31Test {

    private static final String SECRET = "test-secret-be31";
    private static final String POLICY_SHA256 = "sha256-policy-be31-test";
    private static final String USER_ID = "tg:999999";
    private static final long CHAT_ID = 999999L;
    private static final String USER_ID_B = "tg:777777";
    private static final long CHAT_ID_B = 777777L;
    private static final String RELATIVE_UID_1 = "tg:888881";
    private static final String RELATIVE_UID_2 = "tg:888882";

    private FakeTelegramClient telegramClient;
    private FakeUserRepo userRepo;
    private FakeGuardianRepo guardianRepo;
    private FakeAuditRepo auditRepo;
    private FakeSessionStore sessionStore;
    private FakeBufferStore bufferStore;
    private FakeDonationStore donationStore;
    private WebhookHandler handler;

    @BeforeEach
    void setUp() {
        telegramClient = new FakeTelegramClient();
        userRepo = new FakeUserRepo();
        guardianRepo = new FakeGuardianRepo();
        auditRepo = new FakeAuditRepo();
        sessionStore = new FakeSessionStore();
        bufferStore = new FakeBufferStore();
        donationStore = new FakeDonationStore();

        handler = new WebhookHandler(
                telegramClient,
                null,
                null,
                SECRET,
                userRepo,
                auditRepo,
                null,
                guardianRepo,
                null,
                sessionStore,
                bufferStore,
                donationStore,
                POLICY_SHA256);
    }

    // ── 1. Kiểm thử lệnh Rút đồng ý (/rutdongy) ──────────────────────────────

    @Test
    @DisplayName("BE-31: Khi gõ /rutdongy, bot gửi tin nhắn xác nhận kèm 2 nút xác nhận/hủy")
    void testRutDongYCommand_SendsConfirmationPrompt() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));

        APIGatewayProxyRequestEvent req = buildMessageRequest(CHAT_ID, "/rutdongy");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("COMMAND_HANDLED", res.getBody());

        assertFalse(telegramClient.sentMessages.isEmpty(), "Bot phải gửi tin nhắn xác nhận");
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("Nếu rút đồng ý, Bác sẽ không thể tiếp tục gửi tin nhắn kiểm tra lừa đảo. Bác có chắc chắn không?"),
                "Tin nhắn phải có nội dung cảnh báo rút đồng ý");

        assertTrue(userRepo.hasValidConsent(USER_ID, POLICY_SHA256), "Chưa bấm xác nhận thì sự đồng ý vẫn còn");
    }

    @Test
    @DisplayName("BE-31: Khi gõ /rutdongy với xưng hô tùy chỉnh (Cô), bot xưng hô đúng trong tin xác nhận")
    void testRutDongYCommand_WithCustomAddressForm() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        userRepo.updateAddressForm(USER_ID, "Cô");

        APIGatewayProxyRequestEvent req = buildMessageRequest(CHAT_ID, "/rutdongy");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("Nếu rút đồng ý, Cô sẽ không thể tiếp tục"),
                "Bot phải dùng xưng hô 'Cô' khi người dùng đã cài đặt");
    }

    @Test
    @DisplayName("BE-31: Callback consent:revoke_cancel hủy bỏ yêu cầu rút đồng ý")
    void testRutDongYCallbackCancel_KeepsConsent() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));

        APIGatewayProxyRequestEvent req = buildCallbackRequest(CHAT_ID, "consent:revoke_cancel");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("CONSENT_KEPT", res.getBody());

        assertTrue(userRepo.hasValidConsent(USER_ID, POLICY_SHA256));
        assertTrue(telegramClient.answeredCallbacks.get(0).contains("Đã giữ lại đồng ý"));
    }

    @Test
    @DisplayName("BE-31: Callback consent:revoke_confirm cập nhật status CONSENT_REVOKED, huỷ mọi liên kết người thân và ghi Audit")
    void testRutDongYCallbackConfirm_RevokesConsent_DeletesGuardians_RecordsAudit() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_1));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_2));

        APIGatewayProxyRequestEvent req = buildCallbackRequest(CHAT_ID, "consent:revoke_confirm");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("CONSENT_REVOKED", res.getBody());

        Optional<UserRecord> userOpt = userRepo.getUser(USER_ID);
        assertTrue(userOpt.isPresent());
        assertEquals("CONSENT_REVOKED", userOpt.get().getStatus(), "Trạng thái user phải là CONSENT_REVOKED");
        assertFalse(userRepo.hasValidConsent(USER_ID, POLICY_SHA256), "hasValidConsent phải trả về false");

        assertTrue(guardianRepo.listGuardians(USER_ID).isEmpty(), "Mọi liên kết người thân phải bị huỷ");

        List<AuditRecord> audits = auditRepo.listAuditTrail(USER_ID);
        assertEquals(1, audits.size());
        assertEquals("CONSENT_REVOKED", audits.get(0).getEvent());
    }

    @Test
    @DisplayName("BE31-12 & BE31-13: Nhấn xác nhận rút đồng ý nhiều lần (idempotency) -> thông báo an toàn, không duplicate audit")
    void testBE31_12_RutDongYCallback_WhenAlreadyRevoked_ReturnsIdempotentNotice() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));

        // Lần 1: Rút đồng ý thành công
        APIGatewayProxyRequestEvent req1 = buildCallbackRequest(CHAT_ID, "consent:revoke_confirm");
        APIGatewayProxyResponseEvent res1 = handler.handleRequest(req1, fakeContext());
        assertEquals("CONSENT_REVOKED", res1.getBody());
        assertEquals(1, auditRepo.listAuditTrail(USER_ID).size());

        // Lần 2: Nhấn lại nút xác nhận cũ
        APIGatewayProxyRequestEvent req2 = buildCallbackRequest(CHAT_ID, "consent:revoke_confirm");
        APIGatewayProxyResponseEvent res2 = handler.handleRequest(req2, fakeContext());
        assertEquals("CONSENT_ALREADY_REVOKED", res2.getBody());
        assertEquals(1, auditRepo.listAuditTrail(USER_ID).size(), "Không ghi thêm bản ghi audit trùng lặp");
        assertTrue(telegramClient.sentMessages.get(telegramClient.sentMessages.size() - 1).text.contains("Bác đã rút đồng ý trước đó rồi ạ"));
    }

    @Test
    @DisplayName("BE-31: Sau khi rút đồng ý, người dùng gửi tin kiểm tra lừa đảo bị chặn và yêu cầu cấp quyền lại")
    void testPostRevoke_InspectContent_PromptsForConsent() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        userRepo.revokeConsent(USER_ID);

        APIGatewayProxyRequestEvent req = buildMessageRequest(CHAT_ID, "Bác ơi chuyển tiền gấp vào STK 123456789 nhé");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertFalse(telegramClient.sentMessages.isEmpty());
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("Chào mừng Bác đến với AskFirst!") || msg.contains("Tôi đồng ý"),
                "User đã rút consent phải nhận thông báo yêu cầu đồng ý");
    }

    // ── 2. Kiểm thử lệnh Xoá dữ liệu (/xoadulieu) ─────────────────────────────

    @Test
    @DisplayName("BE31-10: Khi gõ /xoadulieu, bot hiển thị cảnh báo xác nhận kèm 2 nút, KHÔNG xoá dữ liệu ngay")
    void testBE31_10_XoaDuLieuCommand_ShowsConfirmationPrompt_DoesNotDeleteImmediately() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_1));

        APIGatewayProxyRequestEvent req = buildMessageRequest(CHAT_ID, "/xoadulieu");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("COMMAND_HANDLED", res.getBody());

        // Kiểm tra bot gửi tin nhắn cảnh báo
        assertFalse(telegramClient.sentMessages.isEmpty());
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("CẢNH BÁO XÓA TOÀN BỘ DỮ LIỆU"), "Phải có tiêu đề cảnh báo");
        assertTrue(msg.contains("Bác có chắc chắn muốn xoá sạch toàn bộ dữ liệu không?"), "Phải hỏi xác nhận người dùng");

        // Dữ liệu vẫn còn nguyên, chưa bị xoá
        assertTrue(userRepo.getUser(USER_ID).isPresent(), "Dữ liệu chưa được xoá khi chưa xác nhận");
        assertEquals(1, guardianRepo.listGuardians(USER_ID).size());
    }

    @Test
    @DisplayName("BE-31: /xoadulieu confirm hoặc callback xoadulieu:confirm quét sạch USER#, GUARDIAN#, SESSION#, BUFFER#, S3, tạo minimal audit")
    void testXoaDuLieuCallback_DeletesAllUserData_PurgesStores_RecordsMinimalAudit_SendsConfirmation() {
        // 1. Hồ sơ User
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true, "donate", true));
        // 2. Liên kết người thân
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_1));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_2));
        // 3. Phiên chat SESSION#
        sessionStore.put(new SessionRecord(String.valueOf(CHAT_ID), "CHECK123", USER_ID, "Tin đã che mờ", null, 1, Instant.now().getEpochSecond() + 1800));
        // 4. Bộ đệm BUFFER#
        bufferStore.setPlaceholder(String.valueOf(CHAT_ID), 77);
        // 5. Mẫu đã đóng góp trên S3 donated/<hash(uid)>/
        donationStore.saveDonatedSample(USER_ID, "CHECK123", "Nội dung đóng góp đã mask");
        // 6. Audit logs cũ
        auditRepo.recordEvent(USER_ID, "CONSENT_GIVEN", "1.0", POLICY_SHA256, "Ban dau");
        auditRepo.recordEvent(USER_ID, "GUARDIAN_LINKED", "1.0", POLICY_SHA256, "Ket noi");

        // Thực hiện xác nhận xoá
        APIGatewayProxyRequestEvent req = buildCallbackRequest(CHAT_ID, "xoadulieu:confirm");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("DATA_DELETED", res.getBody());

        // 1. Hồ sơ người dùng (USER#) đã bị xoá
        assertTrue(userRepo.getUser(USER_ID).isEmpty(), "USER# phải bị xoá hoàn toàn");

        // 2. Mọi liên kết người thân (GUARDIAN#) đã bị xoá
        assertTrue(guardianRepo.listGuardians(USER_ID).isEmpty(), "Mọi GUARDIAN# phải bị xoá sạch");

        // 3. Phiên chat (SESSION#) đã bị xoá
        assertTrue(sessionStore.get(String.valueOf(CHAT_ID)).isEmpty(), "SESSION# phải bị xoá sạch");

        // 4. Bộ đệm (BUFFER#) đã bị xoá
        assertTrue(bufferStore.state(String.valueOf(CHAT_ID)).isEmpty(), "BUFFER# phải bị xoá sạch");

        // 5. Prefix donated/<hash(uid)>/ trên S3 hoàn toàn trống
        assertFalse(donationStore.hasDonatedSamples(USER_ID), "Mọi mẫu đóng góp donated/ của user phải bị xoá sạch");
        String uidHash = AuditRepo.hashUid(USER_ID);
        for (String key : donationStore.donatedFiles.keySet()) {
            assertFalse(key.startsWith("donated/" + uidHash + "/"),
                    "Không được còn file nào thuộc prefix donated/" + uidHash + "/");
        }

        // 6. Kiểm tra điều kiện "Xong khi": query USER#uid chỉ còn bản ghi audit tối thiểu
        List<AuditRecord> auditTrail = auditRepo.listAuditTrail(USER_ID);
        assertEquals(1, auditTrail.size(), "Sau khi xoá, query USER#uid CHỈ CÒN DUY NHẤT bản ghi audit tối thiểu");

        AuditRecord minimalAudit = auditTrail.get(0);
        assertEquals("DATA_DELETED", minimalAudit.getEvent(), "Sự kiện audit phải là DATA_DELETED");

        String expectedDate = LocalDate.now().toString();
        String expectedDetails = "Tài khoản [" + uidHash + "] đã yêu cầu xoá dữ liệu vào ngày " + expectedDate;
        assertEquals(expectedDetails, minimalAudit.getDetails(), "Bản ghi audit phải ẩn danh [hash(uid)] và chứa ngày YYYY-MM-DD");

        // 7. Kiểm tra thông báo Telegram gửi cho người dùng
        assertFalse(telegramClient.sentMessages.isEmpty(), "Bot phải gửi thông báo hoàn tất");
        String sentText = telegramClient.sentMessages.get(0).text;
        assertTrue(sentText.startsWith("Cháu đã xoá sạch toàn bộ dữ liệu của Bác trên hệ thống rồi ạ!"),
                "Tin nhắn phải có câu thông báo hoàn tất chuẩn xác theo yêu cầu");
        assertTrue(sentText.contains("tự xóa cuộc trò chuyện này trên ứng dụng Telegram"),
                "Tin nhắn phải hướng dẫn người dùng tự xoá chat Telegram (theo ARCH §5.6)");
    }

    @Test
    @DisplayName("BE-31: Callback xoadulieu:cancel huỷ bỏ yêu cầu xoá dữ liệu")
    void testXoaDuLieuCallbackCancel_AbortsDeletion() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));

        APIGatewayProxyRequestEvent req = buildCallbackRequest(CHAT_ID, "xoadulieu:cancel");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("DATA_DELETION_CANCELLED", res.getBody());
        assertTrue(userRepo.getUser(USER_ID).isPresent(), "Dữ liệu không bị xoá");
    }

    @Test
    @DisplayName("BE31-11: Xác nhận xoá khi tài khoản đã bị xoá trước đó -> báo an toàn, không ghi trùng audit")
    void testBE31_11_XoaDuLieuCallback_WhenAlreadyDeleted_ReturnsIdempotentNotice() {
        // Thực hiện xoá lần 1
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        handler.handleRequest(buildCallbackRequest(CHAT_ID, "xoadulieu:confirm"), fakeContext());
        assertEquals(1, auditRepo.listAuditTrail(USER_ID).size());

        // Nhấn xoá lần 2 từ callback cũ
        APIGatewayProxyResponseEvent res2 = handler.handleRequest(buildCallbackRequest(CHAT_ID, "xoadulieu:confirm"), fakeContext());
        assertEquals("DATA_ALREADY_DELETED", res2.getBody());
        assertEquals(1, auditRepo.listAuditTrail(USER_ID).size(), "Không ghi duplicate audit DATA_DELETED");
        assertTrue(telegramClient.sentMessages.get(telegramClient.sentMessages.size() - 1).text.contains("đã được xoá sạch trước đó rồi ạ"));
    }

    @Test
    @DisplayName("BE31-14: Xoá người dùng có nhiều objects trên S3 -> toàn bộ files thuộc prefix donated/<hash(uid)>/ bị xoá")
    void testBE31_14_DeleteUserData_PurgesMultipleS3ObjectsInPrefix() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        donationStore.saveDonatedSample(USER_ID, "CK1", "Sample 1");
        donationStore.saveDonatedSample(USER_ID, "CK2", "Sample 2");
        donationStore.saveDonatedSample(USER_ID, "CK3", "Sample 3");
        // User B cũng có mẫu
        donationStore.saveDonatedSample(USER_ID_B, "CK_B", "Sample B");

        assertEquals(4, donationStore.donatedFiles.size());

        handler.handleRequest(buildCallbackRequest(CHAT_ID, "xoadulieu:confirm"), fakeContext());

        assertFalse(donationStore.hasDonatedSamples(USER_ID));
        // Mẫu của User B vẫn còn nguyên
        assertTrue(donationStore.hasDonatedSamples(USER_ID_B));
        assertEquals(1, donationStore.donatedFiles.size());
    }

    @Test
    @DisplayName("BE31-15: S3 xoá thất bại -> handler bắt lỗi, KHÔNG ghi audit DATA_DELETED, KHÔNG báo thành công giả")
    void testBE31_15_S3DeletionFails_DoesNotClaimSuccess_DoesNotRecordAudit() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        donationStore.saveDonatedSample(USER_ID, "CK1", "Sample");
        donationStore.throwOnDelete = true; // Giả lập S3 ném ngoại lệ

        APIGatewayProxyResponseEvent res = handler.handleRequest(buildCallbackRequest(CHAT_ID, "xoadulieu:confirm"), fakeContext());

        assertEquals("PURGE_FAILED", res.getBody());

        // KHÔNG được ghi audit DATA_DELETED
        List<AuditRecord> audits = auditRepo.listAuditTrail(USER_ID);
        assertTrue(audits.stream().noneMatch(a -> "DATA_DELETED".equals(a.getEvent())),
                "Khi S3 lỗi, không được ghi audit DATA_DELETED");

        // Bot phải gửi thông báo lỗi trung thực
        String lastMsg = telegramClient.sentMessages.get(telegramClient.sentMessages.size() - 1).text;
        assertTrue(lastMsg.contains("Đã có lỗi xảy ra trong quá trình dọn dẹp dữ liệu"),
                "Bot phải báo lỗi rõ ràng theo nguyên tắc trung thực BR-027");
    }

    @Test
    @DisplayName("BE31-16: DynamoDB xoá thất bại -> handler bắt lỗi, KHÔNG ghi audit DATA_DELETED, KHÔNG báo thành công giả")
    void testBE31_16_DynamoDbDeletionFails_DoesNotClaimSuccess_DoesNotRecordAudit() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        userRepo.throwOnDelete = true; // Giả lập DynamoDB ném ngoại lệ

        APIGatewayProxyResponseEvent res = handler.handleRequest(buildCallbackRequest(CHAT_ID, "xoadulieu:confirm"), fakeContext());

        assertEquals("PURGE_FAILED", res.getBody());

        List<AuditRecord> audits = auditRepo.listAuditTrail(USER_ID);
        assertTrue(audits.stream().noneMatch(a -> "DATA_DELETED".equals(a.getEvent())));

        String lastMsg = telegramClient.sentMessages.get(telegramClient.sentMessages.size() - 1).text;
        assertTrue(lastMsg.contains("Đã có lỗi xảy ra"), "Phải báo lỗi khi DynamoDB thất bại");
    }

    @Test
    @DisplayName("BE31-17: Xoá quan hệ Guardian 2 chiều khi người dùng là người thân (relative) xoá tài khoản")
    void testBE31_17_PurgesGuardiansInBothDirections() {
        // USER_ID là người thân của 2 người cao tuổi khác
        String elder1 = "tg:111111";
        String elder2 = "tg:222222";
        guardianRepo.save(new GuardianRecord(elder1, USER_ID));
        guardianRepo.save(new GuardianRecord(elder2, USER_ID));
        // USER_ID cũng có người thân bảo vệ mình
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_1));

        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));

        handler.handleRequest(buildCallbackRequest(CHAT_ID, "xoadulieu:confirm"), fakeContext());

        // Kiểm tra chiều USER_ID là elder
        assertTrue(guardianRepo.listGuardians(USER_ID).isEmpty());
        // Kiểm tra chiều USER_ID là guardian ở elder1 và elder2
        assertTrue(guardianRepo.getGuardian(elder1, USER_ID).isEmpty(), "Liên kết ở elder1 phải bị xoá");
        assertTrue(guardianRepo.getGuardian(elder2, USER_ID).isEmpty(), "Liên kết ở elder2 phải bị xoá");
    }

    @Test
    @DisplayName("BE31-19: Bản ghi Audit DATA_DELETED ẩn danh hóa hoàn toàn, attribute user_id lưu hash, details lưu [hash(uid)]")
    void testBE31_19_MinimalAuditRecord_AnonymizedUserId_NoPlaintextPII() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        handler.handleRequest(buildCallbackRequest(CHAT_ID, "xoadulieu:confirm"), fakeContext());

        List<AuditRecord> audits = auditRepo.listAuditTrail(USER_ID);
        assertEquals(1, audits.size());
        AuditRecord audit = audits.get(0);

        String expectedHash = AuditRepo.hashUid(USER_ID);
        assertEquals("DATA_DELETED", audit.getEvent());
        assertEquals(expectedHash, audit.getUserId(), "Trường user_id attribute phải được ẩn danh hóa bằng hash");
        assertTrue(audit.getDetails().contains("[" + expectedHash + "]"));
        assertFalse(audit.getDetails().contains("999999"), "Details không được chứa User ID gốc");
    }

    @Test
    @DisplayName("BE31-20: Xoá tài khoản chưa từng có dữ liệu -> xử lý an toàn (idempotent), tạo audit hợp lệ")
    void testBE31_20_XoaDuLieu_WhenUserHasNoPriorData_SafeAndCreatesMinimalAudit() {
        // User chưa từng có trong userRepo, guardianRepo, s3
        APIGatewayProxyResponseEvent res = handler.handleRequest(buildCallbackRequest(CHAT_ID, "xoadulieu:confirm"), fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("DATA_DELETED", res.getBody());
        assertEquals(1, auditRepo.listAuditTrail(USER_ID).size());
    }

    @Test
    @DisplayName("BE31-21: Xoá dữ liệu của User A không làm ảnh hưởng đến dữ liệu của User B (Data Isolation)")
    void testBE31_21_DataIsolation_DeletingUserA_DoesNotAffectUserB() {
        // Chuẩn bị dữ liệu cho cả User A và User B
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        userRepo.updateConsent(USER_ID_B, POLICY_SHA256, "1.0", Map.of("analyze", true));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_1));
        guardianRepo.save(new GuardianRecord(USER_ID_B, RELATIVE_UID_2));
        sessionStore.put(new SessionRecord(String.valueOf(CHAT_ID), "CK_A", USER_ID, "maskedA", null, 1, 0));
        sessionStore.put(new SessionRecord(String.valueOf(CHAT_ID_B), "CK_B", USER_ID_B, "maskedB", null, 1, 0));
        donationStore.saveDonatedSample(USER_ID, "CK_A", "Sample A");
        donationStore.saveDonatedSample(USER_ID_B, "CK_B", "Sample B");
        auditRepo.recordEvent(USER_ID, "CONSENT_GIVEN", "1.0", POLICY_SHA256, "Audit A");
        auditRepo.recordEvent(USER_ID_B, "CONSENT_GIVEN", "1.0", POLICY_SHA256, "Audit B");

        // Xoá User A
        handler.handleRequest(buildCallbackRequest(CHAT_ID, "xoadulieu:confirm"), fakeContext());

        // User A đã bị xoá
        assertTrue(userRepo.getUser(USER_ID).isEmpty());
        assertTrue(guardianRepo.listGuardians(USER_ID).isEmpty());
        assertTrue(sessionStore.get(String.valueOf(CHAT_ID)).isEmpty());
        assertFalse(donationStore.hasDonatedSamples(USER_ID));
        assertEquals(1, auditRepo.listAuditTrail(USER_ID).size()); // chỉ còn DATA_DELETED

        // Dữ liệu User B vẫn nguyên vẹn 100%
        assertTrue(userRepo.getUser(USER_ID_B).isPresent());
        assertEquals(1, guardianRepo.listGuardians(USER_ID_B).size());
        assertTrue(sessionStore.get(String.valueOf(CHAT_ID_B)).isPresent());
        assertTrue(donationStore.hasDonatedSamples(USER_ID_B));
        assertEquals(1, auditRepo.listAuditTrail(USER_ID_B).size());
        assertEquals("CONSENT_GIVEN", auditRepo.listAuditTrail(USER_ID_B).get(0).getEvent());
    }

    @Test
    @DisplayName("BE31-22: Webhook secret sai hoặc thiếu -> trả về 401 UNAUTHORIZED, không thực hiện xoá")
    void testBE31_22_WebhookSecret_MissingOrInvalid_Rejected401_NoDataPurged() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));

        // Request với secret sai
        APIGatewayProxyRequestEvent req = new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", "wrong-secret"))
                .withBody("""
                        {"update_id":1,"callback_query":{"id":"cq1","from":{"id":999999,"is_bot":false},
                        "data":"xoadulieu:confirm"}}
                        """);

        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());
        assertEquals(401, res.getStatusCode());
        assertEquals("UNAUTHORIZED", res.getBody());

        // User vẫn nguyên vẹn
        assertTrue(userRepo.getUser(USER_ID).isPresent());
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

    // ══ Fake Repos & Stores ═══════════════════════════════════════════════════

    static class FakeUserRepo extends UserRepo {
        final Map<String, UserRecord> users = new ConcurrentHashMap<>();
        boolean throwOnDelete = false;

        @Override
        public Optional<UserRecord> getUser(String userId) {
            return Optional.ofNullable(users.get(userId));
        }

        @Override
        public boolean hasValidConsent(String userId, String expectedSha256) {
            UserRecord r = users.get(userId);
            return r != null && expectedSha256 != null && expectedSha256.equals(r.getPolicySha256())
                    && r.getConsentedAt() != null
                    && Boolean.TRUE.equals(r.getPurposes().get("analyze"));
        }

        @Override
        public void updateConsent(String userId, String policySha256, String version, Map<String, Boolean> purposes) {
            UserRecord r = users.computeIfAbsent(userId, k -> new UserRecord(userId, ""));
            r.setConsentVersion(version);
            r.setPolicySha256(policySha256);
            r.setStatus("active");
            if (purposes != null) {
                r.setPurposes(new HashMap<>(purposes));
            }
            r.setConsentedAt(Instant.now().toString());
        }

        @Override
        public void revokeConsent(String userId) {
            UserRecord r = users.get(userId);
            if (r != null) {
                Map<String, Boolean> revoked = new HashMap<>();
                for (String purpose : r.getPurposes().keySet()) {
                    revoked.put(purpose, false);
                }
                revoked.put("analyze", false);
                r.setPurposes(revoked);
                r.setConsentedAt(null);
                r.setStatus("CONSENT_REVOKED");
            }
        }

        @Override
        public void updateAddressForm(String userId, String addressForm) {
            UserRecord r = users.computeIfAbsent(userId, k -> new UserRecord(userId, ""));
            r.setAddressForm(addressForm);
        }

        @Override
        public void deleteUser(String userId) {
            if (throwOnDelete) {
                throw new RuntimeException("DynamoDB ProvisionedThroughputExceededException (Simulated)");
            }
            users.remove(userId);
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
        public List<GuardianRecord> listWardsOf(String guardianUid) {
            return store.values().stream()
                    .filter(r -> guardianUid.equals(r.getGuardianUid())
                            || ("GUARDIAN#" + guardianUid).equals(r.getSk()))
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
        public void deleteAllGuardians(String userId) {
            store.entrySet()
                    .removeIf(e -> (userId.equals(e.getValue().getElderUid())
                            || ("USER#" + userId).equals(e.getValue().getPk()))
                            || (userId.equals(e.getValue().getGuardianUid())
                            || ("GUARDIAN#" + userId).equals(e.getValue().getSk())));
        }
    }

    static class FakeAuditRepo extends AuditRepo {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void recordEvent(String userId, String event, String ver, String sha, String details) {
            recordEventAnonymized(userId, event, ver, sha, details, userId);
        }

        @Override
        public void recordEventAnonymized(String pkUserId, String event, String ver, String sha, String details, String anonUid) {
            AuditRecord r = new AuditRecord(pkUserId, Instant.now().toString(), event);
            r.setUserId(anonUid);
            r.setPolicyVersion(ver);
            r.setPolicySha256(sha);
            r.setDetails(details);
            records.add(r);
        }

        @Override
        public List<AuditRecord> listAuditTrail(String userId) {
            return records.stream()
                    .filter(r -> userId.equals(r.getUserId())
                            || (userId.startsWith("USER#") ? r.getPk().equals(userId) : r.getPk().equals("USER#" + userId)))
                    .toList();
        }

        @Override
        public void deleteAuditTrail(String userId) {
            records.removeIf(r -> userId.equals(r.getUserId())
                    || (userId.startsWith("USER#") ? r.getPk().equals(userId) : r.getPk().equals("USER#" + userId)));
        }
    }

    static class FakeSessionStore implements SessionStore {
        final Map<String, SessionRecord> store = new ConcurrentHashMap<>();

        @Override
        public Optional<SessionRecord> get(String chatId) {
            return Optional.ofNullable(store.get(chatId));
        }

        @Override
        public void put(SessionRecord record) {
            store.put(record.chatId(), record);
        }

        @Override
        public void delete(String chatId) {
            store.remove(chatId);
        }
    }

    static class FakeBufferStore implements BufferStore {
        final Map<String, State> states = new ConcurrentHashMap<>();

        @Override
        public long append(String chatId, InboundMessage message, long ttlEpochSeconds) {
            return 1;
        }

        @Override
        public void setPlaceholder(String chatId, int messageId) {
            states.put(chatId, new State(1, 0, messageId));
        }

        @Override
        public Optional<State> state(String chatId) {
            return Optional.ofNullable(states.get(chatId));
        }

        @Override
        public List<InboundMessage> itemsAfter(String chatId, long afterSeq, long upToSeq) {
            return List.of();
        }

        @Override
        public boolean finish(String chatId, long seq) {
            states.remove(chatId);
            return true;
        }

        @Override
        public void delete(String chatId) {
            states.remove(chatId);
        }
    }

    static class FakeDonationStore implements DonationStore {
        final Map<String, String> donatedFiles = new ConcurrentHashMap<>();
        boolean throwOnDelete = false;

        @Override
        public String saveDonatedSample(String userId, String checkId, String maskedText) {
            String hash = AuditRepo.hashUid(userId);
            String key = "donated/" + hash + "/" + checkId + ".txt";
            donatedFiles.put(key, maskedText != null ? maskedText : "");
            return key;
        }

        @Override
        public void deleteDonatedSamples(String userId) {
            if (throwOnDelete) {
                throw new RuntimeException("S3 DeleteObject AccessDeniedException (Simulated)");
            }
            String hash = AuditRepo.hashUid(userId);
            String prefix = "donated/" + hash + "/";
            donatedFiles.keySet().removeIf(k -> k.startsWith(prefix));
        }

        @Override
        public boolean hasDonatedSamples(String userId) {
            String hash = AuditRepo.hashUid(userId);
            String prefix = "donated/" + hash + "/";
            return donatedFiles.keySet().stream().anyMatch(k -> k.startsWith(prefix));
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
    }
}
