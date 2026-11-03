"""`ArtifactStore` bản S3 cho Lambda `ai` (AI-03b, plan §3.5).

`path(name)` tải **lười** ở lần gọi đầu: `s3://<bucket>/<s3_prefix><file>` về `/tmp/artifacts/<name>/<version>/`, kiểm
sha256 từng file, sai checksum thì xoá file và raise `ArtifactError`. File đã có sẵn và đúng checksum (container còn
ấm) thì không tải lại. Tên không có trong `artifacts.yaml`, hoặc mục chưa có `version`/`files` (khung G0) cũng raise
`ArtifactError`. File `.part` tải dở luôn bị xoá, kể cả khi `download_file` lỗi giữa chừng.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

import yaml


class ArtifactError(Exception):
    pass


def _sha256(p: Path) -> str:
    with p.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


class S3ArtifactStore:
    def __init__(self, bucket: str, manifest: Path, root: Path = Path("/tmp/artifacts"), s3: Any = None) -> None:
        """`manifest` = `config/artifacts.yaml`; `s3` = `boto3.client("s3")` (test truyền bản giả)."""
        self.bucket, self.root, self._s3 = bucket, root, s3
        self.entries = {e["name"]: e for e in yaml.safe_load(manifest.read_text(encoding="utf-8"))}
        self._ready: dict[str, Path] = {}

    def path(self, name: str) -> Path:
        if name in self._ready:
            return self._ready[name]
        e = self.entries.get(name)
        if e is None:
            raise ArtifactError(f"{name}: không có trong artifacts.yaml")
        if not e.get("version") or not e.get("files"):
            raise ArtifactError(f"{name}: artifacts.yaml chưa có version/files")
        d = self.root / name / e["version"]
        d.mkdir(parents=True, exist_ok=True)
        if self._s3 is None:
            import boto3
            self._s3 = boto3.client("s3")
        for fname, sha in e["files"].items():
            f = d / fname
            if f.exists() and _sha256(f) == sha.lower():
                continue
            part = f.with_name(f.name + ".part")
            try:
                self._s3.download_file(self.bucket, e["s3_prefix"] + fname, str(part))
                if _sha256(part) != sha.lower():
                    raise ArtifactError(f"{name}/{fname}: sai sha256")
                part.replace(f)
            finally:
                part.unlink(missing_ok=True)
        self._ready[name] = d
        return d
