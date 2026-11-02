package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB item representing an Invite code for guardian linking (BE-24, ARCH §5.4, §7).
 * PK: INVITE#<code>
 * SK: META
 * TTL: 24h
 */
@DynamoDbBean
public class InviteRecord {

    private String pk;
    private String sk = "META";
    private String code;
    private String elderUid;
    private String status = "pending"; // pending, used, expired
    private String usedByUid;
    private Long expiresAt;
    private Long ttl;
    private String createdAt;

    public InviteRecord() {
    }

    public InviteRecord(String code, String elderUid, Long ttlEpochSeconds) {
        this.code = code;
        this.elderUid = elderUid;
        this.pk = code.startsWith("INVITE#") ? code : "INVITE#" + code;
        this.sk = "META";
        this.ttl = ttlEpochSeconds;
        this.expiresAt = ttlEpochSeconds;
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

    @DynamoDbAttribute("code")
    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    @DynamoDbAttribute("elder_uid")
    public String getElderUid() {
        return elderUid;
    }

    public void setElderUid(String elderUid) {
        this.elderUid = elderUid;
    }

    @DynamoDbAttribute("status")
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @DynamoDbAttribute("used_by_uid")
    public String getUsedByUid() {
        return usedByUid;
    }

    public void setUsedByUid(String usedByUid) {
        this.usedByUid = usedByUid;
    }

    @DynamoDbAttribute("expires_at")
    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
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
}
