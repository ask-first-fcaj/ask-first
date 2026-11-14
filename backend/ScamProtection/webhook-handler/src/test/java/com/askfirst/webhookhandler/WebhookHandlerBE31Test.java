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
 */
class WebhookHandlerBE31Test {

    private static final String SECRET = "test-secret-be31";
    private static final String POLICY_SHA256 = "sha256-policy-be31-test";
    private static final String USER_ID = "tg:999999";
    private static final long CHAT_ID = 999999L;
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
        // Chuẩn bị: Người dùng đã đồng ý
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));

        APIGatewayProxyRequestEvent req = buildMessageRequest(CHAT_ID, "/rutdongy");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("COMMAND_HANDLED", res.getBody());

        // Bot phải gửi tin nhắn xác nhận đúng nội dung yêu cầu
        assertFalse(telegramClient.sentMessages.isEmpty(), "Bot phải gửi tin nhắn xác nhận");
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("Nếu rút đồng ý, Bác sẽ không thể tiếp tục gửi tin nhắn kiểm tra lừa đảo. Bác có chắc chắn không?"),
                "Tin nhắn phải có nội dung cảnh báo rút đồng ý");

        // Trạng thái user vẫn còn hiệu lực trước khi xác nhận
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
        assertTrue(msg.contains("Nếu rút đồng ý, Cô sẽ không thể tiếp tục gửi tin nhắn kiểm tra lừa đảo. Cô có chắc chắn không?"),
                "Tin nhắn phải xưng hô Cô theo cài đặt");
    }

    @Test
    @DisplayName("BE-31: Xác nhận rút đồng ý -> Cập nhật CONSENT_REVOKED, huỷ mọi liên kết người thân, ghi Audit")
    void testRevokeConsentConfirm_RevokesConsent_CancelsGuardians_RecordsAudit() {
        // Chuẩn bị: User có consent và 2 người thân
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_1));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_2));
        assertEquals(2, guardianRepo.listGuardians(USER_ID).size());

        // Bấm nút [Xác nhận rút đồng ý]
        APIGatewayProxyRequestEvent req = buildCallbackRequest(CHAT_ID, "consent:revoke_confirm");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("CONSENT_REVOKED", res.getBody());

        // 1. UserRepo: status = CONSENT_REVOKED, không còn consent hợp lệ
        UserRecord user = userRepo.getUser(USER_ID).orElseThrow();
        assertEquals("CONSENT_REVOKED", user.getStatus(), "Trạng thái người dùng phải là CONSENT_REVOKED");
        assertNull(user.getConsentedAt(), "consentedAt phải bị xóa");
        assertFalse(userRepo.hasValidConsent(USER_ID, POLICY_SHA256), "Người dùng không còn consent hợp lệ");

        // 2. Huỷ bỏ mọi liên kết người thân đang có
        assertTrue(guardianRepo.listGuardians(USER_ID).isEmpty(), "Mọi liên kết người thân phải bị huỷ bỏ hoàn toàn");

        // 3. Ghi nhận bản ghi vào AuditRepo
        List<AuditRecord> audits = auditRepo.listAuditTrail(USER_ID);
        assertFalse(audits.isEmpty(), "Phải ghi nhận audit trail");
        boolean hasRevokeEvent = audits.stream().anyMatch(a -> "CONSENT_REVOKED".equals(a.getEvent()));
        assertTrue(hasRevokeEvent, "Phải có bản ghi audit event CONSENT_REVOKED");

        // 4. Phản hồi cho người dùng
        assertFalse(telegramClient.answeredCallbacks.isEmpty(), "Phải answer callback query");
        assertTrue(telegramClient.answeredCallbacks.get(0).contains("Đã rút đồng ý"));
        assertFalse(telegramClient.sentMessages.isEmpty(), "Phải gửi tin nhắn thông báo hoàn tất");
    }

    @Test
    @DisplayName("BE-31: Bấm Giữ lại khi rút đồng ý -> Không thay đổi trạng thái và liên kết người thân")
    void testRevokeConsentCancel_KeepsConsentAndGuardians() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_1));

        APIGatewayProxyRequestEvent req = buildCallbackRequest(CHAT_ID, "consent:revoke_cancel");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("CONSENT_KEPT", res.getBody());

        assertTrue(userRepo.hasValidConsent(USER_ID, POLICY_SHA256), "Vẫn còn consent");
        assertEquals(1, guardianRepo.listGuardians(USER_ID).size(), "Người thân không bị xóa");
    }

    @Test
    @DisplayName("BE-31: Lệnh /rutdongy confirm rút đồng ý trực tiếp")
    void testRutDongYDirectConfirm() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_1));

        APIGatewayProxyRequestEvent req = buildMessageRequest(CHAT_ID, "/rutdongy confirm");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("CONSENT_REVOKED", res.getBody());
        assertEquals("CONSENT_REVOKED", userRepo.getUser(USER_ID).orElseThrow().getStatus());
        assertTrue(guardianRepo.listGuardians(USER_ID).isEmpty());
    }

    @Test
    @DisplayName("BE-31: Người dùng đã rút đồng ý gửi tin nhắn kiểm tra -> Bị chặn và yêu cầu /start để đồng ý lại")
    void testRevokedUser_BlockedFromCheckingMessages() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        // Rút đồng ý
        userRepo.revokeConsent(USER_ID);

        // Gửi tin nhắn cần kiểm tra
        APIGatewayProxyRequestEvent req = buildMessageRequest(CHAT_ID, "Bác ơi chuyển tiền gấp vào STK 123456789 nhé");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        // Bot phải gửi lời chào mời đồng ý (/start onboarding) chứ không đưa vào hàng đợi kiểm tra
        assertFalse(telegramClient.sentMessages.isEmpty());
        String msg = telegramClient.sentMessages.get(0).text;
        assertTrue(msg.contains("Chào mừng Bác đến với AskFirst!") || msg.contains("Tôi đồng ý"),
                "User chưa/đã rút consent phải nhận thông báo yêu cầu đồng ý");
    }

    // ── 2. Kiểm thử lệnh Xoá dữ liệu (/xoadulieu) ─────────────────────────────

    @Test
    @DisplayName("BE-31: /xoadulieu quét sạch toàn bộ USER#, GUARDIAN#, SESSION#, BUFFER#, S3 donated/, ghi Audit tối thiểu và gửi thông báo hoàn tất")
    void testXoaDuLieuCommand_DeletesAllUserData_PurgesStores_RecordsMinimalAudit_SendsConfirmation() {
        // Chuẩn bị dữ liệu đầy đủ của người dùng trên toàn bộ hệ thống:
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

        // Xác minh ban đầu dữ liệu tồn tại
        assertTrue(userRepo.getUser(USER_ID).isPresent());
        assertEquals(2, guardianRepo.listGuardians(USER_ID).size());
        assertTrue(sessionStore.get(String.valueOf(CHAT_ID)).isPresent());
        assertTrue(bufferStore.state(String.valueOf(CHAT_ID)).isPresent());
        assertTrue(donationStore.hasDonatedSamples(USER_ID));
        assertEquals(2, auditRepo.listAuditTrail(USER_ID).size());

        // Thực hiện lệnh /xoadulieu
        APIGatewayProxyRequestEvent req = buildMessageRequest(CHAT_ID, "/xoadulieu");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("DATA_DELETED", res.getBody());

        // ── Kiểm tra điều kiện "Xong khi": Mọi truy vấn tìm kiếm dữ liệu user đều trả về rỗng ──

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

        // ── Kiểm tra điều kiện: query USER#uid chỉ còn bản ghi audit tối thiểu ──
        List<AuditRecord> auditTrail = auditRepo.listAuditTrail(USER_ID);
        assertEquals(1, auditTrail.size(), "Sau khi xoá, query USER#uid CHỈ CÒN DUY NHẤT bản ghi audit tối thiểu");

        AuditRecord minimalAudit = auditTrail.get(0);
        assertEquals("DATA_DELETED", minimalAudit.getEvent(), "Sự kiện audit phải là DATA_DELETED");

        String expectedDate = LocalDate.now().toString();
        String expectedDetails = "Tài khoản [" + uidHash + "] đã yêu cầu xoá dữ liệu vào ngày " + expectedDate;
        assertEquals(expectedDetails, minimalAudit.getDetails(), "Bản ghi audit phải ẩn danh [hash(uid)] và chứa ngày YYYY-MM-DD");

        // ── Kiểm tra thông báo Telegram gửi cho người dùng ──
        assertFalse(telegramClient.sentMessages.isEmpty(), "Bot phải gửi thông báo hoàn tất");
        String sentText = telegramClient.sentMessages.get(0).text;
        assertTrue(sentText.startsWith("Cháu đã xoá sạch toàn bộ dữ liệu của Bác trên hệ thống rồi ạ!"),
                "Tin nhắn phải có câu thông báo hoàn tất chuẩn xác theo yêu cầu");
        assertTrue(sentText.contains("tự xóa cuộc trò chuyện này trên ứng dụng Telegram"),
                "Tin nhắn phải hướng dẫn người dùng tự xoá chat Telegram (theo ARCH §5.6)");
    }

    @Test
    @DisplayName("BE-31: Callback xoadulieu:confirm thực hiện xoá dữ liệu tương tự")
    void testXoaDuLieuCallback_ExecutesSameDeletion() {
        userRepo.updateConsent(USER_ID, POLICY_SHA256, "1.0", Map.of("analyze", true));
        guardianRepo.save(new GuardianRecord(USER_ID, RELATIVE_UID_1));
        sessionStore.put(new SessionRecord(String.valueOf(CHAT_ID), "CK1", USER_ID, "masked", null, 1, 0));

        APIGatewayProxyRequestEvent req = buildCallbackRequest(CHAT_ID, "xoadulieu:confirm");
        APIGatewayProxyResponseEvent res = handler.handleRequest(req, fakeContext());

        assertEquals(200, res.getStatusCode());
        assertEquals("DATA_DELETED", res.getBody());

        assertTrue(userRepo.getUser(USER_ID).isEmpty());
        assertTrue(guardianRepo.listGuardians(USER_ID).isEmpty());
        assertTrue(sessionStore.get(String.valueOf(CHAT_ID)).isEmpty());
        assertEquals(1, auditRepo.listAuditTrail(USER_ID).size());
        assertTrue(telegramClient.answeredCallbacks.get(0).contains("Đã xoá dữ liệu"));
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
        public void delete(String elderUid, String gUid) {
            store.entrySet()
                    .removeIf(e -> (elderUid.equals(e.getValue().getElderUid())
                            || ("USER#" + elderUid).equals(e.getValue().getPk()))
                            && (gUid.equals(e.getValue().getGuardianUid())
                            || ("GUARDIAN#" + gUid).equals(e.getValue().getSk())));
        }
    }

    static class FakeAuditRepo extends AuditRepo {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void recordEvent(String userId, String event, String ver, String sha, String details) {
            AuditRecord r = new AuditRecord(userId, Instant.now().toString(), event);
            r.setPolicyVersion(ver);
            r.setPolicySha256(sha);
            r.setDetails(details);
            records.add(r);
        }

        @Override
        public List<AuditRecord> listAuditTrail(String userId) {
            return records.stream()
                    .filter(r -> userId.equals(r.getUserId()) || (userId.startsWith("USER#") ? r.getPk().equals(userId) : r.getPk().equals("USER#" + userId)))
                    .toList();
        }

        @Override
        public void deleteAuditTrail(String userId) {
            records.removeIf(r -> userId.equals(r.getUserId()) || (userId.startsWith("USER#") ? r.getPk().equals(userId) : r.getPk().equals("USER#" + userId)));
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

        @Override
        public String saveDonatedSample(String userId, String checkId, String maskedText) {
            String hash = AuditRepo.hashUid(userId);
            String key = "donated/" + hash + "/" + checkId + ".txt";
            donatedFiles.put(key, maskedText != null ? maskedText : "");
            return key;
        }

        @Override
        public void deleteDonatedSamples(String userId) {
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
