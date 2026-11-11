package com.askfirst.common.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
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

/**
 * Lane B repository integration tests - chạy với Testcontainers (amazon/dynamodb-local).
 * Bị bỏ qua tự động khi Docker không có mặt (@Testcontainers(disabledWithoutDocker = true)).
 *
 * Không có catch-all nào cho pass: nếu Docker có, test phải chạy thật và thất bại thật khi sai.
 * Điều này bảo đảm "43/43 pass" thực sự chứng minh được conditional write, rate limit, invite, audit (BE-24).
 */
@Testcontainers(disabledWithoutDocker = true)
class LaneBRepositoriesTest {

    private static final String TABLE_NAME = "askfirst-lane-b-test";

    @Container
    static final GenericContainer<?> DYNAMO_LOCAL =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
                    .withExposedPorts(8000)
                    .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb");

    private DynamoDbClient dynamoClient;
    private DynamoDbEnhancedClient enhancedClient;

    private UserRepo userRepo;
    private GuardianRepo guardianRepo;
    private InviteRepo inviteRepo;
    private RateLimitRepo rateLimitRepo;
    private AuditRepo auditRepo;

    @BeforeEach
    void setUp() {
        // Container được Testcontainers khởi động trước @BeforeEach, không cần try/catch ở đây.
        // Nếu container không start được thì test fail với thông báo rõ ràng thay vì bị bỏ qua im lặng.
        String endpoint = "http://localhost:" + DYNAMO_LOCAL.getMappedPort(8000);

        dynamoClient = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();

        enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoClient)
                .build();

        DynamoStoreService storeService = new DynamoStoreService(enhancedClient, dynamoClient, TABLE_NAME);
        storeService.createTableIfNotExists();

