# Dự toán chi phí (SH-09, #160)

Trạng thái: **đủ** (AI1 làm giúp phần của BE1, 2026-11-24). Kết luận ở mục 0, đo token Bedrock ở mục 1–3, AWS Pricing Calculator ở mục 4.

## 0. Kết luận: 3.000 lượt kiểm tra/tháng

- **≈ 6,2 USD/tháng** nếu không có Free Tier, **≈ 2,4 USD/tháng** nếu tài khoản còn hưởng các mức always-free (Lambda, SQS, CloudWatch, DynamoDB).
- Khoản lớn nhất là Bedrock (≈ 2,1 USD) và CloudWatch (2,35 USD khi không có Free Tier).
- Link dự toán: https://calculator.aws/#/estimate?id=073f413f6ac370de48bf3bff0ba10a7b870ae807, tạo ngày 2026-11-24, phần dịch vụ ở `ap-southeast-1` (Singapore). Bedrock tính tay vì Calculator không có Qwen3 ở Sydney (mục 4.1).

| Dịch vụ | Cách tính | Không Free Tier | Có Free Tier |
|---|---|---|---|
| Bedrock LLM (Qwen3 32B, backup gpt-oss-120b; `ap-southeast-2`) | tay: 2.250 lượt gọi LLM × 0,000792 USD (`judge_v3`, đã gồm leo thang, `docs/eval-report-final.md`) | 1,78 | 1,78 |
| Bedrock Guardrails | tay: 2.250 × 0,15 USD / 1.000 text unit | 0,34 | 0,34 |
| Lambda `ai` (3008 MB) | Calculator | 0,73 | 0 |
| Lambda `worker` (512 MB) | Calculator | 0,13 | 0 |
| Lambda `webhook` (512 MB) | Calculator | 0,03 | 0 |
| SQS (jobs + DLQ) | Calculator | 0,28 | 0 |
| DynamoDB on-demand | Calculator | 0,29 | < 0,05 |
| S3 `DataBucket` | Calculator | 0,03 | 0,03 |
| ECR (image Lambda `ai`) | Calculator | 0,20 | 0,20 |
| CloudWatch (logs, metric, alarm, dashboard) | Calculator | 2,35 | 0 |
| SNS (email alarm), SSM Parameter Store (standard) | không đưa vào Calculator | ≈ 0 | 0 |
| **Tổng** | | **≈ 6,16** | **≈ 2,4** |

Cột "Có Free Tier" tính tay từ các mức always-free trong mục 4.3. Tài khoản của team có còn hưởng các mức này hay không thì **chưa kiểm** (not verified); xem Billing > Free Tier của tài khoản trước khi đưa số này lên slide.

## 1. Bedrock: token đo thật (AI1, 2026-11-17)

Lệnh đo (chạy từ `ai/`, profile `askfirst-bedrock`, Qwen3 32B ở `ap-southeast-2`, backup gpt-oss-120b):

```
AWS_PROFILE=<profile> uv run python ../eval/measure_cost.py --out ../eval/out/cost
```

`eval/measure_cost.py` chạy `RealPipeline` trên mọi file `eval/datasets/*.jsonl`. Script bọc client Bedrock và Guardrail để đếm token và text unit của mỗi lượt kiểm tra, không đổi `Verdict`. Code ở `main` (`477b1d1`, prompt `judge_v2`, ngưỡng 70/60/25), 1 lượt, 184 mẫu, 0 lỗi, tốn 0,100 USD. Lượt này OCR lỗi vì máy dev chưa có model, nên 50 mẫu ảnh được đo lại riêng (cột tin ảnh).

| Mỗi lượt có gọi LLM | Tin chữ (133 lượt) | Tin ảnh (50 lượt) |
|---|---|---|
| Số lời gọi LLM | TB 1,2 (judge 135, backup 25; tối đa 3) | TB 1,1 (judge 51, backup 4) |
| Token input | TB 3.685, p95 6.357, tối đa 9.477 | TB 3.558, p95 6.707, tối đa 10.074 |
| Token output | TB 297, p95 628, tối đa 1.716 | TB 355, p95 591 |
| Leo thang sang backup | 23/133 lượt (17%) | 2/50 lượt (4%) |
| Guardrail | 1 lời gọi, 1 text unit mỗi lượt | 1 lời gọi, 1 text unit mỗi lượt |
| Chi phí token LLM | **0,000753 USD** | **0,000769 USD** |

