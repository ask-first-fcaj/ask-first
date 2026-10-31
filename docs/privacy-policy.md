# Chính sách quyền riêng tư và lưu ý khi dùng bot "Hỏi Trước"

**Phiên bản:** v0.1 · **Ngày:** 30/10/2026 · **Trạng thái:** nháp, chờ mentor đọc

<!-- SH-07 (docs/implementation-plan.md). Nguồn chính: ARCH = docs/system-architecture.md v3.5, PLAN = docs/implementation-plan.md v3.1.
     Bản này mô tả phạm vi MVP 6 tuần: chưa có voice, chưa có VLM fallback, chưa có hỏi thêm nhiều lượt (PLAN "Thay đổi v3").
     Mỗi lần sửa nội dung file này là đổi policy_sha256 → mọi người dùng phải đồng ý lại (ARCH §5.3, BE-25). Chốt bản v1.0 trước khi bật BE-25 cho người ngoài team.
     Khi FE-00 render trang này: bỏ các HTML comment ra khỏi trang công khai. -->

## 1. Bot là gì, không phải là gì

- "Hỏi Trước" là bot Telegram giúp bạn kiểm tra tin nhắn, đường link hoặc ảnh chụp màn hình mà bạn thấy đáng ngờ, **trước khi** làm theo. <!-- README; ARCH §1.1 -->
- Đây là **dự án học tập của nhóm sinh viên FCAJ**. Bot **không phải** cơ quan nhà nước, công an hay ngân hàng. <!-- README; ARCH §6.8 (chân tin), §9.3 (FRS-076) -->
- Bot **có dùng trí tuệ nhân tạo (AI)** để đọc và đánh giá nội dung. Bot **có thể sai**. Kết quả chỉ để bạn tham khảo. <!-- ARCH §6.8, D4, D7 -->
- Bot chỉ nói nội dung "có **dấu hiệu** lừa đảo". Bot không kết luận một người cụ thể là kẻ lừa đảo. <!-- ARCH §9.1 -->
- Bot không chặn được cuộc gọi hay chặn chuyển khoản. <!-- ARCH §1.4 -->
- **Bot không bao giờ xin mã OTP, mật khẩu hay tiền.** Ai xin những thứ này, kể cả tự xưng là bot, đều là lừa đảo. <!-- ARCH §5.3, §6.8, §6.10 -->
- Bot chỉ có một tài khoản chính thức: **@TODO_username**. Hãy cẩn thận với bot có tên na ná. <!-- TODO: điền @username của bot prod. ARCH §9.3 (FRS-076) yêu cầu công bố trên trang chính sách. -->

**Khi đang nghi bị lừa:** dừng lại, chưa chuyển tiền. Gọi **113** nếu khẩn cấp, hoặc báo công an xã/phường gần nhất. Nếu đã lỡ chuyển tiền hay đưa mã OTP, gọi ngay tổng đài chính thức của ngân hàng để khoá tài khoản.
<!-- 113: bocongan.gov.vn, bài "Cảnh sát 113 - Điểm tựa trong những tình huống khẩn cấp" (11/07/2026): https://bocongan.gov.vn/bai-viet/bac-ninh-canh-sat-113-diem-tua-trong-nhung-tinh-huong-khan-cap-1783907449 — truy cập 30/10/2026.
     Công an xã/phường, hotline ngân hàng: research-concept §3.3. Số hotline cụ thể khác (A05, 156/5656, từng ngân hàng) chưa xác minh → chờ SH-05 / kb/hotlines.yaml, không đưa vào đây. -->

## 2. Bot thu thập những gì

- **Nội dung bạn gửi:** tin nhắn, đường link, ảnh chụp màn hình. Bot chỉ đọc nội dung **sau khi bạn bấm "Tôi đồng ý"**. <!-- ARCH §1.2 FR1, §5.3 -->
- **Thông tin Telegram:** mã tài khoản Telegram và mã cuộc trò chuyện, để bot trả lời đúng người. <!-- ARCH §7 PROFILE: uid = tg:{telegram_user_id}, chat_id -->
- **Kết quả mỗi lần kiểm tra:** thời điểm, loại tin, mức nguy cơ, tên kiểu lừa đảo, mã các dấu hiệu, và ý kiến "Bot báo sai" nếu bạn bấm. **Không có nội dung tin.** <!-- ARCH §7 CHECK# -->
- **Sự đồng ý của bạn:** bạn đồng ý mục nào, lúc nào, theo phiên bản chính sách nào. <!-- ARCH §5.3, §7 PROFILE.consent, AUDIT# -->
- **Cài đặt:** cách xưng hô bạn chọn. <!-- ARCH §5.3 /caidat, §7 PROFILE.prefs -->
- **Nếu bạn liên kết người thân:** mã Telegram của người thân, mức chia sẻ, và số điện thoại của người thân nếu người thân tự bấm gửi (không bắt buộc). <!-- ARCH §5.4 bước 3, §7 GUARDIAN# -->
- Hiện bot **chưa nhận tin thoại (voice)**. <!-- PLAN "Thay đổi v3": voice/ASR hoãn, webhook trả "chưa hỗ trợ voice". TODO: khi bật AI-21/AI-28 phải thêm mục voice (đồng ý riêng, ARCH §5.3) và nâng phiên bản. -->

