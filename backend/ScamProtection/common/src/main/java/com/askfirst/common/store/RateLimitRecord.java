package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB item representing Rate limit atomic counter (BE-24, BE-26, ARCH §7).
 * PK: RATE#<uid>#<windowKey> (e.g. RATE#tg:123#2026110216)
 * SK: COUNTER
 * TTL: 48h
 */
@DynamoDbBean
public class RateLimitRecord {

    private String pk;
    private String sk = "COUNTER";
    private String userId;
    private String windowKey;
    private Long count = 0L;
    private Long ttl;
    private String updatedAt;

    public RateLimitRecord() {
    }

    public RateLimitRecord(String userId, String windowKey, Long count, Long ttlEpochSeconds) {
        this.userId = userId;
        this.windowKey = windowKey;
        this.count = count;
        this.ttl = ttlEpochSeconds;
        this.pk = "RATE#" + userId + "#" + windowKey;
        this.sk = "COUNTER";
    }

    @DynamoDbPartitionKey
    @DynamoDbAttribute("pk")
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("sk")
    public String getSk() {
        return sk;
    }

    public void setSk(String sk) {
        this.sk = sk;
    }

    @DynamoDbAttribute("user_id")
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("window_key")
    public String getWindowKey() {
        return windowKey;
    }

    public void setWindowKey(String windowKey) {
        this.windowKey = windowKey;
    }

    @DynamoDbAttribute("count")
    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }

    @DynamoDbAttribute("ttl")
    public Long getTtl() {
        return ttl;
    }

    public void setTtl(Long ttl) {
        this.ttl = ttl;
    }

    @DynamoDbAttribute("updated_at")
    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
