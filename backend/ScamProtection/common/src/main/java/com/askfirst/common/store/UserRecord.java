package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.util.HashMap;
import java.util.Map;

/**
 * DynamoDB item representing User Profile & Consent (BE-24, BE-25, ARCH §7).
 * PK: USER#<uid> (e.g. USER#tg:12345678)
 * SK: PROFILE
 */
@DynamoDbBean
public class UserRecord {

    public static final String DEFAULT_ADDRESS_FORM = "Bác";

    private String pk;
    private String sk = "PROFILE";
    private String userId;
    private String channel = "telegram";
    private String chatId;
    private String role = "elder";          // elder | guardian | both
    private String addressForm = DEFAULT_ADDRESS_FORM; // Bác | Cô | Chú
    private String status = "active";
    private String consentVersion;
    private String policySha256;
    private String consentedAt;
    private Map<String, Boolean> purposes = new HashMap<>(); // analyze, guardian, voice, donate
    private String createdAt;
    private String updatedAt;

    public UserRecord() {
    }

    public UserRecord(String userId, String chatId) {
        this.userId = userId;
        this.pk = userId.startsWith("USER#") ? userId : "USER#" + userId;
        this.sk = "PROFILE";
        this.chatId = chatId;
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

    @DynamoDbAttribute("channel")
    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    @DynamoDbAttribute("chat_id")
    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    @DynamoDbAttribute("role")
    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    @DynamoDbAttribute("address_form")
    public String getAddressForm() {
        return addressForm;
    }

    public void setAddressForm(String addressForm) {
        this.addressForm = addressForm;
    }

    @DynamoDbAttribute("status")
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @DynamoDbAttribute("consent_version")
    public String getConsentVersion() {
        return consentVersion;
    }

    public void setConsentVersion(String consentVersion) {
        this.consentVersion = consentVersion;
    }

    @DynamoDbAttribute("policy_sha256")
    public String getPolicySha256() {
        return policySha256;
    }

    public void setPolicySha256(String policySha256) {
        this.policySha256 = policySha256;
    }

    @DynamoDbAttribute("consented_at")
    public String getConsentedAt() {
        return consentedAt;
    }

    public void setConsentedAt(String consentedAt) {
        this.consentedAt = consentedAt;
    }

    @DynamoDbAttribute("purposes")
    public Map<String, Boolean> getPurposes() {
        return purposes;
    }

    public void setPurposes(Map<String, Boolean> purposes) {
        this.purposes = purposes != null ? purposes : new HashMap<>();
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