## 3. Dùng để làm gì

1. Đánh giá tin bạn gửi và trả lời bạn.
2. Báo cho người thân khi có nguy cơ cao, **chỉ khi** cả bạn và người thân đã đồng ý (xem mục 6).
3. Cải thiện bot, **chỉ khi** bạn bấm "Đóng góp mẫu" và đồng ý riêng. Nhóm đọc lại các mẫu này để sửa lỗi cho bot. <!-- ARCH §5.6, §6.11 "Vòng phản hồi online" -->
4. Chống lạm dụng: mỗi người dùng được kiểm tra tối đa 30 lượt mỗi giờ và 100 lượt mỗi ngày. <!-- ARCH §6.10, §7 RATE# -->

## 4. Bảo vệ và lưu bao lâu

- **Che số trước:** số điện thoại, số tài khoản, số CCCD, mã OTP, email trong tin được che (ví dụ thành `[SĐT_1]`) **trước khi** gửi cho AI bên ngoài, trước khi ghi nhật ký và trước khi báo người thân. Đường link không bị che vì đó là dấu hiệu chính để kiểm tra. <!-- ARCH §9.1, §9.2 -->
- **Ảnh** được đọc chữ ngay trên máy chủ của bot rồi bỏ, **không lưu lại**. <!-- ARCH D4, §3.1 (S3 "không lưu ảnh hay voice"), §6.3; PLAN INT-02 (4). TODO: ARCH §9.1 có ngoại lệ gửi ảnh (đã làm mờ vùng số) tới VLM khi OCR thất bại; ngoại lệ này đang hoãn (AI-27). Nếu bật thì phải sửa câu này và nâng phiên bản. -->
- Nhật ký hệ thống **không ghi nội dung tin**. <!-- ARCH §9.3; README -->

| Dữ liệu | Giữ bao lâu |
|---|---|
| Nội dung tin gốc | Vài giây, tối đa **10 phút** để gom các tin gửi liền nhau; xoá ngay sau khi xử lý xong <!-- ARCH §7 BUFFER#, §9.1 --> |
| Bản tin **đã che số** | **30 phút** (để bạn kịp bấm "Đóng góp mẫu") <!-- ARCH §7 SESSION#; PLAN dòng ~644 --> |
| Mẫu bạn tự nguyện đóng góp (đã che số) | **30 ngày** <!-- ARCH §5.6 (S3 donated/, lifecycle 30 ngày) --> |
| Kết quả kiểm tra, trạng thái cảnh báo người thân (không có nội dung) | **30 ngày** <!-- ARCH §7 CHECK#, ALERT#; D10 --> |
| Kết quả tạm theo mã băm của tin, kết quả kiểm tra link (không có nội dung) | **24 giờ** <!-- ARCH §7 CACHE#, URL# --> |
| Mã mời người thân | **24 giờ**, dùng một lần <!-- ARCH §5.4, §7 INVITE# --> |
| Nhật ký hệ thống (không có nội dung) | **7 ngày** <!-- ARCH §12 bảng chi phí: "CloudWatch Logs (retention 7 ngày)". TODO: ghi retention này vào infra/template.yaml (BE-03/BE-08) để khớp. --> |
| Hồ sơ, sự đồng ý, liên kết người thân | Tới khi bạn xoá (`/xoadulieu`) |

