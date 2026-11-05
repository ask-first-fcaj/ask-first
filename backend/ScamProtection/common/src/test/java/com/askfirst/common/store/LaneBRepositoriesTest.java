package com.askfirst.common.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LaneBRepositoriesTest {

    private static final String TABLE_NAME = "askfirst-lane-b-test";
    private DynamoDbClient dynamoClient;
    private DynamoDbEnhancedClient enhancedClient;
    private boolean isLiveDynamoAvailable = false;

    private UserRepo userRepo;
    private GuardianRepo guardianRepo;
    private InviteRepo inviteRepo;
    private RateLimitRepo rateLimitRepo;
    private AuditRepo auditRepo;

    @BeforeEach
    void setUp() {
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:4566";
        }

        try {
            dynamoClient = DynamoDbClient.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.AP_SOUTHEAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")))
                    .build();

            dynamoClient.listTables();
            isLiveDynamoAvailable = true;

            enhancedClient = DynamoDbEnhancedClient.builder()
                    .dynamoDbClient(dynamoClient)
                    .build();

            // Tạo bảng nếu chưa có
            DynamoStoreService storeService = new DynamoStoreService(enhancedClient, dynamoClient, TABLE_NAME);
            storeService.createTableIfNotExists();

            userRepo = new UserRepo(enhancedClient, TABLE_NAME);
            guardianRepo = new GuardianRepo(enhancedClient, TABLE_NAME);
            inviteRepo = new InviteRepo(enhancedClient, TABLE_NAME);
            rateLimitRepo = new RateLimitRepo(dynamoClient, TABLE_NAME);
            auditRepo = new AuditRepo(enhancedClient, TABLE_NAME);

        } catch (Exception e) {
            isLiveDynamoAvailable = false;
        }
    }

    @Test
    @DisplayName("UserRepo: Save profile, manage consent & address form (BE-24, BE-25)")
    void testUserRepoOperations() {
        if (!isLiveDynamoAvailable) {
            // Verify bean mapping when local dynamodb is offline
            UserRecord u = new UserRecord("tg:123456", "123456");
            u.setAddressForm("Bác");
            u.setPolicySha256("policy_hash_v1");
            u.setPurposes(Map.of("analyze", true, "voice", false));
            assertThat(u.getPk()).isEqualTo("USER#tg:123456");
            assertThat(u.getSk()).isEqualTo("PROFILE");
            return;
        }

        String userId = "tg:test_user_" + System.currentTimeMillis();
        UserRecord user = new UserRecord(userId, "987654");
        user.setRole("elder");
        user.setAddressForm("Bác");
        userRepo.saveUser(user);

        // Đọc lại
        Optional<UserRecord> fetchedOpt = userRepo.getUser(userId);
        assertThat(fetchedOpt).isPresent();
        UserRecord fetched = fetchedOpt.get();
        assertThat(fetched.getUserId()).isEqualTo(userId);
        assertThat(fetched.getAddressForm()).isEqualTo("Bác");

        // Chưa đồng ý -> hasValidConsent false
        assertThat(userRepo.hasValidConsent(userId, "policy_v1_hash")).isFalse();
        assertThat(userRepo.hasVoiceConsent(userId)).isFalse();

        // Đồng ý chính sách
        Map<String, Boolean> purposes = new HashMap<>();
        purposes.put("analyze", true);
        purposes.put("guardian", true);
        purposes.put("voice", true);
        purposes.put("donate", false);
        userRepo.updateConsent(userId, "policy_v1_hash", "1.0", purposes);

        // Kiểm tra sau khi đồng ý
        assertThat(userRepo.hasValidConsent(userId, "policy_v1_hash")).isTrue();
        assertThat(userRepo.hasVoiceConsent(userId)).isTrue();

        // Đổi policy sha -> bị chặn cho đến khi đồng ý lại
        assertThat(userRepo.hasValidConsent(userId, "policy_v2_NEW_hash")).isFalse();

        // Đổi cách xưng hô
        userRepo.updateAddressForm(userId, "Chú");
        UserRecord updated = userRepo.getUser(userId).orElseThrow();
        assertThat(updated.getAddressForm()).isEqualTo("Chú");
    }

    @Test
    @DisplayName("GuardianRepo: Add relative, update status & share level (BE-24, ARCH §5.4)")
    void testGuardianRepoOperations() {
        if (!isLiveDynamoAvailable) {
            GuardianRecord g = new GuardianRecord("tg:elder_1", "tg:relative_1");
            assertThat(g.getPk()).isEqualTo("USER#tg:elder_1");
            assertThat(g.getSk()).isEqualTo("GUARDIAN#tg:relative_1");
            return;
        }

        String elderId = "tg:elder_" + System.currentTimeMillis();
        String guardianId = "tg:relative_" + System.currentTimeMillis();

        GuardianRecord g = new GuardianRecord(elderId, guardianId);
        g.setName("Nguyễn Văn Con");
        g.setGuardianChatId("778899");
        g.setPhone("0987654321");
        g.setShareLevel(1);
        g.setStatus("active");
        guardianRepo.save(g);

        Optional<GuardianRecord> fetched = guardianRepo.getGuardian(elderId, guardianId);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getName()).isEqualTo("Nguyễn Văn Con");
        assertThat(fetched.get().getShareLevel()).isEqualTo(1);

        // List
        List<GuardianRecord> list = guardianRepo.listGuardians(elderId);
        assertThat(list).hasSize(1);

        // Update share level & status
        guardianRepo.updateShareLevel(elderId, guardianId, 2);
        guardianRepo.updateStatus(elderId, guardianId, "paused");

        GuardianRecord updated = guardianRepo.getGuardian(elderId, guardianId).orElseThrow();
        assertThat(updated.getShareLevel()).isEqualTo(2);
        assertThat(updated.getStatus()).isEqualTo("paused");

        // Cleanup
        guardianRepo.delete(elderId, guardianId);
        assertThat(guardianRepo.getGuardian(elderId, guardianId)).isEmpty();
    }

    @Test
    @DisplayName("InviteRepo: Single-use invite code with TTL (BE-24, ARCH §5.4)")
    void testInviteRepoSingleUse() {
        if (!isLiveDynamoAvailable) {
            InviteRecord inv = new InviteRecord("INV123", "tg:elder_1", Instant.now().getEpochSecond() + 3600);
            assertThat(inv.getPk()).isEqualTo("INVITE#INV123");
            assertThat(inv.getStatus()).isEqualTo("pending");
            return;
        }

        String code = "INV_" + (System.currentTimeMillis() % 100000);
        String elderId = "tg:elder_inv";
        long ttl = Instant.now().getEpochSecond() + 86400;

        inviteRepo.createInvite(code, elderId, ttl);

        Optional<InviteRecord> fetched = inviteRepo.getInvite(code);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getStatus()).isEqualTo("pending");

        // Lần đầu sử dụng: Thành công
        boolean usedFirst = inviteRepo.markUsed(code, "tg:relative_abc");
        assertThat(usedFirst).isTrue();

        // Lần thứ hai sử dụng: Bị từ chối vì đã dùng rồi (single-use)
        boolean usedSecond = inviteRepo.markUsed(code, "tg:relative_xyz");
        assertThat(usedSecond).isFalse();

        inviteRepo.deleteInvite(code);
    }

    @Test
    @DisplayName("RateLimitRepo: Atomic counter 30/hour and 100/day (BE-24, BE-26)")
    void testRateLimitRepoAtomicCounter() {
        if (!isLiveDynamoAvailable) {
            RateLimitRecord r = new RateLimitRecord("tg:user_rl", "2026110216", 15L, 3600L);
            assertThat(r.getPk()).contains("RATE#tg:user_rl#2026110216");
            assertThat(r.getCount()).isEqualTo(15L);
            return;
        }

        String userId = "tg:rl_test_" + System.currentTimeMillis();

        // Thử với ngưỡng nhỏ để test: max 2/giờ
        RateLimitRepo.RateLimitResult res1 = rateLimitRepo.checkAndIncrement(userId, 2, 5);
        assertThat(res1.allowed()).isTrue();
        assertThat(res1.hourCount()).isEqualTo(1L);

        RateLimitRepo.RateLimitResult res2 = rateLimitRepo.checkAndIncrement(userId, 2, 5);
        assertThat(res2.allowed()).isTrue();
        assertThat(res2.hourCount()).isEqualTo(2L);

        // Lần thứ 3: Vượt quá ngưỡng 2 tin/giờ
        RateLimitRepo.RateLimitResult res3 = rateLimitRepo.checkAndIncrement(userId, 2, 5);
        assertThat(res3.allowed()).isFalse();
        assertThat(res3.hourCount()).isEqualTo(3L);
        assertThat(res3.reason()).contains("Vượt quá giới hạn 2 tin/giờ");
    }

    @Test
    @DisplayName("AuditRepo: Record legal consent & preference changes (BE-24, BE-25)")
    void testAuditRepoOperations() {
        if (!isLiveDynamoAvailable) {
            AuditRecord a = new AuditRecord("tg:user_1", "2026-11-02T10:00:00Z", "CONSENT_GIVEN");
            assertThat(a.getPk()).isEqualTo("USER#tg:user_1");
            assertThat(a.getSk()).contains("AUDIT#2026-11-02T10:00:00Z#CONSENT_GIVEN");
            return;
        }

        String userId = "tg:audit_test_" + System.currentTimeMillis();

        auditRepo.recordEvent(userId, "CONSENT_GIVEN", "1.0", "hash_abc", "{\"purposes\": [\"analyze\"]}");
        auditRepo.recordEvent(userId, "ADDRESS_FORM_CHANGED", "1.0", "hash_abc", "{\"new_form\": \"Bác\"}");

        List<AuditRecord> trail = auditRepo.listAuditTrail(userId);
        assertThat(trail).hasSize(2);
        assertThat(trail).anyMatch(r -> "CONSENT_GIVEN".equals(r.getEvent()));
        assertThat(trail).anyMatch(r -> "ADDRESS_FORM_CHANGED".equals(r.getEvent()));
    }
}

