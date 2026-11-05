package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.store.AuditRecord;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.RateLimitRepo;
import com.askfirst.common.store.UserRecord;
import com.askfirst.common.store.UserRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookHandlerTest {

    private static final String SECRET = "my-secret-token-123";
    private static final String POLICY_SHA256 = WebhookHandler.DEFAULT_POLICY_SHA256;

    private FakeTelegramClient fakeTelegram;
    private FakeSqsClient fakeSqs;
    private InMemoryUserRepo fakeUserRepo;
    private InMemoryAuditRepo fakeAuditRepo;
    private InMemoryRateLimitRepo fakeRateLimitRepo;
    private WebhookHandler handler;
    private Context mockContext;

    @BeforeEach
    void setUp() {
        fakeTelegram = new FakeTelegramClient();
        fakeSqs = new FakeSqsClient();
        fakeUserRepo = new InMemoryUserRepo();
        fakeAuditRepo = new InMemoryAuditRepo();
        fakeRateLimitRepo = new InMemoryRateLimitRepo();

        handler = new WebhookHandler(
                fakeTelegram,
                fakeSqs,
                "https://sqs.mock.queue",
                SECRET,
                fakeUserRepo,
                fakeAuditRepo,
                fakeRateLimitRepo,
                POLICY_SHA256);
        mockContext = createMockContext();
    }

    @Test
    @DisplayName("Returns 401 when X-Telegram-Bot-Api-Secret-Token is missing or incorrect")
    void testSecretVerificationFails() {
        APIGatewayProxyRequestEvent noHeaderReq = new APIGatewayProxyRequestEvent()
                .withBody("{\"update_id\": 1}");
        APIGatewayProxyResponseEvent res1 = handler.handleRequest(noHeaderReq, mockContext);
        assertThat(res1.getStatusCode()).isEqualTo(401);
        assertThat(res1.getBody()).isEqualTo("UNAUTHORIZED");

        APIGatewayProxyRequestEvent wrongSecretReq = new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", "wrong-secret"))
                .withBody("{\"update_id\": 1}");
        APIGatewayProxyResponseEvent res2 = handler.handleRequest(wrongSecretReq, mockContext);
        assertThat(res2.getStatusCode()).isEqualTo(401);
        assertThat(res2.getBody()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("BE-25: Unconsented user sending /start receives welcome message with consent buttons")
    void testStartCommandUnconsented() {
        String updateJson = """
                {
                  "update_id": 101,
                  "message": {
                    "message_id": 1,
                    "from": { "id": 111, "is_bot": false, "first_name": "Nam" },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "/start"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("COMMAND_HANDLED");
        assertThat(fakeTelegram.lastText).contains("Chào mừng Bác đến với AskFirst");
        assertThat(fakeTelegram.lastText).contains("Nghị định 356");
        assertThat(fakeTelegram.lastMarkup).isNotNull();
    }

    @Test
    @DisplayName("BE-25: Clicking consent:agree updates UserRepo and AuditRepo, sends confirmation")
    void testConsentAgreeCallback() {
        String callbackJson = """
                {
                  "update_id": 102,
                  "callback_query": {
                    "id": "cb_102",
                    "from": { "id": 111, "is_bot": false, "first_name": "Nam" },
                    "message": {
                      "message_id": 1,
                      "chat": { "id": 222, "type": "private" },
                      "date": 1729000000
                    },
                    "data": "consent:agree"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("CALLBACK_HANDLED");

        // UserRepo updated
        assertThat(fakeUserRepo.hasValidConsent("tg:111", POLICY_SHA256)).isTrue();
        // AuditRepo recorded
        assertThat(fakeAuditRepo.events).contains("CONSENT_GIVEN");
        // Confirmation message sent
        assertThat(fakeTelegram.lastText).contains("Cảm ơn Bác đã đồng ý điều khoản");
    }

    @Test
    @DisplayName("BE-25: Clicking consent:decline records audit and sends polite refusal")
    void testConsentDeclineCallback() {
        String callbackJson = """
                {
                  "update_id": 103,
                  "callback_query": {
                    "id": "cb_103",
                    "from": { "id": 111, "is_bot": false },
                    "message": {
                      "message_id": 1,
                      "chat": { "id": 222, "type": "private" },
                      "date": 1729000000
                    },
                    "data": "consent:decline"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(fakeAuditRepo.events).contains("CONSENT_DECLINED");
        assertThat(fakeTelegram.lastText).contains("không thu thập hoặc phân tích");
    }

    @Test
    @DisplayName("BE-25: Unconsented user sending message is BLOCKED from SQS enqueue")
    void testUnconsentedMessageBlockedFromSqs() {
        String updateJson = """
                {
                  "update_id": 104,
                  "message": {
                    "message_id": 2,
                    "from": { "id": 111, "is_bot": false, "first_name": "Nam" },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "Kiểm tra stk 123456"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("CONSENT_REQUIRED");
        assertThat(fakeSqs.messageSent.get()).isFalse(); // NEVER queued
        assertThat(fakeTelegram.lastText).contains("Tôi đồng ý");
    }

    @Test
    @DisplayName("BE-25: Changing policy_sha256 blocks previously consented user until re-consenting")
    void testPolicyShaMismatchBlocksUser() {
        // User consented with OLD policy
        fakeUserRepo.updateConsent("tg:111", "old_policy_sha", "0.9", Map.of("analyze", true));

        String updateJson = """
                {
                  "update_id": 105,
                  "message": {
                    "message_id": 3,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "Kiểm tra link"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("CONSENT_REQUIRED");
        assertThat(fakeSqs.messageSent.get()).isFalse();
    }

    @Test
    @DisplayName("BE-25: User without voice consent has voice messages rejected")
    void testVoiceMessageRejectedWithoutVoiceConsent() {
        // User consented to analyze, but NOT voice
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true, "voice", false));

        String voiceJson = """
                {
                  "update_id": 106,
                  "message": {
                    "message_id": 4,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "voice": {
                      "file_id": "v_123",
                      "file_unique_id": "uv_123",
                      "mime_type": "audio/ogg",
                      "duration": 10
                    }
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(voiceJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("VOICE_CONSENT_REQUIRED");
        assertThat(fakeSqs.messageSent.get()).isFalse();
        assertThat(fakeTelegram.lastText).contains("cần có sự đồng ý riêng về xử lý dữ liệu âm thanh");
    }

    @Test
    @DisplayName("BE-25: Basic commands: /caidat, /huongdan, /bilua")
    void testBasicCommands() {
        // /caidat
        APIGatewayProxyResponseEvent caidatRes = handler.handleRequest(
                createRequest(
                        "{\"update_id\": 107, \"message\": {\"message_id\": 5, \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 222, \"type\": \"private\"}, \"date\": 1729000000, \"text\": \"/caidat\"}}"),
                mockContext);
        assertThat(caidatRes.getBody()).isEqualTo("COMMAND_HANDLED");
        assertThat(fakeTelegram.lastText).contains("Cài đặt cách xưng hô thân mật");

        // Selecting xungho:co
        handler.handleRequest(
                createRequest(
                        "{\"update_id\": 108, \"callback_query\": {\"id\": \"cb_108\", \"from\": {\"id\": 111, \"is_bot\": false}, \"message\": {\"message_id\": 5, \"chat\": {\"id\": 222, \"type\": \"private\"}, \"date\": 1729000000}, \"data\": \"xungho:co\"}}"),
                mockContext);
        assertThat(fakeUserRepo.getUser("tg:111").get().getAddressForm()).isEqualTo("Cô");

        // /huongdan
        APIGatewayProxyResponseEvent hdRes = handler.handleRequest(
                createRequest(
                        "{\"update_id\": 109, \"message\": {\"message_id\": 6, \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 222, \"type\": \"private\"}, \"date\": 1729000000, \"text\": \"/huongdan\"}}"),
                mockContext);
        assertThat(hdRes.getBody()).isEqualTo("COMMAND_HANDLED");
        assertThat(fakeTelegram.lastText).contains("Hướng dẫn sử dụng AskFirst");

        // /bilua (emergency checklist)
        APIGatewayProxyResponseEvent biluaRes = handler.handleRequest(
                createRequest(
                        "{\"update_id\": 110, \"message\": {\"message_id\": 7, \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 222, \"type\": \"private\"}, \"date\": 1729000000, \"text\": \"/bilua\"}}"),
                mockContext);
        assertThat(biluaRes.getBody()).isEqualTo("COMMAND_HANDLED");
        assertThat(fakeTelegram.lastText).contains("CÁC VIỆC CẦN LÀM NGAY (30 PHÚT ĐẦU");
        assertThat(fakeTelegram.lastText).contains("Khóa thẻ / tài khoản ngân hàng");
        assertThat(fakeTelegram.lastText).contains("113");
    }

    @Test
    @DisplayName("BE-26: Rate limit: 30 requests/hour allowed, 31st request receives friendly reminder")
    void testRateLimitEnforcement() {
        // Give consent first
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        String updateTemplate = """
                {
                  "update_id": %d,
                  "message": {
                    "message_id": %d,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "Kiểm tra tin nhắn %d"
                  }
                }
                """;

        // Gửi 30 tin liên tiếp
        for (int i = 1; i <= 30; i++) {
            APIGatewayProxyResponseEvent res = handler.handleRequest(
                    createRequest(updateTemplate.formatted(200 + i, i, i)),
                    mockContext);
            assertThat(res.getStatusCode()).isEqualTo(200);
            assertThat(res.getBody()).isEqualTo("OK");
        }
        assertThat(fakeSqs.sentCount.get()).isEqualTo(30);

        // Tin thứ 31 trong giờ: Bị chặn và nhận câu nhắc nhẹ nhàng
        APIGatewayProxyResponseEvent res31 = handler.handleRequest(
                createRequest(updateTemplate.formatted(231, 31, 31)),
                mockContext);
        assertThat(res31.getStatusCode()).isEqualTo(200);
        assertThat(res31.getBody()).isEqualTo("RATE_LIMITED");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(30); // KHÔNG enqueue thêm
        assertThat(fakeTelegram.lastText)
                .contains("Bác ơi, hiện tại Bác đã gửi kiểm tra khá nhiều tin trong ngày rồi ạ");
        assertThat(fakeTelegram.lastText).contains("30 tin/giờ");
    }

    private APIGatewayProxyRequestEvent createRequest(String body) {
        return new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", SECRET))
                .withBody(body);
    }

    // In-memory test stubs
    private static class FakeTelegramClient extends TelegramClient {
        Long lastChatId;
        String lastText;
        Map<String, Object> lastMarkup;

        public FakeTelegramClient() {
            super("fake-token");
        }

        @Override
        public int sendText(long chatId, String text, Map<String, Object> replyMarkup) {
            this.lastChatId = chatId;
            this.lastText = text;
            this.lastMarkup = replyMarkup;
            return 999;
        }

        @Override
        public void answerCallbackQuery(String callbackQueryId, String text) {
        }
    }

    private static class FakeSqsClient implements SqsClient {
        AtomicBoolean messageSent = new AtomicBoolean(false);
        AtomicInteger sentCount = new AtomicInteger(0);

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }

        @Override
        public SendMessageResponse sendMessage(SendMessageRequest req) {
            messageSent.set(true);
            sentCount.incrementAndGet();
            return SendMessageResponse.builder().messageId("mock-sqs-id").build();
        }
    }

    private static class InMemoryUserRepo extends UserRepo {
        private final Map<String, UserRecord> store = new HashMap<>();

        public InMemoryUserRepo() {
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
            UserRecord u = store.computeIfAbsent(userId, id -> new UserRecord(id, "123"));
            u.setPolicySha256(policySha256);
            u.setConsentVersion(version);
            u.setConsentedAt(java.time.Instant.now().toString());
            u.setPurposes(purposes);
        }

        @Override
        public void updateAddressForm(String userId, String addressForm) {
            UserRecord u = store.computeIfAbsent(userId, id -> new UserRecord(id, "123"));
            u.setAddressForm(addressForm);
        }
    }

    private static class InMemoryAuditRepo extends AuditRepo {
        List<String> events = new ArrayList<>();

        public InMemoryAuditRepo() {
            super();
        }

        @Override
        public void recordEvent(String userId, String event, String policyVersion, String policySha256,
                String details) {
            events.add(event);
        }
    }

    private static class InMemoryRateLimitRepo extends RateLimitRepo {
        private final Map<String, Long> counts = new HashMap<>();

        public InMemoryRateLimitRepo() {
            super();
        }

        @Override
        public RateLimitResult checkAndIncrement(String userId, int maxPerHour, int maxPerDay) {
            long current = counts.getOrDefault(userId, 0L) + 1;
            counts.put(userId, current);

            if (current > maxPerHour) {
                return new RateLimitResult(false, current, current, "Vượt quá giới hạn " + maxPerHour + " tin/giờ");
            }
            return new RateLimitResult(true, current, current, "OK");
        }
    }

    private static Context createMockContext() {
        return new Context() {
            @Override
            public String getAwsRequestId() {
                return "req-1";
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
            public String getInvokedFunctionArn() {
                return null;
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
        };
    }
}
