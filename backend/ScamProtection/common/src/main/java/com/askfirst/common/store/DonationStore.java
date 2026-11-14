package com.askfirst.common.store;

/**
 * Lưu trữ và quản lý các mẫu tin nhắn người dùng đóng góp
 * lên S3 bucket tại đường dẫn donated/<hash(uid)>/.
 */
public interface DonationStore {

    String saveDonatedSample(String userId, String checkId, String maskedText);

    void deleteDonatedSamples(String userId);

    boolean hasDonatedSamples(String userId);
}
