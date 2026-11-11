# ADR-002: Gate G1, chọn model judge

- **Trạng thái:** Chấp nhận phần số đo (2026-11-11). Phần chấm tay tiếng Việt 20 mẫu đang chờ team, chưa có điểm.
- **Ngày:** 2026-11-11
- **Người quyết định:** AI1
- **Task:** AI-03, issue #38. Dựa trên ADR-001 (AI-02).

## Quyết định

**Judge giữ Qwen3 32B, tắt thinking** (`qwen.qwen3-32b-v1:0`, đã điền trong `ai/config/models.yaml` từ AI-02). Đây là ứng viên duy nhất đạt cả hai ngưỡng eval gate (recall ≥ 90%, báo nhầm ≤ 10%). Nó cũng nhanh nhất, và rẻ thứ hai.

- **Không bật thinking:** recall chỉ tăng 1 mẫu, còn báo nhầm tăng từ 10% lên 45%, độ trễ gần gấp đôi.
- **Backup: `gpt-oss-120b`** (`openai.gpt-oss-120b-1:0`), AI1 duyệt và điền vào `ai/config/models.yaml` ngày 2026-11-11. Lý do chọn: recall 100% và đúng mức 95% khi chạy một mình. Model này báo nhầm 25% khi chạy một mình, nhưng trong cascade nó không làm tăng báo nhầm (xem mục *Đo cascade có backup* bên dưới). Backup được gọi trong hai trường hợp: judge lỗi/throttle/timeout/JSON hỏng, và **leo thang** khi judge trả `confidence=low` hoặc mâu thuẫn với luật. Khi leo thang, câu trả lời của backup thay câu của judge. **Còn thiếu quyền IAM** `bedrock:Converse` trên model này cho Lambda `ai` (BE-05). Khi chưa có quyền, lời gọi backup lỗi và pipeline giữ câu của judge (trường hợp leo thang) hoặc chạy chỉ luật (trường hợp judge lỗi), giống như lúc chưa điền.
- **Claude Haiku 5.5 không đo được:** mọi lời gọi trả `AccessDeniedException` (99/99), vì account chưa được cấp quyền dùng model Anthropic trên Bedrock. Dòng của Haiku trong bảng dưới chỉ là kết quả `rules_only`, không phải chất lượng của model.
- **Mốc tham chiếu thay Haiku: Gemini 3.8 Flash** (`gemini-3.8-flash`, gọi Gemini API trực tiếp, không qua Bedrock), đo ngày 2026-11-11. Model đạt recall 100%, báo nhầm 0%, đúng mức 100%, đúng kịch bản 70%. Như vậy Qwen3 32B còn cách mốc 2 tin báo nhầm (10% so với 0%) và 23 điểm % đúng kịch bản; khoảng cách này là phần prompt/luật còn có thể cải thiện. **Không đổi quyết định:** Gemini chỉ là mốc so sánh. Đưa nó vào production nghĩa là tin của người dùng ra khỏi AWS, phải sửa ADR-001 và kiến trúc. Ngoài ra nó đắt gấp khoảng 5 lần (0,001575 so với 0,000318 USD/mẫu) và chậm gấp khoảng 2,3 lần (p95 3,86 so với 1,67 s).

## Cách đo

- **Bộ dữ liệu:** eval v0 (`eval/datasets/*.jsonl`): 150 mẫu, trong đó 100 mẫu có nhãn (60 lừa đảo, 40 lành tính). 50 mẫu không có nhãn `is_scam` bị bỏ qua khi tính điểm.
- **Mẫu số của từng chỉ số:** recall tính trên 60 mẫu lừa đảo (`real_scam` 40 + `hard` 20). **Báo nhầm chỉ tính trên 20 mẫu `benign_suspicious`** (tin lành tính nhưng trông giống lừa đảo, `eval/run_eval.py`), nên mỗi mẫu bằng 5 điểm %. 20 mẫu `benign_normal` được báo riêng trong `false_alarm_by_split`, lượt nào cũng 0%.
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
| **qwen3-32b** | **98,3%** (59/60) | **10,0%** (2/20) | 94,0% | 47,0% | 99,0% | **1,26 / 1,67** | **1,70** | 0,000318 | 0 |
| qwen3-32b-thinking | 100% | 45,0% | 91,0% | 46,0% | 99,0% | 2,20 / 3,01 | 3,10 | 0,000482 | 0 |
| gpt-oss-120b | 100% | 25,0% | 95,0% | 51,0% | 96,1% | 3,38 / 5,10 | 5,10 | 0,000518 | 0 |
| gemma-3-12b | 98,3% | 25,0% | 89,0% | 52,0% | 98,0% | 4,50 / 6,21 | 6,38 | 0,000168 | 0 |
| claude-haiku-5-5 | (AccessDenied, chỉ rules) | | | | n/a | n/a | 0,67 | 0 | 99 |
| *gemini-3-8-flash (mốc, Gemini API)* | *100%* | *0,0%* | *100%* | *70,0%* | *100%* | *3,04 / 3,86* | *3,85* | *0,001575* | *0* |

