"""AI-26: ``local_models/ocr.py``: hậu xử lý, decode QR, nạp model lười. Engine RapidOCR được thay bằng bản giả;
chạy model thật chỉ khi có ``OCR_MODELS_DIR`` (workflow OCR bench tải model rồi chạy test này)."""

from __future__ import annotations

import os
import pathlib
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from askfirst.local_models import ocr as O


def png(img: np.ndarray) -> bytes:
    return cv2.imencode(".png", img)[1].tobytes()


def qr_image(payload: str) -> np.ndarray:
    q = cv2.QRCodeEncoder.create().encode(payload)
    q = cv2.resize(q, None, fx=8, fy=8, interpolation=cv2.INTER_NEAREST)
    return cv2.cvtColor(cv2.copyMakeBorder(q, 40, 40, 40, 40, cv2.BORDER_CONSTANT, value=255), cv2.COLOR_GRAY2BGR)


class Store:
    def __init__(self, d: pathlib.Path):
        self.d, self.calls = d, []

    def path(self, name: str) -> pathlib.Path:
        self.calls.append(name)
        return self.d


def test_repair_keeps_both_fixes():
    assert O.repair_urls("vào ttps://. vcb.top") == "vào https://vcb.top"
    assert O.repair_urls("https://\nvcb.top") == "https://vcb.top"
    assert O.repair_phones("gọi O9l2 345 678") == "gọi 0912 345 678"


def test_order_by_columns_reads_left_then_right():
    # 2 khung chat cạnh nhau, engine trả xen kẽ trái/phải theo hàng
    box = lambda x, y: [[x, y], [x + 80, y], [x + 80, y + 20], [x, y + 20]]
    txts = ["L1", "R1", "L2", "R2", "L3", "R3"]
    boxes = [box(0 if t[0] == "L" else 200, 30 * int(t[1])) for t in txts]
    assert O.order_by_columns(txts, boxes).splitlines() == ["L1", "L2", "L3", "R1", "R2", "R3"]
    assert O.order_by_columns([], []) == ""


@pytest.mark.parametrize("payload", ["https://vcb-xacthuc.top/a?b=1", "https://qr.top/x", "abc"])
def test_decode_qr(payload):
    assert O.decode_qr(qr_image(payload)) == [payload]


def test_decode_two_qr_codes():
    assert sorted(O.decode_qr(np.hstack([qr_image("https://a.top/1"), qr_image("https://b.top/2")]))) == \
        ["https://a.top/1", "https://b.top/2"]


def test_decode_qr_on_blank_image():
    assert O.decode_qr(np.full((200, 200, 3), 255, np.uint8)) == []


def test_read_is_lazy_and_post_processes(monkeypatch, tmp_path):
    made = []

    class FakeRapidOCR:
        def __init__(self, params):
            made.append(params)

        def __call__(self, img):
            assert img.ndim == 3 and img.shape[2] == 3   # ảnh BGR đã giải mã
            box = [[0, 0], [10, 0], [10, 5], [0, 5]]
            return SimpleNamespace(txts=("vào ttps://vcb.top", "gọi O912 345 678"), boxes=np.array([box, box]),
                                   scores=(0.9, 0.7))

    monkeypatch.setattr("rapidocr.RapidOCR", FakeRapidOCR)
    store = Store(tmp_path)
    eng = O.RapidOcr(store, version="v", threads=2)
    assert store.calls == []                     # dựng xong chưa tải gì
    out = eng.read(png(qr_image("https://qr.top/x")))
    eng.read(png(qr_image("abc")))
    assert store.calls == ["ocr"] and len(made) == 1
    assert made[0]["Det.model_path"] == str(tmp_path / "PP-OCRv6_det_small.onnx")
    assert made[0]["Cls.model_path"] == str(tmp_path / "ch_ppocr_mobile_v2.0_cls_mobile.onnx")
    assert made[0]["EngineConfig.onnxruntime.intra_op_num_threads"] == 2
    assert out.raw_text == "vào ttps://vcb.top\ngọi O912 345 678"
    assert out.text == "vào https://vcb.top\ngọi 0912 345 678"
    assert out.mean_conf == pytest.approx(0.8) and out.qr == ["https://qr.top/x"]


def test_nothing_read_gives_zero_conf(monkeypatch, tmp_path):
    monkeypatch.setattr("rapidocr.RapidOCR", lambda params: lambda img: SimpleNamespace(txts=None, boxes=None, scores=None))
    out = O.RapidOcr(Store(tmp_path)).read(png(np.full((50, 50, 3), 255, np.uint8)))
    assert (out.raw_text, out.text, out.mean_conf, out.qr) == ("", "", 0.0, [])


def test_broken_image_raises(monkeypatch, tmp_path):
    monkeypatch.setattr("rapidocr.RapidOCR", lambda params: None)
    with pytest.raises(Exception):  # noqa: B017 - pipeline bắt mọi lỗi đọc ảnh như nhau
        O.RapidOcr(Store(tmp_path)).read(b"khong phai anh")


def test_huge_image_is_rejected_before_decoding(monkeypatch, tmp_path):
    monkeypatch.setattr(O, "MAX_PIXELS", 100 * 100)
    store = Store(tmp_path)
    with pytest.raises(ValueError):
        O.RapidOcr(store).read(png(np.full((101, 100, 3), 255, np.uint8)))
    assert store.calls == []   # từ chối trước cả khi nạp model


@pytest.mark.skipif(not os.environ.get("OCR_MODELS_DIR"), reason="cần model thật (OCR_MODELS_DIR)")
def test_real_models_read_a_rendered_url():
    img = np.full((120, 900, 3), 255, np.uint8)
    cv2.putText(img, "https://vcb-xacthuc.top/login", (20, 75), cv2.FONT_HERSHEY_SIMPLEX, 1.4, (0, 0, 0), 3)
    out = O.RapidOcr(Store(pathlib.Path(os.environ["OCR_MODELS_DIR"]))).read(png(img))
    assert "vcb-xacthuc.top/login" in out.text and out.mean_conf > 0.5


def test_manifest_lists_exactly_the_model_files():
    import yaml

    manifest = pathlib.Path(__file__).resolve().parents[2] / "config" / "artifacts.yaml"
    entry = next(e for e in yaml.safe_load(manifest.read_text(encoding="utf-8")) if e["name"] == "ocr")
    assert set(entry["files"]) == set(O.MODEL_FILES.values())
    assert all(len(sha) == 64 for sha in entry["files"].values()) and entry["version"] and entry["s3_prefix"]
