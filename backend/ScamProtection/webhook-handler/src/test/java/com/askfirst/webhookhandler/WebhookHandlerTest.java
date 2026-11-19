package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.store.AuditRecord;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.DynamoStoreService;
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

        // Từ BE-34, feature_voice tắt thì voice bị chặn ở bước "chưa hỗ trợ" trước khi hỏi đồng ý voice:
        // bật cờ để kiểm đúng bước đồng ý riêng cho voice của BE-25.
        WebhookHandler voiceOn = handlerWithVoice(true);
        APIGatewayProxyResponseEvent res = voiceOn.handleRequest(createRequest(voiceJson), mockContext);
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
    @DisplayName("BE-23: không có BufferStore vẫn dùng đường cũ (InboundMessage, không bắt buộc envelope analyze)")
    void testWithoutBufferStoreKeepsLegacyEnqueue() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        // handler mặc định trong setUp: bufferStore = null
        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(textUpdate(530, "legacy")), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("OK");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ BE-22: chat riêng, dedup, secret, độ trễ

    @Test
    @DisplayName("BE-22: secret đúng nhưng tên header viết thường (Function URL) vẫn được nhận")
    void testSecretHeaderNameIsCaseInsensitive() {
        APIGatewayProxyRequestEvent req = new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("x-telegram-bot-api-secret-token", SECRET))
                .withBody(textUpdate(700, "/huongdan"));

        assertThat(handler.handleRequest(req, mockContext).getStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("BE-22: secret chỉ trùng phần đầu / dài hơn / rỗng đều bị 401")
    void testSecretPrefixAndLengthMismatchRejected() {
        for (String bad : new String[] {SECRET.substring(0, SECRET.length() - 1), SECRET + "x", ""}) {
            APIGatewayProxyRequestEvent req = new APIGatewayProxyRequestEvent()
                    .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", bad))
                    .withBody(textUpdate(701, "/huongdan"));
            assertThat(handler.handleRequest(req, mockContext).getStatusCode()).as("secret '%s'", bad).isEqualTo(401);
        }
        assertThat(fakeTelegram.sentTexts).isEmpty();
    }

    @Test
    @DisplayName("BE-22: secret sai thì không chạm dedup, không gửi gì")
    void testWrongSecretDoesNotTouchDedup() {
        InMemoryDedup dedup = new InMemoryDedup();
        WebhookHandler h = handlerWithDedup(dedup);
        APIGatewayProxyRequestEvent req = new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", "sai"))
                .withBody(textUpdate(702, "kiểm tra"));

        assertThat(h.handleRequest(req, mockContext).getStatusCode()).isEqualTo(401);
        assertThat(dedup.seen).isEmpty();
        assertThat(fakeSqs.sentCount.get()).isZero();
    }

    @Test
    @DisplayName("BE-22: đường nhanh (/start) gửi trùng update_id thì chỉ trả lời một lần")
    void testFastPathDuplicateRepliesOnce() {
        WebhookHandler h = handlerWithDedup(new InMemoryDedup());

        APIGatewayProxyResponseEvent first = h.handleRequest(createRequest(textUpdate(710, "/start")), mockContext);
        APIGatewayProxyResponseEvent retry = h.handleRequest(createRequest(textUpdate(710, "/start")), mockContext);

        assertThat(first.getBody()).isEqualTo("COMMAND_HANDLED");
        assertThat(retry.getBody()).isEqualTo("DUPLICATE");
        assertThat(fakeTelegram.sentTexts).hasSize(1);
    }

    @Test
    @DisplayName("BE-22: nút bấm (callback) gửi trùng update_id thì chỉ xử lý một lần")
    void testCallbackDuplicateHandledOnce() {
        InMemoryDedup dedup = new InMemoryDedup();
        WebhookHandler h = handlerWithDedup(dedup);

        h.handleRequest(createRequest(callbackUpdate(711, "consent:agree")), mockContext);
        APIGatewayProxyResponseEvent retry = h.handleRequest(createRequest(callbackUpdate(711, "consent:agree")), mockContext);

        assertThat(retry.getBody()).isEqualTo("DUPLICATE");
        assertThat(fakeAuditRepo.events.stream().filter("CONSENT_GIVEN"::equals).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-22: chỉ nhận chat private, tin trong nhóm bị bỏ qua và không ghi dedup")
    void testGroupChatIsIgnored() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        InMemoryDedup dedup = new InMemoryDedup();
        WebhookHandler h = handlerWithDedup(dedup);

        for (String type : new String[] {"group", "supergroup", "channel"}) {
            String update = "{\"update_id\": 720, \"message\": {\"message_id\": 1, \"from\": {\"id\": 111, \"is_bot\": false}, "
                    + "\"chat\": {\"id\": -100222, \"type\": \"" + type + "\"}, \"date\": 1729000000, \"text\": \"kiểm tra\"}}";
            APIGatewayProxyResponseEvent res = h.handleRequest(createRequest(update), mockContext);
            assertThat(res.getStatusCode()).as(type).isEqualTo(200);
            assertThat(res.getBody()).as(type).isEqualTo("IGNORED_NON_PRIVATE");
        }
        assertThat(dedup.seen).isEmpty();
        assertThat(fakeSqs.sentCount.get()).isZero();
        assertThat(fakeTelegram.sentTexts).isEmpty();
    }

    @Test
    @DisplayName("BE-22: nút bấm từ tin trong nhóm cũng bị bỏ qua")
    void testGroupCallbackIsIgnored() {
        String update = "{\"update_id\": 721, \"callback_query\": {\"id\": \"cb\", \"from\": {\"id\": 111, \"is_bot\": false}, "
                + "\"message\": {\"message_id\": 1, \"chat\": {\"id\": -100222, \"type\": \"supergroup\"}, \"date\": 1729000000}, "
                + "\"data\": \"consent:agree\"}}";

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(update), mockContext);

        assertThat(res.getBody()).isEqualTo("IGNORED_NON_PRIVATE");
        assertThat(fakeAuditRepo.events).isEmpty();
        assertThat(fakeUserRepo.getUser("tg:111")).isEmpty();
    }

    @Test
    @DisplayName("BE-22: phần xử lý của handler (không tính mạng) p95 << 500 ms khi đã warm")
    void testHandlerOverheadP95() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        // Không rate limit để cả 700 request đều đi hết đường chậm (buffer + ⏳ + SQS)
        WebhookHandler h = new WebhookHandler(fakeTelegram, new CapturingSqsClient(), "https://sqs.mock.queue", SECRET,
                fakeUserRepo, fakeAuditRepo, null, POLICY_SHA256, null, new InMemoryBufferStore(), null);
        for (int i = 0; i < 200; i++) { // làm nóng JIT
            h.handleRequest(createRequest(textUpdate(800 + i, "khởi động")), mockContext);
        }

        int n = 500;
        long[] micros = new long[n];
        for (int i = 0; i < n; i++) {
            APIGatewayProxyRequestEvent req = createRequest(textUpdate(1000 + i, "kiểm tra " + i));
            long t0 = System.nanoTime();
            h.handleRequest(req, mockContext);
            micros[i] = (System.nanoTime() - t0) / 1000;
        }
        java.util.Arrays.sort(micros);
        long p95Millis = micros[(int) Math.ceil(n * 0.95) - 1] / 1000;

        // Chỉ bảo vệ khỏi việc ai đó thêm xử lý nặng vào đường nóng. p95 thật (gồm DynamoDB, Telegram, SQS)
        // phải đo trên CloudWatch sau khi deploy.
        assertThat(p95Millis).isLessThan(100);
    }

    // ------------------------------------------------------------------ BE-34: chặn media trước khi tạo job

    @Test
    @DisplayName("BE-34: ảnh 25 MB bị từ chối kèm câu giải thích, không có job nào được enqueue")
    void testOversizePhotoRejectedWithoutJob() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        InMemoryBufferStore buffer = new InMemoryBufferStore();
        CapturingSqsClient sqs = new CapturingSqsClient();
        WebhookHandler h = handlerWithBuffer(buffer, sqs, null);

        APIGatewayProxyResponseEvent res = h.handleRequest(
                createRequest(photoUpdate(900, 25L * 1024 * 1024, null)), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("MEDIA_TOO_LARGE");
        assertThat(fakeTelegram.lastText).isEqualTo(
                "Bác ơi, file ảnh này dung lượng hơi lớn (quá 20MB) nên cháu chưa đọc được. "
                        + "Bác chụp lại màn hình gọn hơn giúp cháu nhé!");
        assertThat(sqs.sentCount.get()).isZero();
        assertThat(buffer.lastSeq).isZero();
        assertThat(buffer.placeholderId).isNull();
    }

    @Test
    @DisplayName("BE-34: ảnh bị từ chối không tốn lượt rate limit")
    void testRejectedMediaDoesNotConsumeRateLimit() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        for (int i = 0; i < 40; i++) {
            handler.handleRequest(createRequest(photoUpdate(910 + i, 30L * 1024 * 1024, null)), mockContext);
        }

        APIGatewayProxyResponseEvent ok = handler.handleRequest(createRequest(textUpdate(990, "kiểm tra")), mockContext);

        assertThat(ok.getBody()).isEqualTo("OK");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-34: đúng 20 MB vẫn được nhận, thiếu file_size thì không chặn")
    void testPhotoAtLimitAndUnknownSizeAccepted() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        APIGatewayProxyResponseEvent atLimit = handler.handleRequest(
                createRequest(photoUpdate(920, 20L * 1024 * 1024, null)), mockContext);
        APIGatewayProxyResponseEvent noSize = handler.handleRequest(
                createRequest(photoUpdate(921, null, null)), mockContext);

        assertThat(atLimit.getBody()).isEqualTo("OK");
        assertThat(noSize.getBody()).isEqualTo("OK");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("BE-34: tệp ảnh (document image/*) > 20 MB dùng câu 'file ảnh', tệp khác dùng câu 'tệp'")
    void testOversizeDocumentMessages() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        handler.handleRequest(createRequest(documentUpdate(930, "image/png", "man-hinh.png", 25L * 1024 * 1024)), mockContext);
        assertThat(fakeTelegram.lastText).contains("file ảnh này dung lượng hơi lớn (quá 20MB)");

        handler.handleRequest(createRequest(documentUpdate(931, "application/pdf", "hop-dong.pdf", 25L * 1024 * 1024)), mockContext);
        assertThat(fakeTelegram.lastText).contains("tệp này dung lượng hơi lớn (quá 20MB)");
        assertThat(fakeSqs.sentCount.get()).isZero();
    }

    @Test
    @DisplayName("BE-34: xưng hô theo cài đặt (Cô) trong câu từ chối")
    void testRejectionUsesAddressForm() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        fakeUserRepo.updateAddressForm("tg:111", "Cô");

        handler.handleRequest(createRequest(photoUpdate(940, 25L * 1024 * 1024, null)), mockContext);

        assertThat(fakeTelegram.lastText).startsWith("Cô ơi, file ảnh này").contains("Cô chụp lại màn hình gọn hơn");
    }

    @Test
    @DisplayName("BE-34: album nhiều ảnh quá nặng chỉ nhận một câu giải thích, ảnh bình thường trong album vẫn vào job")
    void testOversizeAlbumExplainedOnce() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        WebhookHandler h = handlerWithDedup(new InMemoryDedup());

        h.handleRequest(createRequest(photoUpdate(950, 25L * 1024 * 1024, "ALBUM1")), mockContext);
        h.handleRequest(createRequest(photoUpdate(951, 30L * 1024 * 1024, "ALBUM1")), mockContext);
        h.handleRequest(createRequest(photoUpdate(952, 40L * 1024 * 1024, "ALBUM1")), mockContext);
        APIGatewayProxyResponseEvent normal = h.handleRequest(
                createRequest(photoUpdate(953, 90_000L, "ALBUM1")), mockContext);

        assertThat(fakeTelegram.sentTexts.stream().filter(t -> t.contains("quá 20MB")).count()).isEqualTo(1);
        assertThat(normal.getBody()).isEqualTo("OK");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-34: chưa đồng ý điều khoản thì ảnh nặng vẫn nhận lời mời đồng ý trước")
    void testOversizeFromUnconsentedUserStillAsksConsent() {
        APIGatewayProxyResponseEvent res = handler.handleRequest(
                createRequest(photoUpdate(960, 25L * 1024 * 1024, null)), mockContext);

        assertThat(res.getBody()).isEqualTo("CONSENT_REQUIRED");
        assertThat(fakeSqs.sentCount.get()).isZero();
    }

    @Test
    @DisplayName("BE-34: voice 150 giây (cờ voice tắt, mặc định MVP) nhận câu 'chưa hỗ trợ', không job")
    void testLongVoiceUnsupportedByDefault() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true, "voice", true));

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(voiceUpdate(970, 150)), mockContext);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("VOICE_UNSUPPORTED");
        assertThat(fakeTelegram.lastText).contains("chưa hỗ trợ tin nhắn thoại");
        assertThat(fakeSqs.sentCount.get()).isZero();
    }

    @Test
    @DisplayName("BE-34: voice ngắn 5 giây, kể cả đã đồng ý voice, vẫn 'chưa hỗ trợ' khi cờ tắt")
    void testShortVoiceAlsoUnsupportedByDefault() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true, "voice", true));

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(voiceUpdate(971, 5)), mockContext);

        assertThat(res.getBody()).isEqualTo("VOICE_UNSUPPORTED");
        assertThat(fakeSqs.sentCount.get()).isZero();
    }

    @Test
    @DisplayName("BE-34: file ghi âm (audio) cũng 'chưa hỗ trợ', không job")
    void testAudioFileUnsupportedByDefault() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        String audio = "{\"update_id\": 972, \"message\": {\"message_id\": 1, \"from\": {\"id\": 111, \"is_bot\": false}, "
                + "\"chat\": {\"id\": 222, \"type\": \"private\"}, \"date\": 1729000000, "
                + "\"audio\": {\"file_id\": \"a\", \"file_unique_id\": \"ua\", \"duration\": 30, \"mime_type\": \"audio/mpeg\"}}}";

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(audio), mockContext);

        assertThat(res.getBody()).isEqualTo("VOICE_UNSUPPORTED");
        assertThat(fakeSqs.sentCount.get()).isZero();
    }

    @Test
    @DisplayName("BE-34: khi bật voice, voice 150 giây bị từ chối vì dài quá 2 phút, không job")
    void testVoiceTooLongWhenEnabled() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true, "voice", true));
        WebhookHandler voiceOn = handlerWithVoice(true);

        APIGatewayProxyResponseEvent res = voiceOn.handleRequest(createRequest(voiceUpdate(980, 150)), mockContext);

        assertThat(res.getBody()).isEqualTo("VOICE_TOO_LONG");
        assertThat(fakeTelegram.lastText).isEqualTo(
                "Bác ơi, đoạn ghi âm dài quá 2 phút rồi ạ. Bác gửi đoạn ngắn hơn giúp cháu nhé!");
        assertThat(fakeSqs.sentCount.get()).isZero();
    }

    @Test
    @DisplayName("BE-34: khi bật voice, đúng 120 giây vẫn được nhận")
    void testVoiceAtLimitAcceptedWhenEnabled() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true, "voice", true));
        WebhookHandler voiceOn = handlerWithVoice(true);

        APIGatewayProxyResponseEvent res = voiceOn.handleRequest(createRequest(voiceUpdate(981, 120)), mockContext);

        assertThat(res.getBody()).isEqualTo("OK");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("BE-34: ảnh và văn bản không bao giờ làm handler đọc cờ voice")
    void testNonAudioNeverReadsVoiceFlag() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        AtomicInteger flagReads = new AtomicInteger();
        WebhookHandler h = new WebhookHandler(fakeTelegram, fakeSqs, "https://sqs.mock.queue", SECRET,
                fakeUserRepo, fakeAuditRepo, fakeRateLimitRepo, POLICY_SHA256, null, null, null,
                () -> {
                    flagReads.incrementAndGet();
                    return true;
                });

        h.handleRequest(createRequest(textUpdate(985, "kiểm tra")), mockContext);
        h.handleRequest(createRequest(photoUpdate(986, 90_000L, null)), mockContext);

        assertThat(flagReads.get()).isZero();
    }

    private WebhookHandler handlerWithVoice(boolean enabled) {
        return new WebhookHandler(fakeTelegram, fakeSqs, "https://sqs.mock.queue", SECRET,
                fakeUserRepo, fakeAuditRepo, fakeRateLimitRepo, POLICY_SHA256, null, null, null, () -> enabled);
    }

    private static String photoUpdate(long updateId, Long largestSizeBytes, String mediaGroupId) {
        String size = largestSizeBytes == null ? "" : ", \"file_size\": " + largestSizeBytes;
        String group = mediaGroupId == null ? "" : ", \"media_group_id\": \"" + mediaGroupId + "\"";
        return "{\"update_id\": " + updateId + ", \"message\": {\"message_id\": " + updateId
                + ", \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 222, \"type\": \"private\"}, "
                + "\"date\": 1729000000" + group + ", \"photo\": ["
                + "{\"file_id\": \"sm" + updateId + "\", \"file_unique_id\": \"usm" + updateId + "\", \"file_size\": 1200}, "
                + "{\"file_id\": \"lg" + updateId + "\", \"file_unique_id\": \"ulg" + updateId + "\"" + size + "}]}}";
    }

    private static String documentUpdate(long updateId, String mime, String fileName, long sizeBytes) {
        return "{\"update_id\": " + updateId + ", \"message\": {\"message_id\": " + updateId
                + ", \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 222, \"type\": \"private\"}, "
                + "\"date\": 1729000000, \"document\": {\"file_id\": \"d" + updateId + "\", \"file_unique_id\": \"ud" + updateId
                + "\", \"mime_type\": \"" + mime + "\", \"file_name\": \"" + fileName + "\", \"file_size\": " + sizeBytes + "}}}";
    }

    private static String voiceUpdate(long updateId, int durationSeconds) {
        return "{\"update_id\": " + updateId + ", \"message\": {\"message_id\": " + updateId
                + ", \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 222, \"type\": \"private\"}, "
                + "\"date\": 1729000000, \"voice\": {\"file_id\": \"v" + updateId + "\", \"file_unique_id\": \"uv" + updateId
                + "\", \"mime_type\": \"audio/ogg\", \"duration\": " + durationSeconds + ", \"file_size\": 30000}}}";
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

    private WebhookHandler handlerWithBuffer(
            InMemoryBufferStore buffer, SqsClient sqs, InMemoryAlertStore alerts) {
        return new WebhookHandler(
                fakeTelegram,
                sqs,
                "https://sqs.mock.queue",
                SECRET,
                fakeUserRepo,
                fakeAuditRepo,
                fakeRateLimitRepo,
                POLICY_SHA256,
                null,
                buffer,
                alerts);
    }

    private static String ackCallback(long updateId, long fromId, String alertId) {
        return "{\"update_id\": " + updateId + ", \"callback_query\": {\"id\": \"cb_" + updateId + "\", "
                + "\"from\": {\"id\": " + fromId + ", \"is_bot\": false}, \"message\": {\"message_id\": 50, "
                + "\"chat\": {\"id\": " + fromId + ", \"type\": \"private\"}, \"date\": 1729000000}, "
                + "\"data\": \"ack:" + alertId + "\"}}";
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
        long lastSeq;
        Integer placeholderId;
        boolean failAppend;

        @Override
        public long append(String chatId, com.askfirst.common.channel.model.InboundMessage message, long ttlEpochSeconds) {
            if (failAppend) {
                throw new IllegalStateException("BUFFER_DOWN");
            }
            lastSeq = seqGenerator.getAndIncrement();
            return lastSeq;
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

        public FakeTelegramClient() {
            super("fake-token");
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
