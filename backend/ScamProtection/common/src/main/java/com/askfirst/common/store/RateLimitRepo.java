package com.askfirst.common.store;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Repository managing Rate Limiting counters with DynamoDB atomic ADD (BE-24, BE-26, ARCH §7).
 * Key format:
 * - Hour: RATE#<uid>#<yyyyMMddHH>
 * - Day:  RATE#<uid>#<yyyyMMdd>
 * SK: COUNTER, TTL: 48 hours.
 */
public class RateLimitRepo {

    public static final int DEFAULT_MAX_PER_HOUR = 30;
    public static final int DEFAULT_MAX_PER_DAY = 100;
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(VIETNAM_ZONE);
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(VIETNAM_ZONE);

    private final DynamoDbClient client;
    private final String tableName;

    public record RateLimitResult(boolean allowed, long hourCount, long dayCount, String reason) {}

    protected RateLimitRepo() {
        this.client = null;
        this.tableName = null;
    }

    public RateLimitRepo(DynamoDbClient client, String tableName) {
        this.client = client;
        this.tableName = (tableName != null && !tableName.isBlank()) ? tableName : DynamoStoreService.DEFAULT_TABLE_NAME;
    }

    /**
     * Atomically increments both hourly and daily counters and checks limits.
     * Limit: 30 requests/hour, 100 requests/day.
     */
    public RateLimitResult checkAndIncrement(String userId) {
        return checkAndIncrement(userId, DEFAULT_MAX_PER_HOUR, DEFAULT_MAX_PER_DAY);
    }

    public RateLimitResult checkAndIncrement(String userId, int maxPerHour, int maxPerDay) {
        Instant now = Instant.now();
        String hourWindow = HOUR_FORMAT.format(now);
        String dayWindow = DAY_FORMAT.format(now);
        long ttlEpoch = now.getEpochSecond() + (48 * 3600); // 48h TTL

        long hourCount = incrementCounter("RATE#" + userId + "#" + hourWindow, userId, ttlEpoch);
        long dayCount = incrementCounter("RATE#" + userId + "#" + dayWindow, userId, ttlEpoch);

        if (hourCount > maxPerHour) {
            return new RateLimitResult(false, hourCount, dayCount, "Vượt quá giới hạn " + maxPerHour + " tin/giờ");
        }
        if (dayCount > maxPerDay) {
            return new RateLimitResult(false, hourCount, dayCount, "Vượt quá giới hạn " + maxPerDay + " tin/ngày");
        }

        return new RateLimitResult(true, hourCount, dayCount, "OK");
    }

    private long incrementCounter(String pk, String userId, long ttlEpoch) {
        try {
            UpdateItemRequest request = UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "pk", AttributeValue.builder().s(pk).build(),
                            "sk", AttributeValue.builder().s("COUNTER").build()
                    ))
                    .updateExpression("ADD #cnt :one SET #ttl = if_not_exists(#ttl, :ttlVal), #uid = :userIdVal")
                    .expressionAttributeNames(Map.of(
                            "#cnt", "count",
                            "#ttl", "ttl",
                            "#uid", "user_id"
                    ))
                    .expressionAttributeValues(Map.of(
                            ":one", AttributeValue.builder().n("1").build(),
                            ":ttlVal", AttributeValue.builder().n(String.valueOf(ttlEpoch)).build(),
                            ":userIdVal", AttributeValue.builder().s(userId).build()
                    ))
                    .returnValues(ReturnValue.UPDATED_NEW)
                    .build();

            UpdateItemResponse response = client.updateItem(request);
            if (response.hasAttributes() && response.attributes().containsKey("count")) {
                return Long.parseLong(response.attributes().get("count").n());
            }
            return 1L;
        } catch (Exception e) {
            // Trường hợp lỗi kết nối DB, fallback để không chặn người dùng
            return 1L;
        }
    }
}
