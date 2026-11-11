# Chấm tay lời giải thích (AI-11)

20 mẫu ngẫu nhiên (seed 11) trong lượt chấm LLM. Mỗi người chấm độc lập, 1–5 cho từng tiêu chí, 
rồi ghi vào `eval/human/ai11_scores.csv` (cột `id,grader,c1,c2,c3,c4`, `grader` là mã người chấm như AI1, AI2).

Tiêu chí (đọc đủ ở `eval/run_judge_eval.py`, biến `RUBRIC`):

- **c1** Đúng trọng tâm
- **c2** Người 70 tuổi hiểu ngay
- **c3** Hướng dẫn hành động rõ
- **c4** Ngắn gọn

## bn-ai-06

Tin gốc:

> Mẹ ơi, trưa nay con họp đột xuất nên không về ăn cơm được. Ba mẹ ăn trước đi nha

Nhãn: lành tính (N0). Bot: LOW_RISK (N0).

Bot trả lời:

```text
⚪ CHƯA THẤY DẤU HIỆU LỪA ĐẢO RÕ RÀNG
Bot chưa thấy dấu hiệu lừa đảo rõ ràng. Nhưng nếu ai bảo chuyển tiền, đọc OTP hay giữ bí mật thì bác hãy dừng lại.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

## bn-ai-12

Tin gốc:

> Anh kiểm tra giúp em email hợp đồng đối tác vừa gửi nhé, em xem điều khoản thanh toán thấy ổn rồi ạ.

Nhãn: lành tính (N0). Bot: LOW_RISK (OTHER).

Bot trả lời:

```text
⚪ CHƯA THẤY DẤU HIỆU LỪA ĐẢO RÕ RÀNG
Bot chưa thấy dấu hiệu lừa đảo rõ ràng. Nhưng nếu ai bảo chuyển tiền, đọc OTP hay giữ bí mật thì bác hãy dừng lại.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

## bn-ai-13

Tin gốc:

> Ngoài Bắc trở gió lạnh lắm, con nhớ mặc áo ấm rồi hẵng đi làm nhé.

Nhãn: lành tính (N0). Bot: LOW_RISK (N0).

Bot trả lời:

```text
⚪ CHƯA THẤY DẤU HIỆU LỪA ĐẢO RÕ RÀNG
Bot chưa thấy dấu hiệu lừa đảo rõ ràng. Nhưng nếu ai bảo chuyển tiền, đọc OTP hay giữ bí mật thì bác hãy dừng lại.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

## bn-ai-19

Tin gốc:

> Chào cả team, do sếp bận lịch tiếp đoàn đối tác đột xuất nên cuộc họp review tiến độ dự án chiều nay lúc 14h sẽ dời sang 9h sáng mai tại phòng họp B nhé. Mọi người chuẩn bị trước slide báo cáo giúp mình.

Nhãn: lành tính (N0). Bot: LOW_RISK (OTHER).

Bot trả lời:

```text
⚪ CHƯA THẤY DẤU HIỆU LỪA ĐẢO RÕ RÀNG
Bot chưa thấy dấu hiệu lừa đảo rõ ràng. Nhưng nếu ai bảo chuyển tiền, đọc OTP hay giữ bí mật thì bác hãy dừng lại.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

## bs-ai-04

Tin gốc:

> VNeID: Tài khoản định danh điện tử của bạn vừa được cập nhật thông tin. Bạn xem chi tiết trong ứng dụng VNeID đã cài trên máy. Không cung cấp mã xác thực cho bất kỳ ai.

Nhãn: lành tính (N0). Bot: UNSURE (N1.4).

Bot trả lời:

```text
🟡 BOT CHƯA CHẮC CHẮN
Bot chưa chắc chắn, bác hỏi con hoặc gọi ngân hàng trước khi làm gì.

Vì sao Bot nghi ngờ:
• Bác nên kiểm tra trực tiếp trên ứng dụng VNeID hoặc liên hệ cơ quan nhà nước để xác minh, vì có thể là tin giả.

Cảnh báo liên quan:
• Công an tỉnh Nghệ An (04/2026)
• Bộ Công an (09/2026)
• Bộ Công an (07/2025)

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
❓ Bác có thường nhận được thông báo từ ứng dụng VNeID không ạ?
```

