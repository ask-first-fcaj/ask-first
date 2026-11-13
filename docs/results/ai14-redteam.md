# Red-team bộ `hard` (AI-14)

- **Ngày chạy:** 2026-11-11, `RealPipeline` thật trên nhánh `feat/ai-08-guardrails`: rules + URL intel + Guardrails + judge Qwen3 32B (`qwen.qwen3-32b-v1:0`), backup để trống.
- **Bộ dữ liệu:** `eval/datasets/hard.jsonl`, 34 mẫu (30 lừa đảo, 4 lành tính khó), id `hd-rt-01`…`hd-rt-34`.
- **Nhãn:** AI soạn nháp (`provenance: ai_drafted`), **chưa có người thứ hai duyệt** (LABELING mục 10). Vì vậy bộ này chưa được tính vào Gate. Một số `min_level` có thể chặt quá, ví dụ `hd-rt-05`: app chỉ được hẹn cài sau, chưa cài.
- **Lệnh** (chạy từ `ai/`, cần quyền Bedrock):
  ```bash
  uv run python ../eval/run_eval.py ../eval/datasets/hard.jsonl --pipeline real --out ../eval/out/hard
  ```

## Mẫu phủ những gì

| Kỹ thuật | Mẫu |
|---|---|
| Pig butchering: làm quen rồi rủ đầu tư, nhờ nạp để rút, kèm qua màn hình | 01, 02, 04 |
| Giả cơ quan nhà nước hẹn gặp tại trụ sở, sau đó mới gọi cài app hoặc gọi video | 05, 06, 07 |
| Tránh từ khoá: "gom tiết kiệm về sổ", "5 lít", "bắn qua", "dãy 6 số", "mấy số xác nhận", "lệ phí hồ sơ" | 08, 09, 10, 11, 15 |
| Không dấu, tách chữ, emoji, chen tiếng Anh | 23, 24, 25, 26 |
| Biên lai giả, tour/homestay, trúng thưởng đòi thuế, QR, APK, chia sẻ màn hình | 16, 17, 18, 27, 28, 29 |
| Tống tiền, deepfake, giáo viên báo tin con cấp cứu, lấy lại tiền | 14, 15, 19, 20 |
| Gọi lại số lạ (điện lực, toà án) | 21, 30 |
| Lành tính khó: OTP thật, giấy mời công an thật, "5 lít" là cho tiền, mời gây quỹ | 31–34 |

## Kết quả

| Chỉ số | Giá trị |
|---|---|
| Recall (30 mẫu lừa đảo, mức ≥ SUSPICIOUS) | 86,7% (26/30) |
| Đạt `min_level` của nhãn (30 mẫu lừa đảo) | 19/30 |
| Báo nhầm (4 mẫu lành tính) | 1/4 (`hd-rt-32`) |
| Đúng mã kịch bản | 50% |
| Rơi về `rules_only` vì JSON của judge bị loại | 3/34 (`hd-rt-12`, `21`, `30`) |
| Độ trễ p50 / p95 | 2,26 / 4,17 s (guard p95 1,57 s, judge p95 3,12 s) |
| Chi phí | 0,0122 USD cả bộ (≈ 0,00036 USD/mẫu) |

Chạy trên máy dev ở Việt Nam tới `ap-southeast-2`. **Chưa đo trên Lambda.**

### Chạy lại sau #138 (2026-11-11)

Nhánh `fix/ai-138-judge-enum-la` (đã có backup gpt-oss-120b). Judge bỏ giá trị `requested_actions` ngoài enum và sửa `"quote": "a", "b"` thay vì loại cả câu trả lời.

| Chỉ số | Trước | Sau |
|---|---|---|
| Recall (30 mẫu lừa đảo) | 86,7% | 93,3% |
| Đúng mã kịch bản | 50% | 61,8% |
| Rơi về `rules_only` | 3/34 | 0/34 |
| Báo nhầm (4 mẫu lành tính) | 1/4 | 0/4 |
| Độ trễ p50 / p95 | 2,26 / 4,17 s | 1,85 / 3,77 s |
| Chi phí cả bộ | 0,0122 USD | 0,0115 USD |

`hd-rt-12`, `21`, `30` chạy 3 lượt riêng: cả 9 lượt đi đường judge, 0 lỗi JSON, không cần backup. Trước khi sửa JSON, chạy riêng `hd-rt-12` 11 lượt: judge lỗi cùng một dạng cả hai lần hỏi ở 9 lượt. Phần lớn được backup cứu, nhưng có 2 lượt rơi về `rules_only`. Lần trước chạy ở nhánh chưa có backup, nên cột "Trước" và "Sau" không chỉ khác nhau ở #138. `hd-rt-07`, `hd-rt-28` vẫn UNSURE (nguyên nhân bên dưới, không thuộc #138).

