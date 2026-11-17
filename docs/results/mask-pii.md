# Đo mask PII (AI-31, #91)

## Kết luận

- Trên **151 mẫu heldout** (132 PII + 20 bẫy), `mask()` bản `main` bắt đúng **101/132** (CI 95% 69–83%). Sau khi chỉnh theo lỗi ở split dev, bản mới bắt **130/132** (CI 95% 95–100%). Che nhầm ở mẫu bẫy: **1/20 ở cả hai bản** (CI 1–24%, mẫu nhỏ nên kết luận về che nhầm yếu).
- Lỗi ở bản `main` nằm chủ yếu ở 3 loại: **OTP kh gọi tên mã** ("dãy số vừa gửi…"), **STK có dấu gạch/chấm hoặc nhóm cuối ngắn**, **email viết `[at]` có dấu cách**.
- **Đề xuất:** (1) nhận các thay đổi `mask.py` kèm đoạn test mới; (2) giữ nguyên quyết định "12 chữ số liền = CCCD" (mã đơn/bưu kiện 12 số bị che nhầm, chấp nhận vì tránh lộ CCCD thật, và `test_no_eval_sample_leaks…` đang yêu cầu điều đó); (3) AI1 duyệt 30 mẫu ngẫu nhiên trước khi coi bộ này là chuẩn (điều kiện "xong khi" của #91, **chưa làm**).
- **Đã quyết (AI1, review PR #158):** giữ phương án A ("12 chữ số liền = CCCD", kể cả sau mã đơn hàng); tạm giữ từ khoá "dãy số" / "N số", siết thêm điều kiện động từ khi có ca đời thường bị báo nhầm ("Dãy số Fibonacci 1 1 2 3 5 8" → `[OTP_1]`, đã chạy tay); bỏ từ khoá "mấy số" vì sau khi bỏ dấu trùng với "máy số" (xem mục "Thay đổi `mask.py`").

## Cách chạy lại

```
cd ai
uv run python ../eval/run_mask_eval.py --split dev        # chỉnh mask theo lỗi ở đây
uv run python ../eval/run_mask_eval.py --split heldout    # số cuối; không sửa mask theo lỗi của split này
```
- Code: nền `d989133` (`origin/main` 16/11/2026) cộng thay đổi `mask.py` + `test_mask.py` ở PR này. Bộ dữ liệu: `eval/mask_pii/mask_pii.jsonl` (500 dòng), sinh lại bằng `python eval/mask_pii/gen_mask_samples.py` (seed cố định, đã kiểm cho ra file giống hệt).
- Chạy ngày 2026-11-16, Python 3.12.14, Windows 10.0.26200. So với `main`: `--ai-src <ai/src của main>`.
- Heldout chỉ chạy để lấy số, không dùng để chỉnh mask. Chạy 2 lần: bản `main` (đối chứng) và bản đã sửa.

## Bộ dữ liệu

500 mẫu: **150 viết tay** (`hand.jsonl`, nháp bởi AI rồi người duyệt, `notes: ai_drafted`) và **350 sinh bằng script** (`notes: script_generated`). Số điện thoại, STK, CCCD, email, OTP đều bịa (ngẫu nhiên theo seed; không loại trừ được xác suất trùng số thật vì không có dải số dành riêng).

| Loại | Số mẫu |
|---|---|
| phone | 81 |
| stk | 78 |
| cccd | 73 |
| email | 71 |
| otp | 70 |
| spoken_number | 66 |
| bẫy (không có PII) | 66 |

Chia `dev` 349 / `heldout` 151 (70/30 theo loại, seed cố định). Số theo loại trong bảng đo dưới là số **PII** (một mẫu có thể có nhiều PII).

## Kết quả

Recall = (n − sót)/n; sót = `value` còn nguyên trong text sau mask. Wilson 95%.

### Heldout (số cuối)

| Loại | `main` | Bản sửa | CI 95% bản sửa |
|---|---|---|---|
| cccd | 20/22 | **22/22** | 85–100% |
| email | 13/21 | **21/21** | 85–100% |
| otp | 13/21 | **21/21** | 85–100% |
| phone | 19/25 | 23/25 | 75–98% |
| spoken_number | 19/20 | **20/20** | 84–100% |
| stk | 17/23 | **23/23** | 86–100% |
| **Tất cả** | **101/132** (69–83%) | **130/132** (95–100%) | |
| Che nhầm (bẫy) | 1/20 | 1/20 | 1–24% |

### Dev (dùng để chỉnh)

| Loại | `main` | Bản sửa |
|---|---|---|
| cccd | 49/51 | 51/51 |
| email | 37/50 | 50/50 |
| otp | 24/49 | 49/49 |
| phone | 49/56 | 56/56 |
| spoken_number | 42/46 | 45/46 |
| stk | 32/55 | 54/55 |
| **Tất cả** | 233/307 (71–80%) | 305/307 (98–100%) |
| Che nhầm (bẫy) | 2/46 | 2/46 |

Số dev của bản sửa **đã tối ưu theo chính split này**, kh dùng làm bằng chứng chất lượng.

## Thay đổi `mask.py`

- OTP: từ khoá "dãy số", "số xác nhận/xác thực", "số bảo mật/bí mật", "mã bí mật", "4–8 số", "số trong tin". Không dùng "mấy số": `mask()` so trên chuỗi đã bỏ dấu nên "mấy số" và "máy số" là một ("Máy số 2 ở phòng khám, bác đọc số thứ tự 1234…" sẽ bị che `1234` thành `[OTP_1]` và bật `R_OTP_REQUEST`). "Mấy số trong tin" vẫn bị che nhờ "số trong tin". Bỏ từ khoá này không đổi số dev/heldout/che nhầm.
- STK: nhận số nhóm bằng `-` hoặc `.` (`4246-2640-6564`, `5604.1989.0273.2`), nhóm cuối ngắn (`9681 3118 81`), số đứng trước tên ngân hàng; từ khoá "sổ tiết kiệm", "số thẻ", "Napas"; chặn ngày dạng `2026.11.16`; giới hạn 19 chữ số.
- Email: cho phép dấu cách quanh `[at]` và `[dot]`.
- Phone: `09 12 345 678`, `(0533) 504407`, số bàn `024 3567 8901`.
- Số đọc bằng chữ: nhận "zero".
- CCCD: từ khoá "giấy tờ tùy thân" (CMND 9 số).

## Ví dụ lỗi còn lại (heldout)

Sót (2):
- `hand-c-014` phone: `0912⏎345678` (số bị xuống dòng giữa chừng).
- `hand-c-032` phone: `0 9 0 1 – 2 3 4 – 5 6 7` (từng chữ số cách nhau, gạch dài `–`).

Che nhầm (1):
- `hand-a-001`: mã đơn 12 số bị đổi thành `[CCCD_1]` ("Đơn hàng 123456789012 …"). Theo thiết kế.

Sót còn lại ở dev (2): `hand-d-013` (mã 4 số đọc bằng chữ; hạ ngưỡng sẽ che nhầm câu thường như "ba năm sau…"), `hand-c-031` (số thẻ 20 số cách nhóm).

Che nhầm ở dev (2): `hand-a-029` (bưu kiện 12 số), `hand-a-043` (mã tủ locker bị che thành OTP; có thể đúng vì là mã dùng một lần).

## Che nhầm đã biết và quyết định

Chọn **phương án A: giữ nguyên thiết kế "che thừa còn hơn sót"**, không sửa che nhầm trong PR này.

| Mẫu | Bị che thành | Lý do giữ |
|---|---|---|
| Mã 12 số: `hand-a-001`, `hand-a-029` ("Đơn hàng 123456789012", "bưu kiện số 987654321098") | `[CCCD_1]` | Quy tắc "12 chữ số liền = CCCD"; test `test_no_eval_sample_leaks…` (tin OCR ViettelPost "đơn hàng 154253046770") yêu cầu kh còn dãy số dài sau mask |
| Mã tủ locker `hand-a-043` | `[OTP_1]` | Mã dùng một lần, che hợp lý; giữ nhãn bẫy để số đo trung thực |
| "Dãy số Fibonacci 1 1 2 3 5 8", "6 số seri 123456" (chạy tay, ngoài bộ đo) | `[OTP_1]` | Hệ quả từ khoá "dãy số", "N số"; chưa thu hẹp |

- **Đã thử rồi gỡ:** loại 12 số sau "đơn hàng/bưu kiện/vận đơn/giao dịch…". Làm test leak ở trên đỏ; muốn làm phải sửa test đó, và CCCD thật đứng sau chữ "đơn hàng" sẽ lọt.
- **Chưa làm, để bàn:** kiểm 3 số đầu có phải mã tỉnh 001–096 (chưa xác nhận dải mã từ nguồn chính thống; sai thì lọt CCCD thật); thu hẹp "dãy số"/"N số" chỉ khi có động từ đọc/gửi/báo/nhập (có thể sót vài kiểu OTP; chưa đo).
- **Hệ quả:** `kb/rules.yaml` có luật khớp trên token `[otp_n]` (`R_OTP_REQUEST`, `R_OTP_GENUINE`), nên số bị che nhầm thành `[OTP_n]` có thể làm luật OTP chạy. **Chưa kiểm** tác động lên điểm. Token `[CCCD_n]` không có luật nào dùng (đã grep).

## Chưa kiểm / hạn chế

- **AI1 chưa duyệt 30 mẫu ngẫu nhiên.** Bộ do AI nháp và người duyệt; chưa có người thứ hai.
- **Khung câu lặp:** các mẫu sinh dùng 10 khung câu mỗi loại, chỉ đổi số và cách viết. Số đo thể hiện khả năng bắt **định dạng số**, kh đo độ đa dạng ngôn ngữ tin thật. Số ngoài đời có thể thấp hơn.
- **Chưa kiểm** với tin nhắn người dùng thật; mẫu bẫy chỉ 20 (heldout) nên Wilson CI rộng (1–24%).
- So sánh `mask()` `main` và bản sửa trên **384 tin có sẵn** (`eval/datasets/*.jsonl`, `ai/tests/data/otp_pairs.jsonl`, OCR) cho **0 tin thay đổi**, nên kh có bằng chứng che nhầm mới, nhưng kh có biến thể mới trong các tin đó.
- Từ khoá "dãy số" và "N số" là rủi ro che nhầm: "Dãy số Fibonacci 1 1 2 3 5 8" sẽ bị che. **Chưa đo.**
- Quyết định "12 chữ số liền = CCCD" kh đổi; mã đơn/bưu kiện 12 số vẫn bị che nhầm.
- Số bằng chữ chỉ che khi có ≥ 6 từ chỉ chữ số liền nhau; số tiền bằng chữ kh che (theo docstring `mask.py`).
