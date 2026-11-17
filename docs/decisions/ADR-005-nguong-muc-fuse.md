# ADR-005: Ngưỡng mức của Fuse

- **Trạng thái:** Chấp nhận cho W5, đo lại khi có bộ eval v1 (300 mẫu) hoặc đổi prompt/model judge.
- **Ngày:** 2026-11-17
- **Người quyết định:** AI1
- **Task:** AI-10, issue #74. Dựa trên ADR-002 (judge Qwen3 32B + backup gpt-oss-120b).

## Bối cảnh

Fuse (ARCH §6.7, `ai/src/askfirst/pipeline/fuse.py`) đổi điểm 0–100 thành mức: `DANGEROUS` / `SUSPICIOUS` / `UNSURE` / `LOW_RISK`. Ngưỡng ban đầu 75/50/25 chỉ là giá trị khởi điểm. ARCH yêu cầu hiệu chỉnh để recall ≥ 90% và báo nhầm ≤ 10% trên tin lành tính trông đáng ngờ (eval gate trong `eval/run_eval.py`).

"Bắt được" nghĩa là mức ≥ `SUSPICIOUS`. Báo nhầm chỉ tính trên 20 mẫu `benign_suspicious`, nên 1 mẫu bằng 5 điểm %.

## Quyết định

1. **Đường có LLM (có `Judgement`): 70/60/25** (`Thresholds()` mặc định).
2. **Đường không có LLM (fast path, chỉ luật, kill switch): giữ 75/50/25** (`RULES_ONLY_THRESHOLDS`). Lượt này không hiệu chỉnh đường chỉ luật. Caller truyền `thresholds` khác mặc định thì vẫn dùng ngưỡng đó.

## Lý do

Chạy `RealPipeline` thật (có RAG) 3 lượt trên 134 mẫu có nhãn: 90 lừa đảo (`real_scam` 40 + `hard` 50) và 44 lành tính (`benign_suspicious` 20, `benign_normal` 20, `hard` 4). Mỗi lượt quét 301 bộ ngưỡng (D 60–90, S 35–65, U 10–40, bước 5, D > S > U). Không lượt nào có mẫu lỗi hay `rules_only`. Đường xử lý: lượt 1 và 2 có 111 `llm`, 22 `escalated`, 1 `fast`; lượt 3 có 108 `llm`, 25 `escalated`, 1 `fast`.

Recall / báo nhầm `benign_suspicious` / đúng mức nhãn:

| Ngưỡng D/S/U | Lượt 1 | Lượt 2 | Lượt 3 | Tin lành bị `DANGEROUS` |
|---|---|---|---|---|
| 75/50/25 (khởi điểm) | 100% / 15% / 95,5% | 100% / 15% / 95,5% | 100% / 10% / 96,3% | 0 |
| 75/60/25 | 100% / 10% / 96,3% | 100% / 10% / 96,3% | 100% / 10% / 96,3% | 0 |
| **70/60/25 (chọn)** | **100% / 10% / 97,0%** | **100% / 10% / 97,0%** | **100% / 10% / 97,0%** | **0** |
| 70/55/25 | 100% / 15% / 96,3% | 100% / 15% / 96,3% | 100% / 10% / 97,0% | 0 |
| 75/65/25 | 96,7% / 0% / 97,0% | 96,7% / 0% / 97,0% | 96,7% / 0% / 97,0% | 0 |
| 60/50/25 | 100% / 15% / 97,0% | 100% / 15% / 97,0% | 100% / 10% / 97,8% | 3 |

Báo nhầm `benign_normal` là 0% ở mọi dòng. Tỉ lệ `UNSURE` trên tin lừa đảo là 0%, trừ 75/65/25 (3,3%).

