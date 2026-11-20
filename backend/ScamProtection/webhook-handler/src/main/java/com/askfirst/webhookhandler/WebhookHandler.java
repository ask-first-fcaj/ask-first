package com.askfirst.webhookhandler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.TelegramClient.InlineButton;
import com.askfirst.common.Ulid;
import com.askfirst.common.channel.TelegramAdapter;
import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.channel.model.TelegramCallbackQuery;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.job.JobCodec;
import com.askfirst.common.store.AlertRecord;
import com.askfirst.common.store.AlertStore;
import com.askfirst.common.store.AuditRecord;
import com.askfirst.common.store.AuditRepo;
import com.askfirst.common.store.BufferStore;
import com.askfirst.common.store.DonationStore;
import com.askfirst.common.store.DynamoAlertStore;
import com.askfirst.common.store.DynamoStoreService;
import com.askfirst.common.store.GuardianRecord;
import com.askfirst.common.store.GuardianRepo;
import com.askfirst.common.store.InviteRecord;
import com.askfirst.common.store.InviteRepo;
import com.askfirst.common.store.RateLimitRepo;
import com.askfirst.common.store.CheckRecord;
import com.askfirst.common.store.CheckRepo;
import com.askfirst.common.store.CheckStore;
import com.askfirst.common.store.DynamoBufferStore;
import com.askfirst.common.store.DynamoCheckStore;
import com.askfirst.common.store.DynamoSessionStore;
import com.askfirst.common.store.S3DonationStore;
import com.askfirst.common.store.SessionRecord;
import com.askfirst.common.store.SessionStore;
import com.askfirst.common.store.UserRecord;
import com.askfirst.common.store.UserRepo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lambda Webhook Handler cho AskFirst Bot (BE-22, BE-25, BE-26, BE-28, ARCH
 * §4.3).
 *
 * Luồng:
 * 1. Xác thực secret Telegram fail-closed (§8.2).
 * 2. Dedup theo update_id (chống duplicate khi Telegram retry).
 * 3. Fast path: Lệnh (/start, /caidat, /huongdan, /bilua, /nguoithan, /ketnoi)
 * và Callback (consent:*, xungho:*, guardian:*).
 * 4. Slow path: Kiểm tra consent, rate limit, gửi "⏳" rồi đẩy vào SQS.
 */
