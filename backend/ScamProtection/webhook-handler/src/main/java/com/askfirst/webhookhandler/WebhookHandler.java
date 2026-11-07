package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.TelegramClient.InlineButton;
import com.askfirst.common.channel.TelegramAdapter;
import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.channel.model.TelegramCallbackQuery;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.DynamoStoreService;
import com.askfirst.common.store.RateLimitRepo;
import com.askfirst.common.store.UserRecord;
import com.askfirst.common.store.UserRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WebhookHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    public static final String POLICY_VERSION = "1.0";

    /**
     * CHỈ dùng cho test / LocalBotRunner. Đây là hash giả, KHÔNG được dùng làm fallback khi chạy thật:
     * constructor mặc định (Lambda) bắt buộc biến môi trường POLICY_SHA256.
     */
    public static final String DEFAULT_POLICY_SHA256 = "d1a8f9b2c3e4567890abcdef1234567890abcdef1234567890abcdef12345678";

    /** Khóa dedup update_id sống 24 giờ (Telegram chỉ retry update trong thời gian ngắn). */
    private static final long DEDUP_TTL_SECONDS = 24 * 3600L;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private final String queueUrl;
    private final String secret;
    private final String policySha256;
    private final TelegramClient telegram;
    private final SqsClient sqs;
    private final TelegramAdapter adapter;

    private final UserRepo userRepo;
    private final AuditRepo auditRepo;
    private final RateLimitRepo rateLimitRepo;

    /** Chống xử lý trùng cùng một update_id (Telegram retry). Null = không dedup (test/local). */
    private final DynamoStoreService dedup;

    /**
     * true chỉ khi tạo qua {@link #forLocalDevelopment(TelegramClient)} (long polling trên máy dev):
     * bỏ kiểm tra secret và cho phép chạy không có SQS. Lambda thật luôn là false.
     */
    private final boolean localDevelopment;

    /**
     * Constructor cho Lambda. Thiếu biến môi trường nào là lỗi ngay lúc khởi tạo (thấy rõ trong CloudWatch)
     * thay vì chạy tiếp rồi mất job / chấp nhận request giả mạo một cách im lặng.
     */
    public WebhookHandler() {
        this.queueUrl = requireEnv("WEBHOOK_QUEUE_URL");
        this.secret = requireEnv("TELEGRAM_WEBHOOK_SECRET");
        this.policySha256 = requireEnv("POLICY_SHA256");
        this.telegram = new TelegramClient(requireEnv("TELEGRAM_BOT_TOKEN"));
        String tableName = requireEnv("DYNAMODB_TABLE_NAME");

        this.sqs = SqsClient.create();
        this.adapter = new TelegramAdapter();
        this.localDevelopment = false;

        DynamoDbClient ddb = DynamoDbClient.create();
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(ddb).build();
        this.userRepo = new UserRepo(enhanced, tableName);
        this.auditRepo = new AuditRepo(enhanced, tableName);
        this.rateLimitRepo = new RateLimitRepo(ddb, tableName);
        this.dedup = DynamoStoreService.create(ddb, tableName);
    }

    public WebhookHandler(TelegramClient telegram, SqsClient sqs, String queueUrl, String secret) {
        this(telegram, sqs, queueUrl, secret, null, null, null, DEFAULT_POLICY_SHA256);
    }

    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            RateLimitRepo rateLimitRepo,
            String policySha256) {
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, rateLimitRepo, policySha256, null, false);
    }

    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            RateLimitRepo rateLimitRepo,
            String policySha256,
            DynamoStoreService dedup) {
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, rateLimitRepo, policySha256, dedup, false);
    }

    private WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            RateLimitRepo rateLimitRepo,
            String policySha256,
            DynamoStoreService dedup,
            boolean localDevelopment) {
        this.telegram = telegram;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.secret = secret;
        this.userRepo = userRepo;
        this.auditRepo = auditRepo;
        this.rateLimitRepo = rateLimitRepo;
        this.policySha256 = (policySha256 != null && !policySha256.isBlank()) ? policySha256 : DEFAULT_POLICY_SHA256;
        this.adapter = new TelegramAdapter();
        this.dedup = dedup;
        this.localDevelopment = localDevelopment;
    }

    /**
     * Handler cho LocalBotRunner (long polling, không có webhook nên không có header secret, không có SQS).
     * Đây là đường DUY NHẤT được bỏ qua kiểm tra secret.
     */
    public static WebhookHandler forLocalDevelopment(TelegramClient telegram) {
        return new WebhookHandler(telegram, null, null, null, null, null, null, DEFAULT_POLICY_SHA256, null, true);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Thiếu biến môi trường bắt buộc: " + name);
        }
        return value;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input,
            Context context) {
        if (!isFromTelegram(input, context)) {
            return response(401, "UNAUTHORIZED");
        }

        final JsonNode root;
        try {
            root = MAPPER.readTree(input.getBody());
        } catch (Exception e) {
            log(context, "Lỗi đọc JSON webhook: " + e);
            return response(400, "INVALID_JSON");
        }

        // Dedup theo update_id: Telegram retry khi không nhận 200 -> không được ghi audit / đếm rate limit / enqueue lần 2
        final String updateId = root.hasNonNull("update_id") ? root.path("update_id").asText() : null;
        boolean lockAcquired = false;
        if (dedup != null && updateId != null) {
            try {
                long ttl = Instant.now().getEpochSecond() + DEDUP_TTL_SECONDS;
                if (!dedup.tryAcquireDedup(updateId, ttl)) {
                    log(context, "Bỏ qua update trùng: " + updateId);
                    return response(200, "DUPLICATE");
                }
                lockAcquired = true;
            } catch (Exception e) {
                log(context, "Không kiểm tra được dedup (" + e + ") -> vẫn xử lý update " + updateId);
            }
        }

        try {
            APIGatewayProxyResponseEvent result = routeUpdate(root, context);
            if (result.getStatusCode() >= 500 && lockAcquired) {
                // Job CHƯA được nhận (vd. SQS lỗi): nhả khóa để Telegram retry được
                releaseDedup(updateId, context);
            }
            return result;
        } catch (Exception e) {
            // Đã có tác dụng phụ một phần -> giữ khóa dedup, trả 200 để Telegram không retry gây xử lý đôi
            log(context, "Lỗi không mong đợi khi xử lý update " + updateId + ": " + e);
            return response(200, "INTERNAL_ERROR_LOGGED");
        }
    }

    private void releaseDedup(String updateId, Context context) {
        try {
            dedup.deleteDedup(updateId);
        } catch (Exception e) {
            log(context, "Không nhả được khóa dedup " + updateId + ": " + e);
        }
    }

    private APIGatewayProxyResponseEvent routeUpdate(JsonNode root, Context context) {
        // 1. Xử lý Callback Query (Bấm nút inline keyboard - Fast Path)
        Optional<TelegramCallbackQuery> cbOpt = adapter.parseCallbackQuery(root);
        if (cbOpt.isPresent()) {
            return handleCallbackQuery(cbOpt.get(), context);
        }

        // 2. Bóc tách InboundMessage
        Optional<InboundMessage> inboundOpt = adapter.parseUpdate(root);
        if (inboundOpt.isEmpty()) {
            return response(200, "IGNORED");
        }

        InboundMessage inbound = inboundOpt.get();
        long chatId;
        try {
            chatId = Long.parseLong(inbound.chatId());
        } catch (NumberFormatException e) {
            return response(400, "INVALID_CHAT_ID");
        }

        String userId = inbound.userId();

        // 3. Fast path: Các lệnh điều khiển (/start, /caidat, /huongdan, /bilua)
        if (TelegramAdapter.isCommand(inbound)) {
            return handleCommand(chatId, userId, inbound.text().trim(), context);
        }

        // 4. Slow path: Kiểm tra tin nhắn lừa đảo
        return handleContentInspection(inbound, chatId, userId, context);
    }

    /** Callback luôn được trả lời: lỗi DB không được để nút "xoay" mãi hay báo "đã lưu" khi chưa lưu. */
    private APIGatewayProxyResponseEvent handleCallbackQuery(TelegramCallbackQuery cb, Context context) {
        try {
            return processCallbackQuery(cb, context);
        } catch (Exception e) {
            log(context, "Lỗi xử lý callback '" + cb.data() + "': " + e);
            try {
                if (telegram != null) {
                    telegram.answerCallbackQuery(cb.queryId(), "Cháu chưa lưu được, Bác bấm lại giúp cháu nhé.");
                }
            } catch (Exception ignored) {
                // không còn gì để làm thêm
            }
            return response(200, "CALLBACK_FAILED");
        }
    }

    private APIGatewayProxyResponseEvent processCallbackQuery(TelegramCallbackQuery cb, Context context) {
        long chatId = Long.parseLong(cb.chatId());
        String userId = cb.userId();
        String data = cb.data();

        if ("consent:agree".equals(data)) {
            if (userRepo != null) {
                // Chỉ bật "analyze": các mục đích khác (guardian/voice/donate) giữ nguyên lựa chọn đã có
                Map<String, Boolean> purposes = new HashMap<>();
                purposes.put("analyze", true);
                userRepo.updateConsent(userId, policySha256, POLICY_VERSION, purposes);
            }
            if (auditRepo != null) {
                auditRepo.recordEvent(userId, "CONSENT_GIVEN", POLICY_VERSION, policySha256, "Agreed via inline button");
            }
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã lưu đồng ý!");
                telegram.sendText(chatId,
                        "✅ <b>Cảm ơn Bác đã đồng ý điều khoản!</b>\n\n" +
                        "Từ bây giờ Bác có thể gửi tin nhắn, đường link hoặc ảnh chụp màn hình bất cứ lúc nào để cháu kiểm tra nhé.\n\n" +
                        "• Bác muốn đổi cách xưng hô thân mật? Gõ /caidat\n" +
                        "• Xem cách gửi tin kiểm tra: Gõ /huongdan\n" +
                        "• Khi lỡ dính bẫy lừa cần làm gì gấp: Gõ /bilua");
            }
            return response(200, "CALLBACK_HANDLED");

        } else if ("consent:decline".equals(data)) {
            if (userRepo != null) {
                // Từ chối = thu hồi đồng ý đã có (nếu có), bot ngừng xử lý nội dung ngay
                userRepo.revokeConsent(userId);
            }
            if (auditRepo != null) {
                auditRepo.recordEvent(userId, "CONSENT_DECLINED", POLICY_VERSION, policySha256, "Declined via inline button");
            }
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã ghi nhận");
                telegram.sendText(chatId,
                        "Dạ cháu đã ghi nhận ạ. Theo quy định bảo vệ dữ liệu cá nhân (Nghị định 356), cháu xin phép " +
                        "<b>không thu thập hoặc phân tích</b> bất kỳ nội dung nào khi chưa có sự đồng ý của Bác.\n\n" +
                        "Khi nào cần sử dụng, Bác chỉ cần gõ /start để xem lại và đồng ý nhé!");
            }
            return response(200, "CALLBACK_HANDLED");

        } else if (data != null && data.startsWith("xungho:")) {
            String form = switch (data) {
                case "xungho:co" -> "Cô";
                case "xungho:chu" -> "Chú";
                default -> "Bác";
            };

            if (userRepo != null) {
                userRepo.updateAddressForm(userId, form);
            }
            if (auditRepo != null) {
                auditRepo.recordEvent(userId, "ADDRESS_FORM_CHANGED", POLICY_VERSION, policySha256, "Form=" + form);
            }
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã đổi thành " + form);
                telegram.sendText(chatId,
                        "Dạ, cháu đã lưu cách xưng hô là <b>" + form + "</b> rồi ạ! Cháu sẽ xưng hô như vậy trong các câu trả lời sau nhé.");
            }
            return response(200, "CALLBACK_HANDLED");
        }

        return response(200, "UNKNOWN_CALLBACK");
    }

    private APIGatewayProxyResponseEvent handleCommand(long chatId, String userId, String text, Context context) {
        String cmd = text.toLowerCase();

        if (cmd.startsWith("/start") || cmd.startsWith("/batdau")) {
            boolean hasConsent = (userRepo != null) && userRepo.hasValidConsent(userId, policySha256);

            if (!hasConsent) {
                // Gửi màn hình chào mừng & đồng ý điều khoản (Onboarding - BE-25)
                String welcomeMsg =
                        "<b>Chào mừng Bác đến với AskFirst!</b> 🛡️\n\n" +
                        "Cháu là trợ lý bảo vệ và kiểm tra tin nhắn lừa đảo dành cho người cao tuổi và gia đình.\n\n" +
                        "<b>Chính sách bảo vệ dữ liệu cá nhân (Nghị định 356):</b>\n" +
                        "• Bot <b>KHÔNG BAO GIỜ</b> hỏi mã OTP, mật khẩu hay yêu cầu nạp tiền.\n" +
                        "• Mọi thông tin gửi vào chỉ dùng để phân tích dấu hiệu lừa đảo, không chia sẻ trái phép cho bên thứ ba.\n" +
                        "• Bác có quyền rút đồng ý hoặc yêu cầu xóa dữ liệu bất kỳ lúc nào.\n\n" +
                        "Bác vui lòng bấm <b>Tôi đồng ý</b> bên dưới để bắt đầu sử dụng nhé!";

                Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                        List.of(
                                new InlineButton("✅ Tôi đồng ý", "consent:agree"),
                                new InlineButton("❌ Không đồng ý", "consent:decline")
                        )
                ));

                if (telegram != null) {
                    telegram.sendText(chatId, welcomeMsg, keyboard);
                }
                return response(200, "COMMAND_HANDLED");
            } else {
                String addressForm = getAddressForm(userId);
                String msg = "Chào mừng " + addressForm + " quay trở lại với AskFirst! " + addressForm + " hãy gửi tin nhắn, đường link hoặc ảnh chụp màn hình cần kiểm tra cho cháu nhé.";
                if (telegram != null) {
                    telegram.sendText(chatId, msg);
                }
                return response(200, "COMMAND_HANDLED");
            }

        } else if (cmd.startsWith("/caidat")) {
            String msg = "<b>Cài đặt cách xưng hô thân mật:</b>\nBác muốn cháu xưng hô với Bác là gì ạ? Bác vui lòng chọn bên dưới nhé:";
            Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                    List.of(
                            new InlineButton("Bác", "xungho:bac"),
                            new InlineButton("Cô", "xungho:co"),
                            new InlineButton("Chú", "xungho:chu")
                    )
            ));
            if (telegram != null) {
                telegram.sendText(chatId, msg, keyboard);
            }
            return response(200, "COMMAND_HANDLED");

        } else if (cmd.startsWith("/huongdan")) {
            String guideMsg =
                    "<b>Hướng dẫn sử dụng AskFirst:</b> 📖\n\n" +
                    "1. <b>Chụp ảnh màn hình:</b> Chụp lại tin nhắn đáng ngờ, cuộc gọi hoặc hóa đơn rồi gửi thẳng vào đây cho cháu.\n" +
                    "2. <b>Chép văn bản / chuyển tiếp:</b> Copy nội dung tin nhắn hoặc bấm chuyển tiếp (Forward) tin nhắn từ người lạ sang.\n" +
                    "3. <b>Chờ kết quả:</b> Trong vài giây, hệ thống AI sẽ phân tích các dấu hiệu lừa đảo và cảnh báo ngay cho Bác.\n\n" +
                    "<i>💡 Mẹo: Bác có thể ghim đoạn chat này lên đầu danh sách Telegram để dễ tìm kiếm khi cần kiểm tra gấp!</i>";

            if (telegram != null) {
                telegram.sendText(chatId, guideMsg);
            }
            return response(200, "COMMAND_HANDLED");

        } else if (cmd.startsWith("/bilua")) {
            String emergencyMsg =
                    "🚨 <b>CÁC VIỆC CẦN LÀM NGAY (30 PHÚT ĐẦU KHI NGHI BỊ LỪA)</b>\n\n" +
                    "1. <b>Khóa thẻ / tài khoản ngân hàng ngay lập tức (0 - 5 phút):</b>\n" +
                    "Gọi ngay tổng đài ngân hàng của Bác, nói rõ nghi bị lừa, yêu cầu khóa thẻ và tạm dừng mọi giao dịch. Đừng chần chừ chờ xác minh!\n\n" +
                    "2. <b>Tắt nguồn / Ngắt mạng điện thoại (0 - 5 phút):</b>\n" +
                    "Nếu Bác đã lỡ bấm vào đường link lạ hoặc cài app lạ, hãy tắt nguồn hoặc tắt Wi-Fi/4G ngay lập tức để kẻ gian không chiếm quyền điều khiển và không lấy được mã OTP.\n\n" +
                    "3. <b>Đổi mật khẩu từ thiết bị khác (5 - 15 phút):</b>\n" +
                    "Dùng điện thoại hoặc máy tính an toàn khác để đổi mật khẩu ngân hàng, ví điện tử, Facebook, Zalo. Gỡ ngay ứng dụng lạ vừa cài.\n\n" +
                    "4. <b>Trình báo Công an gần nhất (15 - 30 phút):</b>\n" +
                    "Đến Công an phường/xã gần nhất hoặc gọi đường dây nóng <b>113</b> để trình báo. Mang theo ảnh chụp tin nhắn, số điện thoại kẻ lừa đảo và biên lai chuyển tiền.\n\n" +
                    "5. <b>Thông báo cho người thân tin cậy:</b>\n" +
                    "Báo ngay cho con cháu trong nhà để cùng hỗ trợ xử lý và cảnh giác nếu kẻ gian tiếp tục liên hệ lừa thêm.";

            if (telegram != null) {
                telegram.sendText(chatId, emergencyMsg);
            }
            return response(200, "COMMAND_HANDLED");
        }

        return response(200, "COMMAND_IGNORED");
    }

    private APIGatewayProxyResponseEvent handleContentInspection(
            InboundMessage inbound,
            long chatId,
            String userId,
            Context context) {

        String addressForm = getAddressForm(userId);

        // 1. Kiểm tra trạng thái Đồng ý điều khoản (Consent Check - BE-25)
        if (userRepo != null && !userRepo.hasValidConsent(userId, policySha256)) {
            String promptConsent =
                    addressForm + " ơi, để bảo vệ dữ liệu cá nhân theo quy định của pháp luật, " +
                    addressForm + " vui lòng bấm <b>Tôi đồng ý</b> bên dưới trước để cháu được phép kiểm tra tin nhắn nhé!";

            Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                    List.of(
                            new InlineButton("✅ Tôi đồng ý", "consent:agree"),
                            new InlineButton("❌ Không đồng ý", "consent:decline")
                    )
            ));

            if (telegram != null) {
                telegram.sendText(chatId, promptConsent, keyboard);
            }
            return response(200, "CONSENT_REQUIRED");
        }

        // 2. Kiểm tra đồng ý tin nhắn thoại (Voice Consent Check - BE-25)
        if ("voice".equalsIgnoreCase(inbound.kind())) {
            boolean hasVoice = (userRepo != null) && userRepo.hasVoiceConsent(userId);
            if (!hasVoice) {
                String voiceMsg =
                        addressForm + " ơi, tính năng kiểm tra tin nhắn thoại (voice) cần có sự đồng ý riêng về xử lý dữ liệu âm thanh. " +
                        "Hiện tại mục này chưa được bật. " + addressForm + " vui lòng chụp màn hình hoặc gửi tin nhắn văn bản cho cháu nhé!";

                if (telegram != null) {
                    telegram.sendText(chatId, voiceMsg);
                }
                return response(200, "VOICE_CONSENT_REQUIRED");
            }
        }

        // 3. Hàng đợi SQS phải sẵn sàng TRƯỚC khi nhận việc: không gửi "⏳" cho một job chắc chắn bị mất.
        //    (Chế độ local long polling không có SQS nên được bỏ qua bước enqueue.)
        boolean queueReady = queueUrl != null && !queueUrl.isBlank() && sqs != null;
        if (!queueReady && !localDevelopment) {
            log(context, "Chưa cấu hình hàng đợi SQS (WEBHOOK_QUEUE_URL) -> từ chối xử lý để không mất job im lặng");
            return response(500, "QUEUE_NOT_CONFIGURED");
        }

        // 4. Kiểm tra Giới hạn tốc độ người dùng (Rate Limiting - BE-26)
        if (rateLimitRepo != null) {
            RateLimitRepo.RateLimitResult rlResult = rateLimitRepo.checkAndIncrement(userId);
            if (!rlResult.allowed()) {
                String rateLimitMsg =
                        addressForm + " ơi, hiện tại " + addressForm + " đã gửi kiểm tra khá nhiều tin trong ngày rồi ạ " +
                        "(giới hạn 30 tin/giờ và 100 tin/ngày). Cháu xin phép tạm nghỉ một chút để đảm bảo an toàn cho hệ thống, " +
                        addressForm + " vui lòng quay lại sau nhé!";

                if (telegram != null) {
                    telegram.sendText(chatId, rateLimitMsg);
                }
                return response(200, "RATE_LIMITED");
            }
        }

        // 5. Bước tin nhắn chờ ("⏳")
        if (telegram != null) {
            try {
                telegram.sendText(
                        chatId,
                        "⏳ Cháu đang kiểm tra, " + addressForm.toLowerCase() + " chờ cháu vài giây nhé...");
            } catch (Exception e) {
                // Tin chờ chỉ là phụ: lỗi ở đây không được làm Telegram retry (gây xử lý/đếm đôi), vẫn đưa job vào hàng đợi
                log(context, "Không gửi được tin nhắn chờ: " + e);
            }
        }

        // 6. Enqueue vào SQS
        if (queueReady) {
            try {
                String payload = MAPPER.writeValueAsString(inbound);
                sqs.sendMessage(
                        SendMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .messageBody(payload)
                                .build());
            } catch (Exception e) {
                log(context, "Lỗi gửi message vào SQS: " + e);
                return response(500, "SQS_FAILED");
            }
        }

        return response(200, "OK");
    }

    private String getAddressForm(String userId) {
        if (userRepo != null) {
            return userRepo.getUser(userId).map(UserRecord::getAddressForm).orElse(UserRecord.DEFAULT_ADDRESS_FORM);
        }
        return UserRecord.DEFAULT_ADDRESS_FORM;
    }

    /**
     * Fail-closed: Function URL công khai (AuthType NONE) nên header secret là lớp xác thực duy nhất.
     * Không có secret được cấu hình => từ chối tất cả. Chỉ chế độ local development mới được bỏ qua.
     */
    private boolean isFromTelegram(APIGatewayProxyRequestEvent input, Context context) {
        if (localDevelopment) {
            return true;
        }
        if (secret == null || secret.isBlank()) {
            log(context, "TELEGRAM_WEBHOOK_SECRET chưa được cấu hình -> từ chối request (fail-closed)");
            return false;
        }
        Map<String, String> headers = input.getHeaders();
        if (headers == null) {
            return false;
        }

        byte[] expectedBytes = secret.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("X-Telegram-Bot-Api-Secret-Token".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                byte[] actualBytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                if (MessageDigest.isEqual(expectedBytes, actualBytes)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void log(Context context, String msg) {
        if (context != null && context.getLogger() != null) {
            context.getLogger().log(msg);
        }
    }

    private APIGatewayProxyResponseEvent response(int status, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(status).withBody(body);
    }
}
