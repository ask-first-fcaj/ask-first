# ADR-002: Gate G1, chọn model judge

- **Trạng thái:** Chấp nhận phần số đo (2026-11-11). Phần chấm tay tiếng Việt 20 mẫu đang chờ team, chưa có điểm.
- **Ngày:** 2026-11-11
- **Người quyết định:** AI1
- **Task:** AI-03, issue #38. Dựa trên ADR-001 (AI-02).

## Quyết định

**Judge giữ Qwen3 32B, tắt thinking** (`qwen.qwen3-32b-v1:0`, đã điền trong `ai/config/models.yaml` từ AI-02). Đây là ứng viên duy nhất đạt cả hai ngưỡng eval gate (recall ≥ 90%, báo nhầm ≤ 10%). Nó cũng nhanh nhất, và rẻ thứ hai.

- **Không bật thinking:** recall chỉ tăng 1 mẫu, còn báo nhầm tăng từ 10% lên 45%, độ trễ gần gấp đôi.
- **Backup:** đề xuất `gpt-oss-120b`, vì recall 100% và đúng mức 95%. Model này báo nhầm 25%, nhưng backup chỉ chạy khi judge lỗi. Lúc đó pipeline vẫn nâng sàn UNSURE khi model không chắc, nên báo nhầm của backup ít ảnh hưởng hơn. **Chưa điền** `backup.model_id` trong PR này: điền vào thì pipeline bắt đầu gọi model thứ hai khi judge throttle, cần AI1 duyệt riêng và cấp quyền IAM (BE-05).
- **Claude Haiku 5.5 không đo được:** mọi lời gọi trả `AccessDeniedException` (99/99), vì account chưa được cấp quyền dùng model Anthropic trên Bedrock. Dòng của Haiku trong bảng dưới chỉ là kết quả `rules_only`, không phải chất lượng của model. Muốn có mốc tham chiếu thì cần mở quyền truy cập model Anthropic trên account, rồi chạy lại `--only claude-haiku-5-5`.

## Cách đo

- **Bộ dữ liệu:** eval v0 (`eval/datasets/*.jsonl`): 150 mẫu, trong đó 100 mẫu có nhãn (60 lừa đảo, 40 lành tính). 50 mẫu không có nhãn `is_scam` bị bỏ qua khi tính điểm.
- **Prompt:** `prompts/judge_v1.md`. Pipeline là `RealPipeline` thật (normalize → mask → rules + URL intel → judge → fuse). Chỉ đổi `judge.model_id` cho từng ứng viên, `backup` để trống, `max_attempts` 1.
- **Lệnh:** chạy từ `ai/`:
  ```bash
  AWS_PROFILE=<profile> uv run python ../eval/benchmark_models.py --out ../eval/out/benchmark
  ```
  Kết quả là `benchmark.json` (chỉ số), `explanations.jsonl` (lời giải thích từng mẫu) và `grading.md` (phiếu chấm tay ẩn tên model).
- **Môi trường:** chạy từ máy dev ở Việt Nam tới Bedrock `ap-southeast-2`. 5 ứng viên chạy song song, mỗi ứng viên gọi tuần tự từng mẫu. **Chưa đo trên Lambda.**
- **Giá dùng để tính** (USD cho 1M token vào / ra): Qwen3 32B và gpt-oss-120b 0,1545 / 0,618; Gemma 3 12B 0,0927 / 0,2987. Chưa đối chiếu với hoá đơn AWS (not verified).
- **Thinking:** bật bằng `reasoning_effort: high` (ADR-001, câu 3), `max_tokens` 2000.

## Kết quả (100 mẫu có nhãn)

| Ứng viên | Recall | Báo nhầm | Đúng mức | Đúng kịch bản | JSON hợp lệ | LLM p50 / p95 (s) | Mẫu p95 (s) | USD/mẫu | Lỗi gọi |
|---|---|---|---|---|---|---|---|---|---|
| **qwen3-32b** | **98,3%** (59/60) | **10,0%** (4/40) | 94,0% | 47,0% | 99,0% | **1,26 / 1,67** | **1,70** | 0,000318 | 0 |
| qwen3-32b-thinking | 100% | 45,0% | 91,0% | 46,0% | 99,0% | 2,20 / 3,01 | 3,10 | 0,000482 | 0 |
| gpt-oss-120b | 100% | 25,0% | 95,0% | 51,0% | 96,1% | 3,38 / 5,10 | 5,10 | 0,000518 | 0 |
| gemma-3-12b | 98,3% | 25,0% | 89,0% | 52,0% | 98,0% | 4,50 / 6,21 | 6,38 | 0,000168 | 0 |
| claude-haiku-5-5 | (AccessDenied, chỉ rules) | | | | n/a | n/a | 0,67 | 0 | 99 |

- **Đúng mức:** mức của verdict nằm trong khoảng `min_level`…`max_level` của nhãn.
- **Đúng kịch bản:** trùng mã kịch bản (vd `N1.1`) của nhãn.
- **JSON hợp lệ:** tỉ lệ lời gọi trả JSON parse được. Qwen3 32B đạt 99%, nên đạt luôn điều kiện "JSON hợp lệ ≥ 98% trên bộ v0" của AI-04.
- **Chi phí:** Qwen3 32B tốn khoảng 0,32 USD cho 1.000 lượt kiểm. Tổng benchmark tốn khoảng 0,15 USD.

## Rủi ro và việc còn lại

- **Báo nhầm 10% nằm sát ngưỡng gate** (4/40 tin lành tính). Chỉ cần thêm 1 tin lành tính bị báo nhầm là trượt gate. Bộ v1 300 mẫu (AI-10) sẽ cho số chắc hơn, và AI-13 tinh chỉnh ngưỡng trên bộ đó.
- **Đúng kịch bản thấp ở mọi model** (46–52%), nghĩa là model hay gán nhầm mã kịch bản chi tiết. Điều này không ảnh hưởng tới cảnh báo, nhưng ảnh hưởng tới nguồn trích dẫn theo kịch bản. Việc này để cho AI-11 (chấm chất lượng lời giải thích) và việc chỉnh prompt.
- **Chấm tay tiếng Việt:** 20 mẫu ngẫu nhiên (seed 3) nằm ở `docs/results/g1-grading.md`. Mỗi mẫu có lời giải thích của 4 model, tên model được ẩn sau chữ cái, bảng giải mã ở cuối file. Team chấm 1–5 rồi ghi điểm trung bình vào ADR này. Nếu Qwen3 32B thua rõ thì mở lại quyết định.
- **Độ trễ trên Lambda** chưa đo. Số ở trên đo từ máy dev, và gồm cả đường mạng Việt Nam ↔ Sydney.