## bs-ai-05

Tin gốc:

> Bà ơi, con vừa chuyển tiền chợ tháng này vào tài khoản của bà rồi đó. Bà xem trong app ngân hàng giúp con nha. Có ai gọi bảo bà đọc mã gì thì bà đừng đọc, cứ gọi lại cho con.

Nhãn: lành tính (N0). Bot: DANGEROUS (N5.4).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Chiếm quyền Zalo hoặc Facebook rồi nhắn người quen vay tiền
Bác ơi, tin này có thể giả mạo để lấy thông tin tài khoản hoặc mã OTP. Bác không nên nhấn vào link hay đọc mã cho người lạ.
👉 Bác ĐỪNG đọc mã OTP cho ai, ĐỪNG bấm link.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Vì sao Bot nghi ngờ:
• "con vừa chuyển tiền chợ tháng này vào tài khoản của bà rồi đó" → Tin đòi chuyển khoản hoặc nạp tiền.

Bác nên làm:
• Gọi lại trực tiếp cho người quen bằng số điện thoại để xác nhận trước khi chuyển tiền
• Không bấm link lạ, không đọc mã xác thực cho ai

Cảnh báo liên quan:
• Bộ Công an (Công an tỉnh Phú Thọ) (09/2026)
• CafeF (dẫn cảnh báo của Công an) (05/2026)
```

## bs-ai-19

Tin gốc:

> [Siêu thị A] Chúc mừng quý khách có mã thẻ thành viên K9902 đã trúng giải Ba (01 Nồi chiên không dầu) chương trình Tri ân mùa thu. Quý khách vui lòng mang theo CCCD đến quầy Dịch vụ khách hàng tại siêu thị trước ngày 30/10/2026 để nhận quà trực tiếp. Lưu ý: Siêu thị không thu bất kỳ khoản phí nào và không yêu cầu cung cấp OTP hay chuyển khoản.

Nhãn: lành tính (N0). Bot: UNSURE (N1.6).

Bot trả lời:

```text
🟡 BOT CHƯA CHẮC CHẮN
Bot chưa chắc chắn, bác hỏi con hoặc gọi ngân hàng trước khi làm gì.

Vì sao Bot nghi ngờ:
• Bác nên cẩn trọng với tin trúng thưởng. Nên liên hệ trực tiếp siêu thị để xác minh thông tin.
• "…A] Chúc mừng quý khách có mã thẻ thành viên K9902 đã trúng giải Ba (01 Nồi chiên không dầu) chương trình Tri ân mùa…" → Tin báo trúng thưởng hoặc quà tri ân bất ngờ.

Cảnh báo liên quan:
• Bộ Công an (Công an tỉnh Thanh Hóa) (06/2025)

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
❓ Bác có thường nhận được tin nhắn trúng thưởng từ siêu thị này không ạ?
```

## hd-ai-18

Tin gốc:

> [VNeID] Giấy chứng nhận quyền sử dụng đất của quý khách chưa được tích hợp. Truy cập https://vneid.gov.vn để cập nhật thông tin.

Nhãn: lừa đảo (N1.4). Bot: DANGEROUS (N1.4).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Giả dịch vụ công (phạt nguội, VNeID, trợ cấp), dụ vào web giả hoặc cài app giả
Bác không nên truy cập nếu chưa kiểm tra kỹ.
👉 Bác ĐỪNG bấm link.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Vì sao Bot nghi ngờ:
• "hxxps://vneid[.]gov[.]vn → vneid-xacthuc[.]example" → Chữ hiện ra trong tin khác với trang mà link thật sự mở.

Bác nên làm:
• Không bấm link trong tin nhắn, không làm theo yêu cầu qua điện thoại của người tự xưng cán bộ
• Công an không yêu cầu nộp phạt hay đưa tài khoản, mật khẩu, mã OTP qua đường link trong tin nhắn
• Muốn tra cứu vi phạm thì vào csgt.vn hoặc ứng dụng VNeTraffic; cần cập nhật thông tin thì ra trực tiếp Công an xã hoặc phường

Cảnh báo liên quan:
• Công an tỉnh Nghệ An (04/2026)
• Bộ Công an (09/2026)
• Bộ Công an (07/2025)
```

