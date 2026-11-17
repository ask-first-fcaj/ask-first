# Kết quả AI-30 (#90): luật OTP trên bộ tin OTP thật và tin lừa

> Người làm: AI2. Ngày đo: 2026-11-16. Commit: `d989133` (`main`). Mốc 2 của #90 theo `docs/ai-viec-nghien-cuu-phu.md`.

## Kết luận

- `R_OTP_REQUEST` và `R_OTP_GENUINE` **không giẫm lên nhau** trên bộ này. Luật bắt đủ 100/100 tin lừa và 25/25 tin có câu phủ định giả. `R_OTP_GENUINE` không bật trên tin lừa nào. Chỉ 1/100 tin thật bị báo nhầm (`otp-b-006`).
- **Số này lạc quan, không phải phép đo độc lập.** Luật AI-23 (#128) được viết và chỉnh trên chính bộ này. `ai/tests/unit/test_signals.py::test_otp_pairs_request_and_genuine_do_not_step_on_each_other` đã khoá đúng các kết quả trên. Muốn biết luật đúng tới đâu với tin mới thì cần một tập kiểm độc lập, chưa có.
- `R_OTP_GENUINE` (−20) **sót 4/34 tin thật có câu cảnh báo**. Các câu bị sót là câu ngắn không có tân ngữ ("Tuyệt đối không chia sẻ.") và tiếng Anh ("Do not share it with anyone"). Tác động nhỏ, vì −20 chỉ hạ điểm.
- `otp-b-006` là báo nhầm đã chấp nhận, đúng như ghi chú trong test. Tin này là SMS thật của MobiFone xin "cung cấp mã cho Mcredit nếu QK đồng ý". Nếu nới luật cứng cho trường hợp này, kẻ gian chèn được câu "nếu QK đồng ý…" để né.
- **Đã quyết (AI1, review PR #159):**
  - giữ `otp-b-006` là báo nhầm chấp nhận;
  - thêm mẫu câu ngắn và "Do not share" cho `R_OTP_GENUINE` ở PR riêng, vì đổi `rules.yaml` là đổi điểm, cần qua eval gate;
  - giữ bộ ở `ai/tests/data/`, chưa chuyển vào `eval/datasets/`, để không làm lệch split của eval gate (chuyển vào thì split `benign_suspicious` tăng từ 20 lên 120 tin);
  - AI1 đã xem các mẫu, nhãn ổn.

## Cách chạy lại

```
cd ai
uv run python ../eval/run_otp_rules.py      # mặc định ai/tests/data/otp_pairs.jsonl
```

Script chạy `RealPipeline` thật ở chế độ chỉ luật (`llm_enabled=False`, `feature_rag=False`): normalize → mask → rules → fuse. Không gọi mạng, không cần AWS. Chạy trên Windows 10.0.26200, Python 3.12.

## Dữ liệu

`ai/tests/data/otp_pairs.jsonl`, vào `main` cùng #128, định dạng eval của plan §3.8:

| | Số mẫu | Ghi chú |
|---|---|---|
| Tin thật (`otp-b-*`, `benign_suspicious`, N0, `max_level` UNSURE) | 100 | SMS OTP và tin biến động số dư; 12 tin không có mã OTP (phần lớn là biến động số dư). Mã và số bịa, một phần dựa trên ảnh chụp tin thật (ảnh không commit) |
| Tin lừa (`otp-s-*`, `real_scam`, N1.1, `min_level` SUSPICIOUS) | 100 | 7 có `source_url` (bocongan.gov.vn, cand.vn, cntt.moj.gov.vn, tuoitre.vn, vnexpress.net); 93 ghi `notes` là tự viết |
| Trong đó `neg-trap` | 25 | chèn câu "không cung cấp mã cho bất kỳ ai" hoặc trấn an "cứ đọc mã" |

Không có hai dòng trùng nội dung.

## Số đo (chỉ luật, Wilson 95%)

| Chỉ số | x/n | Wilson 95% |
|---|---|---|
| Tin thật: bật `R_OTP_REQUEST` (báo nhầm) | 1/100 | 0%–5% |
| Tin thật: mức ≥ SUSPICIOUS (báo nhầm) | 1/100 | 0%–5% |
| Tin thật có câu cảnh báo + mã OTP: không bật `R_OTP_GENUINE` | 4/34 | 5%–27% |
| Tin lừa: không bật `R_OTP_REQUEST` (sót) | 0/100 | 0%–4% |
| Tin lừa: bật `R_OTP_GENUINE` (bị hạ điểm) | 0/100 | 0%–4% |
| Tin lừa: mức < SUSPICIOUS (sót) | 0/100 | 0%–4% |
| `neg-trap`: bật `R_OTP_GENUINE` | 0/25 | 0%–13% |
| `neg-trap`: mức < SUSPICIOUS (sót) | 0/25 | 0%–13% |

Script in ra "Tin thật: không bật `R_OTP_GENUINE` 70/100", nhưng phần lớn số này đúng thiết kế. Luật chỉ bật khi tin có mã OTP (`requires: [otp_n]`), có câu cảnh báo và không có link (`url: forbidden`). Cụ thể:
- 12 tin không có mã OTP (phần lớn là biến động số dư);
- 54 tin OTP không có câu cảnh báo;
- chỉ 4 tin là sót thật (bảng trên).

Ở chế độ chỉ luật, tin thật không có tín hiệu vẫn ra mức UNSURE. Đó là mức tối thiểu khi không có judge, nên mức của tin thật ở đây không nói gì về cách bot trả lời khi có LLM.

## Ví dụ lỗi

Báo nhầm (`R_OTP_REQUEST`, luật cứng, ra DANGEROUS):
- `otp-b-006`: "QK vui long cung cap ma [OTP_1] cho Mcredit neu QK dong y voi chinh sach bao ve du lieu ca nhan cua MobiFone tai http://m…"

`R_OTP_GENUINE` sót, đã mask:
- `otp-b-023`: "Ma xac thuc OTP VieON cua ban la [OTP_1]. Xin vui long khong chia se thong tin nay."
- `otp-b-053`: "OCB OMNI: Ma OTP … la [OTP_1]. Tuyet doi khong chia se."
- `otp-b-024`: "[OTP_1] is your Grab Activation Code (GAC). … Do not share it with anyone."
- `otp-b-086`: "GoJek: [OTP_1] is your verification code. Do not share this code with anyone."

## Chưa kiểm

- Tập kiểm độc lập: luật được chỉnh trên chính bộ này.
- Tác động khi có judge LLM (cần Bedrock).
- Tin OTP thật có link trong nội dung: chỉ có 2 mẫu (`otp-b-002`, `otp-b-006`).
- Với mask của PR #158 (AI-31), 200 tin này không đổi kết quả mask và không đổi tín hiệu luật (đo ngày 2026-11-16).