public class WebhookHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    public static final String POLICY_VERSION = "1.0";

    /**
     * CHỈ dùng cho test / LocalBotRunner. Đây là hash giả, KHÔNG được dùng làm
     * fallback khi chạy thật:
     * constructor mặc định (Lambda) bắt buộc biến môi trường POLICY_SHA256.
     */
    public static final String DEFAULT_POLICY_SHA256 = "d1a8f9b2c3e4567890abcdef1234567890abcdef1234567890abcdef12345678";

    /**
     * Khóa dedup update_id sống 24 giờ (Telegram chỉ retry update trong thời gian
     * ngắn).
     */
    private static final long DEDUP_TTL_SECONDS = 24 * 3600L;

    /** Tin trong BUFFER# chỉ tồn tại tạm thời (plan: tối đa 10 phút), worker xóa ngay khi xử lý xong. */
    private static final long BUFFER_TTL_SECONDS = 10 * 60L;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final String queueUrl;
    private final String secret;
    private final String policySha256;
    private final TelegramClient telegram;
    private final SqsClient sqs;
    private final TelegramAdapter adapter;

    private final UserRepo userRepo;
    private final AuditRepo auditRepo;
    private final RateLimitRepo rateLimitRepo;
    private final GuardianRepo guardianRepo;
    private final InviteRepo inviteRepo;
    private final CheckStore checkStore;
    private final SessionStore sessionStore;
    private final BufferStore bufferStore;
    private final DonationStore donationStore;

    /** Tra cảnh báo người thân để ghi nhận nút "Tôi đã liên hệ". Null = bỏ qua callback ack. */
    private final AlertStore alertStore;

    /**
     * Chống xử lý trùng cùng một update_id (Telegram retry). Null = không dedup
     * (test/local).
     */
    private final DynamoStoreService dedup;

    /**
     * Username của bot (ví dụ: "AskFirstBot"), dùng để tạo deep link mời người
     * thân.
     * Đọc từ biến môi trường TELEGRAM_BOT_USERNAME (hoặc BOT_USERNAME). Nếu không có, không hiển thị nút share
     * (graceful degradation).
     */
    private final String botUsername;

    /**
     * true chỉ khi tạo qua {@link #forLocalDevelopment(TelegramClient)} (long
     * polling trên máy dev):
     * bỏ kiểm tra secret và cho phép chạy không có SQS. Lambda thật luôn là false.
     */
    private final boolean localDevelopment;

    /**
     * Constructor cho Lambda. Thiếu biến môi trường nào là lỗi ngay lúc khởi tạo
     * (thấy rõ trong CloudWatch)
     * thay vì chạy tiếp rồi mất job / chấp nhận request giả mạo một cách im lặng.
     */
    public WebhookHandler() {
        this.queueUrl = requireEnv("WEBHOOK_QUEUE_URL");
        this.secret = secretFromSsmOrEnv("TELEGRAM_WEBHOOK_SECRET");
        this.policySha256 = requireEnv("POLICY_SHA256");
        this.telegram = new TelegramClient(secretFromSsmOrEnv("TELEGRAM_BOT_TOKEN"));
        String tableName = requireEnv("DYNAMODB_TABLE_NAME");

        this.sqs = SqsClient.create();
        this.adapter = new TelegramAdapter();
        this.localDevelopment = false;
        // TELEGRAM_BOT_USERNAME không bắt buộc: nếu thiếu thì không tạo deep link (graceful
        // degradation), hỗ trợ cả BOT_USERNAME cũ để tương thích ngược
        String envBot = System.getenv("TELEGRAM_BOT_USERNAME");
        if (envBot == null || envBot.isBlank()) {
            envBot = System.getenv("BOT_USERNAME");
        }
        this.botUsername = (envBot != null && !envBot.isBlank()) ? envBot.trim() : null;

        DynamoDbClient ddb = DynamoDbClient.create();
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(ddb).build();
        this.userRepo = new UserRepo(enhanced, tableName);
        this.auditRepo = new AuditRepo(enhanced, tableName);
        this.rateLimitRepo = new RateLimitRepo(ddb, tableName);
        this.guardianRepo = new GuardianRepo(enhanced, tableName);
        this.inviteRepo = new InviteRepo(enhanced, tableName);
        this.checkStore = new DynamoCheckStore(ddb, tableName);
        this.sessionStore = new DynamoSessionStore(ddb, tableName);
        this.bufferStore = new DynamoBufferStore(ddb, tableName);

        String bucketName = System.getenv("DATA_BUCKET_NAME");
        if (bucketName == null || bucketName.isBlank()) {
            bucketName = System.getenv("DONATION_BUCKET_NAME");
        }
        if (bucketName == null || bucketName.isBlank()) {
            bucketName = System.getenv("S3_BUCKET_NAME");
        }

        DonationStore dStore = null;
        if (bucketName != null && !bucketName.isBlank()) {
            try {
                dStore = new S3DonationStore(S3Client.create(), bucketName.trim());
            } catch (Exception ignored) {
                dStore = null;
            }
        }
        this.donationStore = dStore;
        this.alertStore = new DynamoAlertStore(ddb, tableName);
        this.dedup = DynamoStoreService.create(ddb, tableName);
    }

    public WebhookHandler(TelegramClient telegram, SqsClient sqs, String queueUrl, String secret) {
        this(telegram, sqs, queueUrl, secret, null, null, null, null, null, DEFAULT_POLICY_SHA256, null, false);
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
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, rateLimitRepo, null, null, policySha256, null,
                false);
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
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, rateLimitRepo, null, null, policySha256, dedup,
                false);
    }

    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            GuardianRepo guardianRepo,
            InviteRepo inviteRepo,
            AuditRepo auditRepo,
            String policySha256) {
        this(telegram, sqs, queueUrl, secret, null, auditRepo, null, guardianRepo, inviteRepo, policySha256, null,
                false, null);
    }

    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            GuardianRepo guardianRepo,
            InviteRepo inviteRepo,
            AuditRepo auditRepo,
            String policySha256,
            String botUsername) {
        this(telegram, sqs, queueUrl, secret, null, auditRepo, null, guardianRepo, inviteRepo, policySha256, null,
                false, botUsername);
    }

    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            RateLimitRepo rateLimitRepo,
            GuardianRepo guardianRepo,
            InviteRepo inviteRepo,
            String policySha256,
            DynamoStoreService dedup,
            boolean localDevelopment) {
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, rateLimitRepo,
                guardianRepo, inviteRepo, policySha256, dedup, localDevelopment, null);
    }

    /**
     * Constructor đầy đủ (13 tham số). Tất cả các constructor khác delegate vào
     * đây.
     *
     * @param botUsername username của bot (ví dụ "AskFirstBot"), dùng để tạo deep
     *                    link.
     *                    Null = không hiển thị nút share (graceful degradation).
     */
    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            RateLimitRepo rateLimitRepo,
            GuardianRepo guardianRepo,
            InviteRepo inviteRepo,
            String policySha256,
            DynamoStoreService dedup,
            boolean localDevelopment,
            String botUsername) {
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, rateLimitRepo,
                guardianRepo, inviteRepo, policySha256, dedup, localDevelopment, botUsername,
                null, null, null, null);
    }

    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            RateLimitRepo rateLimitRepo,
            GuardianRepo guardianRepo,
            InviteRepo inviteRepo,
            SessionStore sessionStore,
            BufferStore bufferStore,
            DonationStore donationStore,
            String policySha256) {
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, rateLimitRepo,
                guardianRepo, inviteRepo, policySha256, null, false, null,
                null, sessionStore, bufferStore, donationStore);
    }

    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            RateLimitRepo rateLimitRepo,
            GuardianRepo guardianRepo,
            InviteRepo inviteRepo,
            SessionStore sessionStore,
            BufferStore bufferStore,
            DonationStore donationStore,
            String policySha256,
            DynamoStoreService dedup,
            boolean localDevelopment,
            String botUsername) {
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, rateLimitRepo,
                guardianRepo, inviteRepo, policySha256, dedup, localDevelopment, botUsername,
                null, sessionStore, bufferStore, donationStore);
    }

    /**
     * Constructor 16 tham số (main BE-30).
     */
    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            RateLimitRepo rateLimitRepo,
            GuardianRepo guardianRepo,
            InviteRepo inviteRepo,
            String policySha256,
            DynamoStoreService dedup,
            boolean localDevelopment,
            String botUsername,
            CheckStore checkStore,
            SessionStore sessionStore,
            DonationStore donationStore) {
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, rateLimitRepo,
                guardianRepo, inviteRepo, policySha256, dedup, localDevelopment, botUsername,
                checkStore, sessionStore, null, donationStore);
    }

    /**
     * Constructor đầy đủ 17 tham số. Tất cả các constructor khác delegate vào đây.
     *
     * @param botUsername username của bot (ví dụ "AskFirstBot"), dùng để tạo deep link.
     */
    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            RateLimitRepo rateLimitRepo,
            GuardianRepo guardianRepo,
            InviteRepo inviteRepo,
            String policySha256,
            DynamoStoreService dedup,
            boolean localDevelopment,
            String botUsername,
            CheckStore checkStore,
            SessionStore sessionStore,
            BufferStore bufferStore,
            DonationStore donationStore) {
        this.telegram = telegram;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.secret = secret;
        this.userRepo = userRepo;
        this.auditRepo = auditRepo;
        this.rateLimitRepo = rateLimitRepo;
        this.guardianRepo = guardianRepo;
        this.inviteRepo = inviteRepo;
        this.policySha256 = (policySha256 != null && !policySha256.isBlank()) ? policySha256 : DEFAULT_POLICY_SHA256;
        this.adapter = new TelegramAdapter();
        this.dedup = dedup;
        this.localDevelopment = localDevelopment;
        this.botUsername = (botUsername != null && !botUsername.isBlank()) ? botUsername : null;
        this.checkStore = checkStore;
        this.sessionStore = sessionStore;
        this.bufferStore = bufferStore;
        this.donationStore = donationStore;
        this.alertStore = null; // chỉ khởi tạo trong constructor Lambda (DynamoAlertStore)
    }

    public WebhookHandler(
            TelegramClient telegram,
            SqsClient sqs,
            String queueUrl,
            String secret,
            UserRepo userRepo,
            AuditRepo auditRepo,
            CheckStore checkStore,
            SessionStore sessionStore,
            DonationStore donationStore,
            String policySha256) {
        this(telegram, sqs, queueUrl, secret, userRepo, auditRepo, null,
                null, null, policySha256, null, false, null,
                checkStore, sessionStore, null, donationStore);
    }

    public CheckStore getCheckStore() {
        return checkStore;
    }

    public SessionStore getSessionStore() {
        return sessionStore;
    }

    public BufferStore getBufferStore() {
        return bufferStore;
    }

    public DonationStore getDonationStore() {
        return donationStore;
    }

    /**
     * Handler cho LocalBotRunner (long polling, không có webhook nên không có
     * header secret, không có SQS).
     * Đây là đường DUY NHẤT được bỏ qua kiểm tra secret.
     * Tự động khởi tạo kho lưu trữ in-memory để kiểm thử toàn bộ luồng (/start,
     * /caidat, /nguoithan, /ketnoi) ngay trên máy dev mà không cần AWS DynamoDB.
     */

    public static WebhookHandler forLocalDevelopment(TelegramClient telegram) {
        String botUser = System.getenv("TELEGRAM_BOT_USERNAME");

        if (botUser == null || botUser.isBlank()) {
            botUser = System.getenv("BOT_USERNAME");
        }

        if (botUser == null || botUser.isBlank()) {
            botUser = readEnvFileBotUsername();
        }

        if ((botUser == null || botUser.isBlank()) && telegram != null) {
            botUser = telegram.getMeUsername();
        }

        return new WebhookHandler(
                telegram,
                null,                       // sqs
                null,                       // queueUrl
                null,                       // secret
                createInMemoryUserRepo(),
                createInMemoryAuditRepo(),
                null,                       // rateLimitRepo
                createInMemoryGuardianRepo(),
                createInMemoryInviteRepo(),
                DEFAULT_POLICY_SHA256,
                null,                       // dedup
                true,                       // localDevelopment
                botUser,
                createInMemoryCheckRepo(), // checkStore
                createInMemorySessionStore(),
                null,                       // bufferStore
                createInMemoryDonationStore()
        );
    }


    private static String readEnvFileBotUsername() {
        File[] candidates = new File[]{
                new File(".env"),
                new File("backend/ScamProtection/.env"),
                new File("../.env"),
                new File("../../.env")
        };
        for (File f : candidates) {
            if (f.exists() && f.isFile()) {
                try (BufferedReader reader = new BufferedReader(
                        new FileReader(f, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("TELEGRAM_BOT_USERNAME=") || line.startsWith("TELEGRAM_BOT_USERNAME =")
                                || line.startsWith("BOT_USERNAME=") || line.startsWith("BOT_USERNAME =")) {
                            String[] parts = line.split("=", 2);
                            return parts[1].trim().replaceAll("^[\"']|[\"']$", "");
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static UserRepo createInMemoryUserRepo() {
        return new UserRepo() {
            private final Map<String, UserRecord> users = new ConcurrentHashMap<>();

            @Override
            public Optional<UserRecord> getUser(String userId) {
                return Optional.ofNullable(users.get(userId));
            }

            @Override
            public boolean hasValidConsent(String userId, String expectedSha256) {
                UserRecord r = users.get(userId);
                return r != null && expectedSha256 != null && expectedSha256.equals(r.getPolicySha256());
            }

            @Override
            public void updateConsent(String userId, String policySha256, String version, Map<String, Boolean> purposes) {
                UserRecord r = users.computeIfAbsent(userId, k -> {
                    UserRecord rec = new UserRecord(userId, "");
                    rec.setStatus("consented");
                    return rec;
                });
                r.setConsentVersion(version);
                r.setPolicySha256(policySha256);
                if (purposes != null) {
                    Map<String, Boolean> merged = new HashMap<>(r.getPurposes());
                    merged.putAll(purposes);
                    r.setPurposes(merged);
                }
                r.setConsentedAt(Instant.now().toString());
            }

            @Override
            public void setPurpose(String userId, String purpose, boolean value) {
                UserRecord r = users.get(userId);
                if (r == null) {
                    return;
                }
                Map<String, Boolean> merged = new HashMap<>(r.getPurposes());
                merged.put(purpose, value);
                r.setPurposes(merged);
            }

            @Override
            public void updateAddressForm(String userId, String addressForm) {
                UserRecord r = users.computeIfAbsent(userId, k -> new UserRecord(userId, ""));
                r.setAddressForm(addressForm);
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
            public void deleteUser(String userId) {
                users.remove(userId);
            }
        };
    }

    private static GuardianRepo createInMemoryGuardianRepo() {
        return new GuardianRepo() {
            private final Map<String, GuardianRecord> store = new ConcurrentHashMap<>();

            @Override
            public void save(GuardianRecord rec) {
                store.put(rec.getPk() + "|" + rec.getSk(), rec);
            }

            @Override
            public Optional<GuardianRecord> getGuardian(String elderUid, String guardianUid) {
                return store.values().stream()
                        .filter(r -> (elderUid.equals(r.getElderUid()) || elderUid.equals(r.getPk()) || ("USER#" + elderUid).equals(r.getPk()))
                                && (guardianUid.equals(r.getGuardianUid()) || guardianUid.equals(r.getSk()) || ("GUARDIAN#" + guardianUid).equals(r.getSk())))
                        .findFirst();
            }

            @Override
            public List<GuardianRecord> listGuardians(String elderUid) {
                return store.values().stream()
                        .filter(r -> elderUid.equals(r.getElderUid()) || ("USER#" + elderUid).equals(r.getPk()) || elderUid.equals(r.getPk()))
                        .toList();
            }

            @Override
            public void delete(String elderUid, String guardianUid) {
                store.entrySet().removeIf(e ->
                        (elderUid.equals(e.getValue().getElderUid()) || ("USER#" + elderUid).equals(e.getValue().getPk()))
                                && (guardianUid.equals(e.getValue().getGuardianUid()) || ("GUARDIAN#" + guardianUid).equals(e.getValue().getSk())));
            }

            @Override
            public void updateShareLevel(String elderUid, String guardianUid, int shareLevel) {
                getGuardian(elderUid, guardianUid).ifPresent(r -> r.setShareLevel(shareLevel));
            }

            @Override
            public List<GuardianRecord> listWardsOf(String guardianUid) {
                return store.values().stream()
                        .filter(r -> guardianUid.equals(r.getGuardianUid()) || ("GUARDIAN#" + guardianUid).equals(r.getSk()))
                        .toList();
            }

            @Override
            public void deleteAllGuardians(String userId) {
                store.entrySet().removeIf(e ->
                        userId.equals(e.getValue().getElderUid()) || ("USER#" + userId).equals(e.getValue().getPk())
                                || userId.equals(e.getValue().getGuardianUid()) || ("GUARDIAN#" + userId).equals(e.getValue().getSk()));
            }
        };
    }

    private static InviteRepo createInMemoryInviteRepo() {
        return new InviteRepo() {
            private final Map<String, InviteRecord> store = new ConcurrentHashMap<>();

            @Override
            public void createInvite(String code, String elderUid, long ttl) {
                InviteRecord r = new InviteRecord(code, elderUid, ttl);
                r.setStatus("pending");
                store.put("INVITE#" + code, r);
            }

            @Override
            public Optional<InviteRecord> getInvite(String code) {
                String key = code.startsWith("INVITE#") ? code : "INVITE#" + code;
                return Optional.ofNullable(store.get(key));
            }

            @Override
            public boolean markUsed(String code, String usedByUid) {
                String key = code.startsWith("INVITE#") ? code : "INVITE#" + code;
                InviteRecord r = store.get(key);
                if (r == null || !"pending".equals(r.getStatus())) return false;
                r.setStatus("used");
                r.setUsedByUid(usedByUid);
                return true;
            }
        };
    }

    private static AuditRepo createInMemoryAuditRepo() {
        return new AuditRepo() {
            private final List<AuditRecord> records = new CopyOnWriteArrayList<>();

            @Override
            public void recordEvent(String userId, String event, String policyVersion, String policySha256, String details) {
                recordEventAnonymized(userId, event, policyVersion, policySha256, details, userId);
            }

            @Override
            public void recordEventAnonymized(String pkUserId, String event, String policyVersion, String policySha256, String details, String anonymizedUserId) {
                AuditRecord r = new AuditRecord(pkUserId, Instant.now().toString(), event);
                r.setUserId(anonymizedUserId);
                r.setPolicyVersion(policyVersion);
                r.setPolicySha256(policySha256);
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
        };
    }

    private static CheckRepo createInMemoryCheckRepo() {
        return new CheckRepo() {
            private final Map<String, CheckRecord> store = new ConcurrentHashMap<>();

            @Override
            public boolean exists(String userId, String checkId) {
                return store.containsKey(userId + "#" + checkId);
            }

            @Override
            public void put(CheckRecord record) {
                store.putIfAbsent(record.userId() + "#" + record.checkId(), record);
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
        };
    }

    private static SessionStore createInMemorySessionStore() {
        return new SessionStore() {
            private final Map<String, SessionRecord> store = new ConcurrentHashMap<>();

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
        };
    }

    private static DonationStore createInMemoryDonationStore() {
        return new DonationStore() {
            private final Map<String, String> store = new ConcurrentHashMap<>();

            @Override
            public String saveDonatedSample(
                    String userId,
                    String checkId,
                    String maskedText) {

                String sampleId = (checkId != null && !checkId.isBlank())
                        ? checkId
                        : java.util.UUID.randomUUID().toString();

                String key = "donated/" + S3DonationStore.hashUid(userId)
                        + "/" + sampleId + ".txt";

                store.put(key, maskedText != null ? maskedText : "");
                return key;
            }

            @Override
            public void deleteDonatedSamples(String userId) {
                String prefix = "donated/" + S3DonationStore.hashUid(userId) + "/";
                store.keySet().removeIf(key -> key.startsWith(prefix));
            }

            @Override
            public boolean hasDonatedSamples(String userId) {
                String prefix = "donated/" + S3DonationStore.hashUid(userId) + "/";
                return store.keySet().stream().anyMatch(key -> key.startsWith(prefix));
            }
        };
    }

    /**
     * Lấy secret. Nếu có biến NAME_PARAM (tên tham số SSM) thì đọc SecureString từ
     * SSM lúc khởi tạo Lambda,
     * nên giá trị không nằm trong cấu hình Lambda. Nếu không có (LocalStack, test)
     * thì đọc biến NAME.
     */
    private static String secretFromSsmOrEnv(String name) {
        String paramName = System.getenv(name + "_PARAM");
        if (paramName == null || paramName.isBlank()) {
            return requireEnv(name);
        }
        try (SsmClient ssm = SsmClient.create()) {
            String value = ssm.getParameter(GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build()).parameter().value();
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Tham số SSM rỗng: " + paramName);
            }
            return value;
        }
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

        // Dedup theo update_id: Telegram retry khi không nhận 200 -> không được ghi
        // audit / đếm rate limit / enqueue lần 2
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
            // Đã có tác dụng phụ một phần -> giữ khóa dedup, trả 200 để Telegram không
            // retry gây xử lý đôi
            log(context, "Lỗi không mong đợi khi xử lý update " + updateId + ": " + e);
            if (telegram != null && root != null) {
                try {
                    long errChatId = root.path("message").path("chat").path("id").asLong();
                    long errFromId = root.path("message").path("from").path("id").asLong();
                    if (errChatId == 0) {
                        errChatId = root.path("callback_query").path("message").path("chat").path("id").asLong();
                        errFromId = root.path("callback_query").path("from").path("id").asLong();
                    }
                    if (errChatId != 0) {
                        String sUserId = errFromId != 0 ? "tg:" + errFromId : null;
                        String addressForm = sUserId != null ? getAddressForm(sUserId, context) : UserRecord.DEFAULT_ADDRESS_FORM;
                        telegram.sendText(errChatId, "Dạ hệ thống đang gặp sự cố gián đoạn tạm thời. "
                                + addressForm + " vui lòng thử lại hoặc gửi lại giúp cháu sau ít phút nhé!");
                    }
                } catch (Exception sendEx) {
                    log(context, "Không gửi được thông báo lỗi cho người dùng khi gặp lỗi không mong đợi: " + sendEx);
                }
            }
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
            // BE-35: Nếu là sticker thì phản hồi lời chào + hướng dẫn gửi tin nhắn
            if (root.has("message") && root.path("message").has("sticker")) {
                JsonNode msg = root.path("message");
                long sChatId = msg.path("chat").path("id").asLong();
                String sUserId = "tg:" + msg.path("from").path("id").asText();
                String sAddressForm = getAddressForm(sUserId, context);
                if (telegram != null) {
                    telegram.sendText(sChatId, "Dạ cháu chào " + sAddressForm + "! Cháu là trợ lý phòng chống lừa đảo AskFirst.\n\n"
                            + sAddressForm + " hãy gửi tin nhắn chữ, hình ảnh chụp màn hình hoặc đường link nghi ngờ để cháu kiểm tra giúp nhé!");
                }
                return response(200, "STICKER_REPLIED");
            }
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

        // 3. Fast path: Các lệnh điều khiển (/start, /caidat, /huongdan, /bilua,
        // /nguoithan, /ketnoi)
        if (TelegramAdapter.isCommand(inbound)) {
            String firstName = root.path("message").path("from").path("first_name").asText("").trim();
            String username = root.path("message").path("from").path("username").asText("").trim();
            return handleCommand(chatId, userId, inbound.text().trim(), firstName, username, context);
        }

        // 4. Slow path: Kiểm tra tin nhắn lừa đảo
        return handleContentInspection(root, inbound, chatId, userId, context);
    }

    /**
     * Callback luôn được trả lời: lỗi DB không được để nút "xoay" mãi hay báo "đã
     * lưu" khi chưa lưu.
     */
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

        if (data != null && data.startsWith("guardian:")) {
            return handleGuardianCallback(cb, chatId, userId, data, context);
        }

        // BE-30: Xử lý nút [Bot báo sai] và luồng đóng góp mẫu (Donation Consent)
        if ("fb:already_reported".equals(data)) {
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Ý kiến báo sai đã được ghi nhận rồi ạ!");
            }
            return response(200, "FEEDBACK_ALREADY_REPORTED");
        }

        if (data != null && (data.equals("fb:wrong") || data.startsWith("fb:wrong:"))) {
            return handleFeedbackWrong(cb, chatId, userId, data, context);
        }

        if (data != null && (data.equals("donate:agree") || data.startsWith("donate:agree:") || data.startsWith("dn:agree:"))) {
            return handleDonateAgree(cb, chatId, userId, data, context);
        }

        if (data != null && (data.equals("donate:decline") || data.startsWith("donate:decline:") || data.startsWith("dn:decline:"))) {
            return handleDonateDecline(cb, chatId, userId, data, context);
        }

        if (data != null && data.startsWith("dn:")) {
            return handleFeedbackWrong(cb, chatId, userId, data, context);
        }

        if ("consent:agree".equals(data)) {
            if (userRepo != null) {
                // Chỉ bật "analyze": các mục đích khác (guardian/voice/donate) giữ nguyên lựa
                // chọn đã có
                Map<String, Boolean> purposes = new HashMap<>();
                purposes.put("analyze", true);
                userRepo.updateConsent(userId, policySha256, POLICY_VERSION, purposes);
            }
            if (auditRepo != null) {
                auditRepo.recordEvent(userId, "CONSENT_GIVEN", POLICY_VERSION, policySha256,
                        "Agreed via inline button");
            }
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã lưu đồng ý!");
                telegram.sendText(chatId,
                        "✅ <b>Cảm ơn Bác đã đồng ý điều khoản!</b>\n\n" +
                                "Từ bây giờ Bác có thể gửi tin nhắn, đường link hoặc ảnh chụp màn hình bất cứ lúc nào để cháu kiểm tra nhé.\n\n"
                                +
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
                auditRepo.recordEvent(userId, "CONSENT_DECLINED", POLICY_VERSION, policySha256,
                        "Declined via inline button");
            }
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã ghi nhận");
                telegram.sendText(chatId,
                        "Dạ cháu đã ghi nhận ạ. Theo quy định bảo vệ dữ liệu cá nhân (Nghị định 356), cháu xin phép " +
                                "<b>không thu thập hoặc phân tích</b> bất kỳ nội dung nào khi chưa có sự đồng ý của Bác.\n\n"
                                +
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
                        "Dạ, cháu đã lưu cách xưng hô là <b>" + form
                                + "</b> rồi ạ! Cháu sẽ xưng hô như vậy trong các câu trả lời sau nhé.");
            }
            return response(200, "CALLBACK_HANDLED");
        }

        // BE-31: Callback xác nhận rút đồng ý
        if ("consent:revoke_confirm".equals(data)) {
            return executeConsentRevocation(chatId, userId, cb.queryId(), context);
        } else if ("consent:revoke_cancel".equals(data)) {
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã giữ lại đồng ý");
                telegram.sendText(chatId, "Dạ cháu vẫn tiếp tục bảo vệ và đồng hành cùng Bác nhé!");
            }
            return response(200, "CONSENT_KEPT");
        }

        // BE-31: Callback xác nhận xoá dữ liệu
        if ("data:delete_confirm".equals(data) || "xoadulieu:confirm".equals(data)) {
            return executeDataDeletion(cb.queryId(), chatId, userId, context);
        } else if ("data:delete_cancel".equals(data) || "xoadulieu:cancel".equals(data)) {
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã huỷ xoá dữ liệu");
                telegram.sendText(chatId, "Dạ cháu đã huỷ yêu cầu xoá dữ liệu, mọi thông tin vẫn được giữ nguyên ạ.");
            }
            return response(200, "DATA_DELETION_CANCELLED");
        }

        // Người thân bấm "Tôi đã liên hệ" trên cảnh báo
        if (data != null && data.startsWith("ack:")) {
            return handleGuardianAck(cb, data.substring("ack:".length()));
        }

        return response(200, "UNKNOWN_CALLBACK");
    }

    /** Người thân bấm "Tôi đã liên hệ". Chỉ đúng người thân của cảnh báo đó mới ghi nhận được. */
    private APIGatewayProxyResponseEvent handleGuardianAck(TelegramCallbackQuery cb, String alertId) {
        if (alertStore == null) {
            return response(200, "UNKNOWN_CALLBACK");
        }
        Optional<AlertRecord> alert = alertStore.get(alertId);
        boolean allowed = alert.isPresent()
                && stripUserPrefix(cb.userId()).equals(stripUserPrefix(alert.get().guardianUid()));
        if (!allowed) {
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Cảnh báo này không còn hiệu lực.");
            }
            return response(200, "CALLBACK_HANDLED");
        }
        alertStore.markAcked(alertId, Instant.now());
        if (telegram != null) {
            telegram.answerCallbackQuery(cb.queryId(), "Cảm ơn bạn, đã ghi nhận.");
        }
        return response(200, "CALLBACK_HANDLED");
    }

    private static String stripUserPrefix(String uid) {
        return uid != null && uid.startsWith("USER#") ? uid.substring("USER#".length()) : String.valueOf(uid);
    }

    private APIGatewayProxyResponseEvent handleGuardianCallback(
            TelegramCallbackQuery cb,
            long chatId,
            String userId,
            String data,
            Context context) {

        // Tạo mã mời mới (kèm deep link 1-chạm nếu BOT_USERNAME được cấu hình)
        if ("guardian:create_invite".equals(data)) {
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đang tạo mã kết nối...");
            }
            int code = ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
            String inviteCode = String.valueOf(code);

            if (inviteRepo != null) {
                inviteRepo.createInvite(inviteCode, userId,
                        Instant.now().getEpochSecond() + (24 * 3600));
            }

            // Xác định botUsername hiệu lực (ưu tiên field cấu hình, fallback Telegram / file .env)
            String effectiveBotUsername = this.botUsername;
            if (effectiveBotUsername == null || effectiveBotUsername.isBlank()) {
                if (telegram != null) {
                    effectiveBotUsername = telegram.getMeUsername();
                }
                if ((effectiveBotUsername == null || effectiveBotUsername.isBlank()) && localDevelopment) {
                    effectiveBotUsername = readEnvFileBotUsername();
                }
            }

            // Tạo deep link Telegram nếu biết botUsername
            String deepLink = (effectiveBotUsername != null && !effectiveBotUsername.isBlank())
                    ? "https://t.me/" + effectiveBotUsername + "?start=g_" + inviteCode
                    : null;

            String msg;
            if (deepLink != null) {
                // Có deep link: hướng dẫn bấm nút share bên dưới
                msg = "✅ Mã kết nối của Bác là: <code><b>" + inviteCode + "</b></code>\n\n"
                        + "⏳ <i>(Mã có hiệu lực trong 24 giờ và chỉ dùng được một lần)</i>\n\n"
                        + "👇 <b>Bác bấm nút bên dưới để gửi lời mời cho con/cháu:</b>\n"
                        + "<i>(Con/cháu chỉ cần bấm vào link, bot sẽ tự kết nối — không cần gõ gì cả!)</i>";
            } else {
                // Không có deep link: hướng dẫn thủ công
                msg = "✅ Mã kết nối của Bác là: <code><b>" + inviteCode + "</b></code>\n\n"
                        + "⏳ <i>(Mã có hiệu lực trong 24 giờ và chỉ dùng được một lần)</i>\n\n"
                        + "Bác hãy gửi hoặc chuyển tiếp lời nhắn này cho con/cháu qua Zalo/Tin nhắn nhé:\n"
                        + "━━━━━━━━━━━━━━━━━━━\n"
                        + "<i>\"Con ơi, Bác/Bố/Mẹ đang dùng trợ lý AskFirst trên Telegram để phòng chống tin nhắn lừa đảo. "
                        + "Con mở bot này rồi gõ dòng lệnh bên dưới để khi có tin nguy hiểm bot sẽ báo cho con cùng biết nhé:\"</i>\n\n"
                        + "<code>/ketnoi " + inviteCode + "</code>\n"
                        + "━━━━━━━━━━━━━━━━━━━";
            }

            if (telegram != null) {
                if (deepLink != null) {
                    // Nút share: mở t.me/share để bác forward link cho con/cháu qua bất kỳ app nào
                    String encodedLink = URLEncoder.encode(deepLink, StandardCharsets.UTF_8);
                    String encodedText;
                    try {
                        encodedText = URLEncoder.encode(
                                "Con ơi, bấm vào link này để kết nối bảo vệ cùng Bác qua AskFirst nhé! 💙",
                                StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        encodedText = "Con+oi+bam+vao+link+nay";
                    }
                    String shareUrl = "https://t.me/share/url?url=" + encodedLink + "&text=" + encodedText;

                    List<List<Map<String, String>>> rows = new ArrayList<>();
                    rows.add(List.of(
                            TelegramClient.urlButtonMap(
                                    new TelegramClient.UrlButton("📤 Gửi lời mời cho con/cháu", shareUrl))));
                    telegram.sendText(chatId, msg, TelegramClient.inlineKeyboardRaw(rows));
                } else {
                    telegram.sendText(chatId, msg);
                }
            }
            return response(200, "INVITE_CREATED");
        }

        // Người cao tuổi chấp nhận kết nối + chọn mức chia sẻ
        if (data.startsWith("guardian:accept:")) {
            String[] parts = data.split(":");
            if (parts.length < 4) {
                if (telegram != null) {
                    telegram.answerCallbackQuery(cb.queryId(), "Dữ liệu không hợp lệ");
                }
                return response(200, "BAD_CALLBACK");
            }

            String relativeUid = parts[2] + ":" + parts[3]; // "tg:12345"
            int shareLevel;
            try {
                shareLevel = Integer.parseInt(parts[4]);
            } catch (Exception e) {
                shareLevel = 1;
            }

            if (guardianRepo != null) {
                boolean accepted = guardianRepo.acceptPending(userId, relativeUid, shareLevel, Instant.now().toString());
                if (!accepted) {
                    if (telegram != null) {
                        telegram.answerCallbackQuery(cb.queryId(), "Yêu cầu kết nối này không còn hiệu lực.");
                        telegram.sendText(chatId,
                                "⚠️ Yêu cầu kết nối này không còn ở trạng thái chờ xác nhận.");
                    }
                    return response(200, "GUARDIAN_NOT_PENDING");
                }
            }

            // Chấp nhận liên kết = người cao tuổi đồng ý mục đích "guardian". Worker chỉ báo người thân khi cờ
            // này bật (RepoGuardianDirectory.elder -> Elder.guardianConsent); không set thì không cảnh báo nào được gửi.
            if (userRepo != null) {
                try {
                    userRepo.setPurpose(userId, "guardian", true);
                } catch (RuntimeException e) {
                    log(context, "Không bật được mục đích guardian: " + e.getClass().getSimpleName());
                }
            }

            if (auditRepo != null) {
                auditRepo.recordEvent(userId, "GUARDIAN_LINKED", POLICY_VERSION, policySha256,
                        "Linked guardian " + relativeUid + " at level " + shareLevel);
            }

            Integer msgId = parseMessageId(cb.messageId());
            if (telegram != null && msgId != null) {
                try {
                    telegram.editReplyMarkup(chatId, msgId, null);
                } catch (Exception ignored) {
                }
            }

            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã kết nối thành công (Mức " + shareLevel + ")!");
                telegram.sendText(chatId,
                        "✅ Đã kết nối thành công! Người thân của Bác sẽ nhận cảnh báo khi cần thiết (Mức " + shareLevel
                                + ").");
            }

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

            if (guardianRepo != null) {
                boolean rejected = guardianRepo.rejectPending(userId, relativeUid);
                if (!rejected) {
                    if (telegram != null) {
                        telegram.answerCallbackQuery(cb.queryId(), "Yêu cầu kết nối này không còn hiệu lực.");
                        telegram.sendText(chatId,
                                "⚠️ Yêu cầu kết nối này không còn ở trạng thái chờ xác nhận.");
                    }
                    return response(200, "GUARDIAN_NOT_PENDING");
                }
            }

            Integer msgId = parseMessageId(cb.messageId());
            if (telegram != null && msgId != null) {
                try {
                    telegram.editReplyMarkup(chatId, msgId, null);
                } catch (Exception ignored) {
                }
            }

            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã từ chối kết nối.");
                telegram.sendText(chatId, "❌ Đã từ chối yêu cầu kết nối.");
            }
            try {
                long relativeChatId = Long.parseLong(relativeUid.replace("tg:", ""));
                if (telegram != null) {
                    telegram.sendText(relativeChatId,
                            "❌ Người thân đã từ chối yêu cầu kết nối lần này.");
                }
            } catch (Exception ignored) {
            }
            return response(200, "GUARDIAN_REJECTED");
        }

        // Gỡ liên kết
        if (data.startsWith("guardian:remove:")) {
            String guardianUid = data.substring("guardian:remove:".length());
            if (guardianRepo != null) {
                guardianRepo.delete(userId, guardianUid);
            }
            if (auditRepo != null) {
                auditRepo.recordEvent(userId, "GUARDIAN_REMOVED", POLICY_VERSION, policySha256,
                        "Removed guardian: " + guardianUid);
            }
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã gỡ kết nối.");
                telegram.sendText(chatId, "✅ Đã gỡ kết nối người thân thành công.");
            }
            return response(200, "GUARDIAN_REMOVED");
        }

        // Đổi mức chia sẻ (hiện nút chọn lại mức)
        if (data.startsWith("guardian:level:")) {
            String guardianUid = data.substring("guardian:level:".length());
            String msg = "Chọn mức chia sẻ mới cho người thân này:";
            Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(List.of(
                    new InlineButton("Mức 1", "guardian:setlevel:" + guardianUid + ":1"),
                    new InlineButton("Mức 2", "guardian:setlevel:" + guardianUid + ":2"),
                    new InlineButton("Mức 3", "guardian:setlevel:" + guardianUid + ":3"))));
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Chọn mức chia sẻ");
                telegram.sendText(chatId, msg, keyboard);
            }
            return response(200, "LEVEL_PICKER_SENT");
        }

        // Lưu mức chia sẻ mới
        if (data.startsWith("guardian:setlevel:")) {
            String[] parts = data.split(":");
            if (parts.length < 4) {
                if (telegram != null) {
                    telegram.answerCallbackQuery(cb.queryId(), "Dữ liệu không hợp lệ");
                }
                return response(200, "BAD_CALLBACK");
            }
            String guardianUid = parts[2] + ":" + parts[3];
            int newLevel;
            try {
                newLevel = Integer.parseInt(parts[4]);
            } catch (Exception e) {
                newLevel = 1;
            }
            if (guardianRepo != null) {
                guardianRepo.updateShareLevel(userId, guardianUid, newLevel);
            }
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Đã đổi thành Mức " + newLevel);
                telegram.sendText(chatId,
                        "✅ Đã cập nhật mức chia sẻ thành Mức " + newLevel + ".");
            }
            return response(200, "SHARE_LEVEL_UPDATED");
        }

        if (telegram != null) {
            telegram.answerCallbackQuery(cb.queryId(), null);
        }
        return response(200, "CALLBACK_IGNORED");
    }

    private static Integer parseMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(messageId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private APIGatewayProxyResponseEvent handleFeedbackWrong(
            TelegramCallbackQuery cb,
            long chatId,
            String userId,
            String data,
            Context context) {
        String addressForm = getAddressForm(userId);
        String checkId = null;
        if (data.startsWith("fb:wrong:")) {
            checkId = data.substring("fb:wrong:".length()).trim();
        } else if (data.startsWith("dn:")) {
            checkId = data.substring("dn:".length()).trim();
        }

        if ((checkId == null || checkId.isBlank()) && sessionStore != null) {
            checkId = sessionStore.get(String.valueOf(chatId)).map(SessionRecord::checkId).orElse(null);
        }

        if (checkStore != null) {
            // Không có check_id thì không có gì để ghi: không được cảm ơn như thể đã ghi nhận
            if (checkId == null || checkId.isBlank()) {
                return feedbackCheckNotFound(cb);
            }
            // Chống bấm lặp lại nút [Bot báo sai]: nếu đã ghi nhận rồi thì thông báo và vô hiệu hóa nút
            if (isReportedWrong(userId, checkId)) {
                return feedbackAlreadyReported(cb, chatId, addressForm);
            }
            // Ghi nguyên tử (check phải còn tồn tại và chưa có feedback). Trả false khi thua request bấm đôi song song
            // hoặc check đã hết TTL: phân biệt hai trường hợp bằng cách đọc lại.
            if (!checkStore.tryMarkFeedback(userId, checkId, "wrong")) {
                return isReportedWrong(userId, checkId)
                        ? feedbackAlreadyReported(cb, chatId, addressForm)
                        : feedbackCheckNotFound(cb);
            }
        }

        if (auditRepo != null) {
            auditRepo.recordEvent(userId, "FEEDBACK_REPORTED", POLICY_VERSION, policySha256,
                    "Wrong verdict reported for check " + (checkId != null ? checkId : ""));
        }

        if (telegram != null) {
            telegram.answerCallbackQuery(cb.queryId(), "Cảm ơn ý kiến của " + addressForm + "!");

            // Đổi riêng nút [Bot báo sai] thành [✅ Đã ghi nhận báo sai], giữ nguyên các nút còn lại
            markReportAcknowledged(cb, chatId);

            String prompt = "Cảm ơn " + addressForm + " đã góp ý! " + addressForm
                    + " có đồng ý cho cháu lưu lại nội dung tin nhắn này (cháu đã che hết số điện thoại và tên riêng) để các kỹ sư hoàn thiện bot tốt hơn không ạ?";

            Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(List.of(
                    new InlineButton("Đồng ý đóng góp", "donate:agree:" + (checkId != null ? checkId : "")),
                    new InlineButton("Không, cảm ơn", "donate:decline:" + (checkId != null ? checkId : "")))));

            telegram.sendText(chatId, prompt, keyboard);
        }

        return response(200, "FEEDBACK_WRONG_HANDLED");
    }

    private boolean isReportedWrong(String userId, String checkId) {
        return checkStore instanceof CheckRepo repo
                && repo.get(userId, checkId).map(r -> "wrong".equals(r.feedback())).orElse(false);
    }

    private APIGatewayProxyResponseEvent feedbackAlreadyReported(
            TelegramCallbackQuery cb, long chatId, String addressForm) {
        if (telegram != null) {
            telegram.answerCallbackQuery(cb.queryId(),
                    "Ý kiến báo sai của " + addressForm + " đã được ghi nhận trước đó rồi ạ!");
            markReportAcknowledged(cb, chatId);
        }
        return response(200, "FEEDBACK_ALREADY_REPORTED");
    }

    private APIGatewayProxyResponseEvent feedbackCheckNotFound(TelegramCallbackQuery cb) {
        if (telegram != null) {
            telegram.answerCallbackQuery(cb.queryId(),
                    "Dạ, cháu không còn tìm thấy lượt kiểm tra này để ghi nhận góp ý ạ.");
        }
        return response(200, "FEEDBACK_CHECK_NOT_FOUND");
    }

    private void markReportAcknowledged(TelegramCallbackQuery cb, long chatId) {
        Integer msgId = parseMessageId(cb.messageId());
        if (telegram == null || msgId == null) {
            return;
        }
        try {
            telegram.editReplyMarkup(chatId, msgId, TelegramClient.inlineKeyboard(List.of(
                    List.of(new InlineButton("✅ Đã ghi nhận báo sai", "fb:already_reported")))));
        } catch (Exception ignored) {
        }
    }

    static Map<String, Object> keyboardWithReportAck(List<List<Map<String, String>>> original) {
        Map<String, String> ack = TelegramClient.callbackButtonMap(
                new InlineButton("✅ Đã ghi nhận báo sai", "fb:already_reported"));
        List<List<Map<String, String>>> rows = new ArrayList<>();
        boolean replaced = false;
        if (original != null) {
            for (List<Map<String, String>> row : original) {
                List<Map<String, String>> out = new ArrayList<>();
                for (Map<String, String> button : row) {
                    String d = button.get("callback_data");
                    boolean reportButton = d != null
                            && (d.equals("fb:wrong") || d.startsWith("fb:wrong:") || d.startsWith("dn:"));
                    if (reportButton) {
                        out.add(ack);
                        replaced = true;
                    } else {
                        out.add(button);
                    }
                }
                rows.add(out);
            }
        }
        if (!replaced) {
            // Không biết bàn phím gốc (hoặc không có nút báo sai): giữ cách cũ, chỉ hiện nút xác nhận
            rows = List.of(List.of(ack));
        }
        return TelegramClient.inlineKeyboardRaw(rows);
    }

    private void removeButtons(TelegramCallbackQuery cb, long chatId) {
        Integer msgId = parseMessageId(cb.messageId());
        if (telegram != null && msgId != null) {
            try {
                telegram.editReplyMarkup(chatId, msgId, null);
            } catch (Exception ignored) {
            }
        }
    }


    /**
     * Session chỉ dùng được khi ĐÚNG chủ, ĐÚNG check_id trên nút bấm và CHƯA quá hạn. SESSION# chỉ có một bản ghi
     * cho mỗi chat (bị ghi đè sau mỗi lượt kiểm tra) và TTL của DynamoDB xoá trễ, nên không được tin vào việc
     * "get() có trả về" là đủ.
     */
    private static boolean isSessionUsable(SessionRecord s, String userId, String requestedCheckId) {
        if (s == null || s.userId() == null || !s.userId().equals(userId)) {
            return false;
        }
        if (requestedCheckId != null && !requestedCheckId.isBlank() && !requestedCheckId.equals(s.checkId())) {
            return false;
        }
        return s.ttlEpochSeconds() > Instant.now().getEpochSecond();
    }

    private APIGatewayProxyResponseEvent handleDonateAgree(
            TelegramCallbackQuery cb,
            long chatId,
            String userId,
            String data,
            Context context) {
        String addressForm = getAddressForm(userId);
        String checkId = null;
        if (data.startsWith("donate:agree:")) {
            checkId = data.substring("donate:agree:".length()).trim();
        } else if (data.startsWith("dn:agree:")) {
            checkId = data.substring("dn:agree:".length()).trim();
        }

        String chat = String.valueOf(chatId);
        final String requestedCheckId = checkId;
        SessionRecord session = sessionStore == null ? null
                : sessionStore.get(chat).filter(s -> isSessionUsable(s, userId, requestedCheckId)).orElse(null);

        if (session == null || session.maskedText() == null || session.maskedText().isBlank()) {
            // Hết hạn, đã xử lý, hoặc session hiện tại là của lượt kiểm tra khác: không đụng vào session đó
            removeButtons(cb, chatId);
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Phiên đã hết hạn hoặc đã được ghi nhận");
                telegram.sendText(chatId,
                        "Dạ phiên kiểm tra này đã kết thúc hoặc không tìm thấy nội dung để đóng góp ạ. Cảm ơn "
                                + addressForm + "!");
            }
            return response(200, "DONATION_SESSION_EXPIRED");
        }
        if (checkId == null || checkId.isBlank()) {
            checkId = session.checkId();
        }

        if (donationStore == null) {
            // Chưa cấu hình kho lưu (thiếu DATA_BUCKET_NAME): không được báo "đã lưu" hay ghi consent
            log(context, "Chưa cấu hình DATA_BUCKET_NAME -> không lưu được mẫu đóng góp");
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Chức năng đóng góp tạm thời chưa dùng được ạ");
            }
            return response(200, "DONATION_UNAVAILABLE");
        }

        // Lưu S3 TRƯỚC, xoá session SAU: S3 lỗi thì session và nút vẫn còn để người dùng bấm lại
        try {
            donationStore.saveDonatedSample(userId, checkId, session.maskedText());
        } catch (RuntimeException e) {
            log(context, "Lỗi lưu mẫu đóng góp: " + e.getClass().getSimpleName());
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(),
                        "Cháu chưa lưu được ạ, " + addressForm + " vui lòng bấm lại sau ít phút nhé");
            }
            return response(200, "DONATION_SAVE_FAILED");
        }
        sessionStore.delete(chat);
        removeButtons(cb, chatId);

        if (userRepo != null) {
            // Chỉ bật mục đích "donate": updateConsent sẽ đóng dấu lại cả policySha256/consentedAt
            try {
                userRepo.setPurpose(userId, "donate", true);
            } catch (RuntimeException e) {
                log(context, "Không ghi được mục đích donate: " + e.getClass().getSimpleName());
            }
        }

        if (auditRepo != null) {
            auditRepo.recordEvent(userId, "CONSENT_GIVEN", POLICY_VERSION, policySha256,
                    "Agreed to donate masked sample for check " + (checkId != null ? checkId : ""));
        }

        if (telegram != null) {
            telegram.answerCallbackQuery(cb.queryId(), "Cảm ơn " + addressForm + " đã đóng góp!");
            telegram.sendText(chatId,
                    "Dạ, cháu đã lưu lại nội dung tin nhắn để các kỹ sư kiểm tra và hoàn thiện bot. Cháu cảm ơn "
                            + addressForm + " nhiều ạ!");
        }

        return response(200, "DONATION_SAVED");
    }

    private APIGatewayProxyResponseEvent handleDonateDecline(
            TelegramCallbackQuery cb,
            long chatId,
            String userId,
            String data,
            Context context) {
        String addressForm = getAddressForm(userId);
        String checkId = null;
        if (data.startsWith("donate:decline:")) {
            checkId = data.substring("donate:decline:".length()).trim();
        } else if (data.startsWith("dn:decline:")) {
            checkId = data.substring("dn:decline:".length()).trim();
        }

        // Xóa nút bấm trên tin nhắn hỏi để người dùng không bấm lại
        removeButtons(cb, chatId);

        boolean hadSession = false;
        if (sessionStore != null) {
            String chat = String.valueOf(chatId);
            final String requestedCheckId = checkId;
            Optional<SessionRecord> sessionOpt = sessionStore.get(chat)
                    .filter(s -> isSessionUsable(s, userId, requestedCheckId));
            if (sessionOpt.isPresent()) {
                hadSession = true;
                if ((checkId == null || checkId.isBlank()) && sessionOpt.get().checkId() != null) {
                    checkId = sessionOpt.get().checkId();
                }
                sessionStore.delete(chat);
            }
        }

        if (!hadSession) {
            if (telegram != null) {
                telegram.answerCallbackQuery(cb.queryId(), "Ý kiến đã được ghi nhận rồi ạ!");
            }
            return response(200, "DONATION_ALREADY_PROCESSED");
        }

        if (auditRepo != null) {
            auditRepo.recordEvent(userId, "DONATION_DECLINED", POLICY_VERSION, policySha256,
                    "User declined donation for check " + (checkId != null ? checkId : ""));
        }

        if (telegram != null) {
            telegram.answerCallbackQuery(cb.queryId(), "Đã ghi nhận ý kiến");
            telegram.sendText(chatId,
                    "Dạ vâng, cháu cảm ơn " + addressForm + "! Cháu sẽ không lưu lại nội dung tin nhắn này ạ.");
        }

        return response(200, "DONATION_DECLINED");
    }

    private APIGatewayProxyResponseEvent handleCommand(long chatId, String userId, String text, Context context) {
        return handleCommand(chatId, userId, text, "", "", context);
    }

    private APIGatewayProxyResponseEvent handleCommand(
            long chatId,
            String userId,
            String text,
            String firstName,
            String username,
            Context context) {
        String cmd = text.toLowerCase();

        // BE-28: /nguoithan — hiện danh sách & nút tạo mã mời
        if (cmd.startsWith("/nguoithan")) {
            return handleGuardianCommand(chatId, userId, context);
        }

        // BE-28: /ketnoi <code> — người thân nhập mã mời
        if (cmd.startsWith("/ketnoi")) {
            return handleKetNoiCommand(chatId, userId, text, firstName, username, context);
        }

        // BE-31: /rutdongy — Rút lại sự đồng ý
        if (cmd.startsWith("/rutdongy")) {
            return handleRutDongYCommand(chatId, userId, text, context);
        }

        // BE-31: /xoadulieu — Xoá sạch toàn bộ dữ liệu cá nhân
        if (cmd.startsWith("/xoadulieu")) {
            return handleXoaDuLieuCommand(chatId, userId, text, context);
        }

        // Lệnh test nút báo sai: CHỈ môi trường local. Trên production lệnh này sẽ cho bất kỳ ai tạo CHECK#/SESSION#
        // giả chứa chính text gốc họ gõ (chưa mask) rồi đẩy lên S3 qua luồng đóng góp.
        if (localDevelopment && (cmd.startsWith("/testbaosai") || cmd.startsWith("/baosai"))) {
            return sendSimulatedCheckResult(chatId, userId, "Tin nhắn mẫu: 'Bác ơi đọc giúp cháu mã số vừa gửi qua SMS'");
        }

        if (cmd.startsWith("/start") || cmd.startsWith("/batdau")) {
            // BE-28: deep link từ lời mời người thân → /start g_<code>
            // Telegram tự gửi lệnh này khi người thân bấm vào deep link
            // t.me/bot?start=g_XXXXXX
            String[] startParts = text.trim().split("\\s+", 2);
            if (startParts.length == 2 && startParts[1].startsWith("g_")) {
                String inviteCode = startParts[1].substring(2); // bỏ tiền tố "g_"
                return handleKetNoiCommand(chatId, userId, "/ketnoi " + inviteCode, firstName, username, context);
            }

            boolean hasConsent = (userRepo != null) && userRepo.hasValidConsent(userId, policySha256);

            if (!hasConsent) {
                // Gửi màn hình chào mừng & đồng ý điều khoản (Onboarding - BE-25)
                String welcomeMsg = "<b>Chào mừng Bác đến với AskFirst!</b> 🛡️\n\n" +
                        "Cháu là trợ lý bảo vệ và kiểm tra tin nhắn lừa đảo dành cho người cao tuổi và gia đình.\n\n" +
                        "<b>Chính sách bảo vệ dữ liệu cá nhân (Nghị định 356):</b>\n" +
                        "• Bot <b>KHÔNG BAO GIỜ</b> hỏi mã OTP, mật khẩu hay yêu cầu nạp tiền.\n" +
                        "• Mọi thông tin gửi vào chỉ dùng để phân tích dấu hiệu lừa đảo, không chia sẻ trái phép cho bên thứ ba.\n"
                        +
                        "• Bác có quyền rút đồng ý hoặc yêu cầu xóa dữ liệu bất kỳ lúc nào.\n\n" +
                        "Bác vui lòng bấm <b>Tôi đồng ý</b> bên dưới để bắt đầu sử dụng nhé!";

                Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                        List.of(
                                new InlineButton("✅ Tôi đồng ý", "consent:agree"),
                                new InlineButton("❌ Không đồng ý", "consent:decline"))));

                if (telegram != null) {
                    telegram.sendText(chatId, welcomeMsg, keyboard);
                }
                return response(200, "COMMAND_HANDLED");
            } else {
                String addressForm = getAddressForm(userId, context);
                String msg = "Chào mừng " + addressForm + " quay trở lại với AskFirst! " + addressForm
                        + " hãy gửi tin nhắn, đường link hoặc ảnh chụp màn hình cần kiểm tra cho cháu nhé.";
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
                            new InlineButton("Chú", "xungho:chu"))));
            if (telegram != null) {
                telegram.sendText(chatId, msg, keyboard);
            }
            return response(200, "COMMAND_HANDLED");

        } else if (cmd.startsWith("/huongdan")) {
            String guideMsg = "<b>Hướng dẫn sử dụng AskFirst:</b> 📖\n\n" +
                    "1. <b>Chụp ảnh màn hình:</b> Chụp lại tin nhắn đáng ngờ, cuộc gọi hoặc hóa đơn rồi gửi thẳng vào đây cho cháu.\n"
                    +
                    "2. <b>Chép văn bản / chuyển tiếp:</b> Copy nội dung tin nhắn hoặc bấm chuyển tiếp (Forward) tin nhắn từ người lạ sang.\n"
                    +
                    "3. <b>Chờ kết quả:</b> Trong vài giây, hệ thống AI sẽ phân tích các dấu hiệu lừa đảo và cảnh báo ngay cho Bác.\n\n"
                    +
                    "<i>💡 Mẹo: Bác có thể ghim đoạn chat này lên đầu danh sách Telegram để dễ tìm kiếm khi cần kiểm tra gấp!</i>";

            if (telegram != null) {
                telegram.sendText(chatId, guideMsg);
            }
            return response(200, "COMMAND_HANDLED");

        } else if (cmd.startsWith("/bilua")) {
            String emergencyMsg = "🚨 <b>CÁC VIỆC CẦN LÀM NGAY (30 PHÚT ĐẦU KHI NGHI BỊ LỪA)</b>\n\n" +
                    "1. <b>Khóa thẻ / tài khoản ngân hàng ngay lập tức (0 - 5 phút):</b>\n" +
                    "Gọi ngay tổng đài ngân hàng của Bác, nói rõ nghi bị lừa, yêu cầu khóa thẻ và tạm dừng mọi giao dịch. Đừng chần chừ chờ xác minh!\n\n"
                    +
                    "2. <b>Tắt nguồn / Ngắt mạng điện thoại (0 - 5 phút):</b>\n" +
                    "Nếu Bác đã lỡ bấm vào đường link lạ hoặc cài app lạ, hãy tắt nguồn hoặc tắt Wi-Fi/4G ngay lập tức để kẻ gian không chiếm quyền điều khiển và không lấy được mã OTP.\n\n"
                    +
                    "3. <b>Đổi mật khẩu từ thiết bị khác (5 - 15 phút):</b>\n" +
                    "Dùng điện thoại hoặc máy tính an toàn khác để đổi mật khẩu ngân hàng, ví điện tử, Facebook, Zalo. Gỡ ngay ứng dụng lạ vừa cài.\n\n"
                    +
                    "4. <b>Trình báo Công an gần nhất (15 - 30 phút):</b>\n" +
                    "Đến Công an phường/xã gần nhất hoặc gọi đường dây nóng <b>113</b> để trình báo. Mang theo ảnh chụp tin nhắn, số điện thoại kẻ lừa đảo và biên lai chuyển tiền.\n\n"
                    +
                    "5. <b>Thông báo cho người thân tin cậy:</b>\n" +
                    "Báo ngay cho con cháu trong nhà để cùng hỗ trợ xử lý và cảnh giác nếu kẻ gian tiếp tục liên hệ lừa thêm.";

            if (telegram != null) {
                telegram.sendText(chatId, emergencyMsg);
            }
            return response(200, "COMMAND_HANDLED");
        }

        return response(200, "COMMAND_IGNORED");
    }

    private APIGatewayProxyResponseEvent handleGuardianCommand(long chatId, String userId, Context context) {
        try {
            List<GuardianRecord> guardians = guardianRepo != null
                    ? guardianRepo.listGuardians(userId)
                    : List.of();

            if (guardians.isEmpty()) {
                String msg = "👨‍👩‍👦 <b>Danh sách người thân</b>\n\n"
                        + "Bác chưa kết nối với người thân nào.\n\n"
                        + "Bấm nút bên dưới để tạo mã kết nối và gửi cho con/cháu:";
                Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                        List.of(new InlineButton("➕ Tạo mã kết nối người thân", "guardian:create_invite"))));
                if (telegram != null) {
                    telegram.sendText(chatId, msg, keyboard);
                }
            } else {
                StringBuilder sb = new StringBuilder("👨‍👩‍👦 <b>Danh sách người thân</b>\n\n");
                List<List<InlineButton>> rows = new ArrayList<>();

                for (GuardianRecord g : guardians) {
                    String status = switch (g.getStatus() != null ? g.getStatus() : "active") {
                        case "paused" -> "⏸ Tạm dừng";
                        case "revoked" -> "❌ Đã huỷ";
                        case "unreachable" -> "⚠️ Không liên lạc được";
                        default -> "✅ Đang hoạt động";
                    };
                    String level = "Mức " + (g.getShareLevel() != null ? g.getShareLevel() : 1);
                    String name = g.getName() != null ? g.getName() : g.getGuardianUid();

                    sb.append("• <b>").append(name).append("</b> — ").append(status)
                            .append(" (").append(level).append(")\n");

                    String gid = g.getGuardianUid();
                    rows.add(List.of(
                            new InlineButton("🔄 Đổi mức — " + name, "guardian:level:" + gid),
                            new InlineButton("🗑 Gỡ", "guardian:remove:" + gid)));
                }

                rows.add(List.of(new InlineButton("➕ Tạo mã kết nối mới", "guardian:create_invite")));
                Map<String, Object> keyboard = TelegramClient.inlineKeyboard(rows);
                if (telegram != null) {
                    telegram.sendText(chatId, sb.toString(), keyboard);
                }
            }
        } catch (Exception e) {
            log(context, "Lỗi /nguoithan: " + e.getMessage());
        }
        return response(200, "COMMAND_HANDLED");
    }

    private APIGatewayProxyResponseEvent handleKetNoiCommand(
            long chatId,
            String userId,
            String text,
            Context context) {
        return handleKetNoiCommand(chatId, userId, text, "", "", context);
    }

    private APIGatewayProxyResponseEvent handleKetNoiCommand(
            long chatId,
            String userId,
            String text,
            String firstName,
            String username,
            Context context) {
        String[] parts = text.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            if (telegram != null) {
                telegram.sendText(chatId,
                        "Vui lòng nhập mã kết nối sau lệnh. Ví dụ:\n<code>/ketnoi 123456</code>");
            }
            return response(200, "CONNECT_NO_CODE");
        }

        String code = parts[1].trim();
        if (inviteRepo == null) {
            return response(200, "INVITE_REPO_UNAVAILABLE");
        }

        Optional<InviteRecord> opt = inviteRepo.getInvite(code);
        if (opt.isEmpty()) {
            if (telegram != null) {
                telegram.sendText(chatId,
                        "❌ Mã kết nối <b>" + code + "</b> không hợp lệ. Vui lòng kiểm tra lại mã được gửi.");
            }
            return response(200, "INVALID_INVITE");
        }

        InviteRecord invite = opt.get();

        if ("used".equals(invite.getStatus())) {
            if (telegram != null) {
                telegram.sendText(chatId,
                        "❌ Mã kết nối này đã được sử dụng rồi. Vui lòng nhờ người thân tạo mã mới.");
            }
            return response(200, "INVITE_ALREADY_USED");
        }

        if (invite.getExpiresAt() != null && invite.getExpiresAt() < Instant.now().getEpochSecond()) {
            if (telegram != null) {
                telegram.sendText(chatId,
                        "⏰ Mã kết nối đã hết hạn (mã chỉ có hiệu lực 24 giờ). Vui lòng nhờ người thân tạo mã mới.");
            }
            return response(200, "INVITE_EXPIRED");
        }

        String elderUid = invite.getElderUid();
        if (elderUid.equals(userId)) {
            if (telegram != null) {
                telegram.sendText(chatId,
                        "⚠️ Bác không thể dùng mã kết nối của chính mình.");
            }
            return response(200, "INVITE_SELF");
        }

        boolean consumed = inviteRepo.markUsed(code, userId);
        if (!consumed) {
            if (telegram != null) {
                telegram.sendText(chatId,
                        "❌ Mã kết nối này vừa được dùng bởi người khác. Vui lòng nhờ tạo mã mới.");
            }
            return response(200, "INVITE_RACE");
        }

        // Định dạng tên người thân để hiển thị cho người cao tuổi (tránh duyệt mù - issue #64)
        String safeFirst = firstName != null ? TelegramClient.escapeHtml(firstName.trim()) : "";
        String safeUser = username != null ? TelegramClient.escapeHtml(username.trim()) : "";
        String senderDisplayName;
        if (!safeFirst.isBlank() && !safeUser.isBlank()) {
            senderDisplayName = safeFirst + " (@" + safeUser + ")";
        } else if (!safeFirst.isBlank()) {
            senderDisplayName = safeFirst;
        } else if (!safeUser.isBlank()) {
            senderDisplayName = "@" + safeUser;
        } else {
            senderDisplayName = "người thân (" + userId + ")";
        }

        // Ghi bản ghi pending vào GuardianRepo (chỉ đổi sang active khi người cao tuổi chấp thuận - issue #64)
        if (guardianRepo != null) {
            GuardianRecord pendingRec = new GuardianRecord(elderUid, userId);
            pendingRec.setStatus("pending");
            pendingRec.setName(senderDisplayName);
            pendingRec.setGuardianConsentAt(Instant.now().toString());
            guardianRepo.save(pendingRec);
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
                    + "Tài khoản <b>" + senderDisplayName + "</b> muốn kết nối với Bác.\n\n"
                    + "Vui lòng chọn <b>mức độ chia sẻ thông tin</b> khi có cảnh báo:\n\n"
                    + "• <b>Mức 1:</b> Chỉ báo có nguy cơ (Nguy hiểm / Nghi ngờ).\n"
                    + "• <b>Mức 2:</b> Kèm tên kịch bản và dấu hiệu chính.\n"
                    + "• <b>Mức 3:</b> Kèm tóm tắt nội dung đã che số.";

            Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                    List.of(
                            new InlineButton("🔒 Mức 1 (Khuyến nghị)", "guardian:accept:" + userId + ":1"),
                            new InlineButton("📋 Mức 2", "guardian:accept:" + userId + ":2"),
                            new InlineButton("📄 Mức 3", "guardian:accept:" + userId + ":3")),
                    List.of(new InlineButton("❌ Từ chối", "guardian:reject:" + userId))));
            if (telegram != null) {
                telegram.sendText(elderChatId, msg, keyboard);
            }
        } catch (Exception e) {
            log(context, "Lỗi gửi yêu cầu về người cao tuổi: " + e.getMessage());
        }

        if (auditRepo != null) {
            auditRepo.recordEvent(userId, "INVITE_REDEEMED", POLICY_VERSION, policySha256,
                    "Redeemed invite from elder: " + elderUid);
        }

        return response(200, "INVITE_REDEEMED");
    }

    // ── BE-31: /rutdongy & /xoadulieu ──────────────────────────────────────────

    private APIGatewayProxyResponseEvent handleRutDongYCommand(
            long chatId,
            String userId,
            String text,
            Context context) {
        String lower = text.toLowerCase().trim();
        if (lower.contains("confirm") || lower.contains("xacnhan")) {
            return executeConsentRevocation(chatId, userId, null, context);
        }

        String addressForm = getAddressForm(userId);
        String confirmMsg = "Nếu rút đồng ý, " + addressForm + " sẽ không thể tiếp tục gửi tin nhắn kiểm tra lừa đảo. "
                + addressForm + " có chắc chắn không?";

        Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                List.of(
                        new InlineButton("⚠️ Xác nhận rút đồng ý", "consent:revoke_confirm"),
                        new InlineButton("Giữ lại", "consent:revoke_cancel"))));

        if (telegram != null) {
            telegram.sendText(chatId, confirmMsg, keyboard);
        }
        return response(200, "COMMAND_HANDLED");
    }

    private APIGatewayProxyResponseEvent executeConsentRevocation(
            long chatId,
            String userId,
            String queryId,
            Context context) {
        // Kiểm tra tiền điều kiện: nếu đã rút đồng ý trước đó (idempotency check)
        if (userRepo != null) {
            Optional<UserRecord> userOpt = userRepo.getUser(userId);
            if (userOpt.isPresent() && "CONSENT_REVOKED".equals(userOpt.get().getStatus())) {
                if (telegram != null) {
                    if (queryId != null) {
                        telegram.answerCallbackQuery(queryId, "Bác đã rút đồng ý trước đó");
                    }
                    telegram.sendText(chatId,
                            "Dạ, Bác đã rút đồng ý trước đó rồi ạ.\n\n"
                                    + "Khi nào Bác cần sử dụng lại, chỉ cần gõ /start để xem và đồng ý lại nhé!");
                }
                return response(200, "CONSENT_ALREADY_REVOKED");
            }
        }

        // 1. Cập nhật trạng thái người dùng thành CONSENT_REVOKED trong UserRepo
        if (userRepo != null) {
            userRepo.revokeConsent(userId);
        }

        // 2. Huỷ bỏ mọi liên kết người thân đang có (cả 2 chiều)
        if (guardianRepo != null) {
            guardianRepo.deleteAllGuardians(userId);
        }

        // 3. Ghi nhận một bản ghi vào AuditRepo
        if (auditRepo != null) {
            auditRepo.recordEvent(userId, "CONSENT_REVOKED", POLICY_VERSION, policySha256,
                    "Revoked consent via /rutdongy");
        }

        // 4. Phản hồi cho người dùng qua Telegram
        if (telegram != null) {
            if (queryId != null) {
                telegram.answerCallbackQuery(queryId, "Đã rút đồng ý");
            }
            telegram.sendText(chatId,
                    "Dạ cháu đã ghi nhận rút đồng ý của Bác. Mọi liên kết người thân đã được huỷ bỏ và cháu sẽ ngừng hỗ trợ kiểm tra tin nhắn lừa đảo.\n\n"
                            + "Khi nào Bác cần sử dụng lại, chỉ cần gõ /start để xem và đồng ý lại nhé!");
        }

        return response(200, "CONSENT_REVOKED");
    }

    private APIGatewayProxyResponseEvent handleXoaDuLieuCommand(
            long chatId,
            String userId,
            String text,
            Context context) {
        String lower = text.toLowerCase().trim();
        if (lower.contains("confirm") || lower.contains("xacnhan")) {
            return executeDataDeletion(null, chatId, userId, context);
        }

        String addressForm = getAddressForm(userId);
        String warningMsg = "⚠️ <b>CẢNH BÁO XÓA TOÀN BỘ DỮ LIỆU</b>\n\n"
                + "Thao tác này sẽ xoá vĩnh viễn trên hệ thống:\n"
                + "• Hồ sơ người dùng và lịch sử các lần kiểm tra\n"
                + "• Mọi kết nối với người thân đang bảo vệ " + addressForm + "\n"
                + "• Toàn bộ dữ liệu phiên chat và mẫu đã đóng góp\n\n"
                + addressForm + " có chắc chắn muốn xoá sạch toàn bộ dữ liệu không?";

        Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                List.of(
                        new InlineButton("🗑️ Xác nhận xoá dữ liệu", "xoadulieu:confirm"),
                        new InlineButton("Giữ lại", "xoadulieu:cancel"))));

        if (telegram != null) {
            telegram.sendText(chatId, warningMsg, keyboard);
        }
        return response(200, "COMMAND_HANDLED");
    }

    private APIGatewayProxyResponseEvent executeDataDeletion(
            String queryId,
            long chatId,
            String userId,
            Context context) {
        String chatStr = String.valueOf(chatId);

        // Kiểm tra tiền điều kiện (idempotency check): nếu tài khoản đã bị xoá trước đó
        boolean hasUser = userRepo != null && userRepo.getUser(userId).isPresent();
        boolean hasGuardians = guardianRepo != null && !guardianRepo.listGuardians(userId).isEmpty();
        boolean hasS3 = donationStore != null && donationStore.hasDonatedSamples(userId);
        if (!hasUser && !hasGuardians && !hasS3) {
            List<AuditRecord> audits = auditRepo != null ? auditRepo.listAuditTrail(userId) : List.of();
            boolean hasDeletedAudit = audits.stream().anyMatch(a -> "DATA_DELETED".equals(a.getEvent()));
            if (hasDeletedAudit) {
                if (telegram != null) {
                    if (queryId != null) {
                        telegram.answerCallbackQuery(queryId, "Dữ liệu đã được xoá trước đó");
                    }
                    telegram.sendText(chatId,
                            "Dạ, toàn bộ dữ liệu của Bác trên hệ thống đã được xoá sạch trước đó rồi ạ!\n\n"
                                    + "Nếu cần sử dụng lại, Bác chỉ cần gõ /start nhé.");
                }
                return response(200, "DATA_ALREADY_DELETED");
            }
        }

        try {
            // 1. Quét và xoá sạch:
            // 1a. DynamoDB single-table level (nếu dedup / standardClient có sẵn)
            if (dedup != null) {
                dedup.purgeUserData(userId, chatStr);
            }

            // 1b. Bản ghi thông tin người dùng (USER#)
            if (userRepo != null) {
                userRepo.deleteUser(userId);
            }

            // 1c. Các liên kết người thân (GUARDIAN#) ở cả 2 chiều
            if (guardianRepo != null) {
                guardianRepo.deleteAllGuardians(userId);
            }

            // 1d. Các phiên chat (SESSION#)
            if (sessionStore != null) {
                sessionStore.delete(chatStr);
            }

            // 1e. Các bộ đệm (BUFFER#)
            if (bufferStore != null) {
                bufferStore.delete(chatStr);
            }

            // 1f. Thư mục donated/<hash(uid)>/ trên S3
            if (donationStore != null) {
                donationStore.deleteDonatedSamples(userId);
            }

            // 2. Ghi vết kiểm toán ẩn danh vào AuditRepo:
            // "Tài khoản [hash(uid)] đã yêu cầu xoá dữ liệu vào ngày YYYY-MM-DD"
            if (auditRepo != null) {
                auditRepo.deleteAuditTrail(userId);
                auditRepo.recordDataDeleted(userId, POLICY_VERSION, policySha256);
            }

            // 3. Gửi tin nhắn thông báo hoàn tất:
            // "Cháu đã xoá sạch toàn bộ dữ liệu của Bác trên hệ thống rồi ạ!"
            if (telegram != null) {
                if (queryId != null) {
                    telegram.answerCallbackQuery(queryId, "Đã xoá dữ liệu");
                }
                telegram.sendText(chatId,
                        "Cháu đã xoá sạch toàn bộ dữ liệu của Bác trên hệ thống rồi ạ!\n\n"
                                + "💡 Để bảo vệ quyền riêng tư tuyệt đối, Bác vui lòng tự xóa cuộc trò chuyện này trên ứng dụng Telegram giúp cháu nhé.");
            }

            return response(200, "DATA_DELETED");

        } catch (Exception e) {
            log(context, "Lỗi khi dọn dẹp dữ liệu của user " + userId + ": " + e.getMessage());
            if (telegram != null) {
                if (queryId != null) {
                    telegram.answerCallbackQuery(queryId, "Lỗi dọn dẹp dữ liệu");
                }
                telegram.sendText(chatId,
                        "⚠️ Đã có lỗi xảy ra trong quá trình dọn dẹp dữ liệu của Bác. Dữ liệu chưa được xoá hoàn tất, Bác vui lòng thử lại sau giây lát hoặc liên hệ hỗ trợ nhé.");
            }
            return response(200, "PURGE_FAILED");
        }
    }

    private APIGatewayProxyResponseEvent handleContentInspection(
            JsonNode root,
            InboundMessage inbound,
            long chatId,
            String userId,
            Context context) {

        String addressForm = getAddressForm(userId, context);

        try {
            // BE-35: 1. Kiểm tra tệp cài đặt Android (.apk) độc hại - Xử lý ngay lập tức (Zero SQS, Zero Download)
            if ("apk".equalsIgnoreCase(inbound.kind())) {
                return handleApkBlocked(root, inbound, chatId, userId, addressForm, context);
            }

            // BE-35: 2. Kiểm tra các định dạng không hỗ trợ (Document non-image, unsupported_media, audio)
            if ("document".equalsIgnoreCase(inbound.kind())) {
                String docMsg = "Dạ hiện tại cháu chưa hỗ trợ đọc trực tiếp định dạng tệp này. "
                        + addressForm + " hãy mở tệp ra rồi <b>CHỤP ẢNH MÀN HÌNH</b> gửi lại cho cháu kiểm tra nhé!";
                if (telegram != null) {
                    telegram.sendText(chatId, docMsg);
                }
                return response(200, "UNSUPPORTED_DOCUMENT");
            }

            if ("unsupported_media".equalsIgnoreCase(inbound.kind())) {
                String mediaMsg = "Dạ hiện tại cháu chưa hỗ trợ kiểm tra trực tiếp video, vị trí hoặc danh bạ. "
                        + addressForm + " hãy <b>CHỤP ẢNH MÀN HÌNH</b> hoặc <b>gõ lại nội dung</b> gửi cho cháu kiểm tra nhé!";
                if (telegram != null) {
                    telegram.sendText(chatId, mediaMsg);
                }
                return response(200, "UNSUPPORTED_MEDIA");
            }

            if ("audio".equalsIgnoreCase(inbound.kind())) {
                String audioMsg = "Dạ hiện tại cháu chưa hỗ trợ kiểm tra tệp âm thanh. "
                        + addressForm + " hãy <b>gõ lại nội dung</b> hoặc <b>chụp ảnh màn hình</b> gửi cho cháu kiểm tra nhé!";
                if (telegram != null) {
                    telegram.sendText(chatId, audioMsg);
                }
                return response(200, "UNSUPPORTED_AUDIO");
            }

            // 1. Kiểm tra trạng thái Đồng ý điều khoản (Consent Check - BE-25)
            if (userRepo != null && !userRepo.hasValidConsent(userId, policySha256)) {
                String promptConsent = addressForm + " ơi, để bảo vệ dữ liệu cá nhân theo quy định của pháp luật, " +
                        addressForm
                        + " vui lòng bấm <b>Tôi đồng ý</b> bên dưới trước để cháu được phép kiểm tra tin nhắn nhé!";

                Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                        List.of(
                                new InlineButton("✅ Tôi đồng ý", "consent:agree"),
                                new InlineButton("❌ Không đồng ý", "consent:decline"))));

                if (telegram != null) {
                    telegram.sendText(chatId, promptConsent, keyboard);
                }
                return response(200, "CONSENT_REQUIRED");
            }

            // 2. Kiểm tra đồng ý tin nhắn thoại (Voice Consent Check - BE-25)
            if ("voice".equalsIgnoreCase(inbound.kind())) {
                boolean hasVoice = (userRepo != null) && userRepo.hasVoiceConsent(userId);
                if (!hasVoice) {
                    String voiceMsg = addressForm
                            + " ơi, tính năng kiểm tra tin nhắn thoại (voice) cần có sự đồng ý riêng về xử lý dữ liệu âm thanh. "
                            +
                            "Hiện tại mục này chưa được bật. " + addressForm
                            + " vui lòng chụp màn hình hoặc gửi tin nhắn văn bản cho cháu nhé!";

                    if (telegram != null) {
                        telegram.sendText(chatId, voiceMsg);
                    }
                    return response(200, "VOICE_CONSENT_REQUIRED");
                }
            }

            // 3. Hàng đợi SQS phải sẵn sàng TRƯỚC khi nhận việc: không gửi "⏳" cho một job
            // chắc chắn bị mất.
            // (Chế độ local long polling không có SQS nên được bỏ qua bước enqueue.)
            boolean queueReady = queueUrl != null && !queueUrl.isBlank() && sqs != null;
            if (!queueReady && !localDevelopment) {
                log(context, "Chưa cấu hình hàng đợi SQS (WEBHOOK_QUEUE_URL) -> từ chối xử lý để không mất job im lặng");
                return response(500, "QUEUE_NOT_CONFIGURED");
            }

            // 4. Kiểm tra Giới hạn tốc độ người dùng (Rate Limiting - BE-26)
            if (rateLimitRepo != null) {
                RateLimitRepo.RateLimitResult rlResult = rateLimitRepo.checkAndIncrement(userId);
                if (!rlResult.allowed()) {
                    String rateLimitMsg = addressForm + " ơi, hiện tại " + addressForm
                            + " đã gửi kiểm tra khá nhiều tin trong ngày rồi ạ " +
                            "(giới hạn 30 tin/giờ và 100 tin/ngày). Cháu xin phép tạm nghỉ một chút để đảm bảo an toàn cho hệ thống, "
                            +
                            addressForm + " vui lòng quay lại sau nhé!";

                    if (telegram != null) {
                        telegram.sendText(chatId, rateLimitMsg);
                    }
                    return response(200, "RATE_LIMITED");
                }
            }

            // 5. Đường chính (Lambda): gom tin vào BUFFER# rồi enqueue AnalyzeJob trễ (debounce)
            if (bufferStore != null && queueReady) {
                return bufferAndEnqueue(inbound, chatId, userId, addressForm, context);
            }

            // 6. Chế độ Local Development (không có SQS/Worker): tự động trả kết quả kèm nút [Bot báo sai]
            if (localDevelopment && !queueReady) {
                return sendSimulatedCheckResult(chatId, userId, inbound.text());
            }

            // 7. Đường cũ (bufferStore == null): gửi placeholder rồi enqueue nguyên InboundMessage
            if (telegram != null) {
                try {
                    telegram.sendText(
                            chatId,
                            "⏳ Cháu đang kiểm tra, " + addressForm.toLowerCase() + " chờ cháu vài giây nhé...");
                } catch (Exception e) {
                    // Tin chờ chỉ là phụ: lỗi ở đây không được làm Telegram retry (gây xử lý/đếm
                    // đôi), vẫn đưa job vào hàng đợi
                    log(context, "Không gửi được tin nhắn chờ: " + e);
                }
            }

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
        } catch (Exception e) {
            log(context, "Lỗi không mong muốn trong handleContentInspection cho user " + userId + ": " + e);
            if (telegram != null) {
                try {
                    String errMsg = "Dạ hệ thống đang gặp gián đoạn tạm thời nên chưa thể xử lý yêu cầu này. "
                            + addressForm + " vui lòng gửi lại tin nhắn hoặc tệp giúp cháu sau ít phút nhé!";
                    telegram.sendText(chatId, errMsg);
                } catch (Exception sendEx) {
                    log(context, "Không gửi được tin nhắn báo lỗi cho user " + chatId + ": " + sendEx);
                }
            }
            return response(200, "CONTENT_INSPECTION_FAILED");
        }
    }

    /**
     * Đường chính: gom tin vào BUFFER# rồi enqueue AnalyzeJob trễ 3 giây (debounce, plan §3.4).
     * Lỗi ghi buffer xảy ra TRƯỚC mọi tác dụng phụ nên trả 500 để Telegram gửi lại update.
     */
    private APIGatewayProxyResponseEvent bufferAndEnqueue(
            InboundMessage inbound, long chatId, String userId, String addressForm, Context context) {
        final long seq;
        try {
            seq = bufferStore.append(inbound.chatId(), inbound, Instant.now().getEpochSecond() + BUFFER_TTL_SECONDS);
        } catch (Exception e) {
            log(context, "Lỗi ghi BUFFER#: " + e.getClass().getSimpleName());
            return response(500, "BUFFER_FAILED");
        }

        // "⏳" chỉ cho tin đầu cụm (seq == 1); message_id được lưu để worker sửa thành kết quả.
        if (seq == 1 && telegram != null) {
            try {
                int placeholderId = telegram.sendText(
                        chatId,
                        "⏳ Cháu đang kiểm tra, " + addressForm.toLowerCase() + " chờ cháu vài giây nhé...");
                bufferStore.setPlaceholder(inbound.chatId(), placeholderId);
            } catch (Exception e) {
                // Tin chờ chỉ là phụ: worker sẽ gửi kết quả thành tin mới nếu không có placeholder
                log(context, "Không gửi/lưu được tin nhắn chờ: " + e.getClass().getSimpleName());
            }
        }

        try {
            AnalyzeJob job = AnalyzeJob.create(Ulid.next(), inbound.chatId(), userId, seq, Instant.now());
            sqs.sendMessage(
                    SendMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .messageBody(JobCodec.toJson(job))
                            .delaySeconds(AnalyzeJob.DELAY_SECONDS)
                            .build());
        } catch (Exception e) {
            log(context, "Lỗi gửi AnalyzeJob vào SQS: " + e.getClass().getSimpleName());
            return response(500, "SQS_FAILED");
        }
        return response(200, "OK");
    }

    private APIGatewayProxyResponseEvent handleApkBlocked(
            JsonNode root,
            InboundMessage inbound,
            long chatId,
            String userId,
            String addressForm,
            Context context) {

        // 1. Gửi cảnh báo khẩn cấp cho người cao tuổi (chữ to, in đậm, hướng dẫn tuyệt đối không cài)
        String apkAlert = "🚨 <b>CẢNH BÁO NGUY HIỂM:</b> Đây là tệp cài đặt ứng dụng ngoài luồng (.apk)!\n\n"
                + "Kẻ gian thường dùng tệp này để chiếm quyền điều khiển điện thoại và lấy cắp tiền ngân hàng. "
                + addressForm + " <b>TUYỆT ĐỐI KHÔNG BẤM CÀI ĐẶT</b> nhé!\n\n"
                + "👉 Nếu " + addressForm.toLowerCase() + " đã lỡ bấm cài đặt:\n"
                + "1. Bật ngay Chế độ máy bay (ngắt mạng Wi-Fi và 4G/5G).\n"
                + "2. Dùng điện thoại khác gọi ngay cho ngân hàng để khoá tài khoản/thẻ.\n"
                + "3. Báo ngay cho con cháu hoặc Công an gần nhất để được hỗ trợ.";

        if (telegram != null) {
            try {
                telegram.sendText(chatId, apkAlert);
            } catch (Exception ex) {
                log(context, "Lỗi gửi cảnh báo APK tới người dùng " + chatId + ": " + ex.getMessage());
            }
        }

        // 2. Kích hoạt cảnh báo khẩn cấp cho người thân (nếu đã liên kết)
        if (guardianRepo != null) {
            try {
                // Lấy tên/hiển thị của người cao tuổi từ payload Telegram để người thân nhận diện rõ ràng
                String elderDisplayName = "";
                if (root != null && root.has("message") && root.path("message").has("from")) {
                    JsonNode fromNode = root.path("message").path("from");
                    String firstName = fromNode.path("first_name").asText("").trim();
                    String lastName = fromNode.path("last_name").asText("").trim();
                    String username = fromNode.path("username").asText("").trim();

                    String fullName = (firstName + (lastName.isBlank() ? "" : " " + lastName)).trim();
                    String safeName = TelegramClient.escapeHtml(fullName);
                    String safeUser = TelegramClient.escapeHtml(username);

                    if (!safeName.isBlank() && !safeUser.isBlank()) {
                        elderDisplayName = safeName + " (@" + safeUser + ")";
                    } else if (!safeName.isBlank()) {
                        elderDisplayName = safeName;
                    } else if (!safeUser.isBlank()) {
                        elderDisplayName = "@" + safeUser;
                    }
                }

                String elderLabel;
                if (!elderDisplayName.isBlank()) {
                    if (elderDisplayName.toLowerCase().startsWith(addressForm.toLowerCase())) {
                        elderLabel = elderDisplayName;
                    } else {
                        elderLabel = addressForm + " " + elderDisplayName;
                    }
                } else {
                    elderLabel = addressForm;
                }

                List<GuardianRecord> guardians = guardianRepo.listGuardians(userId);
                for (GuardianRecord g : guardians) {
                    String status = g.getStatus() != null ? g.getStatus() : "active";
                    if ("active".equals(status)) {
                        String guardianUid = g.getGuardianUid();
                        if (guardianUid != null && !guardianUid.isBlank()) {
                            try {
                                long gChatId = Long.parseLong(guardianUid.replace("tg:", ""));
                                String guardianAlert = "🚨 <b>CẢNH BÁO KHẨN CẤP TỪ ASKFIRST</b>\n\n"
                                        + "Người thân của bạn (" + elderLabel + ") vừa nhận được một <b>tệp cài đặt ứng dụng Android lạ (.apk)</b> nguy hiểm!\n\n"
                                        + "Kẻ gian thường giả danh Công an, Thuế, Dịch vụ công để lừa cài mã độc chiếm quyền điện thoại.\n"
                                        + "👉 <b>Hãy liên hệ ngay với người thân</b> để nhắc nhở tuyệt đối không bấm cài đặt!";
                                if (telegram != null) {
                                    telegram.sendText(gChatId, guardianAlert);
                                }
                            } catch (Exception ex) {
                                log(context, "Lỗi gửi cảnh báo tới người thân " + guardianUid + ": " + ex.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log(context, "Lỗi truy vấn danh sách người thân khi phát hiện APK: " + ex.getMessage());
            }
        }

        // 3. Ghi audit log
        if (auditRepo != null) {
            try {
                String fileName = (inbound.media() != null && inbound.media().fileName() != null)
                        ? inbound.media().fileName()
                        : "unknown.apk";
                auditRepo.recordEvent(userId, "APK_BLOCKED", POLICY_VERSION, policySha256,
                        "Blocked APK file: " + fileName);
            } catch (Exception ex) {
                log(context, "Lỗi ghi audit log khi chặn APK: " + ex.getMessage());
            }
        }

        return response(200, "APK_BLOCKED");
    }

    private String getAddressForm(String userId) {
        return getAddressForm(userId, null);
    }

    private String getAddressForm(String userId, Context context) {
        if (userRepo != null) {
            try {
                return userRepo.getUser(userId).map(UserRecord::getAddressForm).orElse(UserRecord.DEFAULT_ADDRESS_FORM);
            } catch (Exception e) {
                log(context, "Lỗi truy vấn cách xưng hô từ DB cho user " + userId + " (" + e.getMessage() + ") -> dùng mặc định: " + UserRecord.DEFAULT_ADDRESS_FORM);
                return UserRecord.DEFAULT_ADDRESS_FORM;
            }
        }
        return UserRecord.DEFAULT_ADDRESS_FORM;
    }

    private APIGatewayProxyResponseEvent sendSimulatedCheckResult(long chatId, String userId, String text) {
        if (!localDevelopment) {
            return response(200, "IGNORED");
        }
        String addressForm = getAddressForm(userId);
        String checkId = Ulid.next();
        String maskedText = "Nội dung đã che mờ: " + (text != null && !text.isBlank() ? text : "Tin nhắn nghi ngờ lừa đảo");

        if (sessionStore != null) {
            sessionStore.put(new SessionRecord(
                    String.valueOf(chatId),
                    checkId,
                    userId,
                    maskedText,
                    null,
                    1,
                    Instant.now().getEpochSecond() + 1800));
        }

        if (checkStore != null) {
            checkStore.put(new CheckRecord(
                    checkId, userId, "verdict", "DANGEROUS", 85,
                    "N1", "N1.4", "fast",
                    List.of("R_OTP_REQUEST"), List.of("share_otp"), List.of("text"),
                    false, null, Instant.now().toString(), Instant.now().getEpochSecond() + 1800));
        }

        if (telegram != null) {
            String verdictHtml = "🛡️ <b>KẾT QUẢ KIỂM TRA</b>\n\n"
                    + "⚠️ <b>Đánh giá:</b> Tin nhắn có dấu hiệu lừa đảo nguy hiểm!\n"
                    + "• <b>Dấu hiệu:</b> Yêu cầu cung cấp thông tin nhạy cảm / mã OTP.\n"
                    + "• <b>Kịch bản:</b> Giả mạo cơ quan / trúng thưởng tri ân.\n"
                    + "• <b>Lời khuyên:</b> Tuyệt đối không làm theo yêu cầu trong tin nhắn.\n\n"
                    + "<i>(Nếu kết luận này chưa đúng, " + addressForm.toLowerCase() + " vui lòng bấm nút bên dưới để góp ý nhé)</i>";

            Map<String, Object> keyboard = TelegramClient.inlineKeyboard(List.of(
                    List.of(new InlineButton("🚨 Bot báo sai", "fb:wrong:" + checkId))));

            telegram.sendText(chatId, verdictHtml, keyboard);
        }

        return response(200, "LOCAL_SIMULATED_SENT");
    }

    /**
     * Fail-closed: Function URL công khai (AuthType NONE) nên header secret là lớp
     * xác thực duy nhất.
     * Không có secret được cấu hình => từ chối tất cả. Chỉ chế độ local development
     * mới được bỏ qua.
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
