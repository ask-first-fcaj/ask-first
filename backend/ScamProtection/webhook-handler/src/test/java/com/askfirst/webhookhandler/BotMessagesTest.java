package com.askfirst.webhookhandler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotMessagesTest {

    @Test
    @DisplayName("SH-08: welcomeMessage renders correct greeting, disclaimer, and policy link")
    void testWelcomeMessage() {
        String msg = BotMessages.welcomeMessage("Bác", "bác", "https://example.com/policy");
        assertThat(msg).contains("<b>Chào Bác, cháu là AskFirst</b> 🛡️");
        assertThat(msg).contains("Có tin nhắn, cuộc gọi hay đường link nào lạ, bác gửi cho cháu xem");
        assertThat(msg).contains("• Cháu <b>không bao giờ</b> hỏi mã OTP, mật khẩu hay tiền.");
        assertThat(msg).contains("• Tin bác gửi chỉ dùng để kiểm tra lừa đảo.");
        assertThat(msg).contains("• Bác xóa dữ liệu lúc nào cũng được, gõ /xoadulieu.");
        assertThat(msg).contains("Chi tiết: <a href=\"https://example.com/policy\">Chính sách quyền riêng tư</a>");
        assertThat(msg).contains("Bác bấm <b>Đồng ý</b> để bắt đầu ạ.");
    }

    @Test
    @DisplayName("SH-08: welcomeMessage falls back to default policy URL when null or blank")
    void testWelcomeMessageFallbackPolicyUrl() {
        String msgNull = BotMessages.welcomeMessage("Cô", "cô", null);
        assertThat(msgNull).contains(WebhookHandler.DEFAULT_POLICY_URL);

        String msgBlank = BotMessages.welcomeMessage("Chú", "chú", "   ");
        assertThat(msgBlank).contains(WebhookHandler.DEFAULT_POLICY_URL);
    }

    @Test
    @DisplayName("SH-08: consentAgreeMessage guides user politely and suggests pinning chat")
    void testConsentAgreeMessage() {
        String msg = BotMessages.consentAgreeMessage("Cô", "cô");
        assertThat(msg).contains("✅ Cảm ơn cô!");
        assertThat(msg).contains("chuyển tiếp, chụp màn hình hoặc dán chữ đều được");
        assertThat(msg).contains("• Lỡ bị lừa rồi: bấm <b>Tôi bị lừa rồi</b> hoặc gõ /bilua");
        assertThat(msg).contains("• Cách dùng: gõ /huongdan");
        assertThat(msg).contains("• Đổi cách cháu gọi (Bác/Cô/Chú): gõ /caidat");
        assertThat(msg).contains("💡 Cô ghim cuộc trò chuyện này lên đầu cho dễ tìm nhé.");
    }

    @Test
    @DisplayName("SH-08: consentDeclineMessage politely explains why it cannot proceed")
    void testConsentDeclineMessage() {
        String msg = BotMessages.consentDeclineMessage("chú");
        assertThat(msg).isEqualTo("Dạ, chưa có đồng ý thì cháu chưa kiểm tra tin giúp chú được. Khi nào cần, chú gõ /start nhé.");
    }

    @Test
    @DisplayName("SH-08: returningWelcomeMessage is concise and welcoming")
    void testReturningWelcomeMessage() {
        String msg = BotMessages.returningWelcomeMessage("bác");
        assertThat(msg).isEqualTo("Chào bác ạ. Có tin nào lạ, bác gửi vào đây cho cháu xem nhé.");
    }

    @Test
    @DisplayName("SH-08: guideMessage instructs screenshotting and mentions first line conclusion")
    void testGuideMessage() {
        String msg = BotMessages.guideMessage();
        assertThat(msg).contains("📖 <b>Cách nhờ cháu kiểm tra một tin</b>");
        assertThat(msg).contains("1. Tin trong Telegram: bấm giữ tin đó, chọn <b>Chuyển tiếp</b>, chọn AskFirst.");
        assertThat(msg).contains("2. Tin trong Zalo, tin nhắn điện thoại: <b>chụp màn hình</b> rồi gửi ảnh vào đây.");
        assertThat(msg).contains("3. Đường link hoặc chữ: chép rồi dán vào đây.");
        assertThat(msg).contains("<b>Dòng đầu tiên là kết luận.</b>");
    }

    @Test
    @DisplayName("SH-08: emergencyChecklistMessage matches kb/checklist.yaml with 5 steps")
    void testEmergencyChecklistMessage() {
        String msg = BotMessages.emergencyChecklistMessage("bác");
        assertThat(msg).contains("🚨 <b>Nghi bị lừa: bác làm ngay theo thứ tự</b>");
        assertThat(msg).contains("<b>Ngay bây giờ (0-5 phút)</b>");
        assertThat(msg).contains("1. <b>Khóa thẻ và tài khoản ngân hàng</b>");
        assertThat(msg).contains("2. <b>Đã bấm link lạ hoặc cài ứng dụng lạ thì tắt nguồn điện thoại</b>");
        assertThat(msg).contains("<b>5-15 phút</b>");
        assertThat(msg).contains("3. <b>Đổi mật khẩu bằng một điện thoại khác, gỡ ứng dụng lạ</b>");
        assertThat(msg).contains("<b>15-30 phút</b>");
        assertThat(msg).contains("4. <b>Báo công an xã, phường gần nhất</b> (khẩn cấp gọi 113)");
        assertThat(msg).contains("<b>Sau đó</b>");
        assertThat(msg).contains("5. <b>Kể cho con cháu biết</b>");
    }

    @Test
    @DisplayName("SH-08: promptConsentMessage asks user to click Agree")
    void testPromptConsentMessage() {
        String msg = BotMessages.promptConsentMessage("Chú", "chú");
        assertThat(msg).isEqualTo("Chú ơi, để bảo vệ dữ liệu cá nhân theo quy định của pháp luật, chú vui lòng bấm <b>Đồng ý</b> bên dưới trước để cháu được phép kiểm tra tin nhắn nhé!");
    }

    @Test
    @DisplayName("SH-08: 15-minute reminder messages for elder and guardian (BE-29 support)")
    void testReminderMessages() {
        assertThat(BotMessages.reminderElderMessage("Bác", "Nam"))
                .isEqualTo("Bác đã gọi được cho Nam chưa ạ?");
        assertThat(BotMessages.reminderElderAckMessage("Bác"))
                .isEqualTo("Dạ tốt quá. Bác nhớ đừng chuyển tiền hay đọc mã OTP cho ai nhé.");
        assertThat(BotMessages.reminderGuardianMessage("Bác Ba", "14:30"))
                .isEqualTo("⏰ Nhắc lại: <b>Bác Ba</b> vừa nhờ AskFirst kiểm tra một tin có nguy cơ lừa đảo lúc 14:30. Liên hệ được rồi thì bấm nút bên dưới.");
        assertThat(BotMessages.reminderGuardianAckMessage("Nam", "bác"))
                .isEqualTo("✅ Nam đã liên hệ bác.");
    }

    @Test
    @DisplayName("SH-08: toLower converts address form properly")
    void testToLower() {
        assertThat(BotMessages.toLower("Bác")).isEqualTo("bác");
        assertThat(BotMessages.toLower("Cô")).isEqualTo("cô");
        assertThat(BotMessages.toLower("Chú")).isEqualTo("chú");
        assertThat(BotMessages.toLower(null)).isEqualTo("bác");
        assertThat(BotMessages.toLower("  ")).isEqualTo("bác");
    }
}
