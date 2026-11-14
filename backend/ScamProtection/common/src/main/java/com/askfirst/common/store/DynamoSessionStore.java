package com.askfirst.common.store;

import static com.askfirst.common.store.DynamoItems.key;
import static com.askfirst.common.store.DynamoItems.n;
import static com.askfirst.common.store.DynamoItems.s;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * PK = SESSION#{chat_id}, SK = META.
 * TTL 30 phút.
 */
public final class DynamoSessionStore implements SessionStore {

    private static final String META = "META";

    private final DynamoDbClient client;
    private final String table;

    public DynamoSessionStore(DynamoDbClient client, String tableName) {
        this.client = client;
        this.table = tableName == null || tableName.isBlank() ? DynamoStoreService.DEFAULT_TABLE_NAME : tableName;
    }

    static String pk(String chatId) {
        return chatId.startsWith("SESSION#") ? chatId : "SESSION#" + chatId;
    }

    @Override
    public Optional<SessionRecord> get(String chatId) {
        GetItemResponse res = client.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key(pk(chatId), META))
                .consistentRead(true)
                .build());
        if (!res.hasItem() || res.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = res.item();
        String checkId = item.get("check_id") != null ? item.get("check_id").s() : "";
        String userId = item.get("user_id") != null ? item.get("user_id").s() : "";
        String maskedText = item.get("masked_text") != null ? item.get("masked_text").s() : "";
        String followup = item.get("followup_question") != null ? item.get("followup_question").s() : null;
        int turn = item.get("turn") != null && item.get("turn").n() != null
                ? Integer.parseInt(item.get("turn").n()) : 1;
        long ttl = item.get("ttl") != null && item.get("ttl").n() != null
                ? Long.parseLong(item.get("ttl").n()) : 0L;

        return Optional.of(new SessionRecord(chatId, checkId, userId, maskedText, followup, turn, ttl));
    }

    @Override
    public void put(SessionRecord r) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.putAll(key(pk(r.chatId()), META));
        item.put("check_id", s(r.checkId()));
        if (r.userId() != null && !r.userId().isBlank()) {
            item.put("user_id", s(r.userId()));
        }
        item.put("masked_text", s(r.maskedText()));
        if (r.followupQuestion() != null && !r.followupQuestion().isBlank()) {
            item.put("followup_question", s(r.followupQuestion()));
        }
        item.put("turn", n(r.turn()));
        item.put("ttl", n(r.ttlEpochSeconds()));

        client.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(item)
                .build());
    }

    @Override
    public void delete(String chatId) {
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(table)
                .key(key(pk(chatId), META))
                .build());
    }
}

