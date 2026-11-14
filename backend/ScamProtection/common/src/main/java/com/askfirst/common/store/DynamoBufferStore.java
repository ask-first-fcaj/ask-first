package com.askfirst.common.store;

import static com.askfirst.common.store.DynamoItems.key;
import static com.askfirst.common.store.DynamoItems.n;
import static com.askfirst.common.store.DynamoItems.s;

import com.askfirst.common.ai.AiCodec;
import com.askfirst.common.channel.model.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;

/** {@link BufferStore} trên bảng single-table. Xem Javadoc của interface về cấu trúc item. */
public final class DynamoBufferStore implements BufferStore {

    private static final String META = "META";
    private static final String MSG_PREFIX = "MSG#";

    private final DynamoDbClient client;
    private final String table;

    public DynamoBufferStore(DynamoDbClient client, String tableName) {
        this.client = client;
        this.table = tableName == null || tableName.isBlank() ? DynamoStoreService.DEFAULT_TABLE_NAME : tableName;
    }

    static String pk(String chatId) {
        return "BUFFER#" + chatId;
    }

    static String msgSk(long seq) {
        return MSG_PREFIX + String.format("%010d", seq);
    }

    @Override
    public long append(String chatId, InboundMessage message, long ttlEpochSeconds) {
        // ADD là nguyên tử: hai webhook song song luôn nhận hai seq khác nhau.
        UpdateItemResponse response = client.updateItem(UpdateItemRequest.builder()
                .tableName(table)
                .key(key(pk(chatId), META))
                .updateExpression("ADD max_seq :one SET done_seq = if_not_exists(done_seq, :zero), #ttl = :ttl")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .expressionAttributeValues(Map.of(":one", n(1), ":zero", n(0), ":ttl", n(ttlEpochSeconds)))
                .returnValues(ReturnValue.UPDATED_NEW)
                .build());
        long seq = Long.parseLong(response.attributes().get("max_seq").n());

        client.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(Map.of(
                        DynamoStoreService.ATTR_PK, s(pk(chatId)),
                        DynamoStoreService.ATTR_SK, s(msgSk(seq)),
                        "payload", s(AiCodec.toJson(message)),
                        "ttl", n(ttlEpochSeconds)))
                .build());
        return seq;
    }

    @Override
    public void setPlaceholder(String chatId, int messageId) {
        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(key(pk(chatId), META))
                    .updateExpression("SET placeholder_id = :id")
                    .conditionExpression("attribute_exists(PK)")
                    .expressionAttributeValues(Map.of(":id", n(messageId)))
                    .build());
        } catch (ConditionalCheckFailedException ignored) {
            // buffer đã bị xóa: không cần placeholder nữa
        }
    }

    @Override
    public Optional<State> state(String chatId) {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key(pk(chatId), META))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = response.item();
        long placeholder = DynamoItems.num(item, "placeholder_id", -1);
        return Optional.of(new State(
                DynamoItems.num(item, "max_seq", 0),
                DynamoItems.num(item, "done_seq", 0),
                placeholder < 0 ? null : (int) placeholder));
    }

    @Override
    public List<InboundMessage> itemsAfter(String chatId, long afterSeq, long upToSeq) {
        List<InboundMessage> result = new ArrayList<>();
        if (upToSeq <= afterSeq) {
            return result;
        }
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(table)
                    .consistentRead(true)
                    .keyConditionExpression("PK = :pk AND SK BETWEEN :lo AND :hi")
                    .expressionAttributeValues(Map.of(
                            ":pk", s(pk(chatId)),
                            ":lo", s(msgSk(afterSeq + 1)),
                            ":hi", s(msgSk(upToSeq))));
            if (startKey != null) {
                request.exclusiveStartKey(startKey);
            }
            QueryResponse page = client.query(request.build());
            for (Map<String, AttributeValue> item : page.items()) {
                result.add(AiCodec.fromJson(item.get("payload").s(), InboundMessage.class));
            }
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return result;
    }

    @Override
    public boolean finish(String chatId, long seq) {
        // Thứ tự quan trọng: xóa MSG (chỉ đến seq) TRƯỚC, đổi META SAU. Tin mới đến giữa chừng mang
        // seq > seq nên không bị xóa nhầm, và META chỉ bị reset khi chắc chắn không còn tin mới.
        deleteMessagesUpTo(chatId, seq);
        try {
            client.deleteItem(DeleteItemRequest.builder()
                    .tableName(table)
                    .key(key(pk(chatId), META))
                    .conditionExpression("attribute_not_exists(PK) OR max_seq = :seq")
                    .expressionAttributeValues(Map.of(":seq", n(seq)))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException newer) {
            try {
                client.updateItem(UpdateItemRequest.builder()
                        .tableName(table)
                        .key(key(pk(chatId), META))
                        .updateExpression("SET done_seq = :seq REMOVE placeholder_id")
                        .conditionExpression("attribute_exists(PK) AND done_seq < :seq")
                        .expressionAttributeValues(Map.of(":seq", n(seq)))
                        .build());
            } catch (ConditionalCheckFailedException alreadyDone) {
                // đã xong từ lần trước: không sao
            }
            return false;
        }
    }

    /** Xóa theo lô, best-effort: item sót sẽ được TTL dọn nên lỗi ở đây không được làm hỏng job. */
    private void deleteMessagesUpTo(String chatId, long seq) {
        try {
            List<WriteRequest> deletes = new ArrayList<>();
            Map<String, AttributeValue> startKey = null;
            do {
                QueryRequest.Builder request = QueryRequest.builder()
                        .tableName(table)
                        .consistentRead(true)
                        .keyConditionExpression("PK = :pk AND SK BETWEEN :lo AND :hi")
                        .projectionExpression("PK, SK")
                        .expressionAttributeValues(Map.of(
                                ":pk", s(pk(chatId)), ":lo", s(msgSk(1)), ":hi", s(msgSk(seq))));
                if (startKey != null) {
                    request.exclusiveStartKey(startKey);
                }
                QueryResponse page = client.query(request.build());
                for (Map<String, AttributeValue> item : page.items()) {
                    deletes.add(WriteRequest.builder()
                            .deleteRequest(DeleteRequest.builder().key(Map.of(
                                    DynamoStoreService.ATTR_PK, item.get(DynamoStoreService.ATTR_PK),
                                    DynamoStoreService.ATTR_SK, item.get(DynamoStoreService.ATTR_SK))).build())
                            .build());
                }
                startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
            } while (startKey != null);

            for (int i = 0; i < deletes.size(); i += 25) {
                List<WriteRequest> chunk = deletes.subList(i, Math.min(i + 25, deletes.size()));
                client.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(Map.of(table, chunk))
                        .build());
            }
        } catch (RuntimeException ignored) {
            // TTL dọn phần còn lại
        }
    }
}