<!-- TODO (chưa có trong ARCH/PLAN, không tự ghi số):
     (1) AUDIT# chưa có thời hạn: ARCH §7 ghi "⚠️ theo tư vấn pháp lý".
     (2) DynamoDB bật PITR (ARCH §3.1; PLAN BE-03, BE-11): bản sao lưu có thể còn dữ liệu đã xoá một thời gian. Cần team chốt và ghi thời hạn thật vào bảng.
     (3) Các bộ đếm chống lạm dụng (RATE#: 2 giờ / 2 ngày) và DEDUP# (24 giờ) không ghi vào bảng cho gọn; nếu mentor muốn đủ thì thêm. -->

## 5. Dữ liệu được gửi tới đâu

- **Amazon Web Services (AWS):** máy chủ của bot đặt tại **Singapore**. Phần AI đánh giá tin (dịch vụ Amazon Bedrock) chạy tại **Sydney (Úc)** và **chỉ nhận chữ đã che số**, không nhận ảnh. Theo AWS, nhà làm ra mô hình AI không xem được nội dung này. <!-- ARCH D1, D4, D5, §3.1, §9.1 (dòng "Chuyển dữ liệu ra nước ngoài", nguồn [A11]) -->
- **Telegram:** tin bạn gửi bot vẫn nằm trên máy chủ của Telegram. Tin trò chuyện với bot **không được mã hoá đầu cuối**. Bot không xoá được các tin này giúp bạn. <!-- ARCH §2.3 mục 5, §5.6 -->
- **Kiểm tra đường link:** bot có thể tra tên miền của link với dịch vụ tra cứu tên miền công khai, và mở thử link rút gọn để xem link thật. Bot không gửi thông tin của bạn khi tra. <!-- ARCH §6.4 url_intel: RDAP, U_SHORTENER (HEAD, không tải body). TODO: nếu bật Google Safe Browsing/Web Risk (tuỳ chọn, ARCH §6.4, Q8) thì ghi thêm tên dịch vụ đó. -->
- **Nhóm phát triển:** thành viên nhóm xem được số liệu vận hành, và đọc được các mẫu bạn đã tự nguyện đóng góp. <!-- ARCH §5.6, §5.8, §11 -->
- Dữ liệu được xử lý ở nước ngoài (Singapore, Úc, và máy chủ của Telegram). Bot hiện chỉ cho thành viên nhóm và người nhà dùng thử, và chỉ khi họ đã đồng ý. <!-- ARCH §9.1 "Chỉ nhận người tham gia pilot đã đồng ý"; PLAN "Thay đổi v3": test nội bộ (team + người nhà) ở W6. TODO: mentor xem có cần hồ sơ đánh giá tác động chuyển dữ liệu xuyên biên giới (Luật 91/2025 Điều 20) không. -->

## 6. Người thân

- Liên kết cần **cả hai bên đồng ý**: bạn gửi link mời, người thân bấm đồng ý, rồi bạn xác nhận lại. Mỗi người tối đa 5 người thân. <!-- ARCH §5.4 bước 1–5 -->
- Bot chỉ tự báo người thân khi tin **rất nguy hiểm** và có việc rủi ro như chuyển tiền, đưa OTP. Khi chỉ đáng ngờ, bạn tự quyết có bấm "Báo cho con" hay không. <!-- ARCH §5.5 -->
- Bạn chọn người thân thấy bao nhiêu: **Mức 1** (mặc định): chỉ biết có nguy cơ. **Mức 2**: thêm tên kiểu lừa đảo và dấu hiệu chính. **Mức 3**: thêm 1–2 dòng tóm tắt đã che số. <!-- ARCH §5.4 bước 4, §5.5 -->
- Người thân **không bao giờ** nhận ảnh gốc hay số đầy đủ, và **không xem được** lịch sử kiểm tra của bạn. <!-- ARCH §5.5 "Mọi mức", §5.9 (BR-030) -->
- Mỗi bên có thể huỷ liên kết bất cứ lúc nào bằng lệnh `/nguoithan`. <!-- ARCH §5.4 bước 6, §5.9 -->

## 7. Quyền của bạn

Bạn làm ngay trong cuộc trò chuyện với bot:

- `/lichsu`: xem 10 lần kiểm tra gần nhất (giờ, mức nguy cơ, tên kiểu lừa đảo). <!-- ARCH §5.9 -->
- `/nguoithan`: xem, đổi mức, tạm dừng hoặc gỡ người thân. <!-- ARCH §5.9 -->
- `/rutdongy`: rút đồng ý, toàn bộ hoặc từng mục. Rút mục "Đóng góp mẫu" thì các mẫu đã góp cũng bị xoá. <!-- ARCH §5.6 -->
- `/xoadulieu`: xoá toàn bộ dữ liệu của bạn và huỷ mọi liên kết người thân. <!-- ARCH §5.6; PLAN BE-31 -->

Bot luôn hỏi lại trước khi rút đồng ý hay xoá, rồi làm **ngay**. <!-- ARCH §5.9 (xác nhận 2 bước), §5.6 ("thực hiện ngay lập tức") -->
Sau khi xoá, bot chỉ giữ một dòng ghi nhận đã xoá (mã đã mã hoá một chiều, loại yêu cầu, thời điểm) để chứng minh đã làm theo yêu cầu. <!-- ARCH §5.6 (AUDIT: uid đã hash). "mã hoá một chiều" = hash; xem TODO thời hạn ở mục 4. -->
Tin cũ trong Telegram thì bạn tự xoá cuộc trò chuyện trong ứng dụng Telegram. <!-- ARCH §5.6 -->
Bạn có thể đồng ý lại bất cứ lúc nào. Khi chính sách này đổi, bot sẽ hỏi bạn đồng ý lại trước khi kiểm tra tiếp. <!-- ARCH §5.3, §5.6 -->

<!-- TODO (chưa có lệnh trong ARCH/PLAN, không tự đặt tên lệnh):
     - Xem toàn bộ dữ liệu đang lưu (hồ sơ, sự đồng ý) / xuất dữ liệu: chưa có lệnh; /lichsu chỉ hiện CHECK#.
     - Chỉnh sửa dữ liệu (Luật 91/2025 Điều 4 khoản 1 điểm c): chỉ có /caidat (xưng hô) và /nguoithan.
     - Khiếu nại: chưa có kênh, tạm dùng mục 8. -->

## 8. Liên hệ

Có thắc mắc hoặc muốn khiếu nại, xin liên hệ nhóm FCAJ: **TODO** (email/kênh liên hệ do nhóm quyết định).
<!-- TODO: điền kênh liên hệ thật. Không tự đặt email. -->

## Căn cứ

Chính sách này được viết theo các văn bản dưới đây. Đây là tóm tắt của nhóm sinh viên, **không phải tư vấn pháp lý**.

- Luật Bảo vệ dữ liệu cá nhân số **91/2025/QH15**, do **Quốc hội** thông qua ngày 26/6/2025, có hiệu lực từ **01/01/2026** (quyền của người dùng: Điều 4; rút lại đồng ý: Điều 10). Văn bản: [vbpl.vn](https://vbpl.vn/TW/Pages/vbpq-van-ban-goc.aspx?ItemID=179252) · [vanban.chinhphu.vn](https://vanban.chinhphu.vn/?pageid=27160&docid=214590&classid=1&typegroupid=3)
- Nghị định số **356/2025/NĐ-CP** của **Chính phủ**, quy định chi tiết Luật Bảo vệ dữ liệu cá nhân, ban hành 31/12/2025, hiệu lực từ **01/01/2026**. Văn bản: [vanban.chinhphu.vn](https://vanban.chinhphu.vn/?pageid=27160&docid=216387) · [vbpl.vn](https://vbpl.vn/en/van-ban/chi-tiet/nghi-dinh-so-356-2025-nd-cp-quy-dinh-chi-tiet-mot-so-dieu-va-bien-phap-thi-hanh-luat-bao-ve-du-lieu-ca-nhan--187276)

<!-- Kiểm 30/10/2026:
     - Luật 91/2025/QH15: verified. Số, cơ quan, ngày thông qua 26/6/2025, hiệu lực 01/01/2026 (Điều 38 khoản 1), Điều 4 và Điều 10: đọc từ bản Công báo số 971+972 ngày 24/7/2025 (https://congbaocdn.chinhphu.vn/CongBaoCP/VanBan/2025/6/45578/57730-1-2025971-97291-2025-qh15.pdf).
     - NĐ 356/2025/NĐ-CP: số, cơ quan, ngày ban hành 31/12/2025, hiệu lực 01/01/2026 verified trên vanban.chinhphu.vn. Nội dung từng điều: NOT VERIFIED (PDF chính thức là bản scan, không đọc được chữ).
     - NĐ 13/2023/NĐ-CP: KHÔNG đưa vào Căn cứ. Việc NĐ 356 "thay thế" NĐ 13 (research-concept §3.1) NOT VERIFIED trên nguồn chính thức; Luật 91 Điều 39 chỉ nhắc NĐ 13 trong quy định chuyển tiếp. -->