## Mẫu bị dưới mức nhãn và nguyên nhân

| Mẫu | Cần | Bot | Nguyên nhân |
|---|---|---|---|
| hd-rt-21, hd-rt-30 | SUSPICIOUS | UNSURE | **Lỗi schema.** Judge trả `requested_actions: ["click_link", "call_number"]`. `call_number` không có trong enum, nên cả câu trả lời bị loại hai lần và pipeline rơi về `rules_only`. Kiểu lừa "gọi lại số lạ" vì thế luôn trượt LLM. |
| hd-rt-12 | SUSPICIOUS | SUSPICIOUS (`rules_only`) | **JSON sai cú pháp.** `"quote": "a", "b"` (hai chuỗi trong một trường), lỗi hai lần liên tiếp. Mức vẫn đạt nhờ luật, nhưng không có lời giải thích của LLM. |
| hd-rt-10 | DANGEROUS | SUSPICIOUS | **Thiếu luật.** "Đọc lại dãy 6 số vừa gửi đến máy" là xin OTP nhưng không có chữ OTP, nên `R_OTP_REQUEST` không bật. |
| hd-rt-11 | DANGEROUS | SUSPICIOUS | **Thiếu luật.** Shipper xin "mấy số xác nhận" trong tin nhắn: giống hd-rt-10. |
| hd-rt-05, hd-rt-06 | DANGEROUS | SUSPICIOUS | `R_INSTALL_APP` bật nhưng `hard: false`, nên không có sàn DANGEROUS, và LLM chấm SUSPICIOUS vì việc cài app được hẹn làm sau. Có thể nhãn chặt quá (chờ người duyệt). |
| hd-rt-18 | DANGEROUS | SUSPICIOUS | QR kèm "nhập thông tin thẻ" không có luật riêng; LLM gán N1.6. |
| hd-rt-22 | DANGEROUS | SUSPICIOUS | Xin chụp hai mặt thẻ và căn cước: không có luật cho `share_id_card`. |
| hd-rt-27 | DANGEROUS | SUSPICIOUS | Biên lai giả và "chuyển dư, chuyển lại giúp": LLM đúng kịch bản N4.1 nhưng chấm thấp mức. |
| hd-rt-07, hd-rt-28 | SUSPICIOUS | UNSURE | LLM chưa hiểu ẩn ý: tổ công tác hẹn gọi video, homestay đòi cọc 50%. |

Mẫu lành tính bị báo nhầm: **hd-rt-32** (giấy mời làm việc của công an phường, không có app, không gọi điện) ra SUSPICIOUS, chỉ vì `R_AUTHORITY`. Trùng với mẫu điểm thấp nhất của AI-11 (`bs-ai-13`).

Thêm một điểm: judge tự đặt mã tín hiệu giống mã luật (`R_MIMIC_BANK`, `R_PAY_TAX`…) với weight 0. Những mã này không ảnh hưởng điểm, nhưng dễ bị nhầm là luật thật khi đọc log.

## Việc tiếp theo (đề xuất mở issue)

1. **Judge loại cả câu trả lời chỉ vì một giá trị ngoài enum hoặc JSON lặt vặt.** Bỏ giá trị `requested_actions` lạ thay vì từ chối; cân nhắc bật `outputConfig` `json_schema` (ADR-001, AI-04 đang để tắt).
2. **Rules: xin OTP không có chữ OTP** ("dãy 6 số", "mấy số xác nhận", "mã vừa gửi") và xin chụp thẻ/căn cước. Đã làm ở #139 (`rules-2026.11.11`): `hd-rt-10`, `hd-rt-11`, `hd-rt-22` lên sàn luật cứng 80. Luật viết khi đã đọc 3 mẫu này, nên 3 mẫu không còn tính là kiểm độc lập cho phần luật đó.
3. **Hiệu chỉnh mức:** cài app, QR kèm nhập thẻ, biên lai "chuyển dư" và tin công an thật bị báo nhầm. Làm ở AI-13 trên bộ v1, sau khi nhãn của bộ này được duyệt. Không chỉnh prompt hay luật trên chính 34 mẫu này, để còn dùng nó làm bộ kiểm.
