# Model chạy local trong Lambda `ai`

Danh sách artifact ở `config/artifacts.yaml` (nguồn duy nhất cho `version`, `s3_prefix`, sha256). File này ghi nguồn gốc,
license và cách dựng lại. `S3ArtifactStore` tải `s3://$ARTIFACT_BUCKET/<s3_prefix><file>` về `/tmp/artifacts/` và kiểm
sha256 trước khi dùng.

## `ocr`: `ppocrv6-small-rapidocr3.10.0` (AI-26, ADR-003)

Bộ model mặc định của RapidOCR 3.10.0, bản ONNX fp32, dùng qua `askfirst.local_models.ocr.RapidOcr`.

| File | Model gốc | sha256 | Nguồn |
|---|---|---|---|
| `PP-OCRv6_det_small.onnx` | PaddleOCR `PP-OCRv6_small_det` | `090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f` | [ModelScope RapidOCR v3.10.0](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv6/det/PP-OCRv6_det_small.onnx) |
| `PP-OCRv6_rec_small.onnx` | PaddleOCR `PP-OCRv6_small_rec` | `6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884` | [ModelScope RapidOCR v3.10.0](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv6/rec/PP-OCRv6_rec_small.onnx) |
| `ch_ppocr_mobile_v2.0_cls_mobile.onnx` | PaddleOCR `ch_ppocr_mobile_v2.0_cls` | `e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c` | [ModelScope RapidOCR v3.10.0](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv4/cls/ch_ppocr_mobile_v2.0_cls_mobile.onnx) |

- URL và sha256 lấy từ `rapidocr/default_models.yaml` trong wheel `rapidocr==3.10.0`; sha256 của 3 file tải về khớp
  (kiểm bằng `shasum -a 256`, 2026-11-03). Bảng ký tự của bộ đọc nằm trong metadata của `PP-OCRv6_rec_small.onnx`,
  không cần file từ điển riêng.
- **License trọng số: Apache-2.0.** Nguồn: `MODEL_LICENSES.md` đi kèm wheel `rapidocr==3.10.0` (mục 4 và 6). File này
  liệt kê đúng 3 file trên với cùng sha256, ghi bản quyền trọng số thuộc Baidu/PaddleOCR và RapidOCR chỉ chuyển sang ONNX.
  Thẻ model PaddlePaddle trên Hugging Face mà file đó dẫn tới chưa được mở để đối chiếu riêng: **not verified**.
- License code (trường `License` trong metadata wheel đang khoá ở `uv.lock`): `rapidocr` 3.10.0 Apache-2.0,
  `onnxruntime` 1.31.0 MIT, `opencv-python-headless` 5.0.0.93 Apache 2.0 (dùng cho decode QR).
- Model chỉ chạy trong Lambda của mình, không phát lại cho người dùng. Nếu sau này phân phối lại (vd đóng gói vào app),
  phải kèm bản Apache-2.0 và ghi chú nguồn như mục 7 của `MODEL_LICENSES.md`.

### Dựng lại và đưa lên S3

```bash
uv run python scripts/fetch_ocr_models.py out/ocr
```

Script tải 3 file và dừng nếu sha256 khác `artifacts.yaml`. Upload `out/ocr/*` lên `s3://$ARTIFACT_BUCKET/<s3_prefix>`
cần quyền AWS, **chưa làm**.

### int8

Plan ghi ONNX int8. `fetch_ocr_models.py --int8` lượng tử hoá động (`quantize_dynamic`, trọng số `QUInt8`) để workflow
`OCR bench` so với fp32 trên cùng ảnh. Production dùng fp32 cho tới khi bản int8 giữ được mức chép đúng URL; kết quả đo ở
`docs/results/ocr-spike.md` mục 10.

## `embedder`, `kb_index`

Chưa có (khung G0).
