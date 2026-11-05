package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB item representing Guardian link between an elder and a relative (BE-24, ARCH §7).
 * PK: USER#<elder_uid>
 * SK: GUARDIAN#<guardian_uid>
 */
@DynamoDbBean
public class GuardianRecord {

    private String pk;
    private String sk;
    private String elderUid;
    private String guardianUid;
    private String guardianChatId;
    private String name;
    private String phone;
    private Integer shareLevel = 1; // 1: Risk only, 2: Risk + Scenario, 3: Full masked summary
    private String status = "active"; // pending, active, paused, revoked, unreachable
    private String elderConsentAt;
    private String guardianConsentAt;
    private String createdAt;
    private String updatedAt;

    public GuardianRecord() {
    }

    public GuardianRecord(String elderUid, String guardianUid) {
        this.elderUid = elderUid;
        this.guardianUid = guardianUid;
        this.pk = elderUid.startsWith("USER#") ? elderUid : "USER#" + elderUid;
        this.sk = guardianUid.startsWith("GUARDIAN#") ? guardianUid : "GUARDIAN#" + guardianUid;
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

    @DynamoDbAttribute("elder_uid")
    public String getElderUid() {
        return elderUid;
    }

    public void setElderUid(String elderUid) {
        this.elderUid = elderUid;
    }

    @DynamoDbAttribute("guardian_uid")
    public String getGuardianUid() {
        return guardianUid;
    }

    public void setGuardianUid(String guardianUid) {
        this.guardianUid = guardianUid;
    }

    @DynamoDbAttribute("guardian_chat_id")
    public String getGuardianChatId() {
        return guardianChatId;
    }

    public void setGuardianChatId(String guardianChatId) {
        this.guardianChatId = guardianChatId;
    }

    @DynamoDbAttribute("name")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @DynamoDbAttribute("phone")
    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    @DynamoDbAttribute("share_level")
    public Integer getShareLevel() {
        return shareLevel;
    }

    public void setShareLevel(Integer shareLevel) {
        this.shareLevel = shareLevel;
    }

    @DynamoDbAttribute("status")
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @DynamoDbAttribute("elder_consent_at")
    public String getElderConsentAt() {
        return elderConsentAt;
    }

    public void setElderConsentAt(String elderConsentAt) {
        this.elderConsentAt = elderConsentAt;
    }

    @DynamoDbAttribute("guardian_consent_at")
    public String getGuardianConsentAt() {
        return guardianConsentAt;
    }

    public void setGuardianConsentAt(String guardianConsentAt) {
        this.guardianConsentAt = guardianConsentAt;
    }

    @DynamoDbAttribute("created_at")
    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("updated_at")
    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}

