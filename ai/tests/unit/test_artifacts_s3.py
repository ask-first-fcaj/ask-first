import hashlib
import pathlib

import pytest

from askfirst.artifacts.s3 import ArtifactError, S3ArtifactStore

CONFIG = pathlib.Path(__file__).resolve().parents[2] / "config" / "artifacts.yaml"
BLOBS = {"artifacts/ocr/v1/det.onnx": b"det", "artifacts/ocr/v1/rec.onnx": b"rec"}


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


class FakeS3:
    def __init__(self, blobs: dict[str, bytes]) -> None:
        self.blobs, self.calls = blobs, []

    def download_file(self, bucket: str, key: str, filename: str) -> None:
        self.calls.append((bucket, key))
        pathlib.Path(filename).write_bytes(self.blobs[key])


def manifest(tmp_path: pathlib.Path, files: dict[str, str], version: str = "v1") -> pathlib.Path:
    lines = [f"- name: ocr\n  version: '{version}'\n  s3_prefix: artifacts/ocr/v1/\n  license: Apache-2.0\n  files:"]
    lines += [f"    {k}: '{v}'" for k, v in files.items()] or ["    {}"]
    p = tmp_path / "artifacts.yaml"
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return p


def store(tmp_path, s3, **files) -> S3ArtifactStore:
    return S3ArtifactStore("bkt", manifest(tmp_path, files or {"det.onnx": sha(b"det"), "rec.onnx": sha(b"rec")}), tmp_path / "tmp", s3)


def test_downloads_lazily_checks_sha_and_caches(tmp_path) -> None:
    s3 = FakeS3(BLOBS)
    st = store(tmp_path, s3)
    assert s3.calls == []
    d = st.path("ocr")
    assert d == tmp_path / "tmp" / "ocr" / "v1"
    assert (d / "det.onnx").read_bytes() == b"det" and (d / "rec.onnx").read_bytes() == b"rec"
    assert sorted(s3.calls) == [("bkt", "artifacts/ocr/v1/det.onnx"), ("bkt", "artifacts/ocr/v1/rec.onnx")]
    assert st.path("ocr") == d and len(s3.calls) == 2


def test_existing_valid_file_is_not_downloaded_again(tmp_path) -> None:
    store(tmp_path, FakeS3(BLOBS)).path("ocr")
    s3 = FakeS3(BLOBS)
    store(tmp_path, s3).path("ocr")              # cold start mới, /tmp còn file đúng checksum
    assert s3.calls == []


def test_bad_checksum_raises_and_leaves_no_file(tmp_path) -> None:
    st = store(tmp_path, FakeS3(BLOBS), **{"det.onnx": sha(b"other")})
    with pytest.raises(ArtifactError):
        st.path("ocr")
    assert list((tmp_path / "tmp" / "ocr" / "v1").iterdir()) == []


def test_corrupt_local_file_is_replaced(tmp_path) -> None:
    d = store(tmp_path, FakeS3(BLOBS)).path("ocr")
    (d / "det.onnx").write_bytes(b"corrupt")
    assert (store(tmp_path, FakeS3(BLOBS)).path("ocr") / "det.onnx").read_bytes() == b"det"


def test_unknown_or_unconfigured_artifact(tmp_path) -> None:
    st = S3ArtifactStore("bkt", CONFIG, tmp_path, FakeS3({}))     # kb_index còn là khung G0: version/files rỗng
    with pytest.raises(ArtifactError):
        st.path("kb_index")
    with pytest.raises(ArtifactError):
        st.path("khong_co")


def test_failed_download_leaves_no_part_file(tmp_path) -> None:
    class DropS3(FakeS3):
        def download_file(self, bucket: str, key: str, filename: str) -> None:
            pathlib.Path(filename).write_bytes(b"de")         # tải dở rồi đứt mạng
            raise ConnectionError("reset")

    with pytest.raises(ConnectionError):
        store(tmp_path, DropS3(BLOBS)).path("ocr")
    assert list((tmp_path / "tmp" / "ocr" / "v1").iterdir()) == []
