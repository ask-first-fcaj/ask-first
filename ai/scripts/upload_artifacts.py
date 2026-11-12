"""Đẩy một artifact lên S3 đúng `s3_prefix` của `config/artifacts.yaml` (plan §3.5), chỉ khi sha256 từng file khớp.

    ARTIFACT_BUCKET=<bucket> uv run python scripts/upload_artifacts.py NAME DIR
    vd: scripts/upload_artifacts.py embedder out/embedder ; scripts/upload_artifacts.py kb_index out/kb_index

Cần quyền `s3:PutObject` trên bucket (BE1 cấp). File lệch sha256 thì dừng trước khi upload file nào.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import yaml
from fetch_ocr_models import MANIFEST, sha256


def upload(name: str, src: Path, bucket: str, s3, manifest: Path = MANIFEST) -> list[str]:
    entry = next((e for e in yaml.safe_load(manifest.read_text(encoding="utf-8")) if e["name"] == name), None)
    if not entry or not entry.get("version") or not entry.get("files"):
        raise SystemExit(f"{name}: artifacts.yaml chưa có version/files")
    bad = [f for f, sha in entry["files"].items() if not (src / f).is_file() or sha256(src / f) != sha]
    if bad:
        raise SystemExit(f"{name}: thiếu file hoặc sai sha256: {bad}")
    keys = [entry["s3_prefix"] + f for f in entry["files"]]
    for f, key in zip(entry["files"], keys, strict=True):
        s3.upload_file(str(src / f), bucket, key)
    return keys


if __name__ == "__main__":
    if len(sys.argv) != 3 or not os.environ.get("ARTIFACT_BUCKET"):
        sys.exit(__doc__)
    import boto3

    for k in upload(sys.argv[1], Path(sys.argv[2]), os.environ["ARTIFACT_BUCKET"], boto3.client("s3")):
        print(f"đã upload s3://{os.environ['ARTIFACT_BUCKET']}/{k}")
