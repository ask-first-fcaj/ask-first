# Đo độ trễ Lambda `ai` (INT-01 #56, INT-02 #66, INT-03 #76)

> Người đo: AI1. Ngày: 2026-11-24 (ảnh: 2026-11-25). Account team, `ap-southeast-1`, `askfirst-dev-ai` alias `live` = v10 (image `main-48c95f8`, 3008 MB), Bedrock ở account khác qua `BEDROCK_ROLE_ARN` (#182).

## Kết luận

- **Văn bản, container ấm:** p95 **3,5 s** (100 câu, 0 lỗi). Phần lớn thời gian là judge LLM. Chi phí khoảng 0,00076 USD một tin.
- **RAG:** p95 **45 ms**, tối đa 58 ms, dưới 100 ms (#76).
- **Cold start:** init của `ai` p50 6,2 s, tối đa 8,5 s. Init của `worker` khoảng 2,2 s. Phía worker, lần gọi `ai` chậm nhất (có cold start của `ai`) mất 12,3 s. Lần đầu sau khi deploy, init của `ai` từng chạm mốc 10 s và phải chạy lại, request đó mất 13,9 s.
- **Log:** worker và `ai` ghi cùng `trace_id`. Log 30 ngày của cả hai function kh có nội dung tin nhắn.
- **Về stub:** đặt `PIPELINE_IMPL=stub` trên `$LATEST` thì trả `pipeline=stub`, đặt lại `real` thì trả `real`. Alias `live` kh bị đụng.
- **Ảnh, container ấm (#66, đo 25/11):** 21 ảnh qua bot, đều đi path `llm`. Đầu-cuối p95 **17,0 s** (p50 11,3 s), vượt mốc 15 s. Phần trong `ai` p95 12,2 s, cả lần chạy của worker p95 13,5 s. Khoảng 3,5 s còn lại là debounce 3 s của SQS (`AnalyzeJob` `DelaySeconds = 3`) cộng thời gian poll. AI1 chấp nhận vì vẫn dưới ngưỡng khoảng 30 s cho ảnh (ADR-003).

## Văn bản (container ấm)

Lấy 100 câu từ 4 bộ eval (`real_scam`, `benign_normal`, `benign_suspicious`, `hard` bản `ai1`), xáo trộn với seed 7. Gọi lần lượt `askfirst-dev-ai:live` từ máy dev bằng request `contracts/fixtures/analyze/default.json`, mỗi câu có `check_id` riêng và tiền tố riêng để kh trúng cache. Có 3 câu vẫn trúng cache vì trùng tiền tố với lần chạy thử 3 câu ngay trước đó.

| Đo | n | p50 | p95 | tối đa |
|---|---|---|---|---|
| Phía client (gồm mạng VN → Singapore) | 100 | 1,85 s | 3,51 s | 4,18 s |
| `latency_ms.total` trong `ai` | 100 | 1,78 s | 3,42 s | 4,11 s |
| `guard` (Bedrock Guardrail) | 99 | 491 ms | 548 ms | 575 ms |
| `judge` (LLM) | 99 | 1,27 s | 2,92 s | 3,58 s |
| `rag` | 100 | 20 ms | 45 ms | 58 ms |
| `rules` | 100 | 0 ms | 0 ms | 1 ms |

| Đường xử lý | Số câu | p50 / p95 phía client |
|---|---|---|
| `llm` (`qwen.qwen3-32b-v1:0`) | 76 | 1,78 / 2,13 s |
| `escalated` (thêm `openai.gpt-oss-120b-1:0`) | 20 | 3,12 / 4,17 s |
| `cache` | 3 | 73 / 176 ms |
| `fast` | 1 | 99 ms |

Tổng `cost_usd_est` cho 100 câu là 0,0756 USD.

## Ảnh (container ấm, 25/11)

Gửi `eval/assets/ocr/002.png`–`022.png` (21 ảnh) qua Telegram Web tới bot test `@askfirst_ai2_test_bot`. Gửi lần lượt, mỗi ảnh chờ bot trả lời xong mới gửi tấm tiếp; có vài ảnh lớn trả lời chậm hơn 15 s nên hai job có thể chồng nhau. Trên dev, flag `CONFIG#global/FLAGS.llm_enabled` đang là `false`, khi đó ảnh chỉ đi `rules_only` (bot báo bảo trì). Vì vậy flag được bật `true` lúc 04:36 UTC và trả về `false` lúc 04:47 UTC. Trong khoảng đó `ai` và `worker` kh có `INIT_START` nào, cũng kh có `job_failed`.

| Đo | n | p50 | p95 | tối đa |
|---|---|---|---|---|
| `ai` op `analyze` (log `ai`, gồm tải ảnh, OCR, VietOCR, guard, RAG, judge) | 21 | 6,81 s | 12,19 s | 12,84 s |
| Worker gọi `ai` (`ai_invoke … ms=`) | 21 | 6,83 s | 12,21 s | 12,88 s |
| `REPORT Duration` của worker (thêm render, gửi tin, ghi DynamoDB) | 21 | 7,72 s | 13,50 s | 14,31 s |
| Đầu-cuối: `START` webhook → `REPORT` worker | 21 | 11,28 s | **17,02 s** | 18,24 s |

- Job id là ULID do webhook sinh, trễ hơn `START` webhook 0,2–1 s. Đầu-cuối được ghép từ `START` webhook gần nhất trước ULID tới dòng `REPORT` của worker cho job đó.
- Số đầu-cuối chưa gồm thời gian Telegram nhận ảnh từ máy người gửi rồi đẩy webhook.
- Mẫu `001.png` gửi trước khi bật flag ra `rules_only` và gặp cold start (`ai` init 8,9 s, worker thấy 14,7 s), nên kh tính.

## Cold start (log CloudWatch 30 ngày tới 24/11)

| Đo | n | p50 | p95 / tối đa |
|---|---|---|---|
| Init `worker` (`REPORT … Init Duration`) | 18 | 2,21 s | 2,30 s |
| Init `ai` | 17 | 6,24 s | 8,49 s |
| Worker gọi `ai` op `analyze` (`ai_invoke … ms=`) | 21 | 242 ms | p95 10,2 s, tối đa 12,3 s |

- Cold start cộng dồn `worker` + `ai` ước khoảng 2,2 + 12,3 ≈ 14,5 s. Số này là cộng hai log, chưa đo đầu-cuối trong cùng một lần.
- Init của `ai` gồm warm RAG, OCR và VietOCR. `ocr_warm` dao động 2,3–7,3 s.
- Sau khi deploy v10, `INIT_REPORT` báo `Init Duration: 9999.35 ms, Status: timeout`. Lambda chạy init lại và request đầu mất 13 877 ms. Các cold start sau đó kh thấy timeout.

## Log

- `trace_id`: so 2 job ngày 19–20/11. Dòng `ai_invoke op=analyze trace=<id>` của worker khớp dòng `{"trace_id": "<id>", "op": "analyze", …}` của `ai`. Op `render` cũng khớp.
- Kh có nội dung: quét log 30 ngày.
  - Log `ai` chỉ gồm các dòng `event`/`op` (id, op, ms, kết quả), cảnh báo telemetry của onnxruntime và cảnh báo "text detection result is empty" của RapidOCR lúc warm.
  - Log `worker` chỉ gồm id job, op, ms, mức rủi ro, đường xử lý, mã lỗi và cảnh báo SLF4J.
- Ghi chú cho BE: 08:30–08:52 UTC ngày 24/11 worker báo `job_failed … error=AiException:invoke` 7 lần (2 job), nhưng `ai` kh có log nào trong khoảng đó, tức lỗi xảy ra trước khi tới `ai`. Lần thử lúc 08:55 thì chạy được.

## Cách chạy lại

- Văn bản: dùng script tạm (kh commit), gọi `lambda.invoke(FunctionName="askfirst-dev-ai", Qualifier="live")` lần lượt từng câu, có `check_id` và tiền tố riêng, rồi lấy `analyze.verdict.latency_ms`.
- Ảnh: bật `llm_enabled` nếu đang tắt, gửi ảnh qua bot, lấy `ai_invoke op=analyze` và `REPORT` của worker, `START` của webhook trong khoảng thời gian đó, ghép theo job id (ULID mang thời điểm tạo). Đo xong trả flag về như cũ.
- Cold start: chạy CloudWatch Logs Insights `filter @type="REPORT" and ispresent(@initDuration) | stats pct(@initDuration,50), pct(@initDuration,95), max(@initDuration)` trên `/aws/lambda/askfirst-dev-{ai,worker}`.
