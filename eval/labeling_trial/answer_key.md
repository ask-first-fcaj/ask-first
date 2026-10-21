# Đáp án chuẩn và Bảng tổng hợp kết quả gán thử (SH-02)

> **Lưu ý:** File này chứa đáp án chuẩn và phân tích chi tiết. Chỉ mở ra đối chiếu sau khi cả 4 thành viên (AI1, AI2, BE1, BE2) đã hoàn thành gán nhãn độc lập cho 10 mẫu trong `eval/labeling_trial/samples.md`.

---

## 1. ĐÁP ÁN CHUẨN & PHÂN TÍCH CĂN CỨ THEO `LABELING.md`

| Mẫu | Nhãn chuẩn | Phân loại | Căn cứ & Quy tắc phân giải theo `LABELING.md` | Nguồn tham khảo / Cảnh báo thực tế |
|:---:|:---:|---|---|---|
| **01** | **N2** | Mạo danh ngân hàng | Giả mạo tin nhắn thương hiệu VPBank, tạo tâm lý hoang mang (đăng nhập lạ ở nước ngoài) để dụ bấm link phishing `vpbank-xacthuc[.]top`. Khớp Mục 3. | Báo Tuổi Trẻ / Công an Gia Lai cảnh báo SMS Brandname mạo danh ngân hàng. |
| **02** | **N5** | **Mẫu biên 1** (Tình cảm vs Hải quan) | Dù có tình tiết đóng phí Hải quan sân bay, nhưng tin nhắn có bối cảnh tình cảm yêu đương ("em yêu", "David", chuẩn bị cưới, bạn gửi quà). Áp dụng Mục 6 và **Quy tắc 4** -> **N5**. | Công an Lào Cai triệt phá: https://www.bocongan.gov.vn/bai-viet/cong-an-xa-bat-xat-kip-thoi-ngan-chan-vu-lua-dao-qua-tang-quoc-te-giup-nguoi-dan-tranh-mat-140-trieu-dong-1783613735 |
| **03** | **N0** | Lành tính thường | Tin nhắn shipper thật: không yêu cầu bấm link, không đòi OTP/mật khẩu, không yêu cầu chuyển tiền. Khớp Mục 1. | Mẫu tự dựng theo định dạng thông báo giao hàng thực tế của bưu chính SPX Express. |
| **04** | **N4** | Trúng thưởng / Tri ân | Thông báo trúng điện thoại iPhone giá trị lớn từ chương trình tri ân, nhưng bắt đóng 850k phí bảo hiểm vận chuyển trước. Khớp Mục 5. | Công an Thanh Hóa triệt phá: https://bocongan.gov.vn/bai-viet/thanh-hoa-triet-xoa-o-nhom-lua-dao-chiem-doat-tai-san-bang-hinh-thuc-mua-hang-boc-tham-trung-thuong-d22-t45630 |
| **05** | **OTHER** | **Mẫu biên 2** (Hack nick vs N6) | Nick bạn thân bị hack/chiếm quyền nhắn tin vay tiền bằng chữ thông thường. Không có video Deepfake (khác N6), không mạo danh tổ chức (khác N1, N2). Khớp Mục 8 và **Quy tắc 6** -> **OTHER**. | Báo CafeF / Công an cảnh báo: https://cafef.vn/cong-an-canh-bao-chieu-chiem-quyen-tai-khoan-zalo-ma-nguoi-dan-can-canh-giac-188260521103656632.chn |
| **06** | **N1** | Mạo danh nhà nước | Giả danh công an phường, lấy cớ sáp nhập đơn vị hành chính để dụ tải app VNeID chứa mã độc qua link lạ `dichvucong-vneid[.]cc`. Khớp Mục 2. | Bộ Công an cảnh báo: https://bocongan.gov.vn/bai-viet/canh-bao-thu-doan-lua-dao-cai-ung-dung-vneid-gia-mao-d22-t45880 |
| **07** | **N6** | Tống tiền / Deepfake | Có cuộc gọi video ngắn Deepfake nhại mặt con trai, kết hợp tin nhắn khẩn cấp mượn tiền nộp phạt/chuộc người. Khớp Mục 7 và **Quy tắc 3** -> **N6**. | Học viện Kỹ thuật & Công nghệ An ninh - Bộ Công an: https://hvktcnan.bocongan.gov.vn/TrangChu/tin-tuc/1931-phong-chong-hoat-dong-loi-dung-cong-nghe-deepfake-lua-dao-tren-khong-gian-mang.html |
| **08** | **N0** | Lành tính dễ nhầm | SMS trừ tiền dịch vụ ngân hàng thật: có nhắc ngân hàng và số tiền trừ, nhưng số hotline chính thống (`1900545413`), không có link lạ, không bảo giữ bí mật. Áp dụng Mục 1 và **Quy tắc 5** -> **N0**. | Mẫu tự dựng theo định dạng SMS biến động số dư thực tế từ ngân hàng Vietcombank. |
| **09** | **N1** | **Mẫu biên 3** (Thuế vs Ngân hàng) | Có nhắc tới "đăng nhập tài khoản ngân hàng" nhưng bên bị mạo danh là **Tổng cục Thuế (cơ quan nhà nước)**. Áp dụng Mục 2 và **Quy tắc 1 & 2** (mạo danh cơ quan nhà nước thắng) -> **N1**. | Mẫu tự dựng theo cảnh báo của Tổng cục Thuế về đường link giả mạo `gdtgov[.]cfd`. |
| **10** | **N3** | Việc nhẹ lương cao | Mời làm cộng tác viên xem video TikTok kiếm 300k–500k/ngày, không cần kinh nghiệm, dẫn dụ chat qua Telegram để nạp tiền làm nhiệm vụ. Khớp Mục 4. | Công an Vĩnh Long cảnh báo: https://bocongan.gov.vn/bai-viet/cong-an-tinh-vinh-long-canh-giac-voi-bay-viec-nhe-luong-cao-1780907526 |

---

## 2. BẢNG TỔNG HỢP KẾT QUẢ GÁN THỬ 4 NGƯỜI

| Mẫu | Đáp án chuẩn | AI1 | AI2 | BE1 | BE2 | Thống nhất (Đ/S) | Ghi chú tranh luận |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|---|
| 01 | **N2** | | | | | | |
| 02 | **N5** | | | | | | |
| 03 | **N0** | | | | | | |
| 04 | **N4** | | | | | | |
| 05 | **OTHER** | | | | | | |
| 06 | **N1** | | | | | | |
| 07 | **N6** | | | | | | |
| 08 | **N0** | | | | | | |
| 09 | **N1** | | | | | | |
| 10 | **N3** | | | | | | |

- **Tổng số mẫu thống nhất:** `... / 10`
- **Kết luận:** Đạt điều kiện nghiệm thu (≥ 8/10) hay cần hiệu chỉnh lại guide?
