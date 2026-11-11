package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;

/**
 * Tiện ích khóa lạc quan (optimistic locking) dùng chung cho các repo đọc-sửa-ghi (BE-24).
 *
 * Dùng cột updated_at làm "phiên bản": chỉ ghi nếu item vẫn đúng như lúc đọc. Nếu request song song
 * đã ghi trước, ConditionalCheckFailedException được ném ra và repo đọc lại rồi áp thay đổi lên bản mới nhất,
 * nhờ vậy hai request song song không còn đè mất thay đổi của nhau.
 */
final class OptimisticUpdates {

    /** Số lần đọc-sửa-ghi lại tối đa khi bị request khác ghi chen vào. */
    static final int MAX_ATTEMPTS = 5;

    private OptimisticUpdates() {
    }

    /** Điều kiện "item chưa tồn tại" (tạo mới, không ghi đè). */
    static Expression ifAbsent() {
        return Expression.builder()
                .expression("attribute_not_exists(" + DynamoStoreService.ATTR_PK + ")")
                .build();
    }

    /** Điều kiện "item chưa bị ai sửa kể từ lần đọc" (so khớp updated_at). */
    static Expression ifUnchanged(String previousUpdatedAt) {
        if (previousUpdatedAt == null) {
            return Expression.builder().expression("attribute_not_exists(updated_at)").build();
        }
        return Expression.builder()
                .expression("updated_at = :prev")
                .expressionValues(Map.of(":prev", AttributeValue.builder().s(previousUpdatedAt).build()))
                .build();
    }

    /** Timestamp mới, luôn khác giá trị cũ để điều kiện ifUnchanged có ý nghĩa. */
    static String nextTimestamp(String previous) {
        Instant now = Instant.now();
        String candidate = now.toString();
        if (candidate.equals(previous)) {
            candidate = now.plusNanos(1_000).toString();
        }
        return candidate;
    }
}