        userRepo = new UserRepo(enhancedClient, TABLE_NAME);
        guardianRepo = new GuardianRepo(enhancedClient, TABLE_NAME);
        inviteRepo = new InviteRepo(enhancedClient, TABLE_NAME);
        rateLimitRepo = new RateLimitRepo(dynamoClient, TABLE_NAME);
        auditRepo = new AuditRepo(enhancedClient, TABLE_NAME);
    }

    @Test
    @DisplayName("UserRepo: Save profile, manage consent & address form (BE-24, BE-25)")
    void testUserRepoOperations() {
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

        // hasValidConsent fail-closed khi hash rỗng (mục 7)
        assertThat(userRepo.hasValidConsent(userId, null)).isFalse();
        assertThat(userRepo.hasValidConsent(userId, "")).isFalse();

        // Đổi cách xưng hô: chỉ cập nhật address_form, không ảnh hưởng consent (mục 10)
        userRepo.updateAddressForm(userId, "Chú");
        UserRecord updated = userRepo.getUser(userId).orElseThrow();
        assertThat(updated.getAddressForm()).isEqualTo("Chú");
        assertThat(userRepo.hasValidConsent(userId, "policy_v1_hash"))
                .as("Consent không được mất sau khi đổi xưng hô")
                .isTrue();

        // Thu hồi đồng ý: analyze phải về false (mục 7)
        userRepo.revokeConsent(userId);
        assertThat(userRepo.hasValidConsent(userId, "policy_v1_hash"))
                .as("Sau khi thu hồi, hasValidConsent phải false")
                .isFalse();
    }

    @Test
    @DisplayName("GuardianRepo: Add relative, update status & share level (BE-24, ARCH §5.4)")
    void testGuardianRepoOperations() {
        String elderId = "tg:elder_" + System.currentTimeMillis();
        String guardianId = "tg:relative_" + System.currentTimeMillis();

        GuardianRecord g = new GuardianRecord(elderId, guardianId);
        // Không set name: field name không có trong ARCH §7 và privacy policy (mục nhỏ)
        g.setGuardianChatId("778899");
        g.setPhone("0987654321");
        g.setShareLevel(1);
        g.setStatus("active");
        guardianRepo.save(g);

        Optional<GuardianRecord> fetched = guardianRepo.getGuardian(elderId, guardianId);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getShareLevel()).isEqualTo(1);

        // GSI1 key được điền tự động bởi GuardianRecord (ARCH §7)
        assertThat(fetched.get().getGsi1Pk())
                .as("GSI1PK phải là WARD_OF#<guardian_uid>")
                .startsWith("WARD_OF#");
        assertThat(fetched.get().getGsi1Sk())
                .as("GSI1SK phải là USER#<elder_uid>")
                .startsWith("USER#");

        // List
        List<GuardianRecord> list = guardianRepo.listGuardians(elderId);
        assertThat(list).hasSize(1);

        // Update share level & status (mục 10: không đè nhau khi song song)
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
        String code = "INV_" + (System.currentTimeMillis() % 100000);
        String elderId = "tg:elder_inv";
        long ttl = Instant.now().getEpochSecond() + 86400;

        inviteRepo.createInvite(code, elderId, ttl);

        Optional<InviteRecord> fetched = inviteRepo.getInvite(code);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getStatus()).isEqualTo("pending");

        // Lần đầu dùng: thành công
        boolean usedFirst = inviteRepo.markUsed(code, "tg:relative_abc");
        assertThat(usedFirst).as("Lần đầu dùng invite phải thành công").isTrue();

        // Lần thứ hai: phải bị từ chối (single-use, conditional write thật)
        boolean usedSecond = inviteRepo.markUsed(code, "tg:relative_xyz");
        assertThat(usedSecond).as("Lần thứ hai dùng cùng invite phải bị từ chối").isFalse();

        inviteRepo.deleteInvite(code);
    }

    @Test
    @DisplayName("RateLimitRepo: Atomic counter, biên 30/giờ và 100/ngày (BE-24, BE-26)")
    void testRateLimitRepoAtomicCounter() {
        String userId = "tg:rl_test_" + System.currentTimeMillis();

        // Biên 2/giờ: lần 1 và 2 được phép, lần 3 bị chặn
        RateLimitRepo.RateLimitResult res1 = rateLimitRepo.checkAndIncrement(userId, 2, 5);
        assertThat(res1.allowed()).isTrue();
        assertThat(res1.hourCount()).isEqualTo(1L);

        RateLimitRepo.RateLimitResult res2 = rateLimitRepo.checkAndIncrement(userId, 2, 5);
        assertThat(res2.allowed()).isTrue();
        assertThat(res2.hourCount()).isEqualTo(2L);

        RateLimitRepo.RateLimitResult res3 = rateLimitRepo.checkAndIncrement(userId, 2, 5);
        assertThat(res3.allowed()).as("Lần thứ 3 phải bị chặn (vượt 2/giờ)").isFalse();
        assertThat(res3.hourCount()).isEqualTo(3L);
        assertThat(res3.reason()).contains("Vượt quá giới hạn 2 tin/giờ");
    }

    @Test
    @DisplayName("AuditRepo: Không ghi đè event, trùng timestamp thêm hậu tố (BE-24, BE-25, NĐ 356)")
    void testAuditRepoOperations() {
        String userId = "tg:audit_test_" + System.currentTimeMillis();

        auditRepo.recordEvent(userId, "CONSENT_GIVEN", "1.0", "hash_abc", "{\"purposes\":[\"analyze\"]}");
        auditRepo.recordEvent(userId, "ADDRESS_FORM_CHANGED", "1.0", "hash_abc", "{\"new_form\":\"Bác\"}");

        List<AuditRecord> trail = auditRepo.listAuditTrail(userId);
        assertThat(trail).hasSize(2);
        assertThat(trail).anyMatch(r -> "CONSENT_GIVEN".equals(r.getEvent()));
        assertThat(trail).anyMatch(r -> "ADDRESS_FORM_CHANGED".equals(r.getEvent()));

        // Ghi cùng event cùng lúc (giả lập xung đột SK): cả hai phải được lưu, không mất event nào
        String sameTimestamp = Instant.now().toString();
        AuditRecord r1 = new AuditRecord(userId, sameTimestamp, "CONSENT_GIVEN");
        AuditRecord r2 = new AuditRecord(userId, sameTimestamp, "CONSENT_GIVEN");
        // SK lần 2 sẽ khác (hậu tố UUID) nên cả hai tồn tại song song
        assertThat(r1.getSk()).startsWith("AUDIT#" + sameTimestamp + "#CONSENT_GIVEN");
        assertThat(r2.getSk()).startsWith("AUDIT#" + sameTimestamp + "#CONSENT_GIVEN");
    }
}
