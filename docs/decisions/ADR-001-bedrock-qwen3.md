# ADR-001: Bedrock client và spike Qwen3 32B

- **Trạng thái:** Nháp. **Chưa trả lời được câu nào**: spike chưa chạy vì chưa có AWS credentials (BE-05) và `judge.model_id` trong `ai/config/models.yaml` còn trống.
- **Người quyết định:** AI1
- **Task:** AI-02. Issue #16. Mở khoá AI-03 (G1), AI-08.

## Bối cảnh

Judge chạy Qwen3 32B trên Bedrock `ap-southeast-2` (ARCH §6.9). Client nằm ở `ai/src/askfirst/llm/bedrock.py` (Converse API, timeout, retry backoff + jitter, đếm token và chi phí, model đọc từ `models.yaml`, không log prompt/response). Test dùng client giả, không gọi mạng.

## 4 câu cần trả lời

Chạy `cd ai && uv run python scripts/spike_bedrock.py` sau khi có credentials và model ID (copy từ console, không đoán), rồi điền số liệu vào bảng.

| # | Câu hỏi | Kết quả | Ghi chú |
|---|---|---|---|
| 1 | Qwen3 32B chạy qua Converse hay phải dùng `bedrock-mantle`? | _chưa đo_ | Nếu không chạy: viết adapter `bedrock-mantle` (~1 ngày, plan §9) |
| 2 | Structured output có được hỗ trợ không (toolConfig / ép JSON)? | _chưa đo_ | Script đo cả tỉ lệ JSON hợp lệ khi chỉ dùng prompt |
| 3 | Cách tắt thinking | _chưa đo_ | Script thử 4 cách: mặc định, `/no_think`, `enable_thinking=false`, `chat_template_kwargs`. Danh sách là giả thuyết, chưa xác nhận |
| 4 | Độ trễ p50 / p95 | _chưa đo_ | Cổng G1: p95 ≤ 5 giây |

## Quyết định

Chưa có. Cập nhật mục này sau khi chạy spike.
