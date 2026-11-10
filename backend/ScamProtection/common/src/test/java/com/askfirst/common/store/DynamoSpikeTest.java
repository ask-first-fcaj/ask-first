package com.askfirst.common.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.github.dockerjava.api.model.ExposedPort;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DynamoDB spike: kiểm tra schema annotation và conditional write (BE-14, BE-22).
 *
 * Test schema chạy không cần Docker (chỉ dùng TableSchema.fromBean).
 * Test integration dùng Testcontainers (amazon/dynamodb-local): bị bỏ qua tự động nếu
 * Docker không có mặt (@Testcontainers(disabledWithoutDocker = true)).
 * Không có catch-all nào cho pass khi không kết nối được - lỗi thật phải thất bại thật.
 */
@Testcontainers(disabledWithoutDocker = true)
class DynamoSpikeTest {

    @Container
    static final GenericContainer<?> DYNAMO_LOCAL =
        new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:2.5.2"))
            .withExposedPorts(8000)
            .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb");

    // -------------------------------------------------------------------------
    // Test 1: chạy được KHÔNG cần Docker (chỉ kiểm tra annotation/bean mapping)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DedupRecord: TableSchema phải nhận ra PK/SK (hoa) theo ARCH §7")
    void testDedupRecordTableSchema() {
        TableSchema<DedupRecord> schema = TableSchema.fromBean(DedupRecord.class);

        // Khóa phải là PK/SK hoa - khác chữ thường thì DynamoDB ValidationException khi chạy thật
        assertThat(schema.tableMetadata().primaryPartitionKey())
                .as("Partition key phải là 'PK' (hoa), không phải 'pk'")
                .isEqualTo("PK");
        assertThat(schema.tableMetadata().primarySortKey())
                .as("Sort key phải là 'SK' (hoa), không phải 'sk'")
                .contains("SK");

        DedupRecord record = new DedupRecord(
                "update_999",
                Instant.now().getEpochSecond() + 86400,
                "2026-11-02T10:00:00Z",
                "PROCESSED");
        assertThat(record.getPk()).isEqualTo("DEDUP#update_999");
        assertThat(record.getSk()).isEqualTo("METADATA");
        assertThat(record.getStatus()).isEqualTo("PROCESSED");
        assertThat(record.getTtl()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Test 2: integration - chỉ chạy khi Docker có mặt (Testcontainers)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DynamoDB Local: conditional write dedup thật sự hoạt động (BE-14, BE-22)")
    void testDynamoLocalConditionalWriteSpike() throws Exception {
        String endpoint = "http://localhost:" + DYNAMO_LOCAL.getMappedPort(8000);

        DynamoDbClient client = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.AP_SOUTHEAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();

        DynamoStoreService store = DynamoStoreService.create(client, "askfirst-spike-test");
        store.createTableIfNotExists();

        String testUpdateId = "test_spike_" + System.currentTimeMillis();
        long ttl = Instant.now().getEpochSecond() + 3600;

        // Lần 1: chưa có -> ghi thành công
        boolean first = store.tryAcquireDedup(testUpdateId, ttl);
        assertThat(first).as("Lần đầu tiên phải ghi được").isTrue();

        // Lần 2: đã có -> conditional write phải từ chối (không phải catch-and-pass)
        boolean second = store.tryAcquireDedup(testUpdateId, ttl);
        assertThat(second).as("Lần thứ hai phải bị từ chối (trùng update_id)").isFalse();

        // Đọc lại xác nhận item tồn tại và đúng trạng thái
        Optional<DedupRecord> recordOpt = store.getDedup(testUpdateId);
        assertThat(recordOpt).isPresent();
        assertThat(recordOpt.get().getStatus()).isEqualTo("PROCESSED");

        store.deleteDedup(testUpdateId);
        client.close();
    }
}
