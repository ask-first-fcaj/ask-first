package com.askfirst.worker;

import com.askfirst.common.TelegramClient;

/**
 * Câu chữ do BE tự viết (không qua ai) cho các tình huống ai không dùng được. Giá trị động đều được escape HTML.
 */
final class WorkerMessages {

    /** Chưa lưu tên Telegram của người cao tuổi nên dùng cách gọi chung cho tin gửi người thân. */
    static final String ELDER_NAME_FALLBACK = "Người thân của bạn";
    static final String GUARDIAN_FALLBACK_NAME = "người thân";

    private WorkerMessages() {
    }

    /** Cách xưng hô đã lưu (Bác | Cô | Chú); giá trị lạ thì dùng "Bác". */
    static String address(String stored) {
        return "Cô".equals(stored) || "Chú".equals(stored) ? stored : "Bác";
    }

    /** Giá trị address_form của contract ai: elder | aunt | uncle. */
    static String contractAddressForm(String stored) {
        return switch (address(stored)) {
            case "Cô" -> "aunt";
            case "Chú" -> "uncle";
            default -> "elder";
        };
    }

    static String degraded(String addressForm) {
        String a = TelegramClient.escapeHtml(address(addressForm));
        return "Dạ, cháu chưa kiểm tra được tin này ạ. " + a + " đừng làm theo yêu cầu trong tin (chuyển tiền, đọc mã OTP, "
                + "bấm link) khi chưa hỏi người thân hoặc gọi ngân hàng nhé.";
    }

    static String busy(String addressForm) {
        String a = TelegramClient.escapeHtml(address(addressForm));
        return "Dạ, hiện cháu đang bận nên chưa kiểm tra được tin này. " + a + " vui lòng gửi lại sau ít phút nhé. "
                + "Nếu tin yêu cầu chuyển tiền gấp thì chưa làm theo, hãy gọi người thân hoặc 113 trước ạ.";
    }

    static String followupElder(String addressForm) {
        String a = TelegramClient.escapeHtml(address(addressForm));
        return a + " ơi, người thân đã nhận được cảnh báo nhưng chưa xác nhận đã liên hệ. " + a
                + " hãy gọi trực tiếp cho người thân ngay nhé, và chưa làm theo yêu cầu trong tin nhắn lạ ạ.";
    }

    static String followupGuardian(String elderName) {
        return TelegramClient.escapeHtml(elderName) + " vừa nhận một tin nghi lừa đảo cách đây khoảng 15 phút "
                + "và bạn chưa xác nhận. Vui lòng liên hệ ngay, rồi bấm nút bên dưới.";
    }

    static final String ACK_BUTTON = "Tôi đã liên hệ";
}
