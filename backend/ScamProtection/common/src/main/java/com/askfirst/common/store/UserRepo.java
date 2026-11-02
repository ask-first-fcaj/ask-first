package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Repository managing User Profiles and Consents (BE-24, BE-25).
 * Single Table Design: PK=USER#<uid>, SK=PROFILE.
 */
public class UserRepo {

    private static final TableSchema<UserRecord> SCHEMA = TableSchema.fromBean(UserRecord.class);
    private final DynamoDbTable<UserRecord> table;

    protected UserRepo() {
        this.table = null;
    }

    public UserRepo(DynamoDbEnhancedClient enhancedClient, String tableName) {
        this.table = enhancedClient != null ? enhancedClient.table(tableName, SCHEMA) : null;
    }

    public Optional<UserRecord> getUser(String userId) {
        String pk = userId.startsWith("USER#") ? userId : "USER#" + userId;
        Key key = Key.builder().partitionValue(pk).sortValue("PROFILE").build();
        UserRecord user = table.getItem(key);
        return Optional.ofNullable(user);
    }

    public void saveUser(UserRecord user) {
        String now = Instant.now().toString();
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(now);
        }
        user.setUpdatedAt(now);
        table.putItem(user);
    }

    public UserRecord getOrCreateUser(String userId, String chatId) {
        return getUser(userId).orElseGet(() -> {
            UserRecord newUser = new UserRecord(userId, chatId);
            newUser.setCreatedAt(Instant.now().toString());
            newUser.setUpdatedAt(newUser.getCreatedAt());
            table.putItem(newUser);
            return newUser;
        });
    }

    public void updateConsent(String userId, String policySha256, String version, Map<String, Boolean> purposes) {
        UserRecord user = getUser(userId).orElse(new UserRecord(userId, null));
        String now = Instant.now().toString();
        user.setPolicySha256(policySha256);
        user.setConsentVersion(version);
        user.setConsentedAt(now);
        user.setPurposes(purposes);
        user.setUpdatedAt(now);
        table.putItem(user);
    }

    public void updateAddressForm(String userId, String addressForm) {
        UserRecord user = getUser(userId).orElse(new UserRecord(userId, null));
        user.setAddressForm(addressForm);
        user.setUpdatedAt(Instant.now().toString());
        table.putItem(user);
    }

    /**
     * Checks if user has given active consent matching the current policy SHA256.
     * According to ND 356: unconsented or outdated policy versions MUST NOT process user content.
     */
    public boolean hasValidConsent(String userId, String requiredPolicySha256) {
        Optional<UserRecord> opt = getUser(userId);
        if (opt.isEmpty()) {
            return false;
        }
        UserRecord user = opt.get();
        if (user.getConsentedAt() == null || user.getPolicySha256() == null) {
            return false;
        }
        if (requiredPolicySha256 != null && !requiredPolicySha256.isBlank()) {
            if (!requiredPolicySha256.equals(user.getPolicySha256())) {
                return false;
            }
        }
        Map<String, Boolean> p = user.getPurposes();
        return p != null && Boolean.TRUE.equals(p.get("analyze"));
    }

    /**
     * Checks if user has specifically consented to voice processing (ND 356 purpose-based consent).
     */
    public boolean hasVoiceConsent(String userId) {
        Optional<UserRecord> opt = getUser(userId);
        if (opt.isEmpty()) {
            return false;
        }
        Map<String, Boolean> p = opt.get().getPurposes();
        return p != null && Boolean.TRUE.equals(p.get("voice"));
    }
}
