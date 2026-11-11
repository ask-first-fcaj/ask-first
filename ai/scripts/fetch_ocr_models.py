"""Tải model OCR (AI-26) từ ModelScope của RapidOCR về một thư mục, kiểm sha256 theo `config/artifacts.yaml`.

    uv run python scripts/fetch_ocr_models.py OUT_DIR
    uv run --with onnx python scripts/fetch_ocr_models.py OUT_DIR --int8 OUT_INT8_DIR   # thêm bản int8 để so trên OCR bench

Thư mục ra dùng được ngay làm `OCR_MODELS_DIR` (benchmark, test) hoặc để upload lên S3 theo `s3_prefix`.
Bản `--int8` lượng tử hoá động (`onnxruntime.quantization.quantize_dynamic`) chỉ để đo; chưa phải artifact production.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import urllib.request
from pathlib import Path

import yaml

MANIFEST = Path(__file__).resolve().parents[1] / "config" / "artifacts.yaml"
BASE = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/"
# URL lấy từ rapidocr 3.10.0 `default_models.yaml`; sha256 nằm ở artifacts.yaml (nguồn duy nhất).
SOURCES = {
    "PP-OCRv6_det_small.onnx": "PP-OCRv6/det/PP-OCRv6_det_small.onnx",
    "PP-OCRv6_rec_small.onnx": "PP-OCRv6/rec/PP-OCRv6_rec_small.onnx",
    "ch_ppocr_mobile_v2.0_cls_mobile.onnx": "PP-OCRv4/cls/ch_ppocr_mobile_v2.0_cls_mobile.onnx",
}


def sha256(p: Path) -> str:
    with p.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def expected(manifest: Path = MANIFEST, name: str = "ocr", sources: dict[str, str] = SOURCES) -> dict[str, str]:
    entry = next(e for e in yaml.safe_load(manifest.read_text(encoding="utf-8")) if e["name"] == name)
    if set(entry["files"]) != set(sources):
        raise SystemExit(f"artifacts.yaml `{name}` và SOURCES lệch nhau: {sorted(entry['files'])} / {sorted(sources)}")
    return entry["files"]


def fetch(out: Path, name: str = "ocr", base: str = BASE, sources: dict[str, str] = SOURCES) -> None:
    """Cũng dùng cho artifact khác (`fetch_embedder.py`): `sources` = tên file trong artifact -> đường dẫn sau `base`."""
    out.mkdir(parents=True, exist_ok=True)
    for fname, sha in expected(name=name, sources=sources).items():
        f = out / fname
        if f.exists() and sha256(f) == sha:
            print(f"có sẵn {fname}")
            continue
        part = f.with_name(f.name + ".part")
        urllib.request.urlretrieve(base + sources[fname], part)
        if sha256(part) != sha:
            part.unlink()
            raise SystemExit(f"{fname}: sai sha256, không dùng file này")
        part.replace(f)
        print(f"đã tải {fname} ({f.stat().st_size // 1024} KB)")


def quantize(src: Path, out: Path) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    out.mkdir(parents=True, exist_ok=True)
    for fname in SOURCES:
        quantize_dynamic(src / fname, out / fname, weight_type=QuantType.QUInt8)
        print(f"int8 {fname}: {(out / fname).stat().st_size // 1024} KB, sha256 {sha256(out / fname)}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path)
    ap.add_argument("--int8", type=Path, default=None, help="thư mục ghi bản lượng tử hoá int8 (cần gói onnx)")
    a = ap.parse_args()
    fetch(a.out)
    if a.int8:
        quantize(a.out, a.int8)
    return 0


if __name__ == "__main__":
    sys.exit(main())
