package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository managing Legal Audit Trail for ND 356 compliance (BE-24, BE-25, ARCH §7).
 * Single Table Design: PK=USER#<uid>, SK=AUDIT#<timestamp>#<event>.
 */
public class AuditRepo {

    private static final TableSchema<AuditRecord> SCHEMA = TableSchema.fromBean(AuditRecord.class);
    private final DynamoDbTable<AuditRecord> table;

    protected AuditRepo() {
        this.table = null;
    }

    public AuditRepo(DynamoDbEnhancedClient enhancedClient, String tableName) {
        this.table = enhancedClient != null ? enhancedClient.table(tableName, SCHEMA) : null;
    }

    public void recordEvent(String userId, String event, String policyVersion, String policySha256, String details) {
        String timestamp = Instant.now().toString();
        AuditRecord record = new AuditRecord(userId, timestamp, event);
        record.setPolicyVersion(policyVersion);
        record.setPolicySha256(policySha256);
        record.setDetails(details);
        table.putItem(record);
    }

    public List<AuditRecord> listAuditTrail(String userId) {
        String pk = userId.startsWith("USER#") ? userId : "USER#" + userId;
        QueryConditional conditional = QueryConditional.sortBeginsWith(
                Key.builder().partitionValue(pk).sortValue("AUDIT#").build()
        );

        List<AuditRecord> list = new ArrayList<>();
        table.query(QueryEnhancedRequest.builder().queryConditional(conditional).build())
                .items()
                .forEach(list::add);
        return list;
    }
}
