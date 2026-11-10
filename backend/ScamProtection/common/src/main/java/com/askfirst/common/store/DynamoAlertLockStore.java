package com.askfirst.common.store;

import static com.askfirst.common.store.DynamoItems.key;
import static com.askfirst.common.store.DynamoItems.n;
import static com.askfirst.common.store.DynamoItems.s;

import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/** PK = ALERTLOCK#<uid>#<scenario>, SK = META. */
public final class DynamoAlertLockStore implements AlertLockStore {

    private final DynamoDbClient client;
    private final String table;

    public DynamoAlertLockStore(DynamoDbClient client, String tableName) {
        this.client = client;
        this.table = tableName == null || tableName.isBlank() ? DynamoStoreService.DEFAULT_TABLE_NAME : tableName;
    }

    @Override
    public boolean acquire(String userId, String scenario, String owner, long nowEpochSeconds, long ttlEpochSeconds) {
        Map<String, AttributeValue> item = new HashMap<>(key("ALERTLOCK#" + userId + "#" + scenario, "META"));
        item.put("owner", s(owner));
        item.put("ttl", n(ttlEpochSeconds));
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(item)
                    // "ttl < :now": TTL của DynamoDB xóa item trễ (có thể hàng giờ), nên khóa hết hạn vẫn nằm đó.
                    .conditionExpression("attribute_not_exists(PK) OR #o = :owner OR #ttl < :now")
                    .expressionAttributeNames(Map.of("#o", "owner", "#ttl", "ttl"))
                    .expressionAttributeValues(Map.of(":owner", s(owner), ":now", n(nowEpochSeconds)))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException heldByOther) {
            return false;
        }
    }
}
