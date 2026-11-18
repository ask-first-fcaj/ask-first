package com.askfirst.common.store;

import static com.askfirst.common.store.DynamoItems.key;
import static com.askfirst.common.store.DynamoItems.n;
import static com.askfirst.common.store.DynamoItems.s;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/** PK = ALERT#<alert_id>, SK = META. "status" là từ khóa của DynamoDB nên luôn dùng alias #st. */
public final class DynamoAlertStore implements AlertStore {

    private static final String META = "META";

    private final DynamoDbClient client;
    private final String table;

    public DynamoAlertStore(DynamoDbClient client, String tableName) {
        this.client = client;
        this.table = tableName == null || tableName.isBlank() ? DynamoStoreService.DEFAULT_TABLE_NAME : tableName;
    }

    static String pk(String alertId) {
        return "ALERT#" + alertId;
    }

    @Override
    public AlertRecord reserve(AlertRecord p) {
        Map<String, AttributeValue> item = new HashMap<>(key(pk(p.alertId()), META));
        item.put("alert_id", s(p.alertId()));
        item.put("elder_uid", s(p.elderUid()));
        item.put("elder_chat_id", s(p.elderChatId()));
        item.put("check_id", s(p.checkId()));
        item.put("guardian_uid", s(p.guardianUid()));
        item.put("guardian_chat_id", s(p.guardianChatId()));
        item.put("guardian_name", s(p.guardianName() == null ? "" : p.guardianName()));
        item.put("share_level", n(p.shareLevel()));
        item.put("status", s(p.status()));
        item.put("ttl", n(p.ttlEpochSeconds()));
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(item)
                    .conditionExpression("attribute_not_exists(PK)")
                    .build());
            return p;
        } catch (ConditionalCheckFailedException exists) {
            return get(p.alertId()).orElse(p);
        }
    }

    @Override
    public Optional<AlertRecord> get(String alertId) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key(pk(alertId), META))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> i = response.item();
        long message = DynamoItems.num(i, "message_id", -1);
        return Optional.of(new AlertRecord(
                DynamoItems.str(i, "alert_id"),
                DynamoItems.str(i, "elder_uid"),
                DynamoItems.str(i, "elder_chat_id"),
                DynamoItems.str(i, "check_id"),
                DynamoItems.str(i, "guardian_uid"),
                DynamoItems.str(i, "guardian_chat_id"),
                DynamoItems.str(i, "guardian_name"),
                (int) DynamoItems.num(i, "share_level", 1),
                DynamoItems.str(i, "status"),
                message < 0 ? null : (int) message,
                DynamoItems.str(i, "acked_at"),
                DynamoItems.str(i, "reminded_at"),
                DynamoItems.num(i, "ttl", 0)));
    }

    @Override
    public void markSent(String alertId, int messageId) {
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(key(pk(alertId), META))
                    .updateExpression("SET #st = :sent, message_id = :m")
                    .conditionExpression("attribute_exists(PK) AND #st <> :acked")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(
                            ":sent", s(AlertRecord.SENT), ":acked", s(AlertRecord.ACKED), ":m", n(messageId)))
                    .build());
        } catch (ConditionalCheckFailedException ignored) {
            // đã acked hoặc không còn: giữ nguyên
        }
    }

    @Override
    public void markFailed(String alertId) {
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(key(pk(alertId), META))
                    .updateExpression("SET #st = :failed")
                    .conditionExpression("#st = :pending")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(
                            ":failed", s(AlertRecord.FAILED), ":pending", s(AlertRecord.PENDING)))
                    .build());
        } catch (ConditionalCheckFailedException ignored) {
            // không còn pending: giữ nguyên
        }
    }

    @Override
    public boolean markAcked(String alertId, Instant at) {
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(key(pk(alertId), META))
                    .updateExpression("SET #st = :acked, acked_at = if_not_exists(acked_at, :at)")
                    .conditionExpression("attribute_exists(PK)")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(":acked", s(AlertRecord.ACKED), ":at", s(at.toString())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException notFound) {
            return false;
        }
    }

    @Override
    public boolean markReminded(String alertId, Instant at) {
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(key(pk(alertId), META))
                    .updateExpression("SET reminded_at = :at")
                    .conditionExpression("attribute_exists(PK) AND attribute_not_exists(reminded_at)")
                    .expressionAttributeValues(Map.of(":at", s(at.toString())))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException alreadyReminded) {
            return false;
        }
    }
}
