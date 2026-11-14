package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.CheckRecord;
import com.askfirst.common.store.CheckRepo;
import com.askfirst.common.store.DonationStore;
import com.askfirst.common.store.S3DonationStore;
import com.askfirst.common.store.SessionRecord;
import com.askfirst.common.store.SessionStore;
import com.askfirst.common.store.UserRecord;
import com.askfirst.common.store.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests cho tính năng BE-30:
 * 1. Xử lý nút bấm phản hồi [Bot báo sai] (fb:wrong:<check_id>): ghi nhận feedback = "wrong" vào CheckRepo.
 * 2. Luồng xin phép đóng góp mẫu (Donation Consent): gửi lời cảm ơn và hỏi đồng ý, kèm 2 nút [Đồng ý đóng góp] và [Không, cảm ơn].
 * 3. Lưu trữ mẫu đóng góp an toàn: khi người dùng bấm [Đồng ý đóng góp], lưu masked_text vào S3 tại donated/<hash(uid)>/; tuyệt đối không lưu khi từ chối.
 */
class WebhookHandlerBE30Test {

    private static final String SECRET = "secret-token-30";
    private static final String POLICY_SHA256 = WebhookHandler.DEFAULT_POLICY_SHA256;

    private FakeTelegramClient fakeTelegram;
    private InMemoryUserRepo fakeUserRepo;
    private InMemoryAuditRepo fakeAuditRepo;
    private InMemoryCheckRepo fakeCheckRepo;
    private InMemorySessionStore fakeSessionStore;
    private InMemoryDonationStore fakeDonationStore;
    private WebhookHandler handler;
    private Context mockContext;

    @BeforeEach
    void setUp() {
        fakeTelegram = new FakeTelegramClient();
        fakeUserRepo = new InMemoryUserRepo();
        fakeAuditRepo = new InMemoryAuditRepo();
        fakeCheckRepo = new InMemoryCheckRepo();
        fakeSessionStore = new InMemorySessionStore();
        fakeDonationStore = new InMemoryDonationStore();

        handler = new WebhookHandler(
                fakeTelegram,
                null,
                "https://sqs.mock.queue",
                SECRET,
                fakeUserRepo,
                fakeAuditRepo,
                fakeCheckRepo,
                fakeSessionStore,
                fakeDonationStore,
                POLICY_SHA256);

        mockContext = createMockContext();
    }

