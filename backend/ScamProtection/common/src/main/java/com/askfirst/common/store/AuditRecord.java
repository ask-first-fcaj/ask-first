package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB item representing an Audit log entry for legal compliance (BE-24, BE-25, ND 356, ARCH §7).
 * PK: USER#<uid>
 * SK: AUDIT#<timestamp>#<event>
 */
@DynamoDbBean
public class AuditRecord {

    private String pk;
    private String sk;
    private String userId;
    private String event;
    private String policyVersion;
    private String policySha256;
    private String details;
    private String timestamp;

    public AuditRecord() {
    }

    public AuditRecord(String userId, String timestamp, String event) {
        this.userId = userId;
        this.timestamp = timestamp;
        this.event = event;
        this.pk = userId.startsWith("USER#") ? userId : "USER#" + userId;
        this.sk = "AUDIT#" + timestamp + "#" + event;
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

    @DynamoDbAttribute("event")
    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    @DynamoDbAttribute("policy_version")
    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    @DynamoDbAttribute("policy_sha256")
    public String getPolicySha256() {
        return policySha256;
    }

    public void setPolicySha256(String policySha256) {
        this.policySha256 = policySha256;
    }

    @DynamoDbAttribute("details")
    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    @DynamoDbAttribute("timestamp")
    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
