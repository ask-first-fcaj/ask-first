# Đáp án gán thử 10 mẫu (SH-02)

> Chỉ mở sau khi cả 4 người (AI1, AI2, BE1, BE2) đã gán xong `eval/labeling_trial/samples.md` một cách độc lập.
>
> Đáp án theo **`eval/LABELING.md` bản 2** (mã kịch bản research-concept §2.2). Bản 1 dùng nhóm cũ `N1`–`N6`; cách đổi ở `LABELING.md` mục 12.

## 1. Đáp án

| Mẫu | `scenario` | `group` | Căn cứ trong `LABELING.md` | Nguồn |
|:---:|:---:|:---:|---|---|
| 01 | `N1.3` | N1 | Giả SMS VPBank báo đăng nhập lạ, dụ vào link giả. Mạo danh ngân hàng → `N1.3` (mục 3, quy tắc 5). | Công an Gia Lai cảnh báo SMS giả ngân hàng (link trong `samples.md`) |
| 02 | `N3.3` | N3 | **Mẫu biên:** có "hải quan sân bay" đòi phí, nhưng bối cảnh tình cảm ("em yêu", "anh David", chuẩn bị cưới) → `N3.3`, không phải `N1.4` (mục 5, quy tắc 3). | Công an xã Bát Xát (link trong `samples.md`) |
| 03 | `N0` | N0 | Báo giao hàng: không đòi phí, không link, không đòi OTP (mục 2, quy tắc 8). | Mẫu tự dựng |
| 04 | `N1.6` | N1 | Giả Shopee báo trúng iPhone, đòi 850.000đ phí vận chuyển trước. Bên bị mạo danh là sàn TMĐT → `N1.6` (mục 3, quy tắc 5). | Công an Thanh Hóa (link trong `samples.md`) |
| 05 | `N5.4` | N5 | **Mẫu biên:** tin chữ từ tài khoản bạn thân xin vay gấp, không có mặt/giọng giả → `N5.4`, không phải `N2.1` hay `N1.2` (mục 7, quy tắc 4). | CafeF dẫn cảnh báo của công an (link trong `samples.md`) |
| 06 | `N1.4` | N1 | **Mẫu biên:** xưng "công an phường" nhưng nội dung là hồ sơ định danh/VNeID, dụ tải app qua link lạ. Bên bị mạo danh là dịch vụ định danh VNeID → `N1.4`, không phải `N5.1` (mục 3, quy tắc 5). Không có "vụ án", "tài khoản an toàn" nên không phải `N1.1`. | Bộ Công an (link trong `samples.md`) |
| 07 | `N2.1` | N2 | Có cuộc gọi video 4 giây thấy mặt con trai mờ ảo rồi tắt → có dấu hiệu mặt/giọng giả → `N2.1`, thắng `N1.2`/`N5.4` (mục 4, quy tắc 2). | Học viện Kỹ thuật và Công nghệ An ninh (link trong `samples.md`) |
| 08 | `N0` | N0 | SMS biến động số dư thật: có ngân hàng và số tiền, nhưng không link, hotline chính thức, không bảo giữ bí mật (mục 2, quy tắc 8). | Mẫu tự dựng |
| 09 | `N1.4` | N1 | **Mẫu biên:** bảo đăng nhập tài khoản ngân hàng, nhưng bên bị mạo danh là cơ quan thuế → `N1.4`, không phải `N1.3` (mục 3, quy tắc 5). | Mẫu tự dựng |
| 10 | `N3.1` | N3 | Tuyển xem video TikTok tại nhà, lương theo giờ, dẫn sang Telegram → việc nhẹ lương cao (mục 5, quy tắc 6). | Công an tỉnh Vĩnh Long (link trong `samples.md`) |

Mẫu 06 là ca khó nhất (`N1.1` hay `N1.4`). Nếu nhóm lệch ở mẫu này, sửa ranh giới `N1.1`/`N1.4` trong `LABELING.md` mục 3 trước khi gán lại.

## 2. Kết quả gán thử

| Mẫu | Đáp án | AI1 | AI2 | BE1 | BE2 | Cùng `scenario` | Cùng `group` | Ghi chú |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|---|
| 01 | `N1.3` | | | | | | | |
| 02 | `N3.3` | | | | | | | |
| 03 | `N0` | | | | | | | |
| 04 | `N1.6` | | | | | | | |
| 05 | `N5.4` | | | | | | | |
| 06 | `N1.4` | | | | | | | |
| 07 | `N2.1` | | | | | | | |
| 08 | `N0` | | | | | | | |
| 09 | `N1.4` | | | | | | | |
| 10 | `N3.1` | | | | | | | |

- Cùng `scenario`: `... / 10` (cần ≥ 8). Cùng `group`: `... / 10`.
- Đạt hay phải sửa guide và gán lại bằng 10 mẫu mới (`LABELING.md` mục 11, bước 4)?
