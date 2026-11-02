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
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.time.Instant;
import java.util.Optional;

/**
 * Service demonstrating DynamoDB Enhanced Client operations for single-table design (BE-14, ARCH §7).
 * Implements atomic conditional write for deduplication (BE-22).
 */
public class DynamoStoreService {

    public static final String DEFAULT_TABLE_NAME = "askfirst-main";
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
     * Creates the single-table DynamoDB table if it does not already exist.
     * Schema: PK (String, HASH), SK (String, RANGE), BillingMode: PAY_PER_REQUEST.
     */
    public void createTableIfNotExists() {
        try {
            standardClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
        } catch (ResourceNotFoundException e) {
            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("pk")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("sk")
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("pk")
                                    .keyType(KeyType.HASH)
                                    .build(),
                            KeySchemaElement.builder()
                                    .attributeName("sk")
                                    .keyType(KeyType.RANGE)
                                    .build()
                    )
                    .build();

            standardClient.createTable(request);
        }
    }

    /**
     * Atomically tries to acquire a dedup lock using conditional write.
     * Condition: attribute_not_exists(pk).
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

        Expression condition = Expression.builder()
                .expression("attribute_not_exists(pk)")
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