Kiểm tay: 3.685 × 0,1545/1M + 297 × 0,618/1M ≈ 0,000753 USD, khớp số script cộng từ `cost_usd`.

Tin ảnh: 50 mẫu `ocr_images`, OCR local (RapidOCR, model tải bằng `ai/scripts/fetch_ocr_models.py`, khớp sha256) rồi đưa text vào judge như tin chữ, chạy riêng ngày 2026-11-17, tốn 0,038 USD. Tin ảnh đắt hơn tin chữ khoảng 2% nên bảng tháng ở mục 3 dùng số tin chữ. Chưa tính VLM fallback.

Hạn chế của lượt đo:
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

So với ARCH §12 cũ: ARCH giả định 2,5K token input và 300 output mỗi lượt, ra judge ≈ 1,3 + leo thang ≈ 0,15 USD. Đo thật input là 3,7K (cao hơn khoảng 47%, do RAG và prompt), output gần đúng. Số mới đã gộp cả leo thang: ≈ 1,69 USD thay cho ≈ 1,45 USD.

Với `judge_v3` (#167): eval cuối ngày 2026-11-24 đo 0,000792 USD mỗi lượt có LLM (TB 3 lượt × 134 mẫu, `docs/eval-report-final.md`), nên 2.250 × 0,000792 ≈ **1,78 USD**. Bảng ở mục 0 dùng số này.

## 4. Các dịch vụ còn lại: AWS Pricing Calculator (2026-11-24)

### 4.1 Cách lập

- Trang public calculator.aws, không đăng nhập. Mỗi Lambda là một mục riêng, chọn **"Without Free Tier"** để mục nào cũng ra số thật (Calculator cộng Free Tier cho từng mục nên cộng nhiều mục sẽ trừ trùng). Free Tier tính tay ở mục 4.3.
- Region `ap-southeast-1` cho mọi dịch vụ trừ Bedrock.
- Bedrock: chọn region Sydney thì nhà cung cấp **Qwen bị mờ, không chọn được**, nên tính tay theo đơn giá ở mục 2, đúng như issue #160 cho phép.
- Cấu hình lấy theo `infra/template.yaml`: 3 Lambda `java21`/Python x86_64, Function URL (không có API Gateway), SQS + DLQ, DynamoDB `PAY_PER_REQUEST`, S3 có lifecycle `donated/` 30 ngày, 1 alarm, 5 SSM parameter. Lambda `ai` chưa có trong template (BE-06, #62); lấy theo kế hoạch: 3008 MB, image ≤ 2 GB.

### 4.2 Số nhập vào Calculator

| Mục | Nhập | Căn cứ |
|---|---|---|
| Lambda `ai` | 3.000 request, 5.000 ms, 3008 MB, `/tmp` 512 MB | 2.000 tin chữ (p50 cả pipeline 2,4 s, `docs/eval-report-final.md`) + 1.000 ảnh (OCR p50 2,79 s + VietOCR 0,56 s + pipeline ≈ 6 s, `docs/results/ocr-vietocr.md`), TB ≈ 3,6 s, làm tròn lên 5 s để có chỗ cho cold start. **Đo trên máy dev, chưa đo trên Lambda** (not verified) |
| Lambda `worker` | 3.000 request, 5.000 ms, 512 MB | `worker` chờ `ai` trả về nên lấy bằng thời gian của `ai`. Chưa đo (giả định) |
| Lambda `webhook` | 6.000 request, 500 ms, 512 MB | Mỗi lượt kiểm tra 1 update, cộng lệnh, nút bấm, tin không hợp lệ: nhân đôi. Thời gian chưa đo (giả định) |
| SQS | 0,7 triệu request standard | Lambda poll SQS bằng long poll kể cả khi hàng đợi rỗng. Ước tính 5 poller × 3 lần/phút × 60 × 24 × 30 ≈ 650K request/tháng (theo cách Lambda poll, chưa đọc số thật ở CloudWatch: not verified); tin thật chỉ ≈ 9K request |
| DynamoDB | 1 GB, item 1 KB, 15.000 ghi, 15.000 đọc on-demand | ≈ 5 ghi và 5 đọc mỗi lượt (dedup, lượt kiểm tra, người dùng, rate limit, cache). Giả định |
| S3 | 1 GB Standard, 1.000 PUT, 10.000 GET | KB, artifact VietOCR, mẫu `donated/`. Giả định |
| ECR | 2 GB | Image `ai` ≤ 2 GB (giới hạn của BE-06), giữ 1 bản |
| CloudWatch | 0,5 GB log/tháng, 5 metric tự định nghĩa, 5 alarm, 1 dashboard | Code hiện chưa gửi metric tự định nghĩa; 5 metric và 5 alarm là dự trù cho #71, #85. Log chưa đo. Calculator tính phí lưu log với giả định giữ 1 tháng |

Tổng Calculator: **4,04 USD/tháng** (0 USD trả trước, 48,48 USD/12 tháng), chưa có Bedrock.

### 4.3 Free Tier dùng cho cột "Có Free Tier"

Các mức always-free (không hết hạn sau 12 tháng) theo trang giá từng dịch vụ:

- Lambda: 1 triệu request và 400.000 GB-s mỗi tháng. Cả 3 hàm dùng ≈ 44.100 (`ai`) + 7.500 (`worker`) + 1.500 (`webhook`) ≈ 53.100 GB-s và 12.000 request.
- SQS: 1 triệu request mỗi tháng.
- CloudWatch: 10 metric, 10 alarm, 5 GB log, 3 dashboard.
- DynamoDB: 25 GB lưu trữ. Phí đọc/ghi on-demand vẫn tính (vài cent).
- S3 và ECR: không tính Free Tier (Free Tier của 2 dịch vụ này chỉ cho tài khoản mới trong 12 tháng).

Free Tier tính trên cả tài khoản: nếu tài khoản còn chạy việc khác thì phần miễn phí bị chia (not verified).

## 5. Cách tối ưu

1. **Free Tier:** Lambda, SQS, CloudWatch nằm gọn trong mức always-free, giảm ≈ 3,8 USD/tháng (6,2 → 2,4 USD). Phải kiểm loại Free Tier của tài khoản trước.
2. **Fast path và cache:** mỗi lượt không gọi LLM tiết kiệm ≈ 0,00094 USD (0,000792 token + 0,00015 Guardrail). Ở mức 25% (750 lượt/tháng) là ≈ 0,7 USD/tháng. Tỉ lệ 25% chưa kiểm (mục 1).
3. **Kill switch** (`FLAGS`, #79): tắt judge LLM hoặc nhận ảnh khi có alarm chi phí, để Bedrock không vượt ngân sách khi bị spam. Trần chi phí phụ thuộc rate limit mỗi người dùng.
4. **Giữ log 7 ngày:** `infra/template.yaml` hiện chưa đặt `RetentionInDays` nên log Lambda giữ mãi. Thêm `AWS::Logs::LogGroup` với `RetentionInDays: 7` cho 3 hàm (việc của BE). Số tiền nhỏ ở tải này, nhưng chặn được chi phí lưu log tăng dần theo tháng.
5. **Lifecycle cho S3 và ECR:** S3 đã xoá `donated/` sau 30 ngày. ECR chưa có lifecycle policy: mỗi image `ai` ≈ 2 GB giữ lại tốn thêm ≈ 0,20 USD/tháng, nên giữ 2 bản mới nhất (việc của BE-06).

## 6. Chưa kiểm

- Thời gian chạy trên Lambda của cả 3 hàm (BE-06 chưa xong). Số giây ở mục 4.2 lấy từ máy dev hoặc là giả định.
- Tỉ lệ fast path/cache 25%.
- Số request SQS thật khi rỗng, lượng log thật.
- Guardrail tính theo dòng giá nào (mục 2).
- Chi phí thật: đọc Cost Explorer sau tuần test nội bộ (W6) rồi so với bảng này.