## hd-ai-20

Tin gốc:

> Mày ơi, tao đang họp với sếp không tiện nghe máy. Mày chuyển khoản hộ tao [SỐ TIỀN] qua số tài khoản 0000000000 ngân hàng [Ngân hàng A] với nha.

Nhãn: lừa đảo (N5.4). Bot: DANGEROUS (N5.4).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Chiếm quyền Zalo hoặc Facebook rồi nhắn người quen vay tiền
Bác ơi, tin nhắn yêu cầu chuyển tiền gấp và không rõ nguồn gốc có thể là lừa đảo. Bác nên gọi trực tiếp người quen để xác minh.
👉 Bác ĐỪNG chuyển tiền.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Vì sao Bot nghi ngờ:
• "…ơi, tao đang họp với sếp không tiện nghe máy. Mày chuyển khoản hộ tao [SỐ TIỀN] qua số tài khoản [STK_1] ngân hàng…" → Tin đòi chuyển khoản hoặc nạp tiền.

Bác nên làm:
• Gọi lại trực tiếp cho người quen bằng số điện thoại để xác nhận trước khi chuyển tiền
• Không bấm link lạ, không đọc mã xác thực cho ai

Cảnh báo liên quan:
• Bộ Công an (Công an tỉnh Phú Thọ) (09/2026)
• CafeF (dẫn cảnh báo của Công an) (05/2026)
```

## rs-ai-01

Tin gốc:

> Phương tiện của bạn có vi phạm giao thông, vui lòng truy cập website để tra cứu và nộp phạt: abc[.]xyz

Nhãn: lừa đảo (N1.4). Bot: DANGEROUS (N1.4).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Giả dịch vụ công (phạt nguội, VNeID, trợ cấp), dụ vào web giả hoặc cài app giả
Bác không nên click vào link này.
👉 Bác ĐỪNG bấm link.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Bác nên làm:
• Không bấm link trong tin nhắn, không làm theo yêu cầu qua điện thoại của người tự xưng cán bộ
• Công an không yêu cầu nộp phạt hay đưa tài khoản, mật khẩu, mã OTP qua đường link trong tin nhắn
• Muốn tra cứu vi phạm thì vào csgt.vn hoặc ứng dụng VNeTraffic; cần cập nhật thông tin thì ra trực tiếp Công an xã hoặc phường

Cảnh báo liên quan:
• Công an tỉnh Nghệ An (04/2026)
• Bộ Công an (09/2026)
• Bộ Công an (07/2025)
```

## rs-ai-06

Tin gốc:

> Chúc mừng anh/chị đã trúng thưởng một chiếc tivi của công ty. Để nhận quà, anh/chị cần mua thêm sản phẩm khác của công ty và nộp phí vận chuyển nhanh.

Nhãn: lừa đảo (N1.6). Bot: SUSPICIOUS (N1.6).

Bot trả lời:

```text
🟠 NGHI NGỜ – Giả doanh nghiệp báo trúng thưởng hoặc quà tri ân, bắt đóng phí trước
Bác ơi, tin nhắn hứa trúng thưởng nhưng yêu cầu phải mua thêm sản phẩm và nộp phí thường là lừa đảo. Bác không nên làm theo.
👉 Bác khoan làm theo tin này: ĐỪNG chuyển tiền. Hỏi người nhà hoặc gọi ngân hàng trước.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Vì sao Bot nghi ngờ:
• "Chúc mừng anh/chị đã trúng thưởng một chiếc tivi của công ty. Để nhận quà, anh/chị…" → Tin báo trúng thưởng hoặc quà tri ân bất ngờ.

Bác nên làm:
• Không chuyển tiền để nhận thưởng, kể cả các khoản làm biển số, giấy tờ, vận chuyển hay đổi thưởng sang tiền mặt
• Không bấm link hoặc quét mã QR nhận thưởng

Cảnh báo liên quan:
• Bộ Công an (Công an tỉnh Thanh Hóa) (06/2025)
```

## rs-ai-09

Tin gốc:

