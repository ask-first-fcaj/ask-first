package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Repository managing Legal Audit Trail for ND 356 compliance (BE-24, BE-25, ARCH §7).
 * Single Table Design: PK=USER#<uid>, SK=AUDIT#<timestamp>#<event>.
 *
 * Audit là bằng chứng đồng ý nên KHÔNG BAO GIỜ được ghi đè: mọi lần ghi dùng attribute_not_exists(PK).
 */
public class AuditRepo {

    private static final int MAX_WRITE_ATTEMPTS = 5;

    private static final TableSchema<AuditRecord> SCHEMA = TableSchema.fromBean(AuditRecord.class);
    private final DynamoDbTable<AuditRecord> table;

    protected AuditRepo() {
        this.table = null;
    }

    public AuditRepo(DynamoDbEnhancedClient enhancedClient, String tableName) {
        this.table = enhancedClient != null ? enhancedClient.table(tableName, SCHEMA) : null;
    }

    /**
     * Ghi một audit event. Nếu SK (AUDIT#timestamp#event) trùng với event đã có - hai event cùng loại
     * trong cùng một thời điểm - thì thêm hậu tố ngẫu nhiên vào SK thay vì ghi đè event cũ.
     */
    public void recordEvent(String userId, String event, String policyVersion, String policySha256, String details) {
        String timestamp = Instant.now().toString();

        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            AuditRecord record = new AuditRecord(userId, timestamp, event);
            if (attempt > 1) {
                record.setSk(record.getSk() + "#" + UUID.randomUUID().toString().substring(0, 8));
            }
            record.setPolicyVersion(policyVersion);
            record.setPolicySha256(policySha256);
            record.setDetails(details);

            try {
                table.putItem(PutItemEnhancedRequest.builder(AuditRecord.class)
                        .item(record)
                        .conditionExpression(OptimisticUpdates.ifAbsent())
                        .build());
                return;
            } catch (ConditionalCheckFailedException duplicateSortKey) {
                // SK đã tồn tại -> thử lại với hậu tố để giữ cả hai event
            }
        }
        throw new IllegalStateException("Không ghi được audit event " + event + ": trùng khóa liên tiếp");
    }

    public List<AuditRecord> listAuditTrail(String userId) {
        String pk = userId.startsWith("USER#") ? userId : "USER#" + userId;
        QueryConditional conditional = QueryConditional.sortBeginsWith(
                Key.builder().partitionValue(pk).sortValue("AUDIT#").build()
        );

        List<AuditRecord> list = new ArrayList<>();
        if (table != null) {
            table.query(QueryEnhancedRequest.builder().queryConditional(conditional).build())
                    .items()
                    .forEach(list::add);
        }
        return list;
    }

    public void deleteAuditTrail(String userId) {
        if (table == null || userId == null) {
            return;
        }
        for (AuditRecord rec : listAuditTrail(userId)) {
            table.deleteItem(rec);
        }
    }

    public static String hashUid(String uid) {
        if (uid == null) {
            uid = "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(uid.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public void recordDataDeleted(String userId, String policyVersion, String policySha256) {
        String hash = hashUid(userId);
        String today = LocalDate.now().toString();
        String details = "Tài khoản [" + hash + "] đã yêu cầu xoá dữ liệu vào ngày " + today;
        recordEvent(userId, "DATA_DELETED", policyVersion, policySha256, details);
    }
}
