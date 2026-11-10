package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.Optional;

/**
 * Repository managing Single-use Invite Codes for Guardian Linking (BE-24, ARCH §5.4, §7).
 * Single Table Design: PK=INVITE#<code>, SK=META, TTL=24h.
 */
public class InviteRepo {

    private static final TableSchema<InviteRecord> SCHEMA = TableSchema.fromBean(InviteRecord.class);
    private final DynamoDbTable<InviteRecord> table;

    protected InviteRepo() {
        this.table = null;
    }

    public InviteRepo(DynamoDbEnhancedClient enhancedClient, String tableName) {
        this.table = enhancedClient.table(tableName, SCHEMA);
    }

    public void createInvite(String code, String elderUid, long ttlEpochSeconds) {
        InviteRecord record = new InviteRecord(code, elderUid, ttlEpochSeconds);
        record.setCreatedAt(Instant.now().toString());
        record.setStatus("pending");
        table.putItem(record);
    }

    public Optional<InviteRecord> getInvite(String code) {
        String pk = code.startsWith("INVITE#") ? code : "INVITE#" + code;
        Key key = Key.builder().partitionValue(pk).sortValue("META").build();
        return Optional.ofNullable(table.getItem(key));
    }

    /**
     * Atomically marks an invite code as used by a specific guardian.
     * Uses conditional write to guarantee single-use semantics.
     *
     * @return true if successfully consumed, false if already used or expired.
     */
    public boolean markUsed(String code, String usedByUid) {
        Optional<InviteRecord> opt = getInvite(code);
        if (opt.isEmpty()) {
            return false;
        }

        InviteRecord record = opt.get();
        if (!"pending".equals(record.getStatus())) {
            return false;
        }

        // Check expiration
        if (record.getExpiresAt() != null && record.getExpiresAt() < Instant.now().getEpochSecond()) {
            record.setStatus("expired");
            table.putItem(record);
            return false;
        }

        record.setStatus("used");
        record.setUsedByUid(usedByUid);

        Expression condition = Expression.builder()
                .expression("#st = :pending")
                .putExpressionName("#st", "status")
                .putExpressionValue(":pending", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder().s("pending").build())
                .build();

        try {
            table.putItem(PutItemEnhancedRequest.builder(InviteRecord.class)
                    .item(record)
                    .conditionExpression(condition)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    public void deleteInvite(String code) {
        String pk = code.startsWith("INVITE#") ? code : "INVITE#" + code;
        table.deleteItem(Key.builder().partitionValue(pk).sortValue("META").build());
    }
}