> [ACB] Chúng tôi phát hiện tài khoản của bạn đang tiêu dùng ở nước ngoài. Nếu không phải bạn đang tiêu dùng, vui lòng nhấp vào https://v-acb[.]com để hủy thanh toán.

Nhãn: lừa đảo (N1.3). Bot: DANGEROUS (N1.3).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – SMS giả ngân hàng báo điểm thưởng thẻ tín dụng hết hạn, kèm link giả
Bác ơi, tin này có thể giả mạo ngân hàng và yêu cầu bác click vào link lạ. Bác không nên làm theo.
👉 Bác ĐỪNG bấm link.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Vì sao Bot nghi ngờ:
• "v-acb[.]com (giống ACB)" → Link giả tên trang chính thức, chỉ khác vài chữ.

Bác nên làm:
• Không bấm link trong SMS
• Tự mở ứng dụng ngân hàng chính thức để kiểm tra điểm thưởng hoặc giao dịch
• Không nhập thông tin thẻ hoặc mã OTP vào trang mở từ link trong SMS

Cảnh báo liên quan:
• Công an tỉnh Gia Lai (07/2026)
```

## rs-ai-12

Tin gốc:

> [CSGT] Hệ thống xác nhận phương tiện của quý khách vi phạm vượt đèn đỏ ngày 6/8/2026, chưa thanh toán khoản phạt. Nộp phạt trong 48 giờ, quá hạn mức phạt tăng thêm 50% và bị tạm giữ giấy tờ phương tiện. Xem chi tiết và thanh toán tại: dichvucong-nopphat[.]xyz/tracuu

Nhãn: lừa đảo (N1.4). Bot: DANGEROUS (N1.4).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Giả dịch vụ công (phạt nguội, VNeID, trợ cấp), dụ vào web giả hoặc cài app giả
Bác ơi, tin này giả mạo CSGT và dịch vụ công, kèm link lạ và đe dọa phạt nặng nếu không làm theo. Bác không nên truy cập link hay nộp tiền.
👉 Bác ĐỪNG chuyển tiền, ĐỪNG bấm link.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Bác nên làm:
• Không bấm link trong tin nhắn, không làm theo yêu cầu qua điện thoại của người tự xưng cán bộ
• Công an không yêu cầu nộp phạt hay đưa tài khoản, mật khẩu, mã OTP qua đường link trong tin nhắn
• Muốn tra cứu vi phạm thì vào csgt.vn hoặc ứng dụng VNeTraffic; cần cập nhật thông tin thì ra trực tiếp Công an xã hoặc phường

Cảnh báo liên quan:
• Công an tỉnh Nghệ An (04/2026)
• Bộ Công an (09/2026)
• Bộ Công an (07/2025)
```

## rs-ai-16

Tin gốc:

> Bác sĩ nói anh còn có một tháng để sống. Chắc anh sẽ đi du lịch một mình rồi lặng lẻ rời đi. Điều tiếc nuối nhất của anh là không thể sống cuộc đời này cùng em. Anh đã để lại cho em một số tiền, em lưu lại thông tin này: https://quy-thuake[.]top, tài khoản và mật khẩu an đã gửi, số dư 7.661.982 USDT. Xíu nữa anh xóa tài khoản rồi, tạm biệt em

Nhãn: lừa đảo (N3.3). Bot: DANGEROUS (N1.2).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Người lạ xưng công an báo người thân gặp nạn, ép chuyển tiền và giữ bí mật
Bác nên cẩn thận, đây có thể là tin nhắn giả mạo người thân để lừa bác nhấn vào đường link nguy hiểm.
👉 Bác ĐỪNG bấm link.

Bác nên làm:
• Bình tĩnh, cúp máy
• Gọi lại trực tiếp cho người thân bằng số đã lưu để kiểm tra
• Không chuyển tiền, không đọc mã OTP, mật khẩu, số tài khoản cho ai

Cảnh báo liên quan:
• Bộ Công an (Công an xã Chợ Rã, Thái Nguyên) (07/2026)

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

## rs-ai-17

Tin gốc:

