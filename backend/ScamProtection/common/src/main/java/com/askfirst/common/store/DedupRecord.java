package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB item representing an idempotency / dedup record (BE-14, BE-22, ARCH §7).
 * PK: DEDUP#<update_id>
 * SK: METADATA
 * TTL: epoch seconds (auto-deleted by DynamoDB after 24h)
 */
@DynamoDbBean
public class DedupRecord {

    private String pk;
    private String sk;
    private Long ttl;
    private String createdAt;
    private String status;

    public DedupRecord() {
    }

    public DedupRecord(String updateId, Long ttlEpochSeconds, String createdAt, String status) {
        this.pk = "DEDUP#" + updateId;
        this.sk = "METADATA";
        this.ttl = ttlEpochSeconds;
        this.createdAt = createdAt;
        this.status = status;
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

    @DynamoDbAttribute("ttl")
    public Long getTtl() {
        return ttl;
    }

    public void setTtl(Long ttl) {
        this.ttl = ttl;
    }

    @DynamoDbAttribute("created_at")
    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("status")
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

