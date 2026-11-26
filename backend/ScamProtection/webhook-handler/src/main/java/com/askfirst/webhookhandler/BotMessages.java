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

    public static final String BUTTON_AGREE = "✅ Đồng ý";
    public static final String BUTTON_DECLINE = "Không đồng ý";
    public static final String BUTTON_CREATE_INVITE = "➕ Tạo mã kết nối người thân";
    public static final String BUTTON_CREATE_NEW_INVITE = "➕ Tạo mã kết nối mới";
    public static final String BUTTON_SHARE_INVITE = "📤 Gửi lời mời cho người nhà";

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
                + "• Tin " + xh + " gửi chỉ dùng để kiểm tra lừa đảo. Cháu che số điện thoại, số tài khoản trước khi gửi đi phân tích.\n\n"
                + "Chi tiết: <a href=\"" + url + "\">Chính sách quyền riêng tư</a>\n\n"
                + XH + " bấm <b>Đồng ý</b> để bắt đầu ạ.";
    }

    /**
     * 2. Phản hồi sau khi bấm Đồng ý (consent:agree).
     */
    public static String consentAgreeMessage(String XH, String xh) {
        return "✅ Cảm ơn " + xh + "! Từ giờ có tin nào lạ, " + xh
                + " cứ gửi cho cháu: chuyển tiếp, chụp màn hình hoặc dán chữ đều được.\n\n"
                + "• Lỡ bị lừa rồi: gõ <b>Tôi bị lừa rồi</b> hoặc gõ /bilua\n"
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
                + "2. <b>Đã bấm link lạ hoặc cài ứng dụng lạ thì tắt nguồn điện thoại (sau khi đã gọi/khóa ngân hàng xong)</b>\n"
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
     * Cài đặt cách xưng hô (/caidat).
     */
    public static String settingsAddressFormPrompt(String XH) {
        return "<b>Cài đặt cách xưng hô thân mật:</b>\n" + XH
                + " muốn cháu xưng hô với " + XH + " là gì ạ? " + XH + " vui lòng chọn bên dưới nhé:";
    }

    /**
     * Phản hồi sau khi chọn xưng hô thành công.
     */
    public static String settingsAddressFormUpdated(String form) {
        return "Dạ, cháu đã lưu cách xưng hô là <b>" + form
                + "</b> rồi ạ! Cháu sẽ xưng hô như vậy trong các câu trả lời sau nhé.";
    }

    /**
     * Phản hồi khi nhận tin nhắn thoại: FR2 đang hoãn nên voice nào cũng chưa hỗ trợ (BE-34).
     */
    public static String voiceNotSupportedMessage(String XH) {
        return "Dạ hiện tại cháu chưa hỗ trợ kiểm tra tin nhắn thoại. " + XH
                + " hãy <b>gõ lại nội dung</b> hoặc <b>chụp ảnh màn hình</b> gửi cho cháu kiểm tra nhé!";
    }

    /**
     * Phản hồi khi ảnh quá 20 MB (BE-34): Bot API kh tải được file trên 20 MB.
     */
    public static String photoTooLargeMessage(String XH) {
        return XH + " ơi, file ảnh này dung lượng hơi lớn (quá 20MB) nên cháu chưa đọc được. "
                + XH + " chụp lại màn hình gọn hơn giúp cháu nhé!";
    }

    /**
     * Phản hồi khi bị giới hạn tốc độ gửi tin (Rate limit - BE-26).
     */
    public static String rateLimitMessage(String XH, String xh) {
        return XH + " ơi, hiện tại " + XH + " đã gửi kiểm tra khá nhiều tin trong ngày rồi ạ "
                + "(giới hạn 30 tin/giờ và 100 tin/ngày). Cháu xin phép tạm nghỉ một chút để đảm bảo an toàn cho hệ thống, "
                + XH + " vui lòng quay lại sau nhé!";
    }

    /**
     * Tin nhắn chờ trong khi phân tích ("⏳").
     */
    public static String waitingInspectionMessage(String xh) {
        return "⏳ Cháu đang kiểm tra, " + xh + " chờ cháu vài giây nhé...";
    }

    /**
     * Lỗi xử lý callback phía Telegram.
     */
    public static String callbackErrorMessage(String XH) {
        return "Cháu chưa lưu được, " + XH + " bấm lại giúp cháu nhé.";
    }

    /**
     * Danh sách người thân trống (/nguoithan).
     */
    public static String guardianListEmptyMessage(String XH, String xh) {
        return "👨‍👩‍👦 <b>Danh sách người thân</b>\n\n"
                + XH + " chưa kết nối với người thân nào.\n\n"
                + "Bấm nút bên dưới để tạo mã kết nối và gửi cho người nhà nhé:";
    }

    /**
     * Mã kết nối người thân có deep link.
     */
    public static String guardianInviteDeepLinkMessage(String XH, String xh, String inviteCode) {
        return "✅ Mã kết nối của " + XH + " là: <code><b>" + inviteCode + "</b></code>\n\n"
                + "⏳ <i>(Mã có hiệu lực trong 24 giờ và chỉ dùng được một lần)</i>\n\n"
                + "👇 <b>" + XH + " bấm nút bên dưới để gửi lời mời cho người nhà:</b>\n"
                + "<i>(Người nhà chỉ cần bấm vào link, bot sẽ tự kết nối — không cần gõ gì cả!)</i>";
    }

    /**
     * Mã kết nối người thân gửi thủ công.
     */
    public static String guardianInviteManualMessage(String XH, String xh, String inviteCode) {
        return "✅ Mã kết nối của " + XH + " là: <code><b>" + inviteCode + "</b></code>\n\n"
                + "⏳ <i>(Mã có hiệu lực trong 24 giờ và chỉ dùng được một lần)</i>\n\n"
                + XH + " hãy gửi hoặc chuyển tiếp lời nhắn này cho người nhà qua Zalo/Tin nhắn nhé:\n"
                + "━━━━━━━━━━━━━━━━━━━\n"
                + "<i>\"Mình đang dùng trợ lý AskFirst trên Telegram để phòng chống tin nhắn lừa đảo. "
                + "Mở bot này rồi gõ dòng lệnh bên dưới để khi có tin nguy hiểm bot sẽ cùng báo nhé:\"</i>\n\n"
                + "<code>/ketnoi " + inviteCode + "</code>\n"
                + "━━━━━━━━━━━━━━━━━━━";
    }

    /**
     * FR13: báo người cao tuổi sau khi người thân bấm "Tôi đã liên hệ". {@code guardianName} đã escape HTML.
     */
    public static String guardianAckedMessage(String guardianName, String xh) {
        return "Dạ, " + guardianName + " đã nhận cảnh báo và liên hệ " + xh + " rồi ạ.";
    }

    /**
     * Lời nhắn gửi kèm qua nút share Telegram.
     */
    public static String guardianShareText(String XH) {
        return "Con ơi, bấm vào link này để kết nối bảo vệ cùng " + XH + " qua AskFirst nhé! 💙";
    }

    /**
     * Báo lỗi khi tự dùng mã của chính mình.
     */
    public static String guardianSelfInviteErrorMessage(String XH) {
        return "⚠️ " + XH + " không thể dùng mã kết nối của chính mình.";
    }

    /**
     * Yêu cầu kết nối gửi về cho người cao tuổi.
     */
    public static String guardianConnectionRequestMessage(String XH, String senderDisplayName) {
        return "🔔 <b>YÊU CẦU KẾT NỐI TỪ NGƯỜI THÂN</b>\n\n"
                + "Tài khoản <b>" + senderDisplayName + "</b> muốn kết nối với " + XH + ".\n\n"
                + "Vui lòng chọn <b>mức độ chia sẻ thông tin</b> khi có cảnh báo:\n\n"
                + "• <b>Mức 1:</b> Chỉ báo có nguy cơ (Nguy hiểm / Nghi ngờ).\n"
                + "• <b>Mức 2:</b> Kèm tên kịch bản và dấu hiệu chính.\n"
                + "• <b>Mức 3:</b> Kèm tóm tắt nội dung đã che số.";
    }

    /**
     * Thông báo xác nhận kết nối người thân thành công.
     */
    public static String guardianConnectedElderConfirmation(String XH, int shareLevel) {
        return "✅ Đã kết nối thành công! Người thân của " + XH
                + " sẽ nhận cảnh báo khi cần thiết (Mức " + shareLevel + ").";
    }

    /**
     * Thông báo từ chối kết nối.
     */
    public static String guardianRejectedMessage() {
        return "❌ Đã từ chối yêu cầu kết nối.";
    }

    /**
     * Thông báo cho người thân khi yêu cầu kết nối bị từ chối.
     */
    public static String guardianRelativeRejectedMessage() {
        return "❌ Người thân đã từ chối yêu cầu kết nối lần này.";
    }

    /**
     * Thông báo gỡ kết nối người thân.
     */
    public static String guardianRemovedMessage() {
        return "✅ Đã gỡ kết nối người thân thành công.";
    }

    /**
     * Cảnh báo mặc định của worker khi phân tích có nguy cơ lừa đảo.
     */
    public static String workerDefaultWarning(String xh) {
        return "⚠️ Tin này có nguy cơ lừa đảo cao. " + xh
                + " tuyệt đối không làm theo, không chuyển tiền và không cung cấp mã OTP nhé!";
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
