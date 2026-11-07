package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTimeToLiveRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveStatus;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

import java.time.Instant;
import java.util.Optional;

/**
 * Service demonstrating DynamoDB Enhanced Client operations for single-table design (BE-14, ARCH §7).
 * Implements atomic conditional write for deduplication (BE-22).
 *
 * Schema khớp infra/template.yaml: PK/SK (hoa), GSI1 (GSI1PK/GSI1SK), TTL trên attr "ttl".
 */
public class DynamoStoreService {

    /**
     * Chỉ dùng cho local/test. Môi trường chạy thật (Lambda) phải truyền tên bảng từ biến môi trường
     * DYNAMODB_TABLE_NAME (xem WebhookHandler). Giá trị theo quy ước askfirst-&lt;stage&gt; của template.
     */
    public static final String DEFAULT_TABLE_NAME = "askfirst-dev";

    public static final String ATTR_PK = "PK";
    public static final String ATTR_SK = "SK";
    public static final String GSI1_NAME = "GSI1";
    public static final String ATTR_GSI1_PK = "GSI1PK";
    public static final String ATTR_GSI1_SK = "GSI1SK";
    public static final String ATTR_TTL = "ttl";

    private static final TableSchema<DedupRecord> DEDUP_SCHEMA = TableSchema.fromBean(DedupRecord.class);

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbClient standardClient;
    private final String tableName;
    private final DynamoDbTable<DedupRecord> dedupTable;

    public DynamoStoreService(DynamoDbEnhancedClient enhancedClient, DynamoDbClient standardClient, String tableName) {
        this.enhancedClient = enhancedClient;
        this.standardClient = standardClient;
        this.tableName = (tableName != null && !tableName.isBlank()) ? tableName : DEFAULT_TABLE_NAME;
        this.dedupTable = enhancedClient.table(this.tableName, DEDUP_SCHEMA);
    }

    public static DynamoStoreService create(DynamoDbClient client, String tableName) {
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(client)
                .build();
        return new DynamoStoreService(enhanced, client, tableName);
    }

    public String getTableName() {
        return tableName;
    }

    public DynamoDbTable<DedupRecord> getDedupTable() {
        return dedupTable;
    }

    /**
     * Creates the single-table DynamoDB table if it does not already exist, then makes sure TTL is enabled.
     * Schema (ARCH §7, BE-24):
     * - PK (String, HASH), SK (String, RANGE)
     * - GSI1: GSI1PK (HASH), GSI1SK (RANGE), projection ALL (vd. WARD_OF#&lt;guardian&gt; -> USER#&lt;elder&gt;)
     * - TTL attribute: ttl
     * - BillingMode: PAY_PER_REQUEST
     */
    public void createTableIfNotExists() {
        try {
            standardClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
        } catch (ResourceNotFoundException e) {
            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            attributeDefinition(ATTR_PK),
                            attributeDefinition(ATTR_SK),
                            attributeDefinition(ATTR_GSI1_PK),
                            attributeDefinition(ATTR_GSI1_SK)
                    )
                    .keySchema(
                            keySchemaElement(ATTR_PK, KeyType.HASH),
                            keySchemaElement(ATTR_SK, KeyType.RANGE)
                    )
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName(GSI1_NAME)
                                    .keySchema(
                                            keySchemaElement(ATTR_GSI1_PK, KeyType.HASH),
                                            keySchemaElement(ATTR_GSI1_SK, KeyType.RANGE)
                                    )
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .build()
                    )
                    .build();

            try {
                standardClient.createTable(request);
            } catch (ResourceInUseException concurrentCreate) {
                // Tiến trình khác vừa tạo bảng cùng lúc -> chỉ cần chờ bảng sẵn sàng
            }
        }

        // Bảng mới tạo có thể chưa ACTIVE; UpdateTimeToLive cần bảng ACTIVE.
        standardClient.waiter().waitUntilTableExists(DescribeTableRequest.builder().tableName(tableName).build());
        ensureTimeToLive();
    }

    private void ensureTimeToLive() {
        TimeToLiveStatus status = standardClient
                .describeTimeToLive(DescribeTimeToLiveRequest.builder().tableName(tableName).build())
                .timeToLiveDescription()
                .timeToLiveStatus();

        if (status == TimeToLiveStatus.DISABLED) {
            standardClient.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                    .tableName(tableName)
                    .timeToLiveSpecification(TimeToLiveSpecification.builder()
                            .attributeName(ATTR_TTL)
                            .enabled(true)
                            .build())
                    .build());
        }
    }

    private static AttributeDefinition attributeDefinition(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }

    private static KeySchemaElement keySchemaElement(String name, KeyType type) {
        return KeySchemaElement.builder()
                .attributeName(name)
                .keyType(type)
                .build();
    }

    /**
     * Atomically tries to acquire a dedup lock using conditional write.
     * Condition: attribute_not_exists(PK).
     *
     * @param updateId Telegram update_id
     * @param ttlEpochSeconds TTL epoch seconds (e.g. now + 86400 for 24h)
     * @return true if write succeeded (first time seen), false if already exists (duplicate!)
     */
    public boolean tryAcquireDedup(String updateId, long ttlEpochSeconds) {
        DedupRecord record = new DedupRecord(
                updateId,
                ttlEpochSeconds,
                Instant.now().toString(),
                "PROCESSED"
        );

        // Tên attribute phân biệt hoa/thường: phải là PK (không phải pk)
        Expression condition = Expression.builder()
                .expression("attribute_not_exists(" + ATTR_PK + ")")
                .build();

        PutItemEnhancedRequest<DedupRecord> request = PutItemEnhancedRequest.builder(DedupRecord.class)
                .item(record)
                .conditionExpression(condition)
                .build();

        try {
            dedupTable.putItem(request);
            return true;
        } catch (ConditionalCheckFailedException e) {
            // Already processed -> duplicate!
            return false;
        }
    }

    /**
     * Retrieves an existing dedup record.
     */
    public Optional<DedupRecord> getDedup(String updateId) {
        Key key = Key.builder()
                .partitionValue("DEDUP#" + updateId)
                .sortValue("METADATA")
                .build();

        DedupRecord record = dedupTable.getItem(key);
        return Optional.ofNullable(record);
    }

    /**
     * Deletes a dedup record (e.g. for cleanup or test teardown).
     */
    public void deleteDedup(String updateId) {
        Key key = Key.builder()
                .partitionValue("DEDUP#" + updateId)
                .sortValue("METADATA")
                .build();

        dedupTable.deleteItem(key);
    }
}
