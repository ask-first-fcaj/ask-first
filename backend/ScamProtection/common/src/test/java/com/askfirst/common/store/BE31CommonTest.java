package com.askfirst.common.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class BE31CommonTest {

    @Test
    @DisplayName("AuditRepo.hashUid: produces valid 64-character SHA-256 hex string and is deterministic")
    void testHashUid() {
        String uid = "tg:123456789";
        String hash1 = AuditRepo.hashUid(uid);
        String hash2 = AuditRepo.hashUid(uid);

        assertThat(hash1).isNotNull().hasSize(64);
        assertThat(hash1).matches("^[a-f0-9]{64}$");
        assertThat(hash1).isEqualTo(hash2);

        // Different uids produce different hashes
        String hashOther = AuditRepo.hashUid("tg:987654321");
        assertThat(hash1).isNotEqualTo(hashOther);

        // Null and empty handling
        assertThat(AuditRepo.hashUid(null)).isEqualTo(AuditRepo.hashUid(""));
    }

    @Test
    @DisplayName("S3DonationStore.hashUid: matches AuditRepo.hashUid")
    void testS3DonationStoreHashUid() {
        String uid = "tg:999888";
        assertThat(S3DonationStore.hashUid(uid)).isEqualTo(AuditRepo.hashUid(uid));
    }

    @Test
    @DisplayName("SessionRecord: ensures non-null maskedText")
    void testSessionRecord() {
        SessionRecord session = new SessionRecord("100", "CHK1", "tg:100", null, null, 1, 1000L);
        assertThat(session.maskedText()).isEqualTo("");
        assertThat(session.chatId()).isEqualTo("100");
        assertThat(session.checkId()).isEqualTo("CHK1");
    }

    @Test
    @DisplayName("AuditRepo.recordDataDeleted: verifies anonymous format, event name and hash uid attribute")
    void testRecordDataDeletedFormat() {
        String uid = "tg:555444";
        String expectedHash = AuditRepo.hashUid(uid);
        String expectedToday = LocalDate.now().toString();

        FakeTestAuditRepo repo = new FakeTestAuditRepo();
        repo.recordDataDeleted(uid, "1.0", "sha256");

        assertThat(repo.recordedEvent).isEqualTo("DATA_DELETED");
        assertThat(repo.recordedUserId).isEqualTo(expectedHash);
        assertThat(repo.recordedDetails)
                .isEqualTo("Tài khoản [" + expectedHash + "] đã yêu cầu xoá dữ liệu vào ngày " + expectedToday);
    }

    @Test
    @DisplayName("AuditRepo.recordEvent: safely returns without NullPointerException when table is null")
    void testRecordEvent_SafeWhenTableIsNull() {
        AuditRepo noTableRepo = new AuditRepo();
        assertDoesNotThrow(() -> {
            noTableRepo.recordEvent("tg:123", "TEST_EVENT", "1.0", "sha", "details");
            noTableRepo.recordEventAnonymized("tg:123", "TEST_EVENT", "1.0", "sha", "details", "anon");
            noTableRepo.deleteAuditTrail("tg:123");
            noTableRepo.listAuditTrail("tg:123");
        });
    }

    static class FakeTestAuditRepo extends AuditRepo {
        String recordedEvent;
        String recordedDetails;
        String recordedUserId;

        @Override
        public void recordEventAnonymized(String pkUserId, String event, String policyVersion, String policySha256, String details, String anonymizedUserId) {
            this.recordedEvent = event;
            this.recordedDetails = details;
            this.recordedUserId = anonymizedUserId;
        }
    }
}
