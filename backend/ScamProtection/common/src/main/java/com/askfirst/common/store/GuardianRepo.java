package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Repository managing Guardian linkages between elders and relatives (BE-24, ARCH §5.4, §7).
 * Single Table Design: PK=USER#<elder_uid>, SK=GUARDIAN#<guardian_uid>.
 * GSI1: GSI1PK=WARD_OF#<guardian_uid>, GSI1SK=USER#<elder_uid> (được GuardianRecord tự điền khi ghi).
 *
 * Các thao tác cập nhật dùng khóa lạc quan (xem {@link OptimisticUpdates}) nên không đè nhau khi chạy song song.
 */
public class GuardianRepo {

    private static final TableSchema<GuardianRecord> SCHEMA = TableSchema.fromBean(GuardianRecord.class);
    private final DynamoDbTable<GuardianRecord> table;

    protected GuardianRepo() {
        this.table = null;
    }

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
        update(elderUid, guardianUid, g -> g.setStatus(status));
    }

    public void updateShareLevel(String elderUid, String guardianUid, int shareLevel) {
        update(elderUid, guardianUid, g -> g.setShareLevel(shareLevel));
    }

    public void delete(String elderUid, String guardianUid) {
        String pk = elderUid.startsWith("USER#") ? elderUid : "USER#" + elderUid;
        String sk = guardianUid.startsWith("GUARDIAN#") ? guardianUid : "GUARDIAN#" + guardianUid;
        table.deleteItem(Key.builder().partitionValue(pk).sortValue(sk).build());
    }

    /**
     * Đọc-sửa-ghi có khóa lạc quan; chưa có liên kết thì bỏ qua như trước đây.
     */
    private void update(String elderUid, String guardianUid, Consumer<GuardianRecord> change) {
        for (int attempt = 1; ; attempt++) {
            Optional<GuardianRecord> current = getGuardian(elderUid, guardianUid);
            if (current.isEmpty()) {
                return;
            }

            GuardianRecord guardian = current.get();
            String previousUpdatedAt = guardian.getUpdatedAt();
            change.accept(guardian);
            guardian.setUpdatedAt(OptimisticUpdates.nextTimestamp(previousUpdatedAt));

            try {
                table.putItem(PutItemEnhancedRequest.builder(GuardianRecord.class)
                        .item(guardian)
                        .conditionExpression(OptimisticUpdates.ifUnchanged(previousUpdatedAt))
                        .build());
                return;
            } catch (ConditionalCheckFailedException conflict) {
                if (attempt >= OptimisticUpdates.MAX_ATTEMPTS) {
                    throw conflict;
                }
                // Có request khác vừa sửa/xóa liên kết này: đọc lại rồi thử lại
            }
        }
    }
}
