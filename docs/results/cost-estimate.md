# Dự toán chi phí (SH-09, #160)

Trạng thái: **mới có phần Bedrock (AI1)**. Link AWS Pricing Calculator, bảng USD/tháng của các dịch vụ còn lại và cập nhật ARCH §12 là phần của BE1.

## 1. Bedrock: token đo thật (AI1, 2026-11-17)

Lệnh đo (chạy từ `ai/`, profile `askfirst-bedrock`, Qwen3 32B ở `ap-southeast-2`, backup gpt-oss-120b):

```
AWS_PROFILE=<profile> uv run python ../eval/measure_cost.py --out ../eval/out/cost
```

`eval/measure_cost.py` chạy `RealPipeline` trên mọi file `eval/datasets/*.jsonl`. Script bọc client Bedrock và Guardrail để đếm token và text unit của mỗi lượt kiểm tra, không đổi `Verdict`. Code ở `main` (`477b1d1`, prompt `judge_v2`, ngưỡng 70/60/25), 1 lượt, 184 mẫu, 0 lỗi, tốn 0,100 USD.

| | Mỗi lượt có gọi LLM (133 lượt) |
|---|---|
| Số lời gọi LLM | TB 1,2 (judge 135, backup 25; tối đa 3) |
| Token input | TB 3.685, p95 6.357, tối đa 9.477 |
| Token output | TB 297, p95 628, tối đa 1.716 |
| Leo thang sang backup | 23/133 lượt (17%) |
| Guardrail | 1 lời gọi, 1 text unit mỗi lượt |
| Chi phí token LLM | **0,000753 USD** |

Kiểm tay: 3.685 × 0,1545/1M + 297 × 0,618/1M ≈ 0,000753 USD, khớp số script cộng từ `cost_usd`.

Hạn chế của lượt đo:
- 50 mẫu ảnh (`ocr_images`) không đo được vì OCR lỗi `FileNotFoundError`: máy dev không có model OCR trong `ai/artifacts/`. Các lượt đó không gọi LLM, nên **chưa có token cho tin ảnh**. Ảnh chỉ có text OCR thì chắc gần tin chữ, nhưng điều này chưa kiểm.
- Trong 134 tin chữ chỉ 1 tin đi fast path, vì bộ eval gần như toàn tin lừa đảo, tin khó và tin lành mà nghi. Tỉ lệ 25% lượt không gọi LLM ở ARCH §12 vì vậy **chưa kiểm** (not verified).
- Đo với `judge_v2`. `judge_v3` (#167) tốn hơn khoảng 6% mỗi lượt eval (0,105 so với 0,099 USD cho 134 mẫu).
- Chạy từ máy dev, 1 lượt. Token gần như không đổi giữa các lượt vì temperature 0, nhưng lượt leo thang có thể khác.

## 2. Đơn giá (AWS Price List API, `ap-southeast-2`, lấy 2026-11-17)

| Mục | Đơn giá |
|---|---|
| Qwen3 32B, on-demand standard | input 0,1545 USD / 1M token, output 0,618 USD / 1M token |
| gpt-oss-120b (backup), theo `ai/config/models.yaml` | input 0,1545, output 0,618 USD / 1M token |
| Guardrail `ContentPolicyUnitsConsumed` | 0,15 USD / 1.000 text unit |
| Guardrail `GuardrailChecks-PromptAttackCheckUnitsConsumed` | 0,00008 USD / text unit (= 0,08 USD / 1.000) |

Guardrail của mình chỉ bật filter `PROMPT_ATTACK` ở input, tier STANDARD (`ai/scripts/create_guardrail.py`). Bảng giá có cả 2 dòng trên và **chưa rõ AWS tính theo dòng nào** (not verified, xem Cost Explorer sau khi chạy). Dự toán dưới đây lấy 0,15 USD / 1.000 cho chắc.

## 3. Chi phí Bedrock cho 3.000 lượt/tháng

Tải theo ARCH §12: 3.000 lượt/tháng, chưa tính voice và VLM. Tính tay từ đơn giá trên vì Pricing Calculator **chưa kiểm** có Qwen3 hay không.

| Kịch bản | Lượt gọi LLM | LLM (USD/tháng) | Guardrail (USD/tháng) | Cộng |
|---|---|---|---|---|
| ARCH §12: 25% đi fast path/cache | 2.250 | 2.250 × 0,000753 ≈ **1,69** | 2.250 × 0,15/1.000 ≈ 0,34 | **≈ 2,0** |
| Mọi lượt gọi LLM | 3.000 | ≈ 2,26 | ≈ 0,45 | ≈ 2,7 |
| Mọi lượt gọi LLM, token ở mức p95 (6.357 in, 628 out) | 3.000 | ≈ 4,11 | ≈ 0,45 | ≈ 4,6 |

So với ARCH §12: ARCH giả định 2,5K token input và 300 output mỗi lượt, ra judge ≈ 1,3 + leo thang ≈ 0,15 USD. Đo thật input là 3,7K (cao hơn khoảng 47%, do RAG và prompt), output gần đúng. Số mới đã gộp cả leo thang: ≈ 1,69 USD thay cho ≈ 1,45 USD.

## 4. Còn thiếu (BE1)

- Link chia sẻ AWS Pricing Calculator và ngày tạo.
- USD/tháng của Lambda, SQS, DynamoDB, S3, ECR, CloudWatch Logs (`ap-southeast-1`).
- 3–5 cách tối ưu: Free Tier, cache/fast path, kill switch, log retention 7 ngày, S3 lifecycle.
- Cập nhật ARCH §12 theo bảng cuối cùng.