> Em nhớ anh nhiều lắm. Em mới gửi quà cho anh về Việt Nam. Hôm nay Hải quan sân bay thông báo bưu kiện bị giữ, cần chuyển 140tr đồng phí thông quan vào STK 0000000000. Anh mượn tạm ai tiền chuyển đi nha, về nước rồi em trả lại anh, nhanh lên anh

Nhãn: lừa đảo (N3.3). Bot: DANGEROUS (N1.2).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Người lạ xưng công an báo người thân gặp nạn, ép chuyển tiền và giữ bí mật
Bác ơi, đừng vội chuyển tiền. Đây là kiểu lừa đảo phổ biến, người quen giả vờ xin tiền phí hải quan rồi mất tích.
👉 Bác ĐỪNG chuyển tiền.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Vì sao Bot nghi ngờ:
• "…sân bay thông báo bưu kiện bị giữ, cần chuyển 140tr đồng phí thông quan vào STK [STK_1]. Anh mượn tạm ai tiền…" → Tin đòi chuyển khoản hoặc nạp tiền.

Bác nên làm:
• Bình tĩnh, cúp máy
• Gọi lại trực tiếp cho người thân bằng số đã lưu để kiểm tra
• Không chuyển tiền, không đọc mã OTP, mật khẩu, số tài khoản cho ai

Cảnh báo liên quan:
• Bộ Công an (Công an xã Chợ Rã, Thái Nguyên) (07/2026)
```

## rs-ai-19

Tin gốc:

> Chúc mừng quý khách đã trúng thưởng 01 xe máy Honda SH từ chương trình bốc thăm mua hàng tri ân! Để nhận thưởng, vui lòng nộp 2.500.000đ tiền thuế trước bạ và phí vận chuyển vào STK 0000000000 trước khi nhận giải.

Nhãn: lừa đảo (N1.6). Bot: DANGEROUS (N1.6).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Giả doanh nghiệp báo trúng thưởng hoặc quà tri ân, bắt đóng phí trước
Bác ơi, đây là hình thức giả doanh nghiệp báo trúng thưởng rồi yêu cầu nộp tiền trước. Xin bác không chuyển tiền vào tài khoản lạ.
👉 Bác ĐỪNG chuyển tiền.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Vì sao Bot nghi ngờ:
• "Chúc mừng quý khách đã trúng thưởng 01 xe máy Honda SH từ chương trình bốc thăm mua…" → Tin báo trúng thưởng hoặc quà tri ân bất ngờ.

Bác nên làm:
• Không chuyển tiền để nhận thưởng, kể cả các khoản làm biển số, giấy tờ, vận chuyển hay đổi thưởng sang tiền mặt
• Không bấm link hoặc quét mã QR nhận thưởng

Cảnh báo liên quan:
• Bộ Công an (Công an tỉnh Thanh Hóa) (06/2025)
```

## rs-ai-21

Tin gốc:

> Anh ơi em đang ở bệnh viện, mạng yếu nên mất tín hiệu. Em mới bị tai nạn, cần nộp viện phí cấp cứu gấp 20 triệu, anh chuyển ngay vào STK 0000000000 của bác sĩ giúp em với

Nhãn: lừa đảo (N5.4). Bot: DANGEROUS (N1.2).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Người lạ xưng công an báo người thân gặp nạn, ép chuyển tiền và giữ bí mật
Bác ơi, tin nhắn yêu cầu chuyển tiền gấp để cứu người thân có thể là lừa đảo. Bác nên kiểm tra thông tin thật kỹ trước khi làm theo.
👉 Bác ĐỪNG chuyển tiền.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Vì sao Bot nghi ngờ:
• "…tín hiệu. Em mới bị tai nạn, cần nộp viện phí cấp cứu gấp 20 triệu, anh chuyển ngay vào STK [STK_1] của bác sĩ…" → Tin hối thúc làm ngay, dọa khóa tài khoản hoặc phạt.

Bác nên làm:
• Bình tĩnh, cúp máy
• Gọi lại trực tiếp cho người thân bằng số đã lưu để kiểm tra
• Không chuyển tiền, không đọc mã OTP, mật khẩu, số tài khoản cho ai

