# ADR-001: Bedrock client và spike Qwen3 32B

- **Trạng thái:** Chấp nhận. Đã trả lời đủ 4 câu (spike chạy 2026-11-11 trên Bedrock thật).
- **Ngày:** 2026-11-11
- **Người quyết định:** AI1
- **Task:** AI-02. Issue #16. Mở khoá AI-03 (G1), AI-08.

## Bối cảnh

Judge chạy Qwen3 32B trên Bedrock `ap-southeast-2` (ARCH §6.9). Client nằm ở `ai/src/askfirst/llm/bedrock.py`: Converse API, timeout, retry backoff + jitter, đếm token và chi phí, model đọc từ `models.yaml`, không log prompt/response. Test dùng client giả, không gọi mạng.

## Cách đo

- **Model:** `qwen.qwen3-32b-v1:0`. ID lấy từ `aws bedrock list-foundation-models --region ap-southeast-2`, model `ACTIVE`, `ON_DEMAND`. Trạng thái truy cập là `AUTHORIZED` (`get-foundation-model-availability`).
- **Prompt:** system là prompt thật `prompts/judge_v1.md`. User dựng bằng `judge.build_user_message` từ **4 tin tự viết**: phạt nguội, người thân gặp nạn, OTP thật của ngân hàng, trúng thưởng. Không dùng tin của người dùng hay bộ eval.
- **Tham số:** `max_tokens` 600, `temperature` 0. Input khoảng 1.281 token mỗi lần gọi.
- **Cách gọi:** gọi tuần tự từ máy dev ở Việt Nam tới Sydney, không chạy song song.
- **Lệnh:**
  ```bash
  cd ai && AWS_PROFILE=<profile> uv run python scripts/spike_bedrock.py --runs 30 --per-variant 5
  ```
- **Kết quả in ra:** script chỉ in số đếm, token và độ trễ, không in nội dung.
- **Lưu ý credential:** nếu credential lấy từ `aws login` thì boto3 cần `botocore[crt]`. Repo không thêm dependency này. Dùng một profile có `credential_process = aws configure export-credentials --profile <profile> --format process`, hoặc cài `botocore[crt]` ở máy mình.

## 4 câu trả lời

| # | Câu hỏi | Kết quả | Số đo |
|---|---|---|---|
| 1 | Qwen3 32B chạy qua Converse hay phải dùng `bedrock-mantle`? | **Chạy thẳng qua Converse**, không cần adapter `bedrock-mantle` | 107/107 lần gọi thành công, không lỗi, `stopReason` luôn là `end_turn` |
| 2 | Structured output có được hỗ trợ không? | **Có**, qua `outputConfig.textFormat` (`json_schema`). Chỉ dùng prompt thì JSON cũng đã hợp lệ đủ. Không dùng tool ép buộc | Xem chi tiết bên dưới |
| 3 | Cách tắt thinking | **Mặc định đã tắt.** Không cần gửi gì thêm. Thinking chỉ bật khi gửi `reasoning_effort` | Xem chi tiết bên dưới |
| 4 | Độ trễ p50 / p95 | **p50 1,39 s, p95 1,58 s** (max 1,62 s), n = 30, không lỗi | Đạt cổng G1 (p95 ≤ 5 s), dư nhiều |

### Chi tiết câu 2: structured output

Tỉ lệ JSON hợp lệ được tính khi `judge.parse_judgement` (schema `Judgement`) parse thành công.

| Cách | Số lần gọi | JSON hợp lệ | Token output (trung vị) |
|---|---|---|---|
| Chỉ dùng prompt | 30 | 30/30 | 165 |
| `outputConfig` `json_schema` | 5 | 5/5 | 136 |
| Ép tool (`toolChoice.tool`) | 5 | 1/5 | — |
| Ép tool (`toolChoice.any`) | 5 | 0/5 | — |

Hai cách ép tool hay hỏng vì model bỏ trường `followup_question`. Trường này được phép null, nhưng schema vẫn bắt buộc phải có mặt.

### Chi tiết câu 3: thinking

