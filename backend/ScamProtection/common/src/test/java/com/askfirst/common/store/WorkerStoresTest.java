package com.askfirst.common.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.askfirst.common.ai.AiContract.Flags;
import com.askfirst.common.channel.model.InboundMessage;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * Kiểm chứng thật các store của worker (BUFFER#, FLAGS, CHECK#, ALERT#, ALERTLOCK#) với DynamoDB Local.
 * Cùng cách chạy với LaneBRepositoriesTest: tự bỏ qua khi không có Docker (Windows/macOS runner của CI).
 * Nếu LaneBRepositoriesTest trong repo dùng cách khởi tạo client/bảng khác, chỉnh setUp() cho giống.
 */
@Testcontainers(disabledWithoutDocker = true)
class WorkerStoresTest {

    private static final String TABLE = "askfirst-worker-stores-test";
    private static final Instant NOW = Instant.parse("2026-11-08T07:00:00Z");

    @Container
    static final GenericContainer<?> DYNAMO_LOCAL =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
                    .withExposedPorts(8000)
                    .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb");

    private DynamoDbClient client;
    private DynamoBufferStore buffer;
    private DynamoFlagsStore flags;
    private DynamoCheckStore checks;
    private DynamoAlertStore alerts;
    private DynamoAlertLockStore locks;
    private String chat;

    @BeforeEach
    void setUp() {
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://localhost:" + DYNAMO_LOCAL.getMappedPort(8000)))
                .region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        DynamoStoreService.create(client, TABLE).createTableIfNotExists();
        buffer = new DynamoBufferStore(client, TABLE);
        flags = new DynamoFlagsStore(client, TABLE);
        checks = new DynamoCheckStore(client, TABLE);
        alerts = new DynamoAlertStore(client, TABLE);
        locks = new DynamoAlertLockStore(client, TABLE);
        chat = "chat-" + System.nanoTime();   // mỗi test một chat riêng vì bảng dùng chung (-sharedDb)
    }

    private static InboundMessage message(String chat, int n) {
        return new InboundMessage("telegram", "u" + n, chat, "tg:1", "m" + n, "text", "noi dung " + n,
                List.of(), null, null, null, NOW);
    }

    private static long ttl() {
        return Instant.now().getEpochSecond() + 600;
    }

    // ---------- BUFFER# ----------

    @Test
    @DisplayName("BufferStore: seq tăng dần, đọc đúng khoảng và đúng thứ tự")
    void bufferAppendAndRead() {
        assertThat(buffer.append(chat, message(chat, 1), ttl())).isEqualTo(1);
        assertThat(buffer.append(chat, message(chat, 2), ttl())).isEqualTo(2);
        assertThat(buffer.append(chat, message(chat, 3), ttl())).isEqualTo(3);

        assertThat(buffer.state(chat)).hasValueSatisfying(s -> {
            assertThat(s.maxSeq()).isEqualTo(3);
            assertThat(s.doneSeq()).isZero();
            assertThat(s.placeholderId()).isNull();
        });
        assertThat(buffer.itemsAfter(chat, 0, 3)).extracting(InboundMessage::messageId).containsExactly("m1", "m2", "m3");
        assertThat(buffer.itemsAfter(chat, 1, 2)).extracting(InboundMessage::messageId).containsExactly("m2");
        assertThat(buffer.itemsAfter(chat, 3, 3)).isEmpty();
    }

    @Test
    @DisplayName("BufferStore: 20 webhook song song nhận 20 seq khác nhau (ADD nguyên tử)")
    void bufferSeqIsAtomicUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                int n = i;
                futures.add(pool.submit(() -> buffer.append(chat, message(chat, n), ttl())));
            }
            Set<Long> seqs = new TreeSet<>();
            for (Future<Long> f : futures) {
                seqs.add(f.get());
            }
            assertThat(seqs).hasSize(20);
            assertThat(seqs.iterator().next()).isEqualTo(1);
            assertThat(buffer.itemsAfter(chat, 0, 20)).hasSize(20);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("BufferStore: finish giữ lại tin mới hơn, xóa hẳn khi không còn tin mới")
    void bufferFinish() {
        buffer.append(chat, message(chat, 1), ttl());
        buffer.append(chat, message(chat, 2), ttl());
        buffer.setPlaceholder(chat, 77);
        assertThat(buffer.state(chat).get().placeholderId()).isEqualTo(77);

        assertThat(buffer.finish(chat, 1)).isFalse();         // còn tin seq 2
        assertThat(buffer.state(chat)).hasValueSatisfying(s -> {
            assertThat(s.doneSeq()).isEqualTo(1);
            assertThat(s.maxSeq()).isEqualTo(2);
            assertThat(s.placeholderId()).isNull();
        });
        assertThat(buffer.itemsAfter(chat, 0, 2)).extracting(InboundMessage::messageId).containsExactly("m2");

        assertThat(buffer.finish(chat, 2)).isTrue();
        assertThat(buffer.state(chat)).isEmpty();
        assertThat(buffer.itemsAfter(chat, 0, 2)).isEmpty();
        assertThat(buffer.finish(chat, 2)).isTrue();          // gọi lại vẫn an toàn

        assertThat(buffer.append(chat, message(chat, 9), ttl())).isEqualTo(1);   // cụm mới bắt đầu lại từ 1
    }

    @Test
    @DisplayName("BufferStore: setPlaceholder không tạo buffer ma khi buffer đã mất")
    void placeholderDoesNotResurrectBuffer() {
        buffer.setPlaceholder(chat, 5);

        assertThat(buffer.state(chat)).isEmpty();
    }

    // ---------- FLAGS ----------

    @Test
    @DisplayName("FlagsStore: mặc định khi chưa có item, đọc đúng khi có, field thiếu dùng mặc định")
    void flagsDefaultsAndOverrides() {
        assertThat(flags.load()).isEqualTo(FlagsStore.DEFAULTS);

        client.putItem(PutItemRequest.builder().tableName(TABLE).item(Map.of(
                "PK", AttributeValue.builder().s("CONFIG#global").build(),
                "SK", AttributeValue.builder().s("FLAGS").build(),
                "llm_enabled", AttributeValue.builder().bool(false).build(),
                "feature_voice", AttributeValue.builder().bool(true).build())).build());

        assertThat(flags.load()).isEqualTo(new Flags(false, true, true, true));
    }

    // ---------- CHECK# ----------

    @Test
    @DisplayName("CheckStore: exists, put một lần, ghi lại không đè")
    void checkStore() {
        String uid = "tg:" + chat;
        assertThat(checks.exists(uid, "C1")).isFalse();

        checks.put(new CheckRecord("C1", uid, "verdict", "DANGEROUS", 90, "N1", "N1.4", "fast",
                List.of("R_OTP_REQUEST"), List.of("share_otp"), List.of("text"), true, NOW.toString(), ttl()));
        checks.put(new CheckRecord("C1", uid, "degraded", "UNSURE", 0, "OTHER", "OTHER", "none",
                List.of(), List.of(), List.of(), false, NOW.toString(), ttl()));

        assertThat(checks.exists(uid, "C1")).isTrue();
        assertThat(checks.exists(uid, "C2")).isFalse();
    }

    // ---------- ALERT# ----------

    private AlertRecord pending(String alertId) {
        return new AlertRecord(alertId, "tg:" + chat, chat, "C1", "tg:201", "201", "Lan", 2,
                AlertRecord.PENDING, null, null, null, ttl());
    }

    @Test
    @DisplayName("AlertStore: reserve trả bản cũ khi retry, markSent/markAcked/markReminded đúng điều kiện")
    void alertLifecycle() {
        String id = "A-" + chat;
        assertThat(alerts.reserve(pending(id)).status()).isEqualTo(AlertRecord.PENDING);

        alerts.markSent(id, 55);
        AlertRecord again = alerts.reserve(pending(id));       // retry: nhận lại bản đã sent
        assertThat(again.status()).isEqualTo(AlertRecord.SENT);
        assertThat(again.messageId()).isEqualTo(55);

        assertThat(alerts.markReminded(id, NOW)).isTrue();
        assertThat(alerts.markReminded(id, NOW)).isFalse();    // chỉ nhắc một lần

        assertThat(alerts.markAcked(id, NOW)).isTrue();
        alerts.markSent(id, 99);                               // không được hạ cấp acked
        assertThat(alerts.get(id)).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo(AlertRecord.ACKED);
            assertThat(r.ackedAt()).isNotNull();
            assertThat(r.guardianUid()).isEqualTo("tg:201");
        });
        assertThat(alerts.markAcked("KHONG-CO", NOW)).isFalse();
        assertThat(alerts.get("KHONG-CO")).isEmpty();
    }

    @Test
    @DisplayName("AlertStore: markFailed chỉ áp dụng cho pending")
    void alertFailedOnlyFromPending() {
        String pendingId = "P-" + chat;
        String sentId = "S-" + chat;
        alerts.reserve(pending(pendingId));
        alerts.reserve(pending(sentId));
        alerts.markSent(sentId, 1);

        alerts.markFailed(pendingId);
        alerts.markFailed(sentId);

        assertThat(alerts.get(pendingId).get().status()).isEqualTo(AlertRecord.FAILED);
        assertThat(alerts.get(sentId).get().status()).isEqualTo(AlertRecord.SENT);
    }

    // ---------- ALERTLOCK# ----------

    @Test
    @DisplayName("AlertLockStore: chặn job khác, cho chính chủ vào lại, cho qua khi khóa đã hết hạn")
    void alertLock() {
        String uid = "tg:" + chat;
        long now = Instant.now().getEpochSecond();

        assertThat(locks.acquire(uid, "N1.4", "CHECK-A", now, now + 1800)).isTrue();
        assertThat(locks.acquire(uid, "N1.4", "CHECK-B", now, now + 1800)).isFalse();
        assertThat(locks.acquire(uid, "N1.4", "CHECK-A", now, now + 1800)).isTrue();
        assertThat(locks.acquire(uid, "N2.1", "CHECK-B", now, now + 1800)).isTrue();    // kịch bản khác

        assertThat(locks.acquire(uid, "N1.4", "CHECK-B", now + 1801, now + 3600)).isTrue();   // A đã hết hạn
    }
}
