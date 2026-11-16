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

Script tải 3 file và dừng nếu sha256 khác `artifacts.yaml`. Upload lên `s3://$ARTIFACT_BUCKET/<s3_prefix>` bằng
`scripts/upload_artifacts.py` (mục cuối file), cần quyền AWS, **chưa làm**.

### int8

Plan ghi ONNX int8. `fetch_ocr_models.py --int8` lượng tử hoá động (`quantize_dynamic`, trọng số `QUInt8`) để workflow
`OCR bench` so với fp32 trên cùng ảnh. Kết quả (2026-11-03, `docs/results/ocr-spike.md` mục 10): int8 chép đúng URL
15/32 và 9/18, so với 31/32 và 17/18 của fp32, và còn chậm hơn. **Production dùng fp32** (ADR-003); `--int8` giữ lại chỉ để đo.

## `embedder`: `e5-small-qint8-614241f` (AI-29, ARCH §6.5)

`intfloat/multilingual-e5-small` bản ONNX int8 do chính tác giả đưa lên repo model, dùng qua
`askfirst.local_models.embedder.E5Embedder` (mean pooling, chuẩn hoá L2, 384 chiều, tiền tố `query: `/`passage: `).

| File | File gốc (commit `614241f622f53c4eeff9890bdc4f31cfecc418b3`) | sha256 |
|---|---|---|
| `model.onnx` | [`onnx/model_qint8_avx512_vnni.onnx`](https://huggingface.co/intfloat/multilingual-e5-small/blob/614241f622f53c4eeff9890bdc4f31cfecc418b3/onnx/model_qint8_avx512_vnni.onnx) (113 MB) | `dd476dd0c2514e9b9be83aeb3853fac0763e0bdf4a71645407587d77c48a2d88` |
| `sentencepiece.bpe.model` | [`onnx/sentencepiece.bpe.model`](https://huggingface.co/intfloat/multilingual-e5-small/blob/614241f622f53c4eeff9890bdc4f31cfecc418b3/onnx/sentencepiece.bpe.model) | `cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865` |

- sha256 khớp mã LFS do Hugging Face API trả về cho đúng commit trên (kiểm 2026-11-11).
- **License trọng số: MIT** (trường `license: mit` trong model card ở commit trên). License code: `sentencepiece` 0.2.3
  Apache-2.0 (PyPI), `onnxruntime` 1.31.0 MIT.
- Tokenizer: dùng `sentencepiece` (không có dependency phụ) thay cho `tokenizers` (cài thêm 17 gói, gồm cả
  `huggingface-hub`). Đối chiếu với `onnx/tokenizer.json` của model trên chữ của 138 mẫu eval và vài chuỗi Unicode lạ: 137/138 ra
  đúng dãy id. Mẫu còn lại khác cách cắt chuỗi 10 chữ số 0 (`0000|000000` so với `000000|0000`), trong pipeline thật
  số tài khoản đã bị mask trước khi embed.
- int8 so với bản fp32 (`onnx/model.onnx`) trên 40 đoạn văn: cosine giữa hai vector nhỏ nhất 0.9933, trung bình 0.9955.
- Tên file có `avx512_vnni` vì cấu hình lượng tử hoá của Optimum; file vẫn chạy trên CPU không có VNNI (máy đo là
  laptop Windows). Tốc độ trên CPU của Lambda chưa đo.

```bash
uv run python scripts/fetch_embedder.py out/embedder
```

## `kb_index`: `kb-2026.11.11` (AI-29, INT-03)

Không tải từ ngoài: `uv run python scripts/build_index.py out/embedder out/kb_index --version kb-2026.11.11` dựng
`index.npy` (124 × 384 float32, 186 KB) và `meta.json` từ `kb/scenarios.yaml` + `kb/alerts.yaml`, in sha256 để ghi vào
`artifacts.yaml`. sha256 đang ghi là của bản dựng trên laptop của AI2 ngày 2026-11-11 (dựng lại trên cùng máy ra đúng
sha256 đó). Máy khác có thể lệch ở chữ số cuối của float: khi đó upload bản của máy mình và ghi lại sha256.
Đổi `kb/alerts.yaml` hoặc `kb/scenarios.yaml` thì dựng lại với `--version` mới, đổi `version`/`s3_prefix`/sha256 rồi upload.

## Upload lên S3

```bash
ARTIFACT_BUCKET=<bucket> uv run python scripts/upload_artifacts.py embedder out/embedder
ARTIFACT_BUCKET=<bucket> uv run python scripts/upload_artifacts.py kb_index out/kb_index
```

Script kiểm sha256 từng file theo `artifacts.yaml` trước, lệch thì không upload file nào. Cần `s3:PutObject` (BE1 cấp).
**Chưa chạy** (máy AI2 chưa có quyền AWS).
