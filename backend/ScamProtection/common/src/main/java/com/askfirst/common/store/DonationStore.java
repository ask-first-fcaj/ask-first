package com.askfirst.common.store;

/**
 * Lưu trữ các mẫu tin nhắn người dùng đồng ý đóng góp (đã che thông tin cá nhân - masked_text)
 * lên S3 bucket tại đường dẫn donated/<hash(uid)>/.
 */
public interface DonationStore {

    /**
     * Lưu văn bản đã che mờ vào thư mục donated/<hash(uid)>/ trên S3 bucket.
     *
     * @param userId ID người dùng (ví dụ: tg:123456)
     * @param checkId ID lượt kiểm tra (ví dụ: ULID)
     * @param maskedText Nội dung tin nhắn đã được che mờ thông tin cá nhân
     * @return S3 key của mẫu tin đã lưu
     */
    String saveDonatedSample(String userId, String checkId, String maskedText);
}

