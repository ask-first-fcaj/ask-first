package com.askfirst.common.store;

import static com.askfirst.common.store.DynamoItems.key;
import static com.askfirst.common.store.DynamoItems.n;
import static com.askfirst.common.store.DynamoItems.s;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/** PK = USER#<uid> (cùng quy ước UserRecord), SK = CHECK#<check_id>. */
public final class DynamoCheckStore implements CheckRepo {

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
    public Optional<CheckRecord> get(String userId, String checkId) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key(pk(userId), "CHECK#" + checkId))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = response.item();
        String outcome = item.get("outcome") != null ? item.get("outcome").s() : "";
        String level = item.get("level") != null ? item.get("level").s() : "";
        int score = item.get("score") != null && item.get("score").n() != null
                ? Integer.parseInt(item.get("score").n()) : 0;
        String grp = item.get("grp") != null ? item.get("grp").s() : "";
        String scenario = item.get("scenario") != null ? item.get("scenario").s() : "";
        String path = item.get("path") != null ? item.get("path").s() : "";
        List<String> signalCodes = item.get("signal_codes") != null && item.get("signal_codes").hasL()
                ? item.get("signal_codes").l().stream().map(AttributeValue::s).toList() : List.of();
        List<String> riskyActions = item.get("risky_actions") != null && item.get("risky_actions").hasL()
                ? item.get("risky_actions").l().stream().map(AttributeValue::s).toList() : List.of();
        List<String> inputKinds = item.get("input_kinds") != null && item.get("input_kinds").hasL()
                ? item.get("input_kinds").l().stream().map(AttributeValue::s).toList() : List.of();
        boolean alerted = item.get("alerted") != null && Boolean.TRUE.equals(item.get("alerted").bool());
        String feedback = item.get("feedback") != null ? item.get("feedback").s() : null;
        String createdAt = item.get("created_at") != null ? item.get("created_at").s() : "";
        long ttl = item.get("ttl") != null && item.get("ttl").n() != null
                ? Long.parseLong(item.get("ttl").n()) : 0L;

        return Optional.of(new CheckRecord(checkId, userId, outcome, level, score, grp, scenario, path,
                signalCodes, riskyActions, inputKinds, alerted, feedback, createdAt, ttl));
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
        if (r.feedback() != null && !r.feedback().isBlank()) {
            item.put("feedback", s(r.feedback()));
        }
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

    @Override
    public void updateFeedback(String userId, String checkId, String feedback) {
        client.updateItem(UpdateItemRequest.builder()
                .tableName(table)
                .key(key(pk(userId), "CHECK#" + checkId))
                .updateExpression("SET feedback = :fb")
                .expressionAttributeValues(Map.of(":fb", s(feedback)))
                .build());
    }

    private static AttributeValue strings(List<String> values) {
        return AttributeValue.builder().l(values.stream().map(DynamoItems::s).toList()).build();
    }
}
