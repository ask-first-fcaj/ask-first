package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.store.AuditRecord;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.DynamoStoreService;
import com.askfirst.common.store.GuardianRecord;
import com.askfirst.common.store.GuardianRepo;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
        assertThat(fakeTelegram.lastText).contains("Chào Bác, cháu là AskFirst");
        assertThat(fakeTelegram.lastText).contains("Chính sách quyền riêng tư");
        assertThat(fakeTelegram.lastText).contains("<b>không bao giờ</b> hỏi mã OTP");
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
        assertThat(fakeTelegram.lastText).contains("Cảm ơn bác!");
        assertThat(fakeTelegram.lastText).contains("Tôi bị lừa rồi");
        assertThat(fakeTelegram.lastText).contains("/bilua");
        assertThat(fakeTelegram.lastText).contains("ghim cuộc trò chuyện");
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
        assertThat(fakeTelegram.lastText).contains("chưa có đồng ý thì cháu chưa kiểm tra tin giúp bác được");
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
        assertThat(fakeTelegram.lastText).contains("Đồng ý");
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
    @DisplayName("BE-34: Voice chưa hỗ trợ kể cả khi đã bật đồng ý voice, kh tạo job")
    void testVoiceMessageNotSupported() {
        // Đã bật cả voice: vẫn từ chối vì FR2 hoãn
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true, "voice", true));

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
        assertThat(res.getBody()).isEqualTo("UNSUPPORTED_VOICE");
        assertThat(fakeSqs.messageSent.get()).isFalse();
        assertThat(fakeTelegram.lastText).contains("chưa hỗ trợ kiểm tra tin nhắn thoại");
    }

    private static String photoJson(long fileSize) {
        return """
                {
                  "update_id": 107,
                  "message": {
                    "message_id": 5,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "photo": [
                      { "file_id": "p_s", "file_unique_id": "up_s", "width": 90, "height": 90, "file_size": 1000 },
                      { "file_id": "p_l", "file_unique_id": "up_l", "width": 4000, "height": 4000, "file_size": %d }
                    ]
                  }
                }
                """.formatted(fileSize);
    }

    @Test
    @DisplayName("BE-34: Ảnh > 20 MB bị từ chối kèm câu giải thích, kh tạo job")
    void testPhotoTooLargeRejected() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(photoJson(25L * 1024 * 1024)), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("PHOTO_TOO_LARGE");
        assertThat(fakeSqs.messageSent.get()).isFalse();
        assertThat(fakeTelegram.lastText).contains("quá 20MB");
    }

    @Test
    @DisplayName("BE-34: Ảnh đúng 20 MB kh bị chặn bởi giới hạn dung lượng")
    void testPhotoAtLimitNotRejected() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(photoJson(WebhookHandler.MAX_PHOTO_BYTES)), mockContext);
        assertThat(res.getBody()).isNotEqualTo("PHOTO_TOO_LARGE");
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
        assertThat(fakeTelegram.lastText).contains("Cách nhờ cháu kiểm tra một tin");
        assertThat(fakeTelegram.lastText).contains("chụp màn hình");

        // /bilua (emergency checklist)
        APIGatewayProxyResponseEvent biluaRes = handler.handleRequest(
                createRequest(
                        "{\"update_id\": 110, \"message\": {\"message_id\": 7, \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 222, \"type\": \"private\"}, \"date\": 1729000000, \"text\": \"/bilua\"}}"),
                mockContext);
        assertThat(biluaRes.getBody()).isEqualTo("COMMAND_HANDLED");
        assertThat(fakeTelegram.lastText).contains("Nghi bị lừa: cô làm ngay theo thứ tự");
        assertThat(fakeTelegram.lastText).contains("Khóa thẻ và tài khoản ngân hàng");
        assertThat(fakeTelegram.lastText).contains("113");
    }

    @Test
    @DisplayName("SH-08: /start when already consented greets user gently without re-asking consent")
    void testStartCommandWhenAlreadyConsented() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        String updateJson = """
                {
                  "update_id": 111,
                  "message": {
                    "message_id": 8,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "/start"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("COMMAND_HANDLED");
        assertThat(fakeTelegram.lastText).isEqualTo("Chào bác ạ. Có tin nào lạ, bác gửi vào đây cho cháu xem nhé.");
    }

    @Test
    @DisplayName("SH-08: Text 'Tôi bị lừa rồi' triggers emergency checklist (/bilua fast path)")
    void testEmergencyTextMessageTriggersBilua() {
        String updateJson = """
                {
                  "update_id": 112,
                  "message": {
                    "message_id": 9,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "Tôi bị lừa rồi"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("COMMAND_HANDLED");
        assertThat(fakeTelegram.lastText).contains("Nghi bị lừa: bác làm ngay theo thứ tự");
        assertThat(fakeTelegram.lastText).contains("Khóa thẻ và tài khoản ngân hàng");
    }

    @Test
    @DisplayName("SH-08: Custom policyUrl is embedded in unconsented /start welcome message")
    void testCustomPolicyUrl() {
        WebhookHandler customHandler = new WebhookHandler(
                fakeTelegram, fakeSqs, "https://sqs.mock.queue", SECRET,
                fakeUserRepo, fakeAuditRepo, fakeRateLimitRepo,
                null, null, POLICY_SHA256, null, false,
                "AskFirstBot", "https://custom.askfirst.vn/privacy", null, null, null, null, null);

        String updateJson = """
                {
                  "update_id": 113,
                  "message": {
                    "message_id": 10,
                    "from": { "id": 999, "is_bot": false },
                    "chat": { "id": 888, "type": "private" },
                    "date": 1729000000,
                    "text": "/start"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = customHandler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(fakeTelegram.lastText).contains("href=\"https://custom.askfirst.vn/privacy\"");
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

    // ------------------------------------------------------------------ bổ sung: bảo mật, dedup, thu hồi đồng ý

    @Test
    @DisplayName("Fail-closed: webhook chưa cấu hình secret thì từ chối mọi request")
    void testMissingSecretConfigFailsClosed() {
        WebhookHandler noSecret = new WebhookHandler(fakeTelegram, fakeSqs, "https://sqs.mock.queue", null,
                fakeUserRepo, fakeAuditRepo, fakeRateLimitRepo, POLICY_SHA256);

        APIGatewayProxyResponseEvent res = noSecret.handleRequest(createRequest(textUpdate(301, "kiểm tra")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(401);
        assertThat(fakeSqs.sentCount.get()).isZero();
    }

    @Test
    @DisplayName("BE-22: Telegram gửi lại cùng update_id thì không enqueue lần hai")
    void testDuplicateUpdateIsNotEnqueuedTwice() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        WebhookHandler h = handlerWithDedup(new InMemoryDedup());

        APIGatewayProxyResponseEvent first = h.handleRequest(createRequest(textUpdate(401, "kiểm tra")), mockContext);
        APIGatewayProxyResponseEvent retry = h.handleRequest(createRequest(textUpdate(401, "kiểm tra")), mockContext);

        assertThat(first.getBody()).isEqualTo("OK");
        assertThat(retry.getBody()).isEqualTo("DUPLICATE");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-22: SQS lỗi thì nhả khóa dedup để Telegram gửi lại được")
    void testSqsFailureReleasesDedupForRetry() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        InMemoryDedup dedup = new InMemoryDedup();
        WebhookHandler h = handlerWithDedup(dedup);

        fakeSqs.fail = true;
        APIGatewayProxyResponseEvent failed = h.handleRequest(createRequest(textUpdate(402, "kiểm tra")), mockContext);
        assertThat(failed.getStatusCode()).isEqualTo(500);
        assertThat(failed.getBody()).isEqualTo("SQS_FAILED");
        assertThat(dedup.released).containsExactly("402");

        fakeSqs.fail = false;
        APIGatewayProxyResponseEvent retry = h.handleRequest(createRequest(textUpdate(402, "kiểm tra")), mockContext);
        assertThat(retry.getBody()).isEqualTo("OK");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Thiếu hàng đợi thì báo lỗi rõ, không hứa 'đang kiểm tra' với job sẽ bị mất")
    void testMissingQueueFailsLoudly() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        WebhookHandler h = new WebhookHandler(fakeTelegram, fakeSqs, null, SECRET,
                fakeUserRepo, fakeAuditRepo, fakeRateLimitRepo, POLICY_SHA256, new InMemoryDedup());

        APIGatewayProxyResponseEvent res = h.handleRequest(createRequest(textUpdate(403, "kiểm tra")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(500);
        assertThat(res.getBody()).isEqualTo("QUEUE_NOT_CONFIGURED");
        assertThat(fakeSqs.sentCount.get()).isZero();
        assertThat(fakeTelegram.lastText == null || !fakeTelegram.lastText.startsWith("⏳")).isTrue();
    }

    @Test
    @DisplayName("BE-25: Từ chối đồng ý thu hồi quyền phân tích, không chỉ ghi audit")
    void testDeclineRevokesConsent() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        assertThat(fakeUserRepo.hasValidConsent("tg:111", POLICY_SHA256)).isTrue();

        handler.handleRequest(createRequest(callbackUpdate(404, "consent:decline")), mockContext);

        assertThat(fakeUserRepo.hasValidConsent("tg:111", POLICY_SHA256)).isFalse();
        assertThat(fakeAuditRepo.events).contains("CONSENT_DECLINED");
    }

    @Test
    @DisplayName("BE-25: Đồng ý chỉ bật analyze, không đặt lại các mục đích khác")
    void testAgreeOnlyEnablesAnalyze() {
        handler.handleRequest(createRequest(callbackUpdate(405, "consent:agree")), mockContext);

        Map<String, Boolean> purposes = fakeUserRepo.getUser("tg:111").orElseThrow().getPurposes();
        assertThat(purposes).containsOnlyKeys("analyze");
        assertThat(purposes.get("analyze")).isTrue();
    }

    @Test
    @DisplayName("BE-25: Lưu đồng ý lỗi thì báo người dùng là chưa lưu, không báo thành công giả")
    void testConsentSaveFailureIsReportedHonestly() {
        fakeUserRepo.failUpdateConsent = true;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackUpdate(406, "consent:agree")), mockContext);

        assertThat(res.getBody()).isEqualTo("CALLBACK_FAILED");
        assertThat(fakeTelegram.lastCallbackAnswer).contains("chưa lưu");
        assertThat(fakeTelegram.lastText == null || !fakeTelegram.lastText.contains("Cảm ơn")).isTrue();
    }

    @Test
    @DisplayName("BE-23: tin đầu cụm gửi ⏳, ghi buffer seq=1, enqueue AnalyzeJob delay 3s")
    void testBufferFirstMessageSendsPlaceholderAndDelayedJob() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        InMemoryBufferStore buffer = new InMemoryBufferStore();
        CapturingSqsClient sqs = new CapturingSqsClient();
        WebhookHandler h = handlerWithBuffer(buffer, sqs, null);

        APIGatewayProxyResponseEvent res = h.handleRequest(createRequest(textUpdate(501, "kiểm tra 1")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("OK");
        assertThat(buffer.lastSeq).isEqualTo(1L);
        assertThat(buffer.placeholderId).isEqualTo(999);
        assertThat(fakeTelegram.lastText).startsWith("⏳");
        assertThat(sqs.sentCount.get()).isEqualTo(1);
        assertThat(sqs.lastDelaySeconds).isEqualTo(3);
        assertThat(sqs.lastBody).contains("\"seq\":1");
        assertThat(sqs.lastBody).contains("analyze");
    }

    @Test
    @DisplayName("BE-23: tin thứ 2 không gửi thêm ⏳, job seq=2 vẫn delay 3s")
    void testBufferSecondMessageNoExtraPlaceholder() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        InMemoryBufferStore buffer = new InMemoryBufferStore();
        buffer.seqGenerator.set(2);
        CapturingSqsClient sqs = new CapturingSqsClient();
        WebhookHandler h = handlerWithBuffer(buffer, sqs, null);
        fakeTelegram.lastText = null;

        APIGatewayProxyResponseEvent res = h.handleRequest(createRequest(textUpdate(502, "kiểm tra 2")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(buffer.lastSeq).isEqualTo(2L);
        assertThat(buffer.placeholderId).isNull();
        assertThat(fakeTelegram.lastText == null || !fakeTelegram.lastText.startsWith("⏳")).isTrue();
        assertThat(sqs.lastDelaySeconds).isEqualTo(3);
        assertThat(sqs.lastBody).contains("\"seq\":2");
    }

    @Test
    @DisplayName("BE-23: ba tin liên tiếp → đúng một ⏳ và ba job seq 1,2,3")
    void testBufferThreeMessagesOnePlaceholder() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        InMemoryBufferStore buffer = new InMemoryBufferStore();
        CapturingSqsClient sqs = new CapturingSqsClient();
        WebhookHandler h = handlerWithBuffer(buffer, sqs, null);

        h.handleRequest(createRequest(textUpdate(510, "a")), mockContext);
        h.handleRequest(createRequest(textUpdate(511, "b")), mockContext);
        h.handleRequest(createRequest(textUpdate(512, "c")), mockContext);

        assertThat(buffer.placeholderSetCount.get()).isEqualTo(1);
        assertThat(sqs.sentCount.get()).isEqualTo(3);
        assertThat(sqs.bodies).hasSize(3);
        assertThat(sqs.bodies.get(0)).contains("\"seq\":1");
        assertThat(sqs.bodies.get(1)).contains("\"seq\":2");
        assertThat(sqs.bodies.get(2)).contains("\"seq\":3");
        assertThat(sqs.delays).containsExactly(3, 3, 3);
    }

    @Test
    @DisplayName("BE-23: ghi BUFFER# lỗi → 500, không enqueue")
    void testBufferAppendFailureReturns500() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        InMemoryBufferStore buffer = new InMemoryBufferStore();
        buffer.failAppend = true;
        CapturingSqsClient sqs = new CapturingSqsClient();
        WebhookHandler h = handlerWithBuffer(buffer, sqs, null);

        APIGatewayProxyResponseEvent res = h.handleRequest(createRequest(textUpdate(520, "x")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(500);
        assertThat(res.getBody()).isEqualTo("BUFFER_FAILED");
        assertThat(sqs.sentCount.get()).isZero();
    }

    @Test
    @DisplayName("BE-23: SQS lỗi sau khi ghi buffer, Telegram gửi lại cùng update → không ghi lần hai, chỉ enqueue lại")
    void testRetryAfterSqsFailureDoesNotAppendTwice() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        InMemoryBufferStore buffer = new InMemoryBufferStore();
        WebhookHandler h = handlerWithBuffer(buffer, fakeSqs, null);

        fakeSqs.fail = true;
        APIGatewayProxyResponseEvent failed = h.handleRequest(createRequest(textUpdate(530, "kiểm tra")), mockContext);
        assertThat(failed.getBody()).isEqualTo("SQS_FAILED");
        assertThat(buffer.appendCount.get()).isEqualTo(1);

        fakeSqs.fail = false;
        APIGatewayProxyResponseEvent retry = h.handleRequest(createRequest(textUpdate(530, "kiểm tra")), mockContext);

        assertThat(retry.getStatusCode()).isEqualTo(200);
        assertThat(retry.getBody()).isEqualTo("OK");
        assertThat(buffer.appendCount.get()).isEqualTo(1);          // không append lần hai (không có seq mới)
        assertThat(buffer.placeholderSetCount.get()).isEqualTo(1);   // không gửi thêm "⏳"
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1);            // job được enqueue lại
        assertThat(fakeRateLimitRepo.count("tg:111")).isEqualTo(1);  // rate limit chỉ đếm một lần
    }

    @Test
    @DisplayName("BE-23: hai update khác nhau vẫn được ghi thành hai seq riêng")
    void testDifferentUpdatesAreStillAppendedSeparately() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        InMemoryBufferStore buffer = new InMemoryBufferStore();
        WebhookHandler h = handlerWithBuffer(buffer, fakeSqs, null);

        h.handleRequest(createRequest(textUpdate(531, "a")), mockContext);
        h.handleRequest(createRequest(textUpdate(532, "b")), mockContext);

        assertThat(buffer.appendCount.get()).isEqualTo(2);
        assertThat(fakeSqs.sentCount.get()).isEqualTo(2);
        assertThat(fakeRateLimitRepo.count("tg:111")).isEqualTo(2);
    }

    @Test
    @DisplayName("BE-29: lưu tên Telegram của người cao tuổi một lần, tên không đổi thì không ghi lại")
    void testDisplayNameIsRememberedOnce() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        WebhookHandler h = handlerWithBuffer(new InMemoryBufferStore(), fakeSqs, null);

        h.handleRequest(createRequest(textUpdateFrom(540, "a", "Nguyễn", "Văn An")), mockContext);
        h.handleRequest(createRequest(textUpdateFrom(541, "b", "Nguyễn", "Văn An")), mockContext);

        assertThat(fakeUserRepo.getUser("tg:111").orElseThrow().getDisplayName()).isEqualTo("Nguyễn Văn An");
        assertThat(fakeUserRepo.displayNameWrites.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-29: tin không có tên Telegram thì không ghi đè tên đã lưu")
    void testMissingTelegramNameDoesNotTouchStoredName() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        WebhookHandler h = handlerWithBuffer(new InMemoryBufferStore(), fakeSqs, null);

        h.handleRequest(createRequest(textUpdate(542, "a")), mockContext);   // from chỉ có id

        assertThat(fakeUserRepo.displayNameWrites.get()).isZero();
        assertThat(fakeUserRepo.getUser("tg:111").orElseThrow().getDisplayName()).isNull();
    }

    @Test
    @DisplayName("BE-29: tên hiển thị bỏ ký tự điều khiển và cắt tối đa 60 ký tự")
    void testDisplayNameIsSanitized() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(WebhookHandler.displayNameOf(mapper.readTree("{\"first_name\":\"An\\n\",\"last_name\":\"  Văn \"}")))
                .isEqualTo("An Văn");
        assertThat(WebhookHandler.displayNameOf(mapper.readTree("{\"first_name\":\"" + "a".repeat(100) + "\"}")))
                .hasSize(60);
        assertThat(WebhookHandler.displayNameOf(mapper.readTree("{\"id\":1}"))).isEmpty();
    }

    @Test
    @DisplayName("BE-23: không có BufferStore vẫn dùng đường cũ (InboundMessage, không bắt buộc envelope analyze)")
    void testWithoutBufferStoreKeepsLegacyEnqueue() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        // handler mặc định trong setUp: bufferStore = null
        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(textUpdate(530, "legacy")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("OK");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ BE-29: callback ack: người thân

    @Test
    @DisplayName("BE-29: đúng guardian bấm ack: → markAcked và trả lời callback")
    void testGuardianAckMarksAlert() {
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        alerts.seed(pendingAlert("A-1", "tg:201"));
        WebhookHandler h = handlerWithBuffer(null, fakeSqs, alerts);

        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(ackCallback(601, 201, "A-1")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("CALLBACK_HANDLED");
        assertThat(alerts.acked.get()).isTrue();
        assertThat(fakeTelegram.lastCallbackAnswer).contains("đã ghi nhận");
        // Nút trên chính tin vừa bấm (message_id=50, chat 201) được đổi thành nút trạng thái "✅ Đã liên hệ"
        assertThat(fakeTelegram.markupEdits).hasSize(1);
        FakeTelegramClient.MarkupEdit edit = fakeTelegram.markupEdits.get(0);
        assertThat(edit.chatId()).isEqualTo(201L);
        assertThat(edit.messageId()).isEqualTo(50);
        assertThat(edit.markup().toString()).contains("✅ Đã liên hệ").contains("ack_done");
        // FR13: người cao tuổi (chat 222) nhận tin người thân đã liên hệ
        assertThat(fakeTelegram.lastChatId).isEqualTo(222L);
        assertThat(fakeTelegram.lastText).isEqualTo("Dạ, Lan đã nhận cảnh báo và liên hệ bác rồi ạ.");
    }

    @Test
    @DisplayName("FR13: alert đã acked (bấm lại ở tin nhắc) → không báo người cao tuổi lần nữa")
    void testRepeatAckDoesNotNotifyElderAgain() {
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        com.askfirst.common.store.AlertRecord a = pendingAlert("A-6", "tg:201");
        alerts.seed(new com.askfirst.common.store.AlertRecord(a.alertId(), a.elderUid(), a.elderChatId(), a.checkId(),
                a.guardianUid(), a.guardianChatId(), a.guardianName(), a.shareLevel(),
                com.askfirst.common.store.AlertRecord.ACKED, a.messageId(), "2026-11-25T00:00:00Z",
                a.remindedAt(), a.ttlEpochSeconds()));
        WebhookHandler h = handlerWithBuffer(null, fakeSqs, alerts);

        h.handleRequest(createRequest(ackCallback(608, 201, "A-6")), mockContext);

        assertThat(fakeTelegram.lastCallbackAnswer).contains("đã ghi nhận");
        assertThat(fakeTelegram.lastChatId).isNull();
    }

    @Test
    @DisplayName("BE-29: sửa nút thất bại → vẫn markAcked và trả lời callback bình thường")
    void testAckStillRecordedWhenButtonEditFails() {
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        alerts.seed(pendingAlert("A-4", "tg:201"));
        WebhookHandler h = handlerWithBuffer(null, fakeSqs, alerts);
        fakeTelegram.failMarkupEdit = true;

        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(ackCallback(606, 201, "A-4")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("CALLBACK_HANDLED");
        assertThat(alerts.acked.get()).isTrue();
        assertThat(fakeTelegram.lastCallbackAnswer).contains("đã ghi nhận");
        assertThat(fakeTelegram.markupEdits).hasSize(1);   // đã thử sửa, lỗi chỉ ghi log
    }

    @Test
    @DisplayName("BE-29: bấm nút trạng thái ack_done → chỉ answer, không markAcked, không sửa nút")
    void testAckDoneCallbackOnlyAnswers() {
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        alerts.seed(pendingAlert("A-5", "tg:201"));
        WebhookHandler h = handlerWithBuffer(null, fakeSqs, alerts);

        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(callbackWithData(607, 201, "ack_done")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("CALLBACK_HANDLED");
        assertThat(alerts.acked.get()).isFalse();
        assertThat(fakeTelegram.lastCallbackAnswer).contains("đã xác nhận");
        assertThat(fakeTelegram.markupEdits).isEmpty();
    }

    @Test
    @DisplayName("BE-29: sai người bấm ack: → không markAcked")
    void testWrongUserAckIsRejected() {
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        alerts.seed(pendingAlert("A-2", "tg:201"));
        WebhookHandler h = handlerWithBuffer(null, fakeSqs, alerts);

        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(ackCallback(602, 999, "A-2")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(alerts.acked.get()).isFalse();
        assertThat(fakeTelegram.lastCallbackAnswer).contains("không còn hiệu lực");
        assertThat(fakeTelegram.markupEdits).isEmpty();   // không được sửa nút của cảnh báo không hợp lệ
        assertThat(fakeTelegram.lastChatId).isNull();      // không báo người cao tuổi
    }

    @Test
    @DisplayName("BE-29: alert_id không tồn tại → không markAcked")
    void testUnknownAlertAckIsRejected() {
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        WebhookHandler h = handlerWithBuffer(null, fakeSqs, alerts);

        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(ackCallback(603, 201, "KHONG-CO")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(alerts.acked.get()).isFalse();
        assertThat(fakeTelegram.lastCallbackAnswer).contains("không còn hiệu lực");
        assertThat(fakeTelegram.markupEdits).isEmpty();   // không được sửa nút của cảnh báo không hợp lệ
    }

    @Test
    @DisplayName("BE-29: không có AlertStore → UNKNOWN_CALLBACK")
    void testAckWithoutAlertStoreIsUnknown() {
        // handler setUp không có alertStore
        APIGatewayProxyResponseEvent res = handler.handleRequest(
                createRequest(ackCallback(604, 201, "A-9")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("UNKNOWN_CALLBACK");
    }

    @Test
    @DisplayName("BE-29: guardianUid dạng USER#tg:201 vẫn khớp from.id=201")
    void testAckMatchesUserPrefix() {
        InMemoryAlertStore alerts = new InMemoryAlertStore();
        alerts.seed(pendingAlert("A-3", "USER#tg:201"));
        WebhookHandler h = handlerWithBuffer(null, fakeSqs, alerts);

        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(ackCallback(605, 201, "A-3")), mockContext);

        assertThat(res.getBody()).isEqualTo("CALLBACK_HANDLED");
        assertThat(alerts.acked.get()).isTrue();
    }

    static class FakeGuardianRepo extends GuardianRepo {
        final Map<String, GuardianRecord> store = new ConcurrentHashMap<>();

        public FakeGuardianRepo() {
            super();
        }

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

    @Test
    @DisplayName("BE-29 / FR6: cg:<checkId> khi người thân có SĐT → gửi contact card qua sendContact")
    void testCallGuardianSendsContact() {
        FakeGuardianRepo gRepo = new FakeGuardianRepo();
        GuardianRecord gr = new GuardianRecord("tg:111", "tg:201");
        gr.setName("Nam");
        gr.setPhone("0912345678");
        gr.setStatus("active");
        gRepo.save(gr);

        WebhookHandler h = handlerWithBuffer(null, fakeSqs, new InMemoryAlertStore(), gRepo);
        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(callbackWithData(701, 111, "cg:CHECK-001")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(fakeTelegram.lastContactChatId).isEqualTo(111L);
        assertThat(fakeTelegram.lastContactPhone).isEqualTo("0912345678");
        assertThat(fakeTelegram.lastContactName).isEqualTo("Nam");
    }

    @Test
    @DisplayName("BE-29 / FR6: cg:<checkId> khi người thân chưa có SĐT → hướng dẫn mở danh bạ")
    void testCallGuardianWithoutPhoneGuidesElder() {
        FakeGuardianRepo gRepo = new FakeGuardianRepo();
        GuardianRecord gr = new GuardianRecord("tg:111", "tg:201");
        gr.setStatus("active");
        gRepo.save(gr);

        WebhookHandler h = handlerWithBuffer(null, fakeSqs, new InMemoryAlertStore(), gRepo);
        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(callbackWithData(702, 111, "cg:CHECK-002")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(fakeTelegram.lastText).contains("chưa lưu số điện thoại");
    }

    @Test
    @DisplayName("FR6: ag:<checkId> (báo người nhà ở mức SUSPICIOUS) → gửi cảnh báo tới người thân active và đổi nút ag_done")
    void testAlertGuardianManualSendsAlertToActiveGuardians() {
        FakeGuardianRepo gRepo = new FakeGuardianRepo();
        GuardianRecord gr = new GuardianRecord("tg:111", "tg:201");
        gr.setGuardianChatId("201");
        gr.setStatus("active");
        gRepo.save(gr);

        WebhookHandler h = handlerWithBuffer(null, fakeSqs, new InMemoryAlertStore(), gRepo);
        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(callbackWithData(703, 111, "ag:CHECK-003")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(fakeTelegram.markupEdits).isNotEmpty();
        assertThat(fakeTelegram.markupEdits.get(0).markup().toString()).contains("ag_done");
    }

    @Test
    @DisplayName("FR9: fu:called (người cao tuổi đã gọi) → cảm ơn và gỡ nút")
    void testFollowupElderCalledAcknowledges() {
        WebhookHandler h = handlerWithBuffer(null, fakeSqs, new InMemoryAlertStore());
        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(callbackWithData(704, 111, "fu:called")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(fakeTelegram.lastText).contains("tốt quá");
    }

    @Test
    @DisplayName("FR15: Văn bản quá dài > 4000 ký tự → bot thông báo đã tạm cắt bớt")
    void testLongTextNotifiesElderOfTruncation() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        String longText = "a".repeat(4050);
        String updateJson = """
                {
                  "update_id": 705,
                  "message": {
                    "message_id": 99,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "%s"
                  }
                }
                """.formatted(longText);

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(fakeTelegram.sentTexts).anyMatch(t -> t.contains("dài hơn 4.000 ký tự"));
    }

    private WebhookHandler handlerWithBuffer(
            InMemoryBufferStore buffer,
            SqsClient sqs,
            InMemoryAlertStore alerts) {
        return handlerWithBuffer(buffer, sqs, alerts, null);
    }

    private WebhookHandler handlerWithBuffer(
            InMemoryBufferStore buffer,
            SqsClient sqs,
            InMemoryAlertStore alerts,
            GuardianRepo guardianRepo) {

        return new WebhookHandler(
                fakeTelegram,                   // 1
                sqs,                            // 2
                "https://sqs.mock.queue",       // 3
                SECRET,                         // 4
                fakeUserRepo,                   // 5
                fakeAuditRepo,                  // 6
                fakeRateLimitRepo,              // 7
                guardianRepo,                   // 8  GuardianRepo
                null,                           // 9  InviteRepo
                POLICY_SHA256,                  // 10
                null,                           // 11 dedup
                false,                          // 12 localDevelopment
                null,                           // 13 botUsername
                null,                           // 14 policyUrl
                null,                           // 15 CheckStore
                null,                           // 16 SessionStore
                buffer,                         // 17 BufferStore
                null,                           // 18 DonationStore
                alerts                          // 19 AlertStore
        );
    }

    private static String ackCallback(long updateId, long fromId, String alertId) {
        return callbackWithData(updateId, fromId, "ack:" + alertId);
    }

    /** Callback từ tin có message_id=50 trong chat riêng của người bấm. */
    private static String callbackWithData(long updateId, long fromId, String data) {
        return "{\"update_id\": " + updateId + ", \"callback_query\": {\"id\": \"cb_" + updateId + "\", "
                + "\"from\": {\"id\": " + fromId + ", \"is_bot\": false}, \"message\": {\"message_id\": 50, "
                + "\"chat\": {\"id\": " + fromId + ", \"type\": \"private\"}, \"date\": 1729000000}, "
                + "\"data\": \"" + data + "\"}}";
    }

    private static com.askfirst.common.store.AlertRecord pendingAlert(String alertId, String guardianUid) {
        return new com.askfirst.common.store.AlertRecord(
                alertId,
                "tg:111",
                "222",
                "C1",
                guardianUid,
                "201",
                "Lan",
                2,
                com.askfirst.common.store.AlertRecord.PENDING,
                null,
                null,
                null,
                java.time.Instant.now().getEpochSecond() + 3600);
    }
    private static class CapturingSqsClient implements SqsClient {
        final AtomicInteger sentCount = new AtomicInteger(0);
        final List<String> bodies = new ArrayList<>();
        final List<Integer> delays = new ArrayList<>();
        String lastBody;
        Integer lastDelaySeconds;

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }

        @Override
        public SendMessageResponse sendMessage(SendMessageRequest req) {
            lastBody = req.messageBody();
            lastDelaySeconds = req.delaySeconds();
            bodies.add(lastBody);
            delays.add(lastDelaySeconds == null ? 0 : lastDelaySeconds);
            sentCount.incrementAndGet();
            return SendMessageResponse.builder().messageId("mock-sqs-id").build();
        }
    }

    private static class InMemoryBufferStore implements com.askfirst.common.store.BufferStore {
        final AtomicInteger seqGenerator = new AtomicInteger(1);
        final AtomicInteger placeholderSetCount = new AtomicInteger(0);
        final AtomicInteger appendCount = new AtomicInteger(0);
        final Map<String, Long> seqByUpdateId = new HashMap<>();
        long lastSeq;
        Integer placeholderId;
        boolean failAppend;

        @Override
        public long append(String chatId, com.askfirst.common.channel.model.InboundMessage message, long ttlEpochSeconds) {
            if (failAppend) {
                throw new IllegalStateException("BUFFER_DOWN");
            }
            lastSeq = seqGenerator.getAndIncrement();
            appendCount.incrementAndGet();
            seqByUpdateId.put(message.updateId(), lastSeq);
            return lastSeq;
        }

        @Override
        public java.util.OptionalLong findSeqByUpdateId(String chatId, String updateId) {
            Long seq = seqByUpdateId.get(updateId);
            return seq == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(seq);
        }

        @Override
        public void setPlaceholder(String chatId, int messageId) {
            placeholderId = messageId;
            placeholderSetCount.incrementAndGet();
        }

        @Override
        public Optional<State> state(String chatId) {
            return Optional.empty();
        }

        @Override
        public List<com.askfirst.common.channel.model.InboundMessage> itemsAfter(
                String chatId, long afterSeq, long upToSeq) {
            return List.of();
        }

        @Override
        public boolean finish(String chatId, long seq) {
            return true;
        }

        @Override
        public void delete(String chatId) {
        }
    }

    private static class InMemoryAlertStore implements com.askfirst.common.store.AlertStore {
        private com.askfirst.common.store.AlertRecord stored;
        final AtomicBoolean acked = new AtomicBoolean(false);

        void seed(com.askfirst.common.store.AlertRecord record) {
            stored = record;
        }

        @Override
        public com.askfirst.common.store.AlertRecord reserve(com.askfirst.common.store.AlertRecord pending) {
            return pending;
        }

        @Override
        public Optional<com.askfirst.common.store.AlertRecord> get(String alertId) {
            if (stored != null && stored.alertId().equals(alertId)) {
                return Optional.of(stored);
            }
            return Optional.empty();
        }

        @Override
        public void markSent(String alertId, int messageId) {
        }

        @Override
        public void markFailed(String alertId) {
        }

        @Override
        public boolean markAcked(String alertId, java.time.Instant at) {
            if (stored == null || !stored.alertId().equals(alertId)) {
                return false;
            }
            acked.set(true);
            return true;
        }

        @Override
        public boolean markReminded(String alertId, java.time.Instant at) {
            return false;
        }
    }

    private WebhookHandler handlerWithDedup(DynamoStoreService dedup) {
        return new WebhookHandler(fakeTelegram, fakeSqs, "https://sqs.mock.queue", SECRET,
                fakeUserRepo, fakeAuditRepo, fakeRateLimitRepo, POLICY_SHA256, dedup);
    }

    private static String textUpdate(long updateId, String text) {
        return "{\"update_id\": " + updateId + ", \"message\": {\"message_id\": " + updateId
                + ", \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 222, \"type\": \"private\"}, "
                + "\"date\": 1729000000, \"text\": \"" + text + "\"}}";
    }

    private static String textUpdateFrom(long updateId, String text, String firstName, String lastName) {
        return "{\"update_id\": " + updateId + ", \"message\": {\"message_id\": " + updateId
                + ", \"from\": {\"id\": 111, \"is_bot\": false, \"first_name\": \"" + firstName
                + "\", \"last_name\": \"" + lastName + "\"}, \"chat\": {\"id\": 222, \"type\": \"private\"}, "
                + "\"date\": 1729000000, \"text\": \"" + text + "\"}}";
    }

    private static String callbackUpdate(long updateId, String data) {
        return "{\"update_id\": " + updateId + ", \"callback_query\": {\"id\": \"cb_" + updateId + "\", "
                + "\"from\": {\"id\": 111, \"is_bot\": false}, \"message\": {\"message_id\": 1, "
                + "\"chat\": {\"id\": 222, \"type\": \"private\"}, \"date\": 1729000000}, \"data\": \"" + data + "\"}}";
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
        String lastCallbackAnswer;
        final List<String> sentTexts = new ArrayList<>();
        final List<MarkupEdit> markupEdits = new ArrayList<>();
        boolean failMarkupEdit;
        Long lastContactChatId;
        String lastContactPhone;
        String lastContactName;

        record MarkupEdit(long chatId, int messageId, Map<String, Object> markup) {
        }

        public FakeTelegramClient() {
            super("fake-token");
        }

        @Override
        public void sendContact(long chatId, String phoneNumber, String firstName, String lastName) {
            this.lastContactChatId = chatId;
            this.lastContactPhone = phoneNumber;
            this.lastContactName = firstName;
        }

        @Override
        public int sendText(long chatId, String text, Map<String, Object> replyMarkup) {
            this.lastChatId = chatId;
            this.lastText = text;
            this.lastMarkup = replyMarkup;
            this.sentTexts.add(text);
            return 999;
        }

        @Override
        public void answerCallbackQuery(String callbackQueryId, String text) {
            this.lastCallbackAnswer = text;
        }

        @Override
        public void editReplyMarkup(long chatId, int messageId, Map<String, Object> replyMarkup) {
            markupEdits.add(new MarkupEdit(chatId, messageId, replyMarkup));
            if (failMarkupEdit) {
                throw new RuntimeException("Telegram tạm thời không khả dụng");
            }
        }
    }

    private static class FakeSqsClient implements SqsClient {
        AtomicBoolean messageSent = new AtomicBoolean(false);
        AtomicInteger sentCount = new AtomicInteger(0);
        boolean fail = false;

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }

        @Override
        public SendMessageResponse sendMessage(SendMessageRequest req) {
            if (fail) {
                throw new RuntimeException("SQS tạm thời không khả dụng");
            }
            messageSent.set(true);
            sentCount.incrementAndGet();
            return SendMessageResponse.builder().messageId("mock-sqs-id").build();
        }
    }

    private static class InMemoryUserRepo extends UserRepo {
        private final Map<String, UserRecord> store = new HashMap<>();
        boolean failUpdateConsent = false;
        final AtomicInteger displayNameWrites = new AtomicInteger(0);

        @Override
        public void updateDisplayName(String userId, String displayName) {
            displayNameWrites.incrementAndGet();
            UserRecord r = store.get(userId);
            if (r != null) {
                r.setDisplayName(displayName);
            }
        }

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
            if (failUpdateConsent) {
                throw new IllegalStateException("DynamoDB lỗi");
            }
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

        @Override
        public void revokeConsent(String userId) {
            UserRecord u = store.get(userId);
            if (u == null) {
                return;
            }
            Map<String, Boolean> purposes = new HashMap<>(u.getPurposes());
            purposes.replaceAll((k, v) -> false);
            purposes.put("analyze", false);
            u.setPurposes(purposes);
        }
    }

    /** Dedup bản giả: ghi nhớ update_id đã xử lý, và các khóa đã được nhả. */
    private static class InMemoryDedup extends DynamoStoreService {
        final Set<String> seen = new HashSet<>();
        final List<String> released = new ArrayList<>();

        InMemoryDedup() {
            super(null, null, "test-table");
        }

        @Override
        public boolean tryAcquireDedup(String updateId, long ttlEpochSeconds) {
            return seen.add(updateId);
        }

        @Override
        public void deleteDedup(String updateId) {
            released.add(updateId);
            seen.remove(updateId);
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

        long count(String userId) {
            return counts.getOrDefault(userId, 0L);
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
