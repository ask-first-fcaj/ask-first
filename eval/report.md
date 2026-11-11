# Báo cáo chất lượng lời giải thích (AI-11)

- Giám khảo: OpenAI `gpt-5.5-2026-04-23`. Bot: RealPipeline thật (judge Qwen3 32B, `ai/config/models.yaml`).
- Bộ dữ liệu: mẫu có nhãn của eval v0 (`eval/datasets/*.jsonl`), dữ liệu tự soạn.
- Mẫu chấm được: 100/100 (lỗi gọi giám khảo: 0).
- Thang 1–5, đạt = điểm ≥ 4. Tiêu chí đầy đủ: biến `RUBRIC` trong `eval/run_judge_eval.py`.

## Điểm trung bình (tỉ lệ đạt)

| Nhóm | Mẫu | Đúng trọng tâm | Người 70 tuổi hiểu ngay | Hướng dẫn hành động rõ | Ngắn gọn |
|---|---|---|---|---|---|
| tất cả | 100 | 3.54 (63.0%) | 4.34 (99.0%) | 4.14 (83.0%) | 3.97 (66.0%) |
| lừa đảo | 60 | 3.72 (61.7%) | 4.27 (100.0%) | 4.45 (95.0%) | 3.65 (56.7%) |
| lành tính | 40 | 3.27 (65.0%) | 4.45 (97.5%) | 3.67 (65.0%) | 4.45 (80.0%) |

Đo cứng tiêu chí 4: tin kết luận ≤ 600 ký tự hiển thị ở 100.0% số mẫu (p95 550 ký tự).

Token giám khảo: 95507 vào, 14586 ra.

## 5 mẫu điểm thấp nhất

| Mẫu | Mức bot | c1 | c2 | c3 | c4 | Lý do chính |
|---|---|---|---|---|---|---|
| bs-ai-13 | UNSURE | 1 | 4 | 2 | 3 | c1: Kết luận nghi ngờ và gán kịch bản giả công an sai với tin thông báo hành chính lành tính. |
| bs-ai-14 | UNSURE | 1 | 3 | 3 | 3 | c1: Bot gán nhầm tin lành tính thành nghi ngờ và không giải thích vì sao an toàn. |
| bs-ai-15 | SUSPICIOUS | 1 | 4 | 2 | 3 | c1: Kết luận ngược nhãn lành tính và gán sai thành giả ngân hàng điểm thưởng. |
| bs-ai-05 | DANGEROUS | 1 | 4 | 3 | 3 | c1: Kết luận ngược nhãn và gán sai thành lừa đảo vay tiền dù tin có nội dung cảnh báo an toàn. |
| hd-ai-11 | SUSPICIOUS | 2 | 4 | 3 | 2 | c1: Nhận ra link lạ liên quan bưu phẩm nhưng gán sai kịch bản điện lực/nhà mạng và hướng dẫn gọi ngân hàng không phù hợp. |

## Chấm tay so với LLM

20 mẫu, 1 người chấm. Sai lệch = |điểm LLM − trung bình điểm người|.

| Tiêu chí | Người (TB) | LLM (TB) | Sai lệch TB | Lệch ≤ 1 điểm |
|---|---|---|---|---|
| Đúng trọng tâm | 4.30 | 3.80 | 0.80 | 75.0% |
| Người 70 tuổi hiểu ngay | 4.65 | 4.45 | 0.40 | 100.0% |
| Hướng dẫn hành động rõ | 5.00 | 4.35 | 0.65 | 85.0% |
| Ngắn gọn | 4.50 | 4.00 | 0.80 | 80.0% |
