package com.askfirst.worker;

import java.util.List;
import java.util.Optional;

/** Dữ liệu người dùng và người thân mà worker cần, tách khỏi UserRepo/GuardianRepo để test được. */
public interface GuardianDirectory {

    /** {@code addressForm}: Bác | Cô | Chú. {@code guardianConsent}: người cao tuổi đã đồng ý mục đích "guardian". */
    record Elder(String addressForm, boolean guardianConsent) {
    }

    /** Người thân đang ở trạng thái active. */
    record Guardian(String uid, String chatId, String name, int shareLevel) {
    }

    Optional<Elder> elder(String userId);

    List<Guardian> activeGuardians(String elderUid);
}