| Cách | Số lần gọi | Có thinking | Token output (trung vị) |
|---|---|---|---|
| Mặc định | 5 | 0/5 | 165 |
| Thêm `/no_think` vào tin | 5 | 0/5 | 153 |
| `additionalModelRequestFields: {"enable_thinking": false}` | 5 | 0/5 | 156 |
| `chat_template_kwargs.enable_thinking=false` | 5 | 0/5 | 152 |
| `enable_thinking: true` | 2 | 0/2 | — |
| `chat_template_kwargs.enable_thinking=true` | 2 | 0/2 | — |
| `/think` | 2 | 0/2 | — |
| `reasoning_effort: "high"` | 2 | 2/2 | 433, độ trễ 2,25 s |

Các cờ `enable_thinking` và `/think`, `/no_think` bị Bedrock bỏ qua mà không báo lỗi. Khi `reasoning_effort` bật thinking, model trả thêm block `reasoningContent`. Client bỏ block này, không đưa vào `text`.

## Chi phí

Giá on-demand "standard" ở `ap-southeast-2` theo AWS Price List API (2026-11-11): **0,1545 USD / 1M token input, 0,618 USD / 1M token output**. Giá này đã ghi vào `pricing_usd_per_1m` trong `models.yaml`.

- **Mỗi lần gọi:** trung bình khoảng **0,0003 USD**, tức khoảng 0,30 USD cho 1.000 tin. Toàn bộ spike tốn khoảng 0,03 USD.
- **Chưa kiểm:** Price List chỉ có usagetype `qwen.qwen3-32b-mantle-*`, không có dòng riêng cho Converse. Việc Converse tính theo đúng giá này **chưa kiểm (not verified)**. Đối chiếu Cost Explorer khi có hoá đơn.

## Quyết định

1. **Model:** điền `judge.model_id = qwen.qwen3-32b-v1:0` vào `ai/config/models.yaml`. Gọi qua Converse, không viết adapter `bedrock-mantle`.
2. **Thinking:** giữ `thinking: false` bằng cách **không gửi** `reasoning_effort`. Không cần `/no_think` hay `additionalModelRequestFields`. Nếu có code nào bật `reasoning_effort` thì phải đo lại độ trễ: thinking làm số token gấp khoảng 2,6 lần và chậm hơn khoảng 0,8 s.
3. **JSON:** giữ cách hiện tại là prompt, rồi `parse_judgement`, rồi hỏi lại một lần (AI-04, AI-07). Có thể thêm `outputConfig` `json_schema` làm lớp chặn thứ hai. AI-04 quyết việc này sau khi đo ≥ 98% trên bộ v0. Không dùng tool ép buộc.
   - **AI-04 (#40) chốt, 2026-11-11: chưa bật `outputConfig`.** Trên bộ v0, chỉ dùng prompt đã cho JSON hợp lệ 99% (99/100 lời gọi, ADR-002), đạt ngưỡng ≥ 98%. Lời gọi hỏng còn có `parse_judgement`, hỏi lại một lần và `post_check` đỡ. Hỏi lại xong vẫn hỏng thì pipeline về `rules_only` và nâng sàn UNSURE.
   - Lý do chưa bật: `outputConfig` mới thử 5 lần (5/5). Chưa đo nó trên 100 mẫu, chưa biết nó ảnh hưởng gì tới độ trễ và tới chất lượng lời giải thích. Thêm nó vào cũng là thêm một nhánh request cần test.
   - Bật lại khi JSON hợp lệ trên bộ v1 (AI-10) hoặc trên log production xuống dưới 98%. Lúc đó chạy `eval/benchmark_models.py` với ứng viên có `outputConfig` để so cùng bộ.

## Hạn chế, chưa đo

- **Mẫu nhỏ:** các biến thể chỉ chạy 5 lần, p95 tính trên 30 lần với 4 tin ngắn. Chưa có thẻ kịch bản RAG, nên input sẽ dài hơn khi bật RAG. AI-03 đo lại trên 100 mẫu eval (gate G1).
- **Chưa đo từ Lambda:** độ trễ đo từ máy dev ở Việt Nam. Lambda `ai` chạy ở `ap-southeast-1` (ARCH D1), gọi chéo sang Sydney, nên p95 thật phải đo lại trên Lambda (INT-01).
- **Chưa đo tải song song:** chưa đo throttle và quota khi nhiều tin tới cùng lúc.
- **Chưa đo chất lượng:** spike không đánh giá chất lượng phán đoán tiếng Việt. Việc này là của AI-03.
- **Model dự phòng:** `backup` (gpt-oss-120b) và `vlm` chưa điền model ID, ngoài phạm vi AI-02.
