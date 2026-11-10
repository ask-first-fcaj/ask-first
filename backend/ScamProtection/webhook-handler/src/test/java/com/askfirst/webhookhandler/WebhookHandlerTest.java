package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.store.AuditRecord;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.GuardianRecord;
import com.askfirst.common.store.GuardianRepo;
import com.askfirst.common.store.InviteRecord;
import com.askfirst.common.store.InviteRepo;
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
    private InMemoryGuardianRepo fakeGuardianRepo;
    private InMemoryInviteRepo fakeInviteRepo;
    private WebhookHandler handler;
    private Context mockContext;

    @BeforeEach
    void setUp() {
        fakeTelegram = new FakeTelegramClient();
        fakeSqs = new FakeSqsClient();
        fakeUserRepo = new InMemoryUserRepo();
        fakeAuditRepo = new InMemoryAuditRepo();
        fakeRateLimitRepo = new InMemoryRateLimitRepo();
        fakeGuardianRepo = new InMemoryGuardianRepo();
        fakeInviteRepo = new InMemoryInviteRepo();

        handler = new WebhookHandler(
                fakeTelegram,
                fakeSqs,
                "https://sqs.mock.queue",
                SECRET,
                fakeUserRepo,
                fakeAuditRepo,
                fakeRateLimitRepo,
                fakeGuardianRepo,
                fakeInviteRepo,
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

    @Test
    @DisplayName("BE-20/BE-22: Dedup prevents double processing of duplicate update_id")
    void testDedupDuplicateUpdateReturns200AndSkipsProcessing() {
        InMemoryDedupService dedupService = new InMemoryDedupService();
        WebhookHandler dedupHandler = new WebhookHandler(
                fakeTelegram,
                fakeSqs,
                "https://sqs.mock.queue",
                SECRET,
                fakeUserRepo,
                fakeAuditRepo,
                fakeRateLimitRepo,
                POLICY_SHA256,
                dedupService);

        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        String updateJson = """
                {
                  "update_id": 9991,
                  "message": {
                    "message_id": 1,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "Kiểm tra số tài khoản 9999"
                  }
                }
                """;

        // Lần 1: update mới -> xử lý bình thường, đưa vào SQS
        APIGatewayProxyResponseEvent res1 = dedupHandler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res1.getStatusCode()).isEqualTo(200);
        assertThat(res1.getBody()).isEqualTo("OK");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1);
        assertThat(dedupService.acquiredUpdates).contains("9991");

        // Lần 2: Telegram retry gửi lại cùng update_id -> trả về DUPLICATE, không đưa SQS lần 2
        APIGatewayProxyResponseEvent res2 = dedupHandler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res2.getStatusCode()).isEqualTo(200);
        assertThat(res2.getBody()).isEqualTo("DUPLICATE");
        assertThat(fakeSqs.sentCount.get()).isEqualTo(1); // sentCount không tăng
    }

    @Test
    @DisplayName("BE-20: Dedup lock is released when handler returns 500 so Telegram can retry")
    void testDedupLockReleasedOn500Error() {
        InMemoryDedupService dedupService = new InMemoryDedupService();
        WebhookHandler dedupHandler = new WebhookHandler(
                fakeTelegram,
                fakeSqs,
                "https://sqs.mock.queue",
                SECRET,
                fakeUserRepo,
                fakeAuditRepo,
                fakeRateLimitRepo,
                POLICY_SHA256,
                dedupService);

        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        fakeSqs.throwException.set(true); // Gây lỗi SQS 500

        String updateJson = """
                {
                  "update_id": 9992,
                  "message": {
                    "message_id": 2,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "Kiểm tra link https://bad.site"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = dedupHandler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(500);
        assertThat(res.getBody()).isEqualTo("SQS_FAILED");

        // Khóa dedup phải được nhả (xóa khỏi acquiredUpdates) để Telegram retry được
        assertThat(dedupService.acquiredUpdates).doesNotContain("9992");
    }

    @Test
    @DisplayName("BE-26: When SQS fails, handler returns 500 and rolls back rate limit quota")
    void testSqsFailureReturns500AndRollsBackRateLimit() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        fakeSqs.throwException.set(true);

        String updateJson = """
                {
                  "update_id": 9993,
                  "message": {
                    "message_id": 3,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 222, "type": "private" },
                    "date": 1729000000,
                    "text": "Kiểm tra tin nhắn khẩn cấp"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(updateJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(500);
        assertThat(res.getBody()).isEqualTo("SQS_FAILED");

        // Counter phải được hoàn tác (rollback), không làm mất quota của người dùng
        assertThat(fakeRateLimitRepo.getCount("tg:111")).isEqualTo(0L);
    }

    @Test
    @DisplayName("BE-25: Clicking consent:decline revokes active consent and halts inspection")
    void testRevokeConsentWhenDeclineClickedAfterAgree() {
        // Trước đó người dùng đã đồng ý
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        assertThat(fakeUserRepo.hasValidConsent("tg:111", POLICY_SHA256)).isTrue();

        String callbackJson = """
                {
                  "update_id": 9994,
                  "callback_query": {
                    "id": "cb_decline_1",
                    "from": { "id": 111, "is_bot": false },
                    "message": {
                      "message_id": 4,
                      "chat": { "id": 222, "type": "private" },
                      "date": 1729000000
                    },
                    "data": "consent:decline"
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(callbackJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("CALLBACK_HANDLED");

        // Đồng ý phân tích phải bị thu hồi
        assertThat(fakeUserRepo.hasValidConsent("tg:111", POLICY_SHA256)).isFalse();
        // Ghi audit trail
        assertThat(fakeAuditRepo.events).contains("CONSENT_DECLINED");
        // Gửi thông điệp xác nhận đã dừng thu thập
        assertThat(fakeTelegram.lastText).contains("không thu thập hoặc phân tích");
    }

    @Test
    @DisplayName("BE-20: Missing or blank secret on handler fails closed (returns 401)")
    void testSecretNullOrBlankOnHandlerFailsClosed() {
        WebhookHandler nullSecretHandler = new WebhookHandler(
                fakeTelegram,
                fakeSqs,
                "https://sqs.mock.queue",
                null,
                fakeUserRepo,
                fakeAuditRepo,
                fakeRateLimitRepo,
                POLICY_SHA256);

        APIGatewayProxyRequestEvent req = new APIGatewayProxyRequestEvent()
                .withHeaders(Map.of("X-Telegram-Bot-Api-Secret-Token", "any-secret"))
                .withBody("{\"update_id\": 1}");

        APIGatewayProxyResponseEvent res1 = nullSecretHandler.handleRequest(req, mockContext);
        assertThat(res1.getStatusCode()).isEqualTo(401);
        assertThat(res1.getBody()).isEqualTo("UNAUTHORIZED");

        WebhookHandler blankSecretHandler = new WebhookHandler(
                fakeTelegram,
                fakeSqs,
                "https://sqs.mock.queue",
                "   ",
                fakeUserRepo,
                fakeAuditRepo,
                fakeRateLimitRepo,
                POLICY_SHA256);

        APIGatewayProxyResponseEvent res2 = blankSecretHandler.handleRequest(req, mockContext);
        assertThat(res2.getStatusCode()).isEqualTo(401);
        assertThat(res2.getBody()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("BE-35: Receiving an APK file triggers red alert, alerts guardian, and never queues to SQS")
    void testApkFileDetectionBlocksSqsAndAlertsUserAndGuardian() {
        // Elder has active consent
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        // Elder has an active guardian linked
        GuardianRecord guardian = new GuardianRecord("tg:111", "tg:888");
        guardian.setGuardianChatId("888");
        guardian.setStatus("active");
        fakeGuardianRepo.save(guardian);

        String apkJson = """
                {
                  "update_id": 301,
                  "message": {
                    "message_id": 10,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 111, "type": "private" },
                    "date": 1729000000,
                    "document": {
                      "file_id": "doc_apk_1",
                      "file_unique_id": "u_apk_1",
                      "file_name": "DichVuCong_CapNhat.apk",
                      "mime_type": "application/vnd.android.package-archive",
                      "file_size": 15000000
                    }
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(apkJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("APK_BLOCKED");

        // NEVER queued to SQS to avoid worker malware execution
        assertThat(fakeSqs.messageSent.get()).isFalse();
        assertThat(fakeSqs.sentCount.get()).isEqualTo(0);

        // Warning message sent to elder
        String elderText = fakeTelegram.textsByChatId.get(111L);
        assertThat(elderText).contains("CẢNH BÁO NGUY HIỂM KHẨN CẤP");
        assertThat(elderText).contains("DichVuCong_CapNhat.apk");
        assertThat(elderText).contains("TUYỆT ĐỐI KHÔNG BẤM CÀI ĐẶT");

        // Urgent alert sent to guardian
        String guardianText = fakeTelegram.textsByChatId.get(888L);
        assertThat(guardianText).contains("CẢNH BÁO KHẨN CẤP CHO NGƯỜI THÂN");
        assertThat(guardianText).contains("DichVuCong_CapNhat.apk");

        // Audit trail
        assertThat(fakeAuditRepo.events).contains("APK_DETECTED");
    }

    @Test
    @DisplayName("BE-35: Receiving unsupported media guides user to take a screenshot")
    void testUnsupportedMediaGuidesUserToScreenshot() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        String pdfJson = """
                {
                  "update_id": 302,
                  "message": {
                    "message_id": 11,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 111, "type": "private" },
                    "date": 1729000000,
                    "document": {
                      "file_id": "doc_pdf_1",
                      "file_unique_id": "u_pdf_1",
                      "file_name": "hop_dong.pdf",
                      "mime_type": "application/pdf",
                      "file_size": 250000
                    }
                  }
                }
                """;

        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(pdfJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("UNSUPPORTED_MEDIA_GUIDED");

        assertThat(fakeSqs.messageSent.get()).isFalse();
        assertThat(fakeTelegram.lastText).contains("CHỤP ẢNH MÀN HÌNH");
    }

    @Test
    @DisplayName("BE-28/BE-36: /nguoithan command and invite creation")
    void testGuardianManagementAndInviteCreation() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));

        // 1. Send /nguoithan
        String cmdJson = """
                {
                  "update_id": 303,
                  "message": {
                    "message_id": 12,
                    "from": { "id": 111, "is_bot": false },
                    "chat": { "id": 111, "type": "private" },
                    "date": 1729000000,
                    "text": "/nguoithan"
                  }
                }
                """;
        APIGatewayProxyResponseEvent cmdRes = handler.handleRequest(createRequest(cmdJson), mockContext);
        assertThat(cmdRes.getStatusCode()).isEqualTo(200);
        assertThat(cmdRes.getBody()).isEqualTo("COMMAND_HANDLED");
        assertThat(fakeTelegram.lastText).contains("QUẢN LÝ NGƯỜI THÂN ĐỒNG HÀNH");
        assertThat(fakeTelegram.lastText).contains("chưa kết nối với người thân nào");

        // 2. Elder clicks "Tạo mã kết nối"
        String cbJson = """
                {
                  "update_id": 304,
                  "callback_query": {
                    "id": "cb_inv_1",
                    "from": { "id": 111, "is_bot": false },
                    "message": { "message_id": 12, "chat": { "id": 111, "type": "private" }, "date": 1729000000 },
                    "data": "guardian:create_invite"
                  }
                }
                """;
        APIGatewayProxyResponseEvent cbRes = handler.handleRequest(createRequest(cbJson), mockContext);
        assertThat(cbRes.getStatusCode()).isEqualTo(200);
        assertThat(cbRes.getBody()).isEqualTo("CALLBACK_HANDLED");
        assertThat(fakeTelegram.lastText).contains("Mã kết nối mới của Bác là:");
        assertThat(fakeTelegram.lastText).contains("24 giờ");

        // Verify invite in repo
        assertThat(fakeInviteRepo.store).isNotEmpty();
        String createdCode = fakeInviteRepo.store.keySet().iterator().next();
        assertThat(createdCode).hasSize(6);
        assertThat(fakeInviteRepo.store.get(createdCode).getElderUid()).isEqualTo("tg:111");
    }

    @Test
    @DisplayName("BE-28: Relative runs /ketnoi and Elder receives 3-tier double opt-in prompt")
    void testConnectCommandAndOptInAcceptance() {
        // Setup invite code created by elder
        fakeInviteRepo.createInvite("123456", "tg:111", java.time.Instant.now().getEpochSecond() + 86400);

        // Relative (tg:888, chat 888) runs /ketnoi 123456
        String connectJson = """
                {
                  "update_id": 305,
                  "message": {
                    "message_id": 20,
                    "from": { "id": 888, "is_bot": false },
                    "chat": { "id": 888, "type": "private" },
                    "date": 1729000000,
                    "text": "/ketnoi 123456"
                  }
                }
                """;

        APIGatewayProxyResponseEvent connectRes = handler.handleRequest(createRequest(connectJson), mockContext);
        assertThat(connectRes.getStatusCode()).isEqualTo(200);
        assertThat(connectRes.getBody()).isEqualTo("CONNECT_REQUEST_SENT");

        // Invite is marked as used
        assertThat(fakeInviteRepo.store.get("123456").getStatus()).isEqualTo("used");
        assertThat(fakeInviteRepo.store.get("123456").getUsedByUid()).isEqualTo("tg:888");

        // Relative notified
        assertThat(fakeTelegram.textsByChatId.get(888L)).contains("Đã gửi yêu cầu kết nối tới người thân");

        // Elder (111) received double opt-in prompt with 3 share levels
        String elderPrompt = fakeTelegram.textsByChatId.get(111L);
        assertThat(elderPrompt).contains("YÊU CẦU KẾT NỐI TỪ NGƯỜI THÂN");
        assertThat(elderPrompt).contains("Mức 1");
        assertThat(elderPrompt).contains("Mức 2 (Khuyên dùng)");
        assertThat(elderPrompt).contains("Mức 3");

        // Elder accepts at Level 2 (g_acc:2:888:888)
        String acceptJson = """
                {
                  "update_id": 306,
                  "callback_query": {
                    "id": "cb_acc_1",
                    "from": { "id": 111, "is_bot": false },
                    "message": { "message_id": 21, "chat": { "id": 111, "type": "private" }, "date": 1729000000 },
                    "data": "g_acc:2:888:888"
                  }
                }
                """;
        APIGatewayProxyResponseEvent accRes = handler.handleRequest(createRequest(acceptJson), mockContext);
        assertThat(accRes.getStatusCode()).isEqualTo(200);
        assertThat(accRes.getBody()).isEqualTo("CALLBACK_HANDLED");

        // Repo updated with active guardian
        Optional<GuardianRecord> g = fakeGuardianRepo.getGuardian("tg:111", "tg:888");
        assertThat(g).isPresent();
        assertThat(g.get().getShareLevel()).isEqualTo(2);
        assertThat(g.get().getStatus()).isEqualTo("active");

        // Audit recorded
        assertThat(fakeAuditRepo.events).contains("GUARDIAN_LINKED");

        // Both parties informed
        assertThat(fakeTelegram.textsByChatId.get(111L)).contains("Đã kết nối thành công với người thân ở Mức 2");
        assertThat(fakeTelegram.textsByChatId.get(888L)).contains("Kết nối thành công");
    }

    @Test
    @DisplayName("BE-36: Elder unlinks a guardian successfully")
    void testUnlinkGuardian() {
        GuardianRecord guardian = new GuardianRecord("tg:111", "tg:888");
        guardian.setGuardianChatId("888");
        guardian.setStatus("active");
        fakeGuardianRepo.save(guardian);

        String unlinkJson = """
                {
                  "update_id": 307,
                  "callback_query": {
                    "id": "cb_unl_1",
                    "from": { "id": 111, "is_bot": false },
                    "message": { "message_id": 30, "chat": { "id": 111, "type": "private" }, "date": 1729000000 },
                    "data": "g_unlink:tg:888"
                  }
                }
                """;
        APIGatewayProxyResponseEvent res = handler.handleRequest(createRequest(unlinkJson), mockContext);
        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("CALLBACK_HANDLED");

        assertThat(fakeGuardianRepo.getGuardian("tg:111", "tg:888").get().getStatus()).isEqualTo("revoked");
        assertThat(fakeAuditRepo.events).contains("GUARDIAN_UNLINKED");
        assertThat(fakeTelegram.lastText).contains("Đã huỷ kết nối với người thân");
    }

    @Test
    @DisplayName("BE-31: /rutdongy confirmation revokes user consent and unlinks guardians")
    void testRutDongyRevocation() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        GuardianRecord guardian = new GuardianRecord("tg:111", "tg:888");
        guardian.setStatus("active");
        fakeGuardianRepo.save(guardian);

        // 1. /rutdongy
        APIGatewayProxyResponseEvent cmdRes = handler.handleRequest(
                createRequest("{\"update_id\": 308, \"message\": {\"message_id\": 40, \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 111, \"type\": \"private\"}, \"date\": 1729000000, \"text\": \"/rutdongy\"}}"),
                mockContext);
        assertThat(cmdRes.getStatusCode()).isEqualTo(200);
        assertThat(fakeTelegram.lastText).contains("XÁC NHẬN RÚT LẠI SỰ ĐỒNG Ý");

        // 2. Click confirm
        APIGatewayProxyResponseEvent cbRes = handler.handleRequest(
                createRequest("{\"update_id\": 309, \"callback_query\": {\"id\": \"cb_rv_1\", \"from\": {\"id\": 111, \"is_bot\": false}, \"message\": {\"message_id\": 40, \"chat\": {\"id\": 111, \"type\": \"private\"}, \"date\": 1729000000}, \"data\": \"privacy:revoke_consent\"}}"),
                mockContext);
        assertThat(cbRes.getStatusCode()).isEqualTo(200);
        assertThat(fakeUserRepo.hasValidConsent("tg:111", POLICY_SHA256)).isFalse();
        assertThat(fakeGuardianRepo.getGuardian("tg:111", "tg:888").get().getStatus()).isEqualTo("revoked");
        assertThat(fakeAuditRepo.events).contains("CONSENT_REVOKED");
        assertThat(fakeTelegram.lastText).contains("đã ghi nhận rút lại sự đồng ý");
    }

    @Test
    @DisplayName("BE-31: /xoadulieu completely deletes user and guardians under ND 356 with anonymized audit")
    void testXoaDuLieuND356() {
        fakeUserRepo.updateConsent("tg:111", POLICY_SHA256, "1.0", Map.of("analyze", true));
        GuardianRecord guardian = new GuardianRecord("tg:111", "tg:888");
        guardian.setStatus("active");
        fakeGuardianRepo.save(guardian);

        // 1. /xoadulieu
        APIGatewayProxyResponseEvent cmdRes = handler.handleRequest(
                createRequest("{\"update_id\": 310, \"message\": {\"message_id\": 50, \"from\": {\"id\": 111, \"is_bot\": false}, \"chat\": {\"id\": 111, \"type\": \"private\"}, \"date\": 1729000000, \"text\": \"/xoadulieu\"}}"),
                mockContext);
        assertThat(cmdRes.getStatusCode()).isEqualTo(200);
        assertThat(fakeTelegram.lastText).contains("YÊU CẦU XOÁ TOÀN BỘ DỮ LIỆU CÁ NHÂN");

        // 2. Click confirm
        APIGatewayProxyResponseEvent cbRes = handler.handleRequest(
                createRequest("{\"update_id\": 311, \"callback_query\": {\"id\": \"cb_del_1\", \"from\": {\"id\": 111, \"is_bot\": false}, \"message\": {\"message_id\": 50, \"chat\": {\"id\": 111, \"type\": \"private\"}, \"date\": 1729000000}, \"data\": \"privacy:delete_data\"}}"),
                mockContext);
        assertThat(cbRes.getStatusCode()).isEqualTo(200);

        // Data purged
        assertThat(fakeUserRepo.getUser("tg:111")).isEmpty();
        assertThat(fakeGuardianRepo.getGuardian("tg:111", "tg:888")).isEmpty();

        // Anonymous audit
        assertThat(fakeAuditRepo.events).contains("DATA_DELETED");
        assertThat(fakeTelegram.lastText).contains("đã xoá sạch toàn bộ dữ liệu cá nhân");
    }

    @Test
    @DisplayName("BE-30: Feedback wrong triggers sample donation prompt with 30-day lifecycle")
    void testFeedbackWrongAndDonationConsent() {
        // 1. User clicks feedback:wrong
        String fbJson = """
                {
                  "update_id": 312,
                  "callback_query": {
                    "id": "cb_fb_1",
                    "from": { "id": 111, "is_bot": false },
                    "message": { "message_id": 60, "chat": { "id": 111, "type": "private" }, "date": 1729000000 },
                    "data": "feedback:wrong"
                  }
                }
                """;
        APIGatewayProxyResponseEvent fbRes = handler.handleRequest(createRequest(fbJson), mockContext);
        assertThat(fbRes.getStatusCode()).isEqualTo(200);
        assertThat(fakeAuditRepo.events).contains("FEEDBACK_WRONG");
        assertThat(fakeTelegram.lastText).contains("xoá sau 30 ngày");
        assertThat(fakeTelegram.lastText).contains("che sạch toàn bộ thông tin cá nhân");

        // 2. User agrees to donate masked sample
        String donateJson = """
                {
                  "update_id": 313,
                  "callback_query": {
                    "id": "cb_dn_1",
                    "from": { "id": 111, "is_bot": false },
                    "message": { "message_id": 61, "chat": { "id": 111, "type": "private" }, "date": 1729000000 },
                    "data": "donate:agree"
                  }
                }
                """;
        APIGatewayProxyResponseEvent dnRes = handler.handleRequest(createRequest(donateJson), mockContext);
        assertThat(dnRes.getStatusCode()).isEqualTo(200);
        assertThat(fakeAuditRepo.events).contains("DONATION_CONSENT_GIVEN");
        assertThat(fakeTelegram.lastText).contains("Mẫu đóng góp của Bác sẽ giúp bảo vệ");
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
        final List<String> sentTexts = new ArrayList<>();
        final Map<Long, String> textsByChatId = new HashMap<>();

        public FakeTelegramClient() {
            super("fake-token");
        }

        @Override
        public int sendText(long chatId, String text, Map<String, Object> replyMarkup) {
            this.lastChatId = chatId;
            this.lastText = text;
            this.lastMarkup = replyMarkup;
            this.sentTexts.add(text);
            this.textsByChatId.put(chatId, text);
            return 999;
        }

        @Override
        public void answerCallbackQuery(String callbackQueryId, String text) {
        }
    }

    private static class FakeSqsClient implements SqsClient {
        AtomicBoolean messageSent = new AtomicBoolean(false);
        AtomicInteger sentCount = new AtomicInteger(0);
        AtomicBoolean throwException = new AtomicBoolean(false);

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }

        @Override
        public SendMessageResponse sendMessage(SendMessageRequest req) {
            if (throwException.get()) {
                throw software.amazon.awssdk.services.sqs.model.SqsException.builder()
                        .message("Simulated SQS error")
                        .build();
            }
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
        public void revokeConsent(String userId) {
            UserRecord u = store.get(userId);
            if (u != null) {
                Map<String, Boolean> revoked = new HashMap<>();
                for (String p : u.getPurposes().keySet()) {
                    revoked.put(p, false);
                }
                revoked.put("analyze", false);
                u.setPurposes(revoked);
                u.setConsentedAt(null);
            }
        }

        @Override
        public void updateAddressForm(String userId, String addressForm) {
            UserRecord u = store.computeIfAbsent(userId, id -> new UserRecord(id, "123"));
            u.setAddressForm(addressForm);
        }

        @Override
        public void deleteUser(String userId) {
            store.remove(userId);
        }
    }

    private static class InMemoryGuardianRepo extends GuardianRepo {
        final Map<String, GuardianRecord> store = new HashMap<>();

        public InMemoryGuardianRepo() {
            super();
        }

        private String key(String elderUid, String guardianUid) {
            return elderUid + "#" + guardianUid;
        }

        @Override
        public void save(GuardianRecord record) {
            store.put(key(record.getElderUid(), record.getGuardianUid()), record);
        }

        @Override
        public Optional<GuardianRecord> getGuardian(String elderUid, String guardianUid) {
            return Optional.ofNullable(store.get(key(elderUid, guardianUid)));
        }

        @Override
        public List<GuardianRecord> listGuardians(String elderUid) {
            List<GuardianRecord> list = new ArrayList<>();
            for (GuardianRecord r : store.values()) {
                if (elderUid.equals(r.getElderUid())) {
                    list.add(r);
                }
            }
            return list;
        }

        @Override
        public void updateStatus(String elderUid, String guardianUid, String status) {
            GuardianRecord r = store.get(key(elderUid, guardianUid));
            if (r != null) {
                r.setStatus(status);
            }
        }

        @Override
        public void delete(String elderUid, String guardianUid) {
            store.remove(key(elderUid, guardianUid));
        }
    }

    private static class InMemoryInviteRepo extends InviteRepo {
        final Map<String, InviteRecord> store = new HashMap<>();

        public InMemoryInviteRepo() {
            super();
        }

        @Override
        public void createInvite(String code, String elderUid, long ttlEpochSeconds) {
            InviteRecord r = new InviteRecord(code, elderUid, ttlEpochSeconds);
            r.setStatus("pending");
            store.put(code, r);
        }

        @Override
        public Optional<InviteRecord> getInvite(String code) {
            String clean = code.startsWith("INVITE#") ? code.substring(7) : code;
            return Optional.ofNullable(store.get(clean));
        }

        @Override
        public boolean markUsed(String code, String usedByUid) {
            String clean = code.startsWith("INVITE#") ? code.substring(7) : code;
            InviteRecord r = store.get(clean);
            if (r == null || !"pending".equals(r.getStatus())) {
                return false;
            }
            r.setStatus("used");
            r.setUsedByUid(usedByUid);
            return true;
        }

        @Override
        public void deleteInvite(String code) {
            String clean = code.startsWith("INVITE#") ? code.substring(7) : code;
            store.remove(clean);
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

        @Override
        public void rollback(String userId) {
            counts.computeIfPresent(userId, (k, v) -> Math.max(0L, v - 1));
        }

        public long getCount(String userId) {
            return counts.getOrDefault(userId, 0L);
        }
    }

    private static class InMemoryDedupService extends com.askfirst.common.store.DynamoStoreService {
        final java.util.Set<String> acquiredUpdates = new java.util.HashSet<>();

        public InMemoryDedupService() {
            super();
        }

        @Override
        public boolean tryAcquireDedup(String updateId, long ttlEpochSeconds) {
            return acquiredUpdates.add(updateId);
        }

        @Override
        public void deleteDedup(String updateId) {
            acquiredUpdates.remove(updateId);
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
