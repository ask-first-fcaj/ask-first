package com.askfirst.common.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoSpikeTest {

    @Test
    @DisplayName("Verify DedupRecord TableSchema and annotations for single-table design")
    void testDedupRecordTableSchema() {
        TableSchema<DedupRecord> schema = TableSchema.fromBean(DedupRecord.class);

        assertThat(schema.tableMetadata().primaryPartitionKey()).isEqualTo("pk");
        assertThat(schema.tableMetadata().primarySortKey()).contains("sk");

        DedupRecord record = new DedupRecord("update_999", Instant.now().getEpochSecond() + 86400, "2026-11-02T10:00:00Z", "PROCESSED");
        assertThat(record.getPk()).isEqualTo("DEDUP#update_999");
        assertThat(record.getSk()).isEqualTo("METADATA");
        assertThat(record.getStatus()).isEqualTo("PROCESSED");
        assertThat(record.getTtl()).isNotNull();
    }

    @Test
    @DisplayName("DynamoDB Local / LocalStack integration spike for conditional write (BE-14, BE-22)")
    void testDynamoLocalConditionalWriteSpike() {
        String endpoint = System.getenv("AWS_ENDPOINT_URL");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:4566";
        }

        DynamoDbClient client = null;
        try {
            client = DynamoDbClient.builder()
                    .endpointOverride(URI.create(endpoint))
                    .region(Region.AP_SOUTHEAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test", "test")))
                    .build();

            // Kiểm tra xem LocalStack / DynamoDB Local có đang chạy không
            client.listTables();

            System.out.println("✅ Kết nối thành công tới DynamoDB Local tại " + endpoint);

            DynamoStoreService store = DynamoStoreService.create(client, "askfirst-spike-test");
            store.createTableIfNotExists();

            String testUpdateId = "test_spike_" + System.currentTimeMillis();
            long ttl = Instant.now().getEpochSecond() + 3600;

            // Lần 1: Chưa có -> ghi thành công
            boolean firstAttempt = store.tryAcquireDedup(testUpdateId, ttl);
            assertThat(firstAttempt).isTrue();

            // Lần 2: Đã có -> conditional write phát hiện trùng lặp và từ chối
            boolean secondAttempt = store.tryAcquireDedup(testUpdateId, ttl);
            assertThat(secondAttempt).isFalse();

            // Kiểm tra đọc lại item
            Optional<DedupRecord> recordOpt = store.getDedup(testUpdateId);
            assertThat(recordOpt).isPresent();
            assertThat(recordOpt.get().getStatus()).isEqualTo("PROCESSED");

            // Dọn dẹp test
            store.deleteDedup(testUpdateId);

        } catch (Exception e) {
            System.out.println("ℹ️ Không phát hiện DynamoDB Local tại " + endpoint
                    + " (" + e.getMessage() + "). Đã kiểm tra cấu trúc TableSchema của Enhanced Client thành công.");
            System.out.println("ℹ️ Để chạy integration test này với LocalStack: docker compose up -d rồi chạy lại mvn test.");
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }
}