Cảnh báo liên quan:
• Bộ Công an (Công an xã Chợ Rã, Thái Nguyên) (07/2026)
```

## rs-ai-22

Tin gốc:

> Chào bạn, bên mình đang tuyển cộng tác viên làm nhiệm vụ online: xem video, tương tác bài viết, giật đơn hàng trên sàn thương mại điện tử, hoa hồng hấp dẫn. Nhắn vào nhóm Telegram t[.]me/nhiemvu_lienhoan[.]vip để nhận nhiệm vụ, nạp tiền làm nhiệm vụ liên hoàn nhận hoa hồng cao.

Nhãn: lừa đảo (N3.1). Bot: SUSPICIOUS (N3.1).

Bot trả lời:

```text
🟠 NGHI NGỜ – Tuyển dụng “việc nhẹ lương cao” trên mạng xã hội
Bác ơi, lời mời làm việc online với hoa hồng cao và yêu cầu click vào link Telegram có thể là lừa đảo. Bác nên cẩn thận.
👉 Bác khoan làm theo tin này: ĐỪNG bấm link. Hỏi người nhà hoặc gọi ngân hàng trước.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Vì sao Bot nghi ngờ:
• "Chào bạn, bên mình đang tuyển cộng tác viên làm nhiệm vụ online: xem video, tương tác bài…" → Tin mời làm nhiệm vụ hoặc việc nhẹ lương cao.

Bác nên làm:
• Cảnh giác với tin tuyển dụng lương cao, việc nhẹ trên mạng xã hội
• Không chuyển tiền, không đi theo người lạ để nhận việc
• Tìm hiểu kỹ đơn vị tuyển dụng qua kênh chính thức

Cảnh báo liên quan:
• Bộ Công an (Công an tỉnh Vĩnh Long) (06/2026)
```

## rs-ai-29

Tin gốc:

> Mời ông/bà tham dự hội thảo nhận voucher nghỉ dưỡng cao cấp miễn phí. Sau đó ký hợp đồng sở hữu kỳ nghỉ và chuyển trước 200.000.000đ tiền cọc, phí bảo lãnh dịch vụ vào STK 0000000000.

Nhãn: lừa đảo (N1.6). Bot: DANGEROUS (N1.6).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Giả doanh nghiệp báo trúng thưởng hoặc quà tri ân, bắt đóng phí trước
Bác ơi, lời mời tham dự hội thảo kèm yêu cầu chuyển tiền cọc là dấu hiệu lừa đảo cao. Xin bác không làm theo và cẩn thận với các ưu đãi quá tốt.
👉 Bác ĐỪNG chuyển tiền, ĐỪNG cài ứng dụng theo lời họ.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Bác nên làm:
• Không chuyển tiền để nhận thưởng, kể cả các khoản làm biển số, giấy tờ, vận chuyển hay đổi thưởng sang tiền mặt
• Không bấm link hoặc quét mã QR nhận thưởng

Cảnh báo liên quan:
• Bộ Công an (Công an tỉnh Thanh Hóa) (06/2025)
```

## rs-ai-40

Tin gốc:

> Anh ơi, cho em mượn tạm [SỐ TIỀN] với. Anh chuyển vào số tài khoản ngân hàng mang tên em nhé.

Nhãn: lừa đảo (N5.4). Bot: DANGEROUS (N5.4).

Bot trả lời:

```text
🔴 RẤT GIỐNG LỪA ĐẢO – Chiếm quyền Zalo hoặc Facebook rồi nhắn người quen vay tiền
Bác ơi, tin nhắn yêu cầu chuyển tiền vào tài khoản cá nhân có thể là lừa đảo. Bác nên xác nhận rõ với người quen trước khi làm theo.
👉 Bác ĐỪNG chuyển tiền.

ℹ️ Bot là công cụ hỗ trợ của nhóm sinh viên, có thể sai. Bot không bao giờ xin OTP, mật khẩu hay tiền.
```

```text
Bác nên làm:
• Gọi lại trực tiếp cho người quen bằng số điện thoại để xác nhận trước khi chuyển tiền
• Không bấm link lạ, không đọc mã xác thực cho ai

Cảnh báo liên quan:
• Bộ Công an (Công an tỉnh Phú Thọ) (09/2026)
• CafeF (dẫn cảnh báo của Công an) (05/2026)
```
