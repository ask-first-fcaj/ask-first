package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.channel.TelegramAdapter;
import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.GuardianRecord;
import com.askfirst.common.store.GuardianRepo;
import com.askfirst.common.store.InviteRecord;
import com.askfirst.common.store.InviteRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lambda Webhook Handler cho AskFirst Bot (BE-22, BE-28, ARCH §4.3).
 *
 * Luồng:
 *  1. Xác thực secret Telegram fail-closed (§8.2).
 *  2. Parse update qua TelegramAdapter.
 *  3. Đường nhanh: lệnh (/nguoithan, /ketnoi) và callback (guardian:*) → trả lời ngay.
 *  4. Đường chậm: gửi "⏳" rồi đẩy vào SQS.
 */
public class WebhookHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private static final String POLICY_VERSION = "1.0";
    private static final String DEFAULT_POLICY_SHA256 = "00000000";

    private final String queueUrl;
    private final String secret;
    private final String policySha256;

    private final TelegramClient telegram;
    private final SqsClient sqs;
    private final GuardianRepo guardianRepo;
    private final InviteRepo inviteRepo;
    private final AuditRepo auditRepo;

    // ── Constructor dùng cho Lambda (production) ──────────────────────────────
    public WebhookHandler() {
        this.queueUrl     = System.getenv("WEBHOOK_QUEUE_URL");
        this.secret       = System.getenv("TELEGRAM_WEBHOOK_SECRET");
        this.policySha256 = Optional.ofNullable(System.getenv("POLICY_SHA256"))
                .filter(s -> !s.isBlank()).orElse(DEFAULT_POLICY_SHA256);

        this.telegram = new TelegramClient(System.getenv("TELEGRAM_BOT_TOKEN"));
        this.sqs      = SqsClient.create();

        String tableName = Optional.ofNullable(System.getenv("DYNAMODB_TABLE_NAME"))
                .filter(s -> !s.isBlank()).orElse("askfirst-dev");

        DynamoDbClient ddb = DynamoDbClient.create();
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(ddb).build();

        this.guardianRepo = new GuardianRepo(enhanced, tableName);
        this.inviteRepo   = new InviteRepo(enhanced, tableName);
        this.auditRepo    = new AuditRepo(enhanced, tableName);
    }

    // ── Constructor dùng cho Unit Test (inject fake/in-memory repos) ──────────
    public WebhookHandler(TelegramClient telegram,
                          SqsClient sqs,
                          String queueUrl,
                          String secret,
                          GuardianRepo guardianRepo,
                          InviteRepo inviteRepo,
                          AuditRepo auditRepo,
                          String policySha256) {
        this.telegram     = telegram;
        this.sqs          = sqs;
        this.queueUrl     = queueUrl;
        this.secret       = secret;
        this.guardianRepo = guardianRepo;
        this.inviteRepo   = inviteRepo;
        this.auditRepo    = auditRepo;
        this.policySha256 = (policySha256 != null && !policySha256.isBlank())
                ? policySha256 : DEFAULT_POLICY_SHA256;
    }

    // ── Entry point ───────────────────────────────────────────────────────────
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        // 1. Xác thực secret fail-closed
        if (!isFromTelegram(input)) {
            return response(401, "UNAUTHORIZED");
        }

        // 2. Parse update
        final JsonNode root;
        try {
            root = MAPPER.readTree(input.getBody());
        } catch (Exception e) {
            log(context, "Lỗi parse body: " + e.getMessage());
            return response(400, "INVALID_BODY");
        }

        // 2a. Callback query (đường nhanh)
        JsonNode callbackNode = root.path("callback_query");
        if (!callbackNode.isMissingNode()) {
            return handleCallback(callbackNode, context);
        }

        // 2b. Message / command
        TelegramAdapter adapter = new TelegramAdapter();
        Optional<InboundMessage> inboundOpt = adapter.parseUpdate(root);
        if (inboundOpt.isEmpty()) {
            return response(200, "IGNORED");
        }

        InboundMessage inbound = inboundOpt.get();
        long chatId   = Long.parseLong(inbound.chatId());
        String userId = "tg:" + chatId;
        String text   = inbound.text() != null ? inbound.text().trim() : "";

        // 3. Đường nhanh: lệnh /nguoithan và /ketnoi
        if (TelegramAdapter.isCommand(inbound)) {
            return handleCommand(chatId, userId, text, context);
        }

        // 4. Đường chậm: đẩy vào SQS
        try {
            String payload = MAPPER.writeValueAsString(inbound);
            if (telegram != null) {
                try {
                    telegram.sendText(chatId, "⏳ Đang kiểm tra, vui lòng chờ...");
                } catch (Exception ignored) { /* không block */ }
            }
            if (sqs != null && queueUrl != null) {
                sqs.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl).messageBody(payload).build());
            }
        } catch (Exception e) {
            log(context, "Lỗi enqueue SQS: " + e.getMessage());
            return response(500, "SQS_FAILED");
        }

        return response(200, "QUEUED");
    }

    // ── Xử lý lệnh bot ───────────────────────────────────────────────────────
    private APIGatewayProxyResponseEvent handleCommand(long chatId, String userId, String text, Context context) {
        String cmd = text.toLowerCase();

        // BE-28: /nguoithan — hiện danh sách & nút tạo mã mời
        if (cmd.startsWith("/nguoithan")) {
            return handleGuardianCommand(chatId, userId, context);
        }

        // BE-28: /ketnoi <code> — người thân nhập mã mời
        if (cmd.startsWith("/ketnoi")) {
            return handleKetNoiCommand(chatId, userId, text, context);
        }

        return response(200, "COMMAND_IGNORED");
    }

    // ── /nguoithan: danh sách người thân + nút [Tạo mã kết nối] ─────────────
    private APIGatewayProxyResponseEvent handleGuardianCommand(long chatId, String userId, Context context) {
        try {
            List<GuardianRecord> guardians = guardianRepo != null
                    ? guardianRepo.listGuardians(userId) : List.of();

            if (guardians.isEmpty()) {
                // Chưa có người thân nào
                String msg = "👨‍👩‍👦 <b>Danh sách người thân</b>\n\n"
                        + "Bác chưa kết nối với người thân nào.\n\n"
                        + "Bấm nút bên dưới để tạo mã kết nối và gửi cho con/cháu:";
                Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                        List.of(new TelegramClient.InlineButton("➕ Tạo mã kết nối người thân", "guardian:create_invite"))
                ));
                if (telegram != null) telegram.sendText(chatId, msg, keyboard);
            } else {
                // Hiện danh sách
                StringBuilder sb = new StringBuilder("👨‍👩‍👦 <b>Danh sách người thân</b>\n\n");
                List<List<TelegramClient.InlineButton>> rows = new java.util.ArrayList<>();

                for (GuardianRecord g : guardians) {
                    String status = switch (g.getStatus() != null ? g.getStatus() : "active") {
                        case "paused"      -> "⏸ Tạm dừng";
                        case "revoked"     -> "❌ Đã huỷ";
                        case "unreachable" -> "⚠️ Không liên lạc được";
                        default            -> "✅ Đang hoạt động";
                    };
                    String level = "Mức " + (g.getShareLevel() != null ? g.getShareLevel() : 1);
                    String name  = g.getName() != null ? g.getName() : g.getGuardianUid();

                    sb.append("• <b>").append(name).append("</b> — ").append(status)
                      .append(" (").append(level).append(")\n");

                    String gid = g.getGuardianUid();
                    rows.add(List.of(
                            new TelegramClient.InlineButton("🔄 Đổi mức — " + name, "guardian:level:" + gid),
                            new TelegramClient.InlineButton("🗑 Gỡ",                 "guardian:remove:" + gid)
                    ));
                }

                rows.add(List.of(new TelegramClient.InlineButton("➕ Tạo mã kết nối mới", "guardian:create_invite")));
                Map<String, Object> keyboard = TelegramClient.inlineKeyboard(rows);
                if (telegram != null) telegram.sendText(chatId, sb.toString(), keyboard);
            }
        } catch (Exception e) {
            log(context, "Lỗi /nguoithan: " + e.getMessage());
        }
        return response(200, "COMMAND_HANDLED");
    }

    // ── /ketnoi <code>: người thân dùng mã mời ───────────────────────────────
    private APIGatewayProxyResponseEvent handleKetNoiCommand(long chatId, String userId, String text, Context context) {
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            if (telegram != null) {
                telegram.sendText(chatId,
                        "Vui lòng nhập mã kết nối sau lệnh. Ví dụ:\n<code>/ketnoi 123456</code>");
            }
            return response(200, "CONNECT_NO_CODE");
        }

        String code = parts[1].trim();
        if (inviteRepo == null) return response(200, "INVITE_REPO_UNAVAILABLE");

        Optional<InviteRecord> opt = inviteRepo.getInvite(code);
        if (opt.isEmpty()) {
            if (telegram != null) telegram.sendText(chatId,
                    "❌ Mã kết nối <b>" + code + "</b> không hợp lệ. Vui lòng kiểm tra lại mã được gửi.");
            return response(200, "INVALID_INVITE");
        }

        InviteRecord invite = opt.get();

        if ("used".equals(invite.getStatus())) {
            if (telegram != null) telegram.sendText(chatId,
                    "❌ Mã kết nối này đã được sử dụng rồi. Vui lòng nhờ người thân tạo mã mới.");
            return response(200, "INVITE_ALREADY_USED");
        }

        if (invite.getExpiresAt() != null && invite.getExpiresAt() < Instant.now().getEpochSecond()) {
            if (telegram != null) telegram.sendText(chatId,
                    "⏰ Mã kết nối đã hết hạn (mã chỉ có hiệu lực 24 giờ). Vui lòng nhờ người thân tạo mã mới.");
            return response(200, "INVITE_EXPIRED");
        }

        String elderUid = invite.getElderUid();
        if (elderUid.equals(userId)) {
            if (telegram != null) telegram.sendText(chatId,
                    "⚠️ Bác không thể dùng mã kết nối của chính mình.");
            return response(200, "INVITE_SELF");
        }

        boolean consumed = inviteRepo.markUsed(code, userId);
        if (!consumed) {
            if (telegram != null) telegram.sendText(chatId,
                    "❌ Mã kết nối này vừa được dùng bởi người khác. Vui lòng nhờ tạo mã mới.");
            return response(200, "INVITE_RACE");
        }

        // Thông báo cho người thân (bản thân họ)
        if (telegram != null) {
            telegram.sendText(chatId,
                    "✅ Đã gửi yêu cầu kết nối! Vui lòng chờ người thân xác nhận và chọn mức chia sẻ.");
        }

        // Thông báo cho người cao tuổi kèm 3 nút chọn mức chia sẻ
        try {
            long elderChatId = Long.parseLong(elderUid.replace("tg:", ""));
            String msg = "🔔 <b>YÊU CẦU KẾT NỐI TỪ NGƯỜI THÂN</b>\n\n"
                    + "Có người thân vừa nhập mã kết nối của Bác.\n\n"
                    + "Vui lòng chọn <b>mức độ chia sẻ thông tin</b> khi có cảnh báo:\n\n"
                    + "• <b>Mức 1:</b> Chỉ báo có nguy cơ (Nguy hiểm / Nghi ngờ).\n"
                    + "• <b>Mức 2:</b> Kèm tên kịch bản và dấu hiệu chính.\n"
                    + "• <b>Mức 3:</b> Kèm tóm tắt nội dung đã che số.";

            Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                    List.of(
                            new TelegramClient.InlineButton("🔒 Mức 1 (Khuyến nghị)", "guardian:accept:" + userId + ":1"),
                            new TelegramClient.InlineButton("📋 Mức 2",              "guardian:accept:" + userId + ":2"),
                            new TelegramClient.InlineButton("📄 Mức 3",              "guardian:accept:" + userId + ":3")
                    ),
                    List.of(new TelegramClient.InlineButton("❌ Từ chối", "guardian:reject:" + userId))
            ));
            if (telegram != null) telegram.sendText(elderChatId, msg, keyboard);
        } catch (Exception e) {
            log(context, "Lỗi gửi yêu cầu về người cao tuổi: " + e.getMessage());
        }

        if (auditRepo != null) {
            auditRepo.recordEvent(userId, "INVITE_REDEEMED", POLICY_VERSION, policySha256,
                    "Redeemed invite from elder: " + elderUid);
        }

        return response(200, "INVITE_REDEEMED");
    }

    // ── Callback query (guardian:accept, guardian:reject, guardian:remove, guardian:create_invite) ──
    private APIGatewayProxyResponseEvent handleCallback(JsonNode callbackNode, Context context) {
        String data         = callbackNode.path("data").asText("");
        long callbackChatId = callbackNode.path("from").path("id").asLong();
        long messageId      = callbackNode.path("message").path("message_id").asLong();
        String callerId     = "tg:" + callbackChatId;

        // Tạo mã mời mới
        if ("guardian:create_invite".equals(data)) {
            int code = ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
            String inviteCode = String.valueOf(code);

            if (inviteRepo != null) {
                inviteRepo.createInvite(inviteCode, callerId,
                        Instant.now().getEpochSecond() + (24 * 3600));
            }

            String msg = "✅ Mã kết nối mới của Bác là: <code><b>" + inviteCode + "</b></code>\n\n"
                    + "⏳ <i>(Mã có hiệu lực 24 giờ, chỉ dùng được một lần)</i>\n\n"
                    + "Bác hãy gửi mã này cho con/cháu và bảo họ gõ:\n"
                    + "<code>/ketnoi " + inviteCode + "</code>";
            if (telegram != null) telegram.sendText(callbackChatId, msg);
            return response(200, "INVITE_CREATED");
        }

        // Người cao tuổi chấp nhận kết nối + chọn mức chia sẻ
        if (data.startsWith("guardian:accept:")) {
            // Format: guardian:accept:<relativeUid>:<level>
            String[] parts = data.split(":");
            if (parts.length < 4) return response(200, "BAD_CALLBACK");

            String relativeUid = parts[2] + ":" + parts[3]; // "tg:12345"
            int shareLevel;
            try { shareLevel = Integer.parseInt(parts[4]); }
            catch (Exception e) { shareLevel = 1; }

            // Lưu quan hệ 2 chiều
            if (guardianRepo != null) {
                GuardianRecord rec = new GuardianRecord(callerId, relativeUid);
                rec.setShareLevel(shareLevel);
                rec.setStatus("active");
                rec.setElderConsentAt(Instant.now().toString());
                guardianRepo.save(rec);
            }

            if (auditRepo != null) {
                auditRepo.recordEvent(callerId, "GUARDIAN_LINKED", POLICY_VERSION, policySha256,
                        "Linked guardian " + relativeUid + " at level " + shareLevel);
            }

            // Thông báo cho người cao tuổi
            if (telegram != null) {
                telegram.sendText(callbackChatId,
                        "✅ Đã kết nối thành công! Người thân của Bác sẽ nhận cảnh báo khi cần thiết (Mức " + shareLevel + ").");
            }

            // Thông báo cho người thân
            try {
                long relativeChatId = Long.parseLong(relativeUid.replace("tg:", ""));
                if (telegram != null) {
                    telegram.sendText(relativeChatId,
                            "✅ <b>Kết nối thành công!</b>\n\nBạn sẽ nhận được cảnh báo khi người thân gặp tin nhắn nguy hiểm.");
                }
            } catch (Exception e) {
                log(context, "Lỗi thông báo người thân sau kết nối: " + e.getMessage());
            }

            return response(200, "GUARDIAN_LINKED");
        }

        // Người cao tuổi từ chối
        if (data.startsWith("guardian:reject:")) {
            String relativeUid = data.substring("guardian:reject:".length());
            if (telegram != null) {
                telegram.sendText(callbackChatId, "❌ Đã từ chối yêu cầu kết nối.");
            }
            try {
                long relativeChatId = Long.parseLong(relativeUid.replace("tg:", ""));
                if (telegram != null) {
                    telegram.sendText(relativeChatId,
                            "❌ Người thân đã từ chối yêu cầu kết nối lần này.");
                }
            } catch (Exception ignored) { /* không block */ }
            return response(200, "GUARDIAN_REJECTED");
        }

        // Gỡ liên kết
        if (data.startsWith("guardian:remove:")) {
            String guardianUid = data.substring("guardian:remove:".length());
            if (guardianRepo != null) {
                guardianRepo.delete(callerId, guardianUid);
            }
            if (auditRepo != null) {
                auditRepo.recordEvent(callerId, "GUARDIAN_REMOVED", POLICY_VERSION, policySha256,
                        "Removed guardian: " + guardianUid);
            }
            if (telegram != null) {
                telegram.sendText(callbackChatId, "✅ Đã gỡ kết nối người thân thành công.");
            }
            return response(200, "GUARDIAN_REMOVED");
        }

        // Đổi mức chia sẻ (hiện nút chọn lại mức)
        if (data.startsWith("guardian:level:")) {
            String guardianUid = data.substring("guardian:level:".length());
            String msg = "Chọn mức chia sẻ mới cho người thân này:";
            Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(List.of(
                    new TelegramClient.InlineButton("Mức 1", "guardian:setlevel:" + guardianUid + ":1"),
                    new TelegramClient.InlineButton("Mức 2", "guardian:setlevel:" + guardianUid + ":2"),
                    new TelegramClient.InlineButton("Mức 3", "guardian:setlevel:" + guardianUid + ":3")
            )));
            if (telegram != null) telegram.sendText(callbackChatId, msg, keyboard);
            return response(200, "LEVEL_PICKER_SENT");
        }

        // Lưu mức chia sẻ mới
        if (data.startsWith("guardian:setlevel:")) {
            String[] parts = data.split(":");
            if (parts.length < 4) return response(200, "BAD_CALLBACK");
            String guardianUid = parts[2] + ":" + parts[3];
            int newLevel;
            try { newLevel = Integer.parseInt(parts[4]); } catch (Exception e) { newLevel = 1; }
            if (guardianRepo != null) guardianRepo.updateShareLevel(callerId, guardianUid, newLevel);
            if (telegram != null) telegram.sendText(callbackChatId,
                    "✅ Đã cập nhật mức chia sẻ thành Mức " + newLevel + ".");
            return response(200, "SHARE_LEVEL_UPDATED");
        }

        return response(200, "CALLBACK_IGNORED");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private boolean isFromTelegram(APIGatewayProxyRequestEvent input) {
        if (secret == null || secret.isBlank()) return false;
        Map<String, String> headers = input.getHeaders();
        if (headers == null) return false;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if ("X-Telegram-Bot-Api-Secret-Token".equalsIgnoreCase(entry.getKey())
                    && entry.getValue() != null) {
                byte[] expected = secret.getBytes(StandardCharsets.UTF_8);
                byte[] actual   = entry.getValue().getBytes(StandardCharsets.UTF_8);
                return MessageDigest.isEqual(expected, actual);
            }
        }
        return false;
    }

    private APIGatewayProxyResponseEvent response(int status, String body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(status).withBody(body);
    }

    private void log(Context context, String msg) {
        if (context != null) context.getLogger().log("[WebhookHandler] " + msg);
    }
}
