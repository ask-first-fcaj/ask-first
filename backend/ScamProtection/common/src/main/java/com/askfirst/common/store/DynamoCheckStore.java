package com.askfirst.common.store;

import static com.askfirst.common.store.DynamoItems.key;
import static com.askfirst.common.store.DynamoItems.n;
import static com.askfirst.common.store.DynamoItems.s;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/** PK = USER#<uid> (cùng quy ước UserRecord), SK = CHECK#<check_id>. */
public final class DynamoCheckStore implements CheckStore {

    private final DynamoDbClient client;
    private final String table;

    public DynamoCheckStore(DynamoDbClient client, String tableName) {
        this.client = client;
        this.table = tableName == null || tableName.isBlank() ? DynamoStoreService.DEFAULT_TABLE_NAME : tableName;
    }

    static String pk(String userId) {
        return userId.startsWith("USER#") ? userId : "USER#" + userId;
    }

    @Override
    public boolean exists(String userId, String checkId) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key(pk(userId), "CHECK#" + checkId))
                .consistentRead(true)
                .projectionExpression("PK")
                .build());
        return response.hasItem() && !response.item().isEmpty();
    }

    @Override
    public void put(CheckRecord r) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.putAll(key(pk(r.userId()), "CHECK#" + r.checkId()));
        item.put("check_id", s(r.checkId()));
        item.put("user_id", s(r.userId()));
        item.put("outcome", s(r.outcome()));
        item.put("level", s(r.level()));
        item.put("score", n(r.score()));
        item.put("grp", s(r.group()));
        item.put("scenario", s(r.scenario()));
        item.put("path", s(r.path()));
        item.put("signal_codes", strings(r.signalCodes()));
        item.put("risky_actions", strings(r.riskyActions()));
        item.put("input_kinds", strings(r.inputKinds()));
        item.put("alerted", AttributeValue.builder().bool(r.alerted()).build());
        item.put("created_at", s(r.createdAt()));
        item.put("ttl", n(r.ttlEpochSeconds()));
        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(item)
                    .conditionExpression("attribute_not_exists(PK)")
                    .build());
        } catch (ConditionalCheckFailedException alreadyThere) {
            // ghi lại cùng check_id: giữ bản đầu
        }
    }

    private static AttributeValue strings(List<String> values) {
        return AttributeValue.builder().l(values.stream().map(DynamoItems::s).toList()).build();
    }
}
