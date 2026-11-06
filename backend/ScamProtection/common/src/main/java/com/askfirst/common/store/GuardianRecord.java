package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB item representing Guardian link between an elder and a relative (BE-24, ARCH §7).
 * PK: USER#<elder_uid>
 * SK: GUARDIAN#<guardian_uid>
 * GSI1PK: WARD_OF#<guardian_uid>   (tra ngược: người thân này đang bảo vệ những ai)
 * GSI1SK: USER#<elder_uid>
 */
@DynamoDbBean
public class GuardianRecord {

    private String pk;
    private String sk;
    private String gsi1Pk;
    private String gsi1Sk;
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
    @DynamoDbAttribute("PK")
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("SK")
    public String getSk() {
        return sk;
    }

    public void setSk(String sk) {
        this.sk = sk;
    }

    private static String stripPrefix(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return value.substring(prefix.length());
            }
        }
        return value;
    }

    /**
     * GSI1PK = WARD_OF#<guardian_uid>. Nếu chưa set tường minh thì suy ra từ guardianUid,
     * để item tạo bằng constructor rỗng + setter vẫn có mặt trong GSI1.
     */
    @DynamoDbSecondaryPartitionKey(indexNames = "GSI1")
    @DynamoDbAttribute("GSI1PK")
    public String getGsi1Pk() {
        if (gsi1Pk != null) {
            return gsi1Pk;
        }
        return guardianUid == null ? null : "WARD_OF#" + stripPrefix(guardianUid, "GUARDIAN#", "USER#");
    }

    public void setGsi1Pk(String gsi1Pk) {
        this.gsi1Pk = gsi1Pk;
    }

    /** GSI1SK = USER#<elder_uid>. */
    @DynamoDbSecondarySortKey(indexNames = "GSI1")
    @DynamoDbAttribute("GSI1SK")
    public String getGsi1Sk() {
        if (gsi1Sk != null) {
            return gsi1Sk;
        }
        return elderUid == null ? null : "USER#" + stripPrefix(elderUid, "USER#");
    }

    public void setGsi1Sk(String gsi1Sk) {
        this.gsi1Sk = gsi1Sk;
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

