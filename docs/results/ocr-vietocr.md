# Kết quả AI-26 (#61): VietOCR đọc lại dòng tiếng Việt sau RapidOCR

> Người làm: AI2. Ngày đo: 2026-11-23. Code: nhánh `feat/ai-26-vietocr-reread` từ `main` `9396c65`. ADR liên quan: ADR-003.

## Kết luận

- **Nguyên nhân:** bộ chữ của `PP-OCRv6_rec_small` thiếu 88 chữ tiếng Việt (ả ạ ấ ộ ữ…; [PaddleOCR #18254](https://github.com/PaddlePaddle/PaddleOCR/issues/18254), đã kiểm trong metadata `character` của file ONNX). Model không in được các chữ này nên **bỏ cả nguyên âm**: "chuyển tiền" → "chuyn tin", "tài khoản" → "tài khon". Luật so khớp trên chữ đã bỏ dấu (`fold`) nên mất dấu thì không sao (bỏ hết dấu ở chữ đáp án: 0/66 ảnh đổi tín hiệu), nhưng mất chữ cái thì luật trượt: **15/33 tín hiệu luật bị mất** trên 66 ảnh.
- **Cách sửa:** RapidOCR vẫn tìm dòng, đọc URL và SĐT. Dòng nào RapidOCR đã ra ít nhất 1 chữ có dấu và không có URL thì VietOCR `vgg_seq2seq` (ONNX) đọc lại. Hai bản được **ghép theo từng cụm chữ cái** (`merge`): giữ nguyên chuỗi RapidOCR, chỉ thay cụm chữ nào mà VietOCR **chỉ thêm nguyên âm** (cùng phụ âm, cùng hoa thường). Số, dấu câu, khoảng trắng, URL luôn là bản RapidOCR.
- **Kết quả:** URL và SĐT giữ nguyên; từ có dấu đúng 37% → 89% (50 ảnh) và 27% → 97% (16 ảnh); tín hiệu luật bị mất 15/33 → 6/33. Trên Lambda 3008 MB, mỗi ảnh chậm thêm p50 0,56 s, p95 3,42 s.
- **Phát hiện kèm:** riêng RapidOCR production trên Lambda đã có **p95 5,07 s**, vượt mục tiêu 3 s của ADR-003 (điều kiện 2, trước giờ chỉ đo thay thế trên GitHub Actions).

**Quyết định (AI1, 2026-11-24):** bật VietOCR. Chấp nhận thêm p95 3,42 s mỗi ảnh để đọc đúng chữ có dấu. `version` của `vietocr` đã có trên `main` từ #178. Việc còn lại: đưa artifact lên S3 và đo lại trên Lambda `ai` thật (BE-06).

**Đề xuất ban đầu của AI2:**
1. Bật VietOCR (artifact `vietocr` trong `config/artifacts.yaml`), đánh đổi độ trễ lấy chữ đúng. Nếu chưa chấp nhận độ trễ, xoá `version` của mục này là tắt hẳn, không cần đổi code.
2. Mở lại ADR-003 phần độ trễ: ngay cả khi không có VietOCR, OCR trên Lambda đã vượt 3 s ở 30/66 ảnh.

## Cách chạy lại

```
# 1. Dựng artifact (env torch riêng, xem đầu file): ai/scripts/export_vietocr_onnx.py OUT_DIR
# 2. Đo trên 2 bộ ảnh (code repo, engine của eval/ocr_benchmark.py), chạy từ gốc repo:
OCR_MODELS_DIR=<ocr> VIETOCR_MODELS_DIR=<OUT_DIR> uv run --project ai python eval/ocr_benchmark.py \
    --engines askfirst askfirst_vietocr [--dataset eval/testocr/test_images.jsonl]
```

- Máy dev: Windows 10.0.26200, Python 3.12.14, onnxruntime 1.31.0, rapidocr 3.10.0.
- Lambda: image `public.ecr.aws/lambda/python:3.12` (x86_64), 3008 MB (2 vCPU), cùng code và model; một function tạm chạy `RapidOcr.read` trên 66 ảnh đóng gói sẵn trong image, lượt thứ 2 (container ấm). Chữ ra trên Lambda **trùng 66/66 ảnh** với máy dev cho cả 2 bản.
- Tác động lên luật: `RealPipeline` chỉ luật (`llm_enabled=False`) trên chữ đáp án, chữ production và chữ có VietOCR. Script tạm, không commit.

## Dữ liệu

| Bộ | Ảnh | URL | SĐT | Từ có dấu |
|---|---|---|---|---|
| `eval/datasets/ocr_images.jsonl` (phát triển) | 50 | 32 | 16 | 2.327 |
| `eval/testocr/test_images.jsonl` (kiểm độc lập của ADR-003) | 16 | 18 | 20 | 129 |

Bộ lọc của bản ghép theo dòng (bản trước) được chỉnh 3 lần theo lỗi thấy trên **cả hai bộ**, nên bộ 16 ảnh không còn là phép đo độc lập cho phần này. Bản ghép theo từ được viết theo quy tắc chung rồi sửa một lần theo lỗi của bộ 50 ảnh (`oi-044`: "pht.Hôm" dính dấu chấm); không sửa theo lỗi của bộ 16 ảnh, nhưng bộ này vẫn đã bị nhìn từ trước.

## Kết quả

| Chỉ số | Production | + VietOCR, ghép theo dòng (bản trước) | **+ VietOCR, ghép theo từ** |
|---|---|---|---|
| URL đúng (50 ảnh · 16 ảnh) | 31/32 · 17/18 | 31/32 · 17/18 | 31/32 · 17/18 |
| SĐT đúng | 16/16 · 20/20 | 16/16 · 20/20 | 16/16 · 20/20 |
| Từ có dấu đúng, 50 ảnh | 863/2327 (37%, Wilson 35–39%) | 1900/2327 (82%, Wilson 80–83%) | **2072/2327 (89%, Wilson 88–90%)** |
| Từ có dấu đúng, 16 ảnh | 35/129 (27%, Wilson 20–35%) | 66/129 (51%, Wilson 43–60%) | **125/129 (97%, Wilson 92–99%)** |
| CER, 50 ảnh · 16 ảnh | 12,8% · 7,1% | 7,7% · 6,5% | 6,8% · 5,3% |
| Tín hiệu luật `R_*` bị mất (so chữ đáp án) | 15/33 (15 ảnh) | 7/33 (7 ảnh) | **6/33** (6 ảnh) |
| Tín hiệu luật thừa | 2 | 2 (giống nhau) | 2 (giống nhau) |
| Lambda: p50 / p90 / p95 mỗi ảnh | 2,79 / 4,13 / 5,07 s | 3,60 / 6,51 / 7,70 s | 3,56 / 6,43 / 7,52 s |
| Lambda: thời gian thêm mỗi ảnh (so production cùng lượt) | | p50 0,60 s, p95 3,41 s, tối đa 4,13 s | p50 0,56 s, p95 3,42 s, tối đa 3,92 s |
| Lambda: ảnh quá 3 s | 30/66 | 43/66 | 43/66 (production lượt này 29/66) |
| Lambda: nạp model lần đầu | 15,1 s (tải từ S3 + nạp) | +1,1 s | +1,1 s |
| Dung lượng model | | 88 MB (fp32) | 88 MB (fp32) |

Hai bản ghép gửi cho VietOCR **đúng những dòng giống nhau** (552/1.254 dòng), chỉ khác bước ghép chữ sau khi đọc. Bước ghép theo từ cho 552 dòng (cả 66 ảnh) mất khoảng 70 ms trên máy dev, tức khoảng 1 ms mỗi ảnh. Đo lại trên Lambda (function tạm thứ hai, cùng cấu hình): độ trễ bằng bản ghép theo dòng trong sai số giữa hai lượt (production hai lượt lệch 0,01–0,1 s), chữ ra trùng máy dev 66/66 ảnh.

Bản ghép theo dòng phải nhận hoặc bỏ **cả dòng**: dòng lẫn số ("Gi chót đồi quà! 9082 đim") hoặc chữ chèn ký tự (`Ta,i,D'en'S;o'`) bị bỏ hết. Ghép theo từ giữ số và ký tự của RapidOCR, vẫn thay được phần chữ: "Giờ chót đồi quà! 9082 điểm". Bộ 16 ảnh có nhiều tin kiểu chèn ký tự nên tăng mạnh nhất (51% → 97%).

### Thử và bỏ

| Cách | Kết quả | Lý do bỏ |
|---|---|---|
| Đọc lại không có bộ lọc | URL 16 ảnh 17/18 → 10/18, SĐT 20/20 → 15/20 | VietOCR đọc sai số và phần đuôi URL |
| Gửi cả dòng thuần ASCII | gửi 1.160 dòng thay vì 552, từ có dấu 83% so với 82% | thời gian thêm mỗi ảnh p50 2,28 s so với 0,88 s (máy dev) |
| Gộp bề rộng ảnh dòng (đệm mép phải) để chạy theo lô | chỉ 79% dòng ra giống bản không đệm, có dòng bịa ("COILITE" → "SING THE CHONES") | đệm làm model bịa chữ |
| `cnn.onnx` int8 tĩnh (QDQ, theo kênh) | máy dev 82%; **Lambda 49% (50 ảnh), 36% (16 ảnh)** | lệch theo CPU; dùng fp32 |
| Đổi rec của RapidOCR (v6 medium, v5 latin) | đã thử 20/11 | v6 dùng chung bộ chữ; v5 latin thiếu 99 chữ, URL tụt |

Phần giải mã của bản ONNX nhanh hơn bản xuất đầu tiên khoảng 2,7 lần (30,8 s → 11,3 s trên 1.254 dòng, máy dev) nhờ tính sẵn phần attention chỉ phụ thuộc encoder; chữ ra y hệt (1.254/1.254 dòng).

## Ví dụ lỗi

Tín hiệu vẫn mất khi có VietOCR ghép theo từ (6):
- `oi-003` (`R_PRIZE`): dòng "Nhn ngay phn thưng đc quyn:https://vie" có URL nên không được gửi đọc lại.
- `oi-008` (`R_TRAFFIC_FINE`): chữ đã đúng, nhưng "vi phạm giao" và "thông" nằm ở 2 dòng ảnh khác nhau; luật không khớp qua dòng. Do ngắt dòng, không do dấu.
- `oi-011` (`R_URGENCY`): tin không dấu, RapidOCR đọc "24h" thành "24". Không liên quan dấu.
- `oi-043` (`R_URGENCY`): các dòng "xin tr cp", "trc tuyn ngay", "bay gi" vẫn thiếu chữ, tức bản VietOCR không khớp bộ khung phụ âm. Chưa xem VietOCR đọc ra gì.
- `oi-022` (`R_TRAFFIC_FINE`): chưa xem nguyên nhân.
- `oi-021` (`R_OTP_GENUINE` mất, `R_OTP_REQUEST` thừa): giống bản production, không do VietOCR.

Cụm VietOCR không được nhận (giữ bản RapidOCR):
- `+84 79 9165198` → VietOCR `48479 91651980`: số luôn lấy bản RapidOCR.
- `viphm` → VietOCR `vi phạm`: hai bản tách từ khác nhau, không khớp được.
- `BanK` → VietOCR `Bank`: đổi hoa thường.

Ảnh trong bộ là ảnh tổng hợp hoặc đã che; không có PII thật.

## Chưa kiểm

- Ảnh mới ngoài 66 ảnh này (bộ 16 ảnh đã bị nhìn khi chỉnh bản trước): **not verified**.
- Tác động khi có judge LLM (LLM có thể tự đoán chữ thiếu): **not verified**, cần Bedrock.
- License trọng số VietOCR: README chỉ ghi "pretrain model được cung cấp sẵn", không ghi license riêng: **not verified**.
- Lambda arm64, mức RAM khác 3008 MB: chưa đo.
- Ảnh có chữ dọc, chữ viết tay: chưa có trong bộ.
