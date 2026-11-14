package com.askfirst.common.store;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Repository managing User Profiles and Consents (BE-24, BE-25).
 * Single Table Design: PK=USER#<uid>, SK=PROFILE.
 *
 * Mọi thao tác cập nhật đều đọc-sửa-ghi có khóa lạc quan (xem
 * {@link OptimisticUpdates}),
 * tạo mới dùng điều kiện attribute_not_exists(PK) nên request song song không
 * đè dữ liệu của nhau.
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

    public void deleteUser(String userId) {
        if (table == null || userId == null) {
            return;
        }
        String pk = userId.startsWith("USER#") ? userId : "USER#" + userId;
        Key key = Key.builder().partitionValue(pk).sortValue("PROFILE").build();
        table.deleteItem(key);
    }

    /**
     * Lấy user, chưa có thì tạo. Tạo bằng conditional put nên hai request đầu tiên
     * song song
     * không ghi đè nhau: bên thua sẽ đọc lại bản của bên thắng.
     */
    public UserRecord getOrCreateUser(String userId, String chatId) {
        Optional<UserRecord> existing = getUser(userId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UserRecord newUser = new UserRecord(userId, chatId);
        newUser.setCreatedAt(Instant.now().toString());
        newUser.setUpdatedAt(newUser.getCreatedAt());
        try {
            putWithCondition(newUser, OptimisticUpdates.ifAbsent());
            return newUser;
        } catch (ConditionalCheckFailedException lostRace) {
            return getUser(userId).orElseThrow(
                    () -> new IllegalStateException("User vừa được tạo nhưng không đọc lại được", lostRace));
        }
    }

    /**
     * Ghi nhận đồng ý. Các mục đích trong {@code purposes} được GỘP vào mục đích
     * hiện có,
     * những mục đích không nằm trong tham số (guardian/voice/donate...) giữ nguyên
     * giá trị cũ.
     */
    public void updateConsent(String userId, String policySha256, String version, Map<String, Boolean> purposes) {
        update(userId, true, user -> {
            Map<String, Boolean> merged = new HashMap<>(user.getPurposes());
            if (purposes != null) {
                merged.putAll(purposes);
            }
            user.setPolicySha256(policySha256);
            user.setConsentVersion(version);
            user.setConsentedAt(Instant.now().toString());
            user.setPurposes(merged);
        });
    }

    /**
     * Thu hồi đồng ý (user bấm "Không đồng ý"): tắt mọi mục đích, bao gồm analyze,
     * để bot ngừng xử lý nội dung.
     * Bằng chứng đồng ý/từ chối vẫn nằm trong audit trail. User chưa có hồ sơ thì
     * không cần làm gì.
     */
    public void revokeConsent(String userId) {
        update(userId, false, user -> {
            Map<String, Boolean> revoked = new HashMap<>();
            for (String purpose : user.getPurposes().keySet()) {
                revoked.put(purpose, false);
            }
            revoked.put("analyze", false);
            user.setPurposes(revoked);
            user.setConsentedAt(null);
            user.setStatus("CONSENT_REVOKED");
        });
    }

    public void updateAddressForm(String userId, String addressForm) {
        update(userId, true, user -> user.setAddressForm(addressForm));
    }

    /**
     * Checks if user has given active consent matching the current policy SHA256.
     * According to ND 356: unconsented or outdated policy versions MUST NOT process
     * user content.
     * Không có hash policy để đối chiếu thì KHÔNG coi là đồng ý hợp lệ
     * (fail-closed).
     */
    public boolean hasValidConsent(String userId, String requiredPolicySha256) {
        if (requiredPolicySha256 == null || requiredPolicySha256.isBlank()) {
            return false;
        }
        Optional<UserRecord> opt = getUser(userId);
        if (opt.isEmpty()) {
            return false;
        }
        UserRecord user = opt.get();
        if (user.getConsentedAt() == null || user.getPolicySha256() == null) {
            return false;
        }
        if (!requiredPolicySha256.equals(user.getPolicySha256())) {
            return false;
        }
        Map<String, Boolean> p = user.getPurposes();
        return p != null && Boolean.TRUE.equals(p.get("analyze"));
    }

    /**
     * Checks if user has specifically consented to voice processing (ND 356
     * purpose-based consent).
     */
    public boolean hasVoiceConsent(String userId) {
        Optional<UserRecord> opt = getUser(userId);
        if (opt.isEmpty()) {
            return false;
        }
        Map<String, Boolean> p = opt.get().getPurposes();
        return p != null && Boolean.TRUE.equals(p.get("voice"));
    }

    /**
     * Đọc-sửa-ghi có khóa lạc quan. {@code change} được áp lên bản mới nhất ở mỗi
     * lần thử.
     *
     * @param createIfMissing true: chưa có hồ sơ thì tạo mới rồi áp thay đổi;
     *                        false: chưa có thì bỏ qua
     */
    private void update(String userId, boolean createIfMissing, Consumer<UserRecord> change) {
        for (int attempt = 1;; attempt++) {
            Optional<UserRecord> current = getUser(userId);

            UserRecord user;
            Expression condition;
            String previousUpdatedAt;
            if (current.isPresent()) {
                user = current.get();
                previousUpdatedAt = user.getUpdatedAt();
                condition = OptimisticUpdates.ifUnchanged(previousUpdatedAt);
            } else if (createIfMissing) {
                user = new UserRecord(userId, null);
                user.setCreatedAt(Instant.now().toString());
                previousUpdatedAt = null;
                condition = OptimisticUpdates.ifAbsent();
            } else {
                return;
            }

            change.accept(user);
            user.setUpdatedAt(OptimisticUpdates.nextTimestamp(previousUpdatedAt));

            try {
                putWithCondition(user, condition);
                return;
            } catch (ConditionalCheckFailedException conflict) {
                if (attempt >= OptimisticUpdates.MAX_ATTEMPTS) {
                    throw conflict;
                }
                // Request khác vừa ghi chen vào: đọc lại bản mới nhất rồi áp lại thay đổi
            }
        }
    }

    private void putWithCondition(UserRecord user, Expression condition) {
        table.putItem(PutItemEnhancedRequest.builder(UserRecord.class)
                .item(user)
                .conditionExpression(condition)
                .build());
    }
}