- **S = 60:** điểm thấp nhất của tin lừa đảo là 60 ở cả 3 lượt (`hd-rt-27`, `hd-rt-28`, `rs-ai-38`), nên S = 60 vẫn bắt đủ 90/90. S = 50/55 để lọt `bs-ai-18` (55, 55, 45 qua 3 lượt) thành báo nhầm 15% ở 2 lượt. S = 65 hết báo nhầm nhưng mất 3 tin lừa đảo điểm 60 (recall 96,7%).
- **D = 70:** đúng mức nhãn tăng vì `hd-rt-05` (điểm 70, nhãn `min_level` `DANGEROUS`) lên đúng mức. Không tin lành nào có điểm ≥ 70, nên không thêm báo người thân nhầm.
- **Không chọn D = 60** dù lượt 3 xếp nó đầu (đúng mức 97,8%): ở D = 60 có 3 tin lành bị `DANGEROUS` (`bs-ai-05`, `bs-ai-09` và `hd-rt-32`, cùng điểm 60). Mức `DANGEROUS` kèm hành động rủi ro sẽ báo người thân, sai ở đây tốn hơn sai thiếu 1 mẫu đúng mức. Script không chấm tiêu chí này nên bộ chọn ở lượt 3 do AI1 chọn tay theo cột cuối.
- **U = 25:** U từ 15 đến 35 cho cùng số liệu (tin `benign_normal` có điểm 5–15, tin lừa đảo ≥ 60), nên giữ khởi điểm.
- **Đường chỉ luật giữ 75/50/25:** gate eval trên CI chạy chỉ luật (chưa có `EVAL_AWS_ROLE_ARN`) và so với nhánh base. Áp 70/60/25 cho đường này làm recall chỉ luật tụt 22,2% → 17,8%, gate drift fail. Giữ 75/50/25 thì giả lập gate ra 22,2% (+0,0 điểm), đạt. Kill switch cũng không bắt kém đi so với trước.

## Cách đo

`eval/calibrate_thresholds.py` chạy pipeline một lần, ghi đầu vào của `fuse` cho từng mẫu (tín hiệu luật, `Judgement`, URL chính thức, có leo thang không), rồi gọi lại `fuse` với từng bộ ngưỡng. Kết quả bằng với chạy lại cả pipeline, vì các bước trước fuse (kể cả leo thang judge) không dùng ngưỡng. Chạy từ `ai/`:

```bash
AWS_PROFILE=askfirst-bedrock uv run --with "botocore[crt]" python ../eval/calibrate_thresholds.py --capture ../eval/out/fuse_inputs.jsonl
uv run python ../eval/calibrate_thresholds.py --inputs ../eval/out/fuse_inputs.jsonl   # chỉ quét lại, không gọi LLM
```

`botocore[crt]` cần cho profile tạo bằng `aws login`. Cần `ai/artifacts/` (embedder + `kb_index` `kb-2026.11.11`, xem `ai/MODELS.md`) để RAG chạy như production; thiếu thì RAG lỗi và bộ ngưỡng chọn ra không dùng được.

Thứ tự xếp hạng của script: báo nhầm ≤ 10% trước, rồi recall cao nhất, báo nhầm thấp nhất, đúng mức nhãn cao nhất, cuối cùng gần ngưỡng đang dùng nhất.

## Hạn chế

- **134 mẫu, không phải 300** như issue #74 ghi. Bộ v1 chưa có; khi có thì chạy lại lệnh trên.
- **Báo nhầm đo trên 20 mẫu**, 1 mẫu = 5 điểm %. 10% là đúng ngưỡng, không có biên.
- **S = 60 không có biên:** tin lừa đảo thấp nhất và 2 báo nhầm còn lại (`bs-ai-05`, `bs-ai-09`) đều 60 điểm. Ngưỡng không tách được hai nhóm này; kéo báo nhầm xuống dưới 10% là việc của prompt/luật (AI-13), không phải ngưỡng. Tin lừa đảo bị chấm 55 sẽ thành `UNSURE` (không thành `LOW_RISK`).
- Dữ liệu do team tự viết; vài luật `hd-rt` viết sau khi đã đọc chính các mẫu đó, nên recall 100% có thể cao hơn thực tế.
- Chạy từ máy dev, không phải Lambda. Điểm LLM dao động giữa các lượt (`bs-ai-18`), 3 lượt chưa đủ để nói chắc.
- Đường chỉ luật chưa hiệu chỉnh: recall chỉ luật 22,2% là số trên CI, không phải mục tiêu.

## Khi nào xem lại

- Có bộ eval v1, hoặc đổi prompt/model judge/backup.
- Eval M4 thấy báo nhầm > 10% hoặc recall < 90% trên dữ liệu mới.
