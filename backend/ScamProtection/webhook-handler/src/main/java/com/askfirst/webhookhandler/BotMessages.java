package com.askfirst.webhookhandler;

import java.util.Locale;

/**
 * Tập trung toàn bộ câu từ, mẫu tin nhắn phản hồi của Bot phía Backend (SH-08).
 * Quy ước:
 * - Bot luôn xưng "cháu" lễ phép với người cao tuổi.
 * - XH: cách xưng hô viết hoa đầu câu (Bác / Cô / Chú).
 * - xh: cách xưng hô viết thường giữa câu (bác / cô / chú).
 * - Không dùng từ chuyên môn gây bối rối ("Nghị định 356", "hệ thống AI", "Forward").
 */
public final class BotMessages {

    private BotMessages() {
    }

    /**
     * 1. Lời chào và xin phép bảo mật dữ liệu (/start khi chưa có consent).
     */
    public static String welcomeMessage(String XH, String xh, String policyUrl) {
        String url = (policyUrl != null && !policyUrl.isBlank())
                ? policyUrl
                : WebhookHandler.DEFAULT_POLICY_URL;

        return "<b>Chào " + XH + ", cháu là AskFirst</b> 🛡️\n\n"
                + "Có tin nhắn, cuộc gọi hay đường link nào lạ, " + xh
                + " gửi cho cháu xem trước khi làm theo nhé.\n\n"
                + "• Cháu <b>không bao giờ</b> hỏi mã OTP, mật khẩu hay tiền.\n"
                + "• Tin " + xh + " gửi chỉ dùng để kiểm tra lừa đảo. Cháu che số điện thoại, số tài khoản trước khi gửi đi phân tích.\n"
                + "• " + XH + " xóa dữ liệu lúc nào cũng được, gõ /xoadulieu.\n\n"
                + "Chi tiết: <a href=\"" + url + "\">Chính sách quyền riêng tư</a>\n\n"
                + XH + " bấm <b>Đồng ý</b> để bắt đầu ạ.";
    }

    /**
     * 2. Phản hồi sau khi bấm Đồng ý (consent:agree).
     */
    public static String consentAgreeMessage(String XH, String xh) {
        return "✅ Cảm ơn " + xh + "! Từ giờ có tin nào lạ, " + xh
                + " cứ gửi cho cháu: chuyển tiếp, chụp màn hình hoặc dán chữ đều được.\n\n"
                + "• Lỡ bị lừa rồi: bấm <b>Tôi bị lừa rồi</b> hoặc gõ /bilua\n"
                + "• Cách dùng: gõ /huongdan\n"
                + "• Đổi cách cháu gọi (Bác/Cô/Chú): gõ /caidat\n\n"
                + "💡 " + XH + " ghim cuộc trò chuyện này lên đầu cho dễ tìm nhé.";
    }

    /**
     * 3. Phản hồi khi bấm Không đồng ý (consent:decline).
     */
    public static String consentDeclineMessage(String xh) {
        return "Dạ, chưa có đồng ý thì cháu chưa kiểm tra tin giúp " + xh
                + " được. Khi nào cần, " + xh + " gõ /start nhé.";
    }

    /**
     * 4. /start khi người dùng đã đồng ý điều khoản.
     */
    public static String returningWelcomeMessage(String xh) {
        return "Chào " + xh + " ạ. Có tin nào lạ, " + xh + " gửi vào đây cho cháu xem nhé.";
    }

    /**
     * 5. Hướng dẫn sử dụng (/huongdan).
     */
    public static String guideMessage() {
        return "📖 <b>Cách nhờ cháu kiểm tra một tin</b>\n\n"
                + "1. Tin trong Telegram: bấm giữ tin đó, chọn <b>Chuyển tiếp</b>, chọn AskFirst.\n"
                + "2. Tin trong Zalo, tin nhắn điện thoại: <b>chụp màn hình</b> rồi gửi ảnh vào đây.\n"
                + "3. Đường link hoặc chữ: chép rồi dán vào đây.\n\n"
                + "Vài giây sau cháu trả lời. <b>Dòng đầu tiên là kết luận.</b>";
    }

