package com.askfirst.common.store;

import static com.askfirst.common.store.DynamoItems.key;

import com.askfirst.common.ai.AiContract.Flags;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

/** Đọc CONFIG#global / FLAGS. Không cache ở đây: cache 30 giây do tầng gọi đảm nhiệm. */
public final class DynamoFlagsStore implements FlagsStore {

    private final DynamoDbClient client;
    private final String table;

    public DynamoFlagsStore(DynamoDbClient client, String tableName) {
        this.client = client;
        this.table = tableName == null || tableName.isBlank() ? DynamoStoreService.DEFAULT_TABLE_NAME : tableName;
    }

    @Override
    public Flags load() {
        GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(key("CONFIG#global", "FLAGS"))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return DEFAULTS;
        }
        Map<String, AttributeValue> item = response.item();
        return new Flags(
                bool(item, "llm_enabled", DEFAULTS.llmEnabled()),
                bool(item, "feature_image", DEFAULTS.featureImage()),
                bool(item, "feature_voice", DEFAULTS.featureVoice()),
                bool(item, "feature_rag", DEFAULTS.featureRag()));
    }

    private static boolean bool(Map<String, AttributeValue> item, String name, boolean fallback) {
        AttributeValue v = item.get(name);
        return v == null || v.bool() == null ? fallback : v.bool();
    }
}
