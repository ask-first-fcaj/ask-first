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
import java.util.Optional;

/**
 * Repository managing Guardian linkages between elders and relatives (BE-24, ARCH §5.4, §7).
 * Single Table Design: PK=USER#<elder_uid>, SK=GUARDIAN#<guardian_uid>.
 */
public class GuardianRepo {

    private static final TableSchema<GuardianRecord> SCHEMA = TableSchema.fromBean(GuardianRecord.class);
    private final DynamoDbTable<GuardianRecord> table;

    public GuardianRepo(DynamoDbEnhancedClient enhancedClient, String tableName) {
        this.table = enhancedClient.table(tableName, SCHEMA);
    }

    public void save(GuardianRecord record) {
        String now = Instant.now().toString();
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(now);
        }
        record.setUpdatedAt(now);
        table.putItem(record);
    }

    public Optional<GuardianRecord> getGuardian(String elderUid, String guardianUid) {
        String pk = elderUid.startsWith("USER#") ? elderUid : "USER#" + elderUid;
        String sk = guardianUid.startsWith("GUARDIAN#") ? guardianUid : "GUARDIAN#" + guardianUid;
        Key key = Key.builder().partitionValue(pk).sortValue(sk).build();
        return Optional.ofNullable(table.getItem(key));
    }

    public List<GuardianRecord> listGuardians(String elderUid) {
        String pk = elderUid.startsWith("USER#") ? elderUid : "USER#" + elderUid;
        QueryConditional queryConditional = QueryConditional.sortBeginsWith(
                Key.builder().partitionValue(pk).sortValue("GUARDIAN#").build()
        );

        List<GuardianRecord> results = new ArrayList<>();
        table.query(QueryEnhancedRequest.builder().queryConditional(queryConditional).build())
                .items()
                .forEach(results::add);
        return results;
    }

    public void updateStatus(String elderUid, String guardianUid, String status) {
        getGuardian(elderUid, guardianUid).ifPresent(g -> {
            g.setStatus(status);
            g.setUpdatedAt(Instant.now().toString());
            table.putItem(g);
        });
    }

    public void updateShareLevel(String elderUid, String guardianUid, int shareLevel) {
        getGuardian(elderUid, guardianUid).ifPresent(g -> {
            g.setShareLevel(shareLevel);
            g.setUpdatedAt(Instant.now().toString());
            table.putItem(g);
        });
    }

    public void delete(String elderUid, String guardianUid) {
        String pk = elderUid.startsWith("USER#") ? elderUid : "USER#" + elderUid;
        String sk = guardianUid.startsWith("GUARDIAN#") ? guardianUid : "GUARDIAN#" + guardianUid;
        table.deleteItem(Key.builder().partitionValue(pk).sortValue(sk).build());
    }
}
