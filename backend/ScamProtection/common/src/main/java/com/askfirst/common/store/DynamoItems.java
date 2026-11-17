package com.askfirst.common.store;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Hàm nhỏ dùng chung cho các store gọi DynamoDbClient trực tiếp (cùng kiểu với RateLimitRepo). */
final class DynamoItems {

    private DynamoItems() {
    }

    static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    static Map<String, AttributeValue> key(String pk, String sk) {
        return Map.of(DynamoStoreService.ATTR_PK, s(pk), DynamoStoreService.ATTR_SK, s(sk));
    }

    static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null || v.s() == null ? null : v.s();
    }

    static long num(Map<String, AttributeValue> item, String name, long fallback) {
        AttributeValue v = item.get(name);
        return v == null || v.n() == null ? fallback : Long.parseLong(v.n());
    }
}
