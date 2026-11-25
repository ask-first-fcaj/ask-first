# Báo cáo eval cuối (W6, M6)

- **Ngày đo:** 2026-11-24, từ máy dev, gọi Bedrock `ap-southeast-2` thật.
- **Người đo:** AI1.
- **Code đo:** nhánh `fix/ai-165-judge-v3` (#167) commit `212cbe4`, tức `main` `7fcec9c` cộng `judge_v3`. Số dưới đây đúng cho `main` **sau khi #167 merge**; `main` hiện tại vẫn chạy `judge_v2` (số ở `docs/eval-report-m4.md`).

## Kết luận

| Tiêu chí | M4 (`judge_v2`, 17/11) | **Cuối (`judge_v3`, 24/11)** |
|---|---|---|
| Recall (90 tin lừa đảo) | 100% cả 3 lượt | **100%** cả 3 lượt |
| Báo nhầm `benign_suspicious` (n=20) | 5 / 10 / 10% | **5 / 5 / 5%** |
| Báo nhầm `hard` lành tính (n=4) | 25% | **0%** |
| Đúng mức nhãn | 97,0–97,8% | **99,3%** |
| Đúng mã kịch bản | 59,7–61,2% | **67,2–68,7%** |
| p95 cả pipeline (máy dev) | 5,3–5,7 s | **4,9–5,6 s** |
| USD / mẫu (chỉ token LLM) | 0,000739 | **0,000792** (≈ 20 đ) |

Đạt ngưỡng recall ≥ 90% và báo nhầm ≤ 10% với biên 1 mẫu (5%). Vẫn chỉ có **134 mẫu, 0 mẫu thật** (`real_anonymized`), nên mọi số là trên dữ liệu team tự soạn hoặc viết lại.

## Cách đo

Giống `docs/eval-report-m4.md`: `RealPipeline` đầy đủ (chuẩn hoá → che PII → luật/URL → RAG → Guardrail → judge Qwen3 32B, backup gpt-oss-120b → fuse 70/60/25), 3 lượt độc lập, mỗi lượt cache riêng, trên 134 mẫu có nhãn (`ocr_images.jsonl` bị bỏ vì không có `is_scam`).

Lượt đầu chạy 6 lượt song song (3 lượt `judge_v3`, 3 lượt `judge_v4` ở phần dưới). 1–5 mẫu mỗi lượt rơi về `rules_only` (judge quá giờ khoảng 10 s khi gọi song song nhiều). Các mẫu này không được cache nên chạy lại từng cặp 2 lượt; cuối cùng **134/134 mẫu có LLM ở cả 6 lượt**, 0 lỗi.

Lệnh (từ `ai/`, cần `ai/artifacts/`):

```bash
for i in 1 2 3; do
  AWS_PROFILE=askfirst-bedrock uv run --with "botocore[crt]" python ../eval/run_eval.py \
    ../eval/datasets/{benign_normal,benign_suspicious,hard,real_scam}.ai1.jsonl ../eval/datasets/hard.jsonl \
    --pipeline real --out ../eval/out/final/v3/run$i --cache ../eval/out/final/v3/run$i/cache
done
```

## Chất lượng (`judge_v3`)

| | Lượt 1 | Lượt 2 | Lượt 3 |
|---|---|---|---|
| Recall | 100% | 100% | 100% |
| `UNSURE` trên tin lừa đảo | 0% | 0% | 0% |
| Báo nhầm `benign_suspicious` | 5% | 5% | 5% |
| Báo nhầm `benign_normal` | 0% | 0% | 0% |
| Báo nhầm `hard` lành tính | 0% | 0% | 0% |
| Đúng mức nhãn | 99,3% | 99,3% | 99,3% |
| Đúng mã kịch bản | 67,2% | 68,7% | 67,9% |
| Thiếu `must_signals` | 0 | 0 | 0 |

| Nhóm | Số mẫu | F1 lượt 1 | F1 lượt 2 | F1 lượt 3 |
|---|---|---|---|---|
| N1 Mạo danh cơ quan, tổ chức | 43 | 81,2% | 80,0% | 81,2% |
| N2 AI, deepfake | 5 | 100% | 100% | 100% |
| N3 Việc làm, đầu tư, tình cảm | 22 | 95,5% | 95,5% | 95,5% |
| N4 Mua bán trực tuyến | 7 | 80,0% | 80,0% | 80,0% |
| **N5 Chiếm quyền thiết bị, tài khoản** | 9 | **12,5%** | **11,8%** | **12,5%** |
| N6 Lấy lại tiền bị lừa | 2 | 100% | 100% | 100% |

Theo nguồn mẫu: `rewritten` (38) và `ai_drafted` (56) đúng mức 100%; `constructed` (40) báo nhầm 6,7% (1/15), đúng mức 97,5%.

Mẫu lành tính điểm cao nhất: `bs-ai-05` 60 (`SUSPICIOUS`, cả 3 lượt, là mẫu báo nhầm duy nhất), `hd-rt-32` 50, `bs-ai-18` 45 (2/3 lượt).

## Độ trễ và chi phí

| | Lượt 1 | Lượt 2 | Lượt 3 |
|---|---|---|---|
| p50 cả pipeline | 2,42 s | 2,36 s | 2,47 s |
| p95 cả pipeline | 5,02 s | 5,57 s | 4,91 s |
| p95 judge | 4,30 s | 4,86 s | 4,18 s |
| p95 Guardrail | 0,84 s | 0,85 s | 0,85 s |
| Đường `llm` / `escalated` / `fast` | 107 / 26 / 1 | 105 / 28 / 1 | 105 / 28 / 1 |
| USD / mẫu | 0,000783 | 0,000804 | 0,000790 |

Độ trễ đo khi đang chạy song song nhiều lượt, nên có thể cao hơn khi chạy một mình. Chưa đo trên Lambda.

Theo đường: `llm` ≈ 0,00066 USD, `escalated` ≈ 0,0013 USD. TB 0,000792 USD × 25.820 đ/USD (tỉ giá và nguồn như báo cáo M4) ≈ **20 đ mỗi lượt kiểm tra**, 1.000 lượt ≈ 0,79 USD. Tăng 7% so với M4, có thể do `judge_v3` dài hơn (thêm danh sách mã dấu hiệu); `Verdict` không ghi token nên chưa tách được (not verified). **Chỉ tính token LLM**, chưa gồm Guardrail, Lambda, API Gateway, DynamoDB, S3; chi phí AWS thật cần lấy từ Cost Explorer (not verified).

## Thử và bỏ: `judge_v4` (câu ngắn, bỏ tiếng Anh)

Từ đo độ dễ đọc AI-32 (#172): nhiều lời giải thích có câu dài và chêm app/click/website. `judge_v4` = `judge_v3` thêm đúng mục sau trước "## Câu hỏi thêm":

```
## Cách viết short_explanation
- Người đọc lớn tuổi, đọc chậm: viết câu ngắn, mỗi câu không quá 20 tiếng. Có 2 ý thì tách thành 2 câu, không nối dài bằng "đặc biệt nếu…".
- Không dùng từ tiếng Anh: viết "ứng dụng" (không viết app), "bấm" (không viết click), "trang web" (không viết website), "số tổng đài" (không viết hotline). Riêng "OTP" và "link" giữ nguyên vì người dùng đã quen.
```

Đo cùng buổi, cùng cách, 3 lượt. Tiêu chí đặt trước khi đo: giữ v4 chỉ khi recall và báo nhầm không xấu đi.

| | `judge_v3` | `judge_v4` |
|---|---|---|
| Recall | 100% | 100% |
| Báo nhầm `benign_suspicious` | 5 / 5 / 5% | **10 / 10 / 5%** |
| Đúng mức nhãn | 99,3% | 98,5–99,3% |
| Đúng mã kịch bản | 67,2–68,7% | 64,9–67,2% |
| USD / mẫu | 0,000792 | 0,000836 |
| Lời giải thích có câu dài TB > 20 tiếng | 61/399 (15%) | **20/399 (5%)** |
| Tiếng / câu TB | 16,8 | **13,9** |
| Có tiếng Anh ngoài "otp", "link" | 101/399 (25%) | **51/399 (13%)** |

Đo dễ đọc bằng `eval/readability.py` của nhánh AI-32 (chưa merge), trên `explanation` của mọi mẫu có LLM (3 lượt × 133 mẫu). Ngưỡng của script chưa hiệu chỉnh; danh sách từ tiếng Anh của script tính cả "otp" và "link", nên tách thêm cột bỏ hai từ này.

**Kết quả: bỏ v4.** Dễ đọc hơn rõ, nhưng báo nhầm tăng ở 2/3 lượt, đúng một mẫu: `bs-ai-15` (tin báo lương kèm link portal công ty). v3 chấm `LOW_RISK` 10 cả 3 lượt; v4 làm judge chính không chắc, chuyển sang backup và ra `SUSPICIOUS` 60 (2 lượt), `UNSURE` 45 (1 lượt). Có 20 mẫu nên 1 mẫu là 5 điểm %, nhưng lệch lặp lại ở cùng mẫu nên không coi là nhiễu. Muốn thử lại: sửa lời giải thích ở bước sau judge (không đổi prompt chấm điểm), hoặc đo trên bộ báo nhầm lớn hơn.

## OCR

AI1 quyết bật VietOCR ngày 2026-11-24 (chấp nhận thêm p95 3,42 s mỗi ảnh; `docs/results/ocr-vietocr.md`). Báo cáo này **không đo ảnh**: `ocr_images.jsonl` không có nhãn `is_scam`, và tác động của VietOCR khi có judge LLM vẫn not verified.

## Điểm yếu còn lại

1. **N5 F1 ~12%** (M4 ~24%). 9 mẫu, bị xếp vào N1 24/27 lần (3 lượt); ảnh hưởng mã kịch bản và card RAG, không ảnh hưởng mức.
2. **`bs-ai-05` vẫn báo nhầm** (60, cả 3 lượt), cùng mẫu sát ngưỡng ở M4.
3. **Recall 100% có thể lạc quan:** vài luật `hd-rt` viết sau khi đã đọc chính các mẫu đó.

## Hạn chế

- 134 mẫu, không phải 300; 0 mẫu thật.
- Báo nhầm đo trên 20 mẫu: 1 mẫu = 5 điểm %, khoảng tin cậy rất rộng.
- Từ máy dev, chưa đo trên Lambda `ai` (BE-06 chưa xong); chi phí chỉ tính token LLM.
- `json_valid_rate`, `rejected_citation_rate` vẫn `null` như M4.