    @Test
    @DisplayName("BE-30: Bấm [Bot báo sai] cập nhật CHECK.feedback = 'wrong' và gửi prompt xin phép đóng góp mẫu")
    void testFeedbackWrongUpdatesCheckRepoAndSendsConsentPrompt() {
        String checkId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        String userId = "tg:111";

        // Tạo sẵn bản ghi check trước đó
        fakeCheckRepo.put(new CheckRecord(
                checkId, userId, "safe", "LOW", 10,
                "none", "none", "rule", List.of(), List.of(), List.of(),
                false, null, Instant.now().toString(), Instant.now().getEpochSecond() + 1800));

        // Người dùng bấm [Bot báo sai]
        String callbackJson = callbackUpdate(101, "fb:wrong:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackJson), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);

        // 1. Kiểm tra feedback hiện trong CHECK.feedback = "wrong"
        Optional<CheckRecord> checkOpt = fakeCheckRepo.get(userId, checkId);
        assertThat(checkOpt).isPresent();
        assertThat(checkOpt.get().feedback()).isEqualTo("wrong");

        // 2. Kiểm tra bot đã trả lời callback query
        assertThat(fakeTelegram.lastCallbackAnswer).contains("Cảm ơn");

        // 3. Kiểm tra tin nhắn xin phép đóng góp mẫu đúng nội dung
        assertThat(fakeTelegram.lastText).contains("Cảm ơn Bác đã góp ý! Bác có đồng ý cho cháu lưu lại nội dung tin nhắn này");
        assertThat(fakeTelegram.lastText).contains("cháu đã che hết số điện thoại và tên riêng");

        // 4. Kiểm tra hiển thị 2 nút bấm [Đồng ý đóng góp] và [Không, cảm ơn]
        assertThat(fakeTelegram.lastMarkup).isNotNull();
        List<List<Map<String, Object>>> keyboard = extractKeyboard(fakeTelegram.lastMarkup);
        assertThat(keyboard).isNotEmpty();

        List<Map<String, Object>> buttons = keyboard.get(0);
        assertThat(buttons).hasSize(2);
        assertThat(buttons.get(0).get("text")).isEqualTo("Đồng ý đóng góp");
        assertThat((String) buttons.get(0).get("callback_data")).startsWith("donate:agree:" + checkId);
        assertThat(buttons.get(1).get("text")).isEqualTo("Không, cảm ơn");
        assertThat((String) buttons.get(1).get("callback_data")).startsWith("donate:decline:" + checkId);

        // 5. Kiểm tra audit log ghi nhận FEEDBACK_REPORTED
        assertThat(fakeAuditRepo.events).contains("FEEDBACK_REPORTED");
    }

    @Test
    @DisplayName("BE-30: Bấm [Bot báo sai] xưng hô theo cài đặt người dùng (Cô/Chú)")
    void testFeedbackWrongWithCustomAddressForm() {
        String checkId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        String userId = "tg:111";

        UserRecord user = new UserRecord(userId, "222");
        user.setAddressForm("Cô");
        fakeUserRepo.saveUser(user);

        fakeCheckRepo.put(new CheckRecord(
                checkId, userId, "safe", "LOW", 10,
                "none", "none", "rule", List.of(), List.of(), List.of(),
                false, null, Instant.now().toString(), Instant.now().getEpochSecond() + 1800));

        String callbackJson = callbackUpdate(102, "fb:wrong:" + checkId, 111, 222);
        handler.handleRequest(createRequest(callbackJson), mockContext);

        assertThat(fakeTelegram.lastText).contains("Cảm ơn Cô đã góp ý! Cô có đồng ý cho cháu lưu lại nội dung tin nhắn này");
    }

    @Test
    @DisplayName("BE-30: Người dùng bấm [Đồng ý đóng góp] thì lưu masked_text vào S3 donated/<hash(uid)>/ và cập nhật consent")
    void testDonateAgreeSavesMaskedTextToS3AndUpdatesConsent() {
        String checkId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        String userId = "tg:111";
        String chatId = "222";
        String masked = "Cháu nhận được tin nhắn từ số [SDT] thông báo trúng thưởng [TEN_RIENG]";

        // Lưu ngữ cảnh phiên kiểm tra vào SESSION#{chat_id}
        fakeSessionStore.put(new SessionRecord(
                chatId, checkId, userId, masked, null, 1, Instant.now().getEpochSecond() + 1800));

        // Người dùng bấm [Đồng ý đóng góp]
        String callbackJson = callbackUpdate(103, "donate:agree:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackJson), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);

        // 1. Kiểm tra S3 lưu đúng đường dẫn donated/<hash(uid)>/
        String expectedHash = S3DonationStore.hashUid(userId);
        String expectedKey = "donated/" + expectedHash + "/" + checkId + ".txt";

        assertThat(fakeDonationStore.savedSamples).containsKey(expectedKey);
        assertThat(fakeDonationStore.savedSamples.get(expectedKey)).isEqualTo(masked);

        // 2. Kiểm tra cập nhật mục đích "donate" trong UserRepo
        Optional<UserRecord> userOpt = fakeUserRepo.getUser(userId);
        assertThat(userOpt).isPresent();
        assertThat(userOpt.get().getPurposes()).containsEntry("donate", true);

        // 3. Kiểm tra audit trail
        assertThat(fakeAuditRepo.events).contains("CONSENT_GIVEN");

        // 4. Kiểm tra bot cảm ơn
        assertThat(fakeTelegram.lastCallbackAnswer).contains("Cảm ơn");
        assertThat(fakeTelegram.lastText).contains("lưu lại nội dung tin nhắn để các kỹ sư kiểm tra");
    }

    @Test
    @DisplayName("BE-30: Người dùng bấm [Không, cảm ơn] thì TUYỆT ĐỐI KHÔNG lưu vào S3")
    void testDonateDeclineNeverSavesToS3() {
        String checkId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        String userId = "tg:111";
        String chatId = "222";
        String masked = "Nội dung nhạy cảm đã che mờ";

        fakeSessionStore.put(new SessionRecord(
                chatId, checkId, userId, masked, null, 1, Instant.now().getEpochSecond() + 1800));

        // Người dùng bấm [Không, cảm ơn]
        String callbackJson = callbackUpdate(104, "donate:decline:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackJson), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);

        // 1. Tuyệt đối không lưu vào S3
        assertThat(fakeDonationStore.savedSamples).isEmpty();

        // 2. Ghi nhận từ chối trong Audit trail
        assertThat(fakeAuditRepo.events).contains("DONATION_DECLINED");

        // 3. Phản hồi lịch sự xác nhận không lưu
        assertThat(fakeTelegram.lastText).contains("không lưu lại nội dung tin nhắn này");
    }

    @Test
    @DisplayName("BE-30: Người dùng đồng ý đóng góp nhưng phiên SESSION đã hết hạn")
    void testDonateAgreeWhenSessionExpired() {
        String checkId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

        // Không có dữ liệu trong SessionStore (đã hết hạn)
        String callbackJson = callbackUpdate(105, "donate:agree:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackJson), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);

        // Không lưu gì vào S3 vì không có masked_text
        assertThat(fakeDonationStore.savedSamples).isEmpty();

        // Thông báo phiên đã hết hạn
        assertThat(fakeTelegram.lastText).contains("phiên kiểm tra này đã kết thúc");
    }

    @Test
    @DisplayName("BE-30: Callback 'dn:<check_id>' (ButtonMapper kind=donate) kích hoạt luồng xin phép")
    void testDirectDonateButtonKindCallback() {
        String checkId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        String userId = "tg:111";

        fakeCheckRepo.put(new CheckRecord(
                checkId, userId, "unsafe", "HIGH", 85,
                "impersonation", "fake_brand", "rule", List.of(), List.of(), List.of(),
                true, null, Instant.now().toString(), Instant.now().getEpochSecond() + 1800));

        String callbackJson = callbackUpdate(106, "dn:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackJson), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(fakeCheckRepo.get(userId, checkId).get().feedback()).isEqualTo("wrong");
        assertThat(fakeTelegram.lastText).contains("Cảm ơn Bác đã góp ý!");
    }

    @Test
    @DisplayName("BE-30: Callback 'fb:wrong' không kèm checkId thì lấy checkId từ SESSION")
    void testFeedbackWrongFallbackToCheckIdFromSession() {
        String checkId = "01ARZ3NDEKTSV4RRFFQ69G5FAV";
        String userId = "tg:111";
        String chatId = "222";

        fakeSessionStore.put(new SessionRecord(
                chatId, checkId, userId, "Tin nhắn text", null, 1, Instant.now().getEpochSecond() + 1800));

        fakeCheckRepo.put(new CheckRecord(
                checkId, userId, "unsafe", "HIGH", 90,
                "impersonation", "fake_brand", "rule", List.of(), List.of(), List.of(),
                true, null, Instant.now().toString(), Instant.now().getEpochSecond() + 1800));

        String callbackJson = callbackUpdate(107, "fb:wrong", 111, 222);
        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackJson), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(fakeCheckRepo.get(userId, checkId).get().feedback()).isEqualTo("wrong");
    }

    @Test
    @DisplayName("BE-30: Chống bấm lặp lại nút [Bot báo sai] - lần 2 trả về FEEDBACK_ALREADY_REPORTED và đổi nút")
    void testFeedbackWrongDuplicateClickPrevented() {
        String checkId = "01JD9ZCHECKDUP001";
        String userId = "tg:111";

        fakeCheckRepo.put(new CheckRecord(
                checkId, userId, "unsafe", "HIGH", 90,
                "impersonation", "fake_brand", "rule", List.of(), List.of(), List.of(),
                true, null, Instant.now().toString(), Instant.now().getEpochSecond() + 1800));

        // Lần 1: Bấm báo sai
        String cb1 = callbackUpdate(201, "fb:wrong:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res1 = handler.handleRequest(createRequest(cb1), mockContext);
        assertThat(res1.getStatusCode()).isEqualTo(200);
        assertThat(res1.getBody()).isEqualTo("FEEDBACK_WRONG_HANDLED");
        assertThat(fakeCheckRepo.get(userId, checkId).get().feedback()).isEqualTo("wrong");
        assertThat(fakeTelegram.lastEditMessageId).isEqualTo(99);

        // Lần 2: Cố tình bấm lại fb:wrong
        String cb2 = callbackUpdate(202, "fb:wrong:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res2 = handler.handleRequest(createRequest(cb2), mockContext);
        assertThat(res2.getStatusCode()).isEqualTo(200);
        assertThat(res2.getBody()).isEqualTo("FEEDBACK_ALREADY_REPORTED");
        assertThat(fakeTelegram.lastCallbackAnswer).contains("đã được ghi nhận");

        // Lần 3: Bấm vào nút fb:already_reported
        String cb3 = callbackUpdate(203, "fb:already_reported", 111, 222);
        APIGatewayProxyResponseEvent res3 = handler.handleRequest(createRequest(cb3), mockContext);
        assertThat(res3.getStatusCode()).isEqualTo(200);
        assertThat(res3.getBody()).isEqualTo("FEEDBACK_ALREADY_REPORTED");
    }

    @Test
    @DisplayName("BE-30: Chống bấm lặp lại nút [Đồng ý đóng góp] - xoá nút trên message và xoá session ngay lần 1")
    void testDonateAgreeDuplicateClickPrevented() {
        String checkId = "01JD9ZCHECKAGREE01";
        String userId = "tg:111";
        String chatId = "222";

        fakeSessionStore.put(new SessionRecord(
                chatId, checkId, userId, "Chuyển tiền gấp nha bạn", null, 1, Instant.now().getEpochSecond() + 1800));

        // Lần 1: Bấm đồng ý
        String cb1 = callbackUpdate(301, "donate:agree:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res1 = handler.handleRequest(createRequest(cb1), mockContext);
        assertThat(res1.getStatusCode()).isEqualTo(200);
        assertThat(res1.getBody()).isEqualTo("DONATION_SAVED");
        assertThat(fakeDonationStore.savedSamples).hasSize(1);
        assertThat(fakeTelegram.lastEditMessageId).isEqualTo(99);
        // Session phải bị xoá ngay lập tức
        assertThat(fakeSessionStore.get(chatId)).isEmpty();

        // Lần 2: Cố tình bấm lại đồng ý
        String cb2 = callbackUpdate(302, "donate:agree:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res2 = handler.handleRequest(createRequest(cb2), mockContext);
        assertThat(res2.getStatusCode()).isEqualTo(200);
        assertThat(res2.getBody()).isEqualTo("DONATION_SESSION_EXPIRED");
        // Không lưu file lần 2 vào S3
        assertThat(fakeDonationStore.savedSamples).hasSize(1);
        assertThat(fakeTelegram.lastCallbackAnswer).contains("ghi nhận");
    }

    @Test
    @DisplayName("BE-30: Chống bấm lặp lại nút [Không, cảm ơn] - xoá session và không lưu S3")
    void testDonateDeclineDuplicateClickPrevented() {
        String checkId = "01JD9ZCHECKDECL01";
        String userId = "tg:111";
        String chatId = "222";

        fakeSessionStore.put(new SessionRecord(
                chatId, checkId, userId, "Nội dung cần che", null, 1, Instant.now().getEpochSecond() + 1800));

        // Lần 1: Bấm từ chối
        String cb1 = callbackUpdate(401, "donate:decline:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res1 = handler.handleRequest(createRequest(cb1), mockContext);
        assertThat(res1.getStatusCode()).isEqualTo(200);
        assertThat(res1.getBody()).isEqualTo("DONATION_DECLINED");
        assertThat(fakeDonationStore.savedSamples).isEmpty();
        assertThat(fakeSessionStore.get(chatId)).isEmpty();

        // Lần 2: Cố tình bấm lại từ chối
        String cb2 = callbackUpdate(402, "donate:decline:" + checkId, 111, 222);
        APIGatewayProxyResponseEvent res2 = handler.handleRequest(createRequest(cb2), mockContext);
        assertThat(res2.getStatusCode()).isEqualTo(200);
        assertThat(res2.getBody()).isEqualTo("DONATION_ALREADY_PROCESSED");
        assertThat(fakeDonationStore.savedSamples).isEmpty();
        assertThat(fakeTelegram.lastCallbackAnswer).contains("đã được ghi nhận");
    }

    // --- Helpers & Fakes ---

    private APIGatewayProxyRequestEvent createRequest(String body) {
        return new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", SECRET))
                .withBody(body);
    }

    private static String callbackUpdate(long updateId, String data, long fromId, long chatId) {
        return """
                {
                  "update_id": %d,
                  "callback_query": {
                    "id": "query-%d",
                    "from": { "id": %d, "is_bot": false, "first_name": "Nguyen" },
                    "message": {
                      "message_id": 99,
                      "chat": { "id": %d, "type": "private" },
                      "date": 1729000000,
                      "text": "Ket qua kiem tra"
                    },
                    "data": "%s"
                  }
                }
                """.formatted(updateId, updateId, fromId, chatId, data);
    }

    @SuppressWarnings("unchecked")
    private static List<List<Map<String, Object>>> extractKeyboard(Map<String, Object> markup) {
        return (List<List<Map<String, Object>>>) markup.get("inline_keyboard");
    }

    private static Context createMockContext() {
        return new Context() {
            @Override
            public String getAwsRequestId() {
                return "req-be30";
            }

            @Override
            public String getLogGroupName() {
                return null;
            }

            @Override
            public String getLogStreamName() {
                return null;
            }

            @Override
            public String getFunctionName() {
                return "webhook";
            }

            @Override
            public String getFunctionVersion() {
                return "1";
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
                return 30000;
            }

            @Override
            public int getMemoryLimitInMB() {
                return 512;
            }

            @Override
            public LambdaLogger getLogger() {
                return new LambdaLogger() {
                    @Override
                    public void log(String s) {
                    }

                    @Override
                    public void log(byte[] b) {
                    }
                };
            }

            @Override
            public String getInvokedFunctionArn() {
                return null;
            }
        };
    }

    private static class FakeTelegramClient extends TelegramClient {
        Long lastChatId;
        String lastText;
        Map<String, Object> lastMarkup;
        String lastCallbackAnswer;
        Integer lastEditMessageId;
        Map<String, Object> lastEditMarkup;

        FakeTelegramClient() {
            super("fake-token");
        }

        @Override
        public int sendText(long chatId, String text, Map<String, Object> replyMarkup) {
            this.lastChatId = chatId;
            this.lastText = text;
            this.lastMarkup = replyMarkup;
            return 888;
        }

        @Override
        public void answerCallbackQuery(String callbackQueryId, String text) {
            this.lastCallbackAnswer = text;
        }

        @Override
        public void editReplyMarkup(long chatId, int messageId, Map<String, Object> replyMarkup) {
            this.lastChatId = chatId;
            this.lastEditMessageId = messageId;
            this.lastEditMarkup = replyMarkup;
        }
    }

    private static class InMemoryUserRepo extends UserRepo {
        private final Map<String, UserRecord> store = new HashMap<>();

        InMemoryUserRepo() {
            super();
        }

        @Override
        public Optional<UserRecord> getUser(String userId) {
            return Optional.ofNullable(store.get(userId));
        }

        @Override
        public void saveUser(UserRecord user) {
            store.put(user.getUserId(), user);
        }

        @Override
        public void updateConsent(String userId, String policySha256, String version, Map<String, Boolean> purposes) {
            UserRecord u = store.computeIfAbsent(userId, id -> new UserRecord(id, "222"));
            u.setPolicySha256(policySha256);
            u.setConsentVersion(version);
            u.setConsentedAt(Instant.now().toString());
            Map<String, Boolean> merged = new HashMap<>(u.getPurposes());
            if (purposes != null) {
                merged.putAll(purposes);
            }
            u.setPurposes(merged);
        }

        @Override
        public void updateAddressForm(String userId, String addressForm) {
            UserRecord u = store.computeIfAbsent(userId, id -> new UserRecord(id, "222"));
            u.setAddressForm(addressForm);
        }
    }

    private static class InMemoryAuditRepo extends AuditRepo {
        final List<String> events = new ArrayList<>();

        InMemoryAuditRepo() {
            super();
        }

        @Override
        public void recordEvent(String userId, String event, String policyVersion, String policySha256, String details) {
            events.add(event);
        }
    }

    private static class InMemoryCheckRepo implements CheckRepo {
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
            } else {
                store.put(userId + "#" + checkId, new CheckRecord(
                        checkId, userId, "unknown", "UNSURE", 0, "", "", "",
                        List.of(), List.of(), List.of(), false, feedback,
                        Instant.now().toString(), 0L));
            }
        }
    }

    private static class InMemorySessionStore implements SessionStore {
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

    private static class InMemoryDonationStore implements DonationStore {
        final Map<String, String> savedSamples = new ConcurrentHashMap<>();

        @Override
        public String saveDonatedSample(String userId, String checkId, String maskedText) {
            String uidHash = S3DonationStore.hashUid(userId);
            String key = "donated/" + uidHash + "/" + (checkId != null ? checkId : "sample") + ".txt";
            savedSamples.put(key, maskedText);
            return key;
        }
    }
}