    /**
     * 6. Checklist khẩn cấp 30 phút đầu khi nghi bị lừa (/bilua hoặc "Tôi bị lừa rồi").
     * Đồng bộ trực tiếp từ kb/checklist.yaml.
     */
    public static String emergencyChecklistMessage(String xh) {
        return "🚨 <b>Nghi bị lừa: " + xh + " làm ngay theo thứ tự</b>\n\n"
                + "<b>Ngay bây giờ (0-5 phút)</b>\n"
                + "1. <b>Khóa thẻ và tài khoản ngân hàng</b>\n"
                + "Khóa ngay trong ứng dụng ngân hàng, hoặc gọi tổng đài ngân hàng và nói rõ là nghi bị lừa. Chưa chắc bị lừa cũng cứ khóa trước.\n\n"
                + "2. <b>Đã bấm link lạ hoặc cài ứng dụng lạ thì tắt nguồn điện thoại</b>\n"
                + "Để kẻ gian không lấy được mã OTP hay dữ liệu khuôn mặt, vân tay.\n\n"
                + "<b>5-15 phút</b>\n"
                + "3. <b>Đổi mật khẩu bằng một điện thoại khác, gỡ ứng dụng lạ</b>\n"
                + "Đổi mật khẩu ngân hàng, ví điện tử, Zalo, Facebook bằng máy khác. Gỡ ứng dụng mà người lạ bảo cài.\n\n"
                + "<b>15-30 phút</b>\n"
                + "4. <b>Báo công an xã, phường gần nhất</b> (khẩn cấp gọi 113)\n"
                + "Mang theo ảnh chụp tin nhắn, số điện thoại của kẻ gian, số tài khoản đã chuyển tiền và biên lai.\n\n"
                + "<b>Sau đó</b>\n"
                + "5. <b>Kể cho con cháu biết</b>\n"
                + "Để cả nhà cùng xử lý và cảnh giác nếu kẻ gian gọi lại.";
    }

    /**
     * Nhắc người dùng bấm đồng ý khi gửi tin nhắn kiểm tra mà chưa consent.
     */
    public static String promptConsentMessage(String XH, String xh) {
        return XH + " ơi, để bảo vệ dữ liệu cá nhân theo quy định của pháp luật, " + xh
                + " vui lòng bấm <b>Đồng ý</b> bên dưới trước để cháu được phép kiểm tra tin nhắn nhé!";
    }

    /**
     * 7. Nhắc lại sau 15 phút (BE-29, ARCH §5.5) - Dành cho người cao tuổi.
     */
    public static String reminderElderMessage(String XH, String guardian) {
        return XH + " đã gọi được cho " + guardian + " chưa ạ?";
    }

    /**
     * Nhắc lại sau 15 phút - Khi người cao tuổi bấm "Rồi".
     */
    public static String reminderElderAckMessage(String XH) {
        return "Dạ tốt quá. " + XH + " nhớ đừng chuyển tiền hay đọc mã OTP cho ai nhé.";
    }

    /**
     * Nhắc lại sau 15 phút - Dành cho người thân.
     */
    public static String reminderGuardianMessage(String elder, String at) {
        return "⏰ Nhắc lại: <b>" + elder + "</b> vừa nhờ AskFirst kiểm tra một tin có nguy cơ lừa đảo lúc "
                + at + ". Liên hệ được rồi thì bấm nút bên dưới.";
    }

    /**
     * Nhắc lại sau 15 phút - Khi người thân bấm "Tôi đã liên hệ".
     */
    public static String reminderGuardianAckMessage(String guardian, String xh) {
        return "✅ " + guardian + " đã liên hệ " + xh + ".";
    }

    /**
     * Chuẩn hóa xưng hô viết hoa (XH) và viết thường (xh).
     */
    public static String toLower(String addressForm) {
        if (addressForm == null || addressForm.isBlank()) {
            return "bác";
        }
        return addressForm.toLowerCase(Locale.ROOT);
    }
}
