"""Tải embedder (AI-29) `intfloat/multilingual-e5-small` bản ONNX int8 về một thư mục, kiểm sha256 theo `config/artifacts.yaml`.

    uv run python scripts/fetch_embedder.py OUT_DIR

Thư mục ra dùng được ngay cho `build_index.py`, `EMBEDDER_DIR` (test) hoặc để upload lên S3 theo `s3_prefix`.
"""

from __future__ import annotations

import sys
from pathlib import Path

from fetch_ocr_models import fetch

# Ghim commit của repo Hugging Face để file không đổi dưới chân; sha256 nằm ở artifacts.yaml (nguồn duy nhất).
BASE = "https://huggingface.co/intfloat/multilingual-e5-small/resolve/614241f622f53c4eeff9890bdc4f31cfecc418b3/onnx/"
SOURCES = {"model.onnx": "model_qint8_avx512_vnni.onnx", "sentencepiece.bpe.model": "sentencepiece.bpe.model"}

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    fetch(Path(sys.argv[1]), "embedder", BASE, SOURCES)
