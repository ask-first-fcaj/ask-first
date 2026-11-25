package com.askfirst.worker;

import java.util.List;
import java.util.Optional;

/** Dữ liệu người dùng và người thân mà worker cần, tách khỏi UserRepo/GuardianRepo để test được. */
public interface GuardianDirectory {

    /**
     * {@code addressForm}: Bác | Cô | Chú. {@code guardianConsent}: người cao tuổi đã đồng ý mục đích "guardian".
     * {@code displayName}: tên Telegram, null nếu chưa lưu (khi đó tin gửi người thân dùng cách gọi chung).
     */
    record Elder(String addressForm, boolean guardianConsent, String displayName) {
        public Elder(String addressForm, boolean guardianConsent) {
            this(addressForm, guardianConsent, null);
        }
    }

    /** Người thân đang ở trạng thái active. */
    record Guardian(String uid, String chatId, String name, int shareLevel) {
    }

    Optional<Elder> elder(String userId);

    List<Guardian> activeGuardians(String elderUid);
}
