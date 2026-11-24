"""AI-26: ``local_models/vietocr.py``: chọn dòng đọc lại, ghép theo từ ``merge``, nối vào ``RapidOcr``. Model VietOCR thật chỉ
chạy khi có ``VIETOCR_MODELS_DIR`` (thư mục ra của ``scripts/export_vietocr_onnx.py``)."""

from __future__ import annotations

import os
import pathlib
from types import SimpleNamespace

import cv2
import numpy as np
import pytest

from askfirst.local_models import ocr as O
from askfirst.local_models import vietocr as V


@pytest.mark.parametrize(("rapid", "viet", "merged"), [
    ("chuyn tin vào tài khon", "chuyển tiền vào tài khoản", "chuyển tiền vào tài khoản"),
    # số giữ bản RapidOCR, chữ quanh số vẫn lấy bản VietOCR
    ("Gi chót đồi quà! 9082 đim", "Giờ chót đổi quà! 9083 điểm", "Giờ chót đổi quà! 9082 điểm"),
    ("gọi +84 79 9165198", "gọi 48479 91651980", "gọi +84 79 9165198"),   # SĐT đọc sai: giữ cả SĐT
    ("Tin nhn văn bn · SMS", "Tin nhắn văn bản - SMS", "Tin nhắn văn bản · SMS"),   # ký hiệu giữ bản RapidOCR
    ("Xe bn có viphm, x", "Xe bạn có vi phạm, xử", "Xe bạn có viphm, xử"),   # tách từ khác nhau: giữ bản RapidOCR
    ("quyn:https://vie", "quyền:https://vie", "quyn:https://vie"),   # từ dính URL: giữ
    ("Kính gui ngui dùng BanK,", "Kính gửi người dùng Bank,", "Kính gửi người dùng BanK,"),   # đổi hoa thường: giữ
    ("vie", "viers", "vie"),                                         # thêm phụ âm: bịa chữ
    ("dichvucong-", "eichvucong", "dichvucong-"),
    ("chưa nộp pht.Hôm nay", "chưa nộp phạt. Hôm nay", "chưa nộp phạt.Hôm nay"),   # dấu câu dính chữ: chỉ thay cụm chữ
    # URL nằm giữa dòng: chữ quanh được thay, tên miền giữ nguyên
    ("vao vietcombank.vn-yys.club de sua", "vào vietcombank.vn-yys.ciub để sửa", "vào vietcombank.vn-yys.club để sửa"),
    ("", "abc", ""),
])
def test_merge_takes_only_words_where_vietocr_adds_vowels(rapid, viet, merged):
    assert V.merge(rapid, viet) == merged


@pytest.mark.parametrize(("line", "expected"), [
    ("Tin nhn văn bn", True),
    ("Quy khach vui long dang nhap", False),           # thuần ASCII: tin không dấu
    ("vào https://vcb-xacthuc.top ngay", False),        # có URL: giữ RapidOCR
    ("truy cập vietcombank.vn-yys.club", False),
    ("liên hệ abc@gmail.com", False),
    ("12:30", False),
])
def test_needs_reread(line, expected):
    assert V.needs_reread(line) is expected


def box(y: int) -> list[list[int]]:
    return [[5, y], [200, y], [200, y + 20], [5, y + 20]]


class FakeEngine:
    def __init__(self, txts):
        self.txts = txts

    def __call__(self, img):
        return SimpleNamespace(txts=tuple(self.txts), boxes=np.array([box(10 + 30 * i) for i in range(len(self.txts))]),
                               scores=tuple(0.9 for _ in self.txts))


def png(h: int = 200) -> bytes:
    return cv2.imencode(".png", np.full((h, 300, 3), 255, np.uint8))[1].tobytes()


def test_reread_only_sends_vietnamese_lines_and_keeps_rejected(monkeypatch, tmp_path):
    txts = ["Tin nhn văn bn", "https://vietcombank.vn-yys.club", "gọi +84 79 9165198", "Quy khach vui long"]
    monkeypatch.setattr("rapidocr.RapidOCR", lambda params: FakeEngine(txts))
    sent = []

    def fake_predict(images):
        sent.extend(images)
        return ["Tin nhắn văn bản", "gọi 48479 91651980"][: len(images)]

    v = V.VietOcr(SimpleNamespace(path=lambda name: tmp_path))
    monkeypatch.setattr(v, "predict", fake_predict)
    out = O.RapidOcr(SimpleNamespace(path=lambda name: tmp_path), reread=v).read(png())
    assert len(sent) == 2 and all(im.mode == "RGB" for im in sent)   # chỉ 2 dòng có chữ có dấu, không có URL
    assert out.text.splitlines() == ["Tin nhắn văn bản", "https://vietcombank.vn-yys.club", "gọi +84 79 9165198",
                                     "Quy khach vui long"]           # SĐT bị VietOCR đọc sai: giữ bản RapidOCR
    assert out.raw_text.splitlines()[0] == "Tin nhn văn bn"          # raw_text luôn là bản RapidOCR


def test_missing_model_falls_back_and_is_tried_only_once(monkeypatch, tmp_path, caplog):
    monkeypatch.setattr("rapidocr.RapidOCR", lambda params: FakeEngine(["Tin nhn văn bn"]))
    calls = []

    def missing(name):   # như S3ArtifactStore khi artifact chưa upload: mỗi lần gọi là một lần tải hỏng
        calls.append(name)
        raise FileNotFoundError(name)

    eng = O.RapidOcr(SimpleNamespace(path=lambda name: tmp_path), reread=V.VietOcr(SimpleNamespace(path=missing)))
    for _ in range(3):
        assert eng.read(png()).text == "Tin nhn văn bn"   # vẫn trả bản RapidOCR
    assert calls == ["vietocr"]                            # không thử tải lại cho từng ảnh
    assert caplog.text.count("ocr_reread_failed") == 1     # log một lần


def test_manifest_lists_exactly_the_model_files():
    import yaml

    manifest = pathlib.Path(__file__).resolve().parents[2] / "config" / "artifacts.yaml"
    entry = next(e for e in yaml.safe_load(manifest.read_text(encoding="utf-8")) if e["name"] == "vietocr")
    assert set(entry["files"]) == set(V.MODEL_FILES)
    assert all(len(sha) == 64 for sha in entry["files"].values()) and entry["version"] and entry["s3_prefix"]


@pytest.mark.skipif(not os.environ.get("VIETOCR_MODELS_DIR"), reason="cần model thật (VIETOCR_MODELS_DIR)")
def test_real_model_reads_vietnamese_diacritics():
    from PIL import Image, ImageDraw, ImageFont

    font = next((p for p in ("C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
                 if pathlib.Path(p).exists()), None)
    if font is None:
        pytest.skip("không có font Unicode")
    im = Image.new("RGB", (420, 48), "white")
    ImageDraw.Draw(im).text((8, 8), "Chuyển tiền vào tài khoản", fill="black", font=ImageFont.truetype(font, 28))
    v = V.VietOcr(SimpleNamespace(path=lambda name: pathlib.Path(os.environ["VIETOCR_MODELS_DIR"])))
    assert v.predict([im]) == ["Chuyển tiền vào tài khoản"]