- **Dòng Gemini** chạy riêng, sau 5 dòng trên, cùng 100 mẫu và cùng prompt: `GEMINI_API_KEY=<key> uv run python ../eval/benchmark_models.py --only gemini-3-8-flash`. Bật thinking mức `low` (mặc định của `eval/gemini_client.py`); token suy nghĩ tính vào output. Giá tính theo bảng giá chuẩn của Gemini API ngày 2026-11-11: 0,75 / 3,75 USD cho 1M token vào / ra. Đây là giá giới thiệu tới 2026-12-31, từ 2027-01-01 tăng gấp đôi ([nguồn](https://ai.google.dev/gemini-api/docs/pricing)). **Chỉ dùng key Gemini ở paid tier.** Theo trang giá, nội dung gửi qua free tier được Google dùng để cải thiện sản phẩm. Bộ v0 có 22 mẫu `ai_drafted`, nên không được đưa sang đó. Chỉ gửi bộ eval tự soạn, không bao giờ gửi tin của người dùng. Lời giải thích của Gemini **không** có trong phiếu chấm tay `docs/results/g1-grading.md`, để không đổi phiếu team đang chấm.

- **Đúng mức:** mức của verdict nằm trong khoảng `min_level`…`max_level` của nhãn.
- **Đúng kịch bản:** trùng mã kịch bản (vd `N1.1`) của nhãn.
- **JSON hợp lệ:** tỉ lệ lời gọi trả JSON parse được. Qwen3 32B đạt 99%, nên đạt luôn điều kiện "JSON hợp lệ ≥ 98% trên bộ v0" của AI-04.
- **Chi phí:** Qwen3 32B tốn khoảng 0,32 USD cho 1.000 lượt kiểm. Tổng benchmark tốn khoảng 0,15 USD.

## Đo cascade có backup (2026-11-11)

Chạy `RealPipeline` với cấu hình production (`judge` thử lại 1 lần, `backup` 1 lần) trên 100 mẫu có nhãn của eval v0 (50 mẫu không có nhãn `is_scam` bị bỏ qua). Hai cấu hình chạy cùng lúc, chỉ khác `backup.model_id`.

| Cấu hình | Recall | Báo nhầm | Đúng mức | Đúng kịch bản | Mẫu p50 / p95 (s) | USD/mẫu | Leo thang | Lỗi gọi |
|---|---|---|---|---|---|---|---|---|
| Qwen3 32B, backup trống | 98,3% | 15,0% (3/20) | 93,0% | 47,0% | 1,33 / 1,71 | 0,000313 | 20 lần (backup trống nên giữ câu judge) | 0 |
| Qwen3 32B + gpt-oss-120b | 98,3% | 15,0% (3/20) | 93,0% | 48,0% | 1,35 / 4,43 | 0,000397 | 19 lần gọi backup | 0 |

- **Mức cảnh báo không đổi ở mẫu nào** (0/100 mẫu đổi `level`). Đúng kịch bản tăng 1 điểm % (47 → 48%).
- **Giá:** tăng khoảng 27% (thêm 0,000084 USD/mẫu). **Độ trễ:** p95 tăng từ 1,71 lên 4,43 s, vì khoảng 20% mẫu leo thang. Vẫn trong ngân sách p95 ≤ 15 s (ARCH §2).
- **Lợi ích chính** là khi judge lỗi hoặc bị throttle: có backup thì vẫn có câu trả lời của LLM thay vì chạy chỉ luật. Lượt chạy này judge không lỗi lần nào, nên phần này **chưa đo được**.
- **Báo nhầm 15% ở cả hai cấu hình, cao hơn 10% của bảng kết quả ở trên** (cùng 100 mẫu, cùng prompt). Xem mục *Đo độ dao động* bên dưới.
- Tỉ lệ leo thang khoảng 20%, gấp đôi mức ước tính 10% trong ARCH §6.9. Chi phí tháng tăng tương ứng.

## Đo độ dao động của báo nhầm (2026-11-11)

Chạy thêm 3 lượt Qwen3 32B (backup trống, cấu hình production) trên 100 mẫu có nhãn, lưu mức của từng mẫu.

- **Cả 5 lượt chiều nay** (2 lượt A/B ở trên + 3 lượt này) đều báo nhầm **15% (3/20)**, recall 98,3%, đúng mức 93%. **0 mẫu đổi mức** giữa các lượt. Đúng kịch bản dao động 46–48%.
- **Khác với lượt benchmark buổi sáng (2/20) đúng 1 mẫu:** `bs-ai-11`, tin báo hồ sơ dịch vụ công đã tiếp nhận, kèm link `dichvucong.gov.vn`. Buổi sáng ra `UNSURE`, chiều nay ra `SUSPICIOUS`: judge gán `N1.4`, `risk_score` 50, `confidence` medium. Link được gắn tín hiệu `U_OFFICIAL` nhưng trọng số 0, nên không kéo điểm xuống. Ngoài ra `rs-ai-11` (mẫu lừa đảo) lên từ `SUSPICIOUS` thành `DANGEROUS`, không ảnh hưởng gate.
- **Hai mẫu báo nhầm còn lại ổn định ở cả 6 lượt:** `bs-ai-15` (tin báo lương kèm cổng nội bộ công ty, judge cho 55) và `bs-ai-05` (cháu nhắn đã chuyển tiền cho ông bà, judge gán `N5.4`, 80, `DANGEROUS`).
- **Kết luận:** đây không phải nhiễu ngẫu nhiên từng lời gọi. Trong cùng một buổi Qwen3 trả kết quả giống hệt nhau, nhưng giữa sáng và chiều thì lệch 1 mẫu. Code pipeline, prompt và KB không đổi giữa hai lượt: `git diff f8401e1 -- ai prompts kb` chỉ có dòng `backup.model_id` và 1 test, mà 3 lượt đo này để backup trống. Nguyên nhân có thể nằm phía Bedrock (đổi bản phục vụ model), chưa xác minh (not verified).
- **Hệ quả với gate:** Qwen3 32B **hiện trượt ngưỡng báo nhầm ≤ 10% đúng 1 mẫu**. Với 20 mẫu thì mỗi mẫu là 5 điểm %, nên con số này chưa đủ chắc để đổi quyết định chọn model: các ứng viên khác đều báo nhầm ≥ 25% khi chạy một mình. Giữ Qwen3 32B, xử lý ở AI-13:
  - cho tín hiệu `U_OFFICIAL` (link `.gov.vn` hoặc tên miền trong allowlist, có `https`) hạ điểm hoặc chặn trần mức, thay vì trọng số 0;
  - đo lại trên bộ v1 300 mẫu (AI-10) để mẫu số báo nhầm lớn hơn;
  - chạy gate ít nhất 2 lượt ở 2 thời điểm khác nhau và báo khoảng, không báo một con số.

## Rủi ro và việc còn lại

- **Báo nhầm nằm đúng ngưỡng gate và đã trượt 1 mẫu ở lượt chiều** (sáng 2/20 = 10%, chiều 3/20 = 15%, xem mục *Đo độ dao động*). Bộ v1 300 mẫu (AI-10) sẽ cho số chắc hơn, và AI-13 tinh chỉnh ngưỡng cùng trọng số `U_OFFICIAL` trên bộ đó.
- **Đúng kịch bản thấp ở mọi model** (46–52%), nghĩa là model hay gán nhầm mã kịch bản chi tiết. Điều này không ảnh hưởng tới cảnh báo, nhưng ảnh hưởng tới nguồn trích dẫn theo kịch bản. Việc này để cho AI-11 (chấm chất lượng lời giải thích) và việc chỉnh prompt.
- **Chấm tay tiếng Việt:** 20 mẫu ngẫu nhiên (seed 3) nằm ở `docs/results/g1-grading.md`. Mỗi mẫu có lời giải thích của 4 model, tên model được ẩn sau chữ cái, bảng giải mã ở cuối file. Team chấm 1–5 rồi ghi điểm trung bình vào ADR này. Nếu Qwen3 32B thua rõ thì mở lại quyết định.
- **Độ trễ trên Lambda** chưa đo. Số ở trên đo từ máy dev, và gồm cả đường mạng Việt Nam ↔ Sydney.
