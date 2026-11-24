"""Đọc lại dòng tiếng Việt bằng VietOCR (AI-26). Bộ chữ của `PP-OCRv6_rec_small` thiếu 88 chữ có dấu (ả ạ ấ ộ ữ…), nên
RapidOCR bỏ mất cả nguyên âm: "chuyển tiền" → "chuyn tin" (PaddleOCR #18254). RapidOCR vẫn tìm dòng và đọc URL/SĐT;
VietOCR `vgg_seq2seq` (ONNX, `scripts/export_vietocr_onnx.py`) đọc lại những dòng đã có chữ có dấu, rồi ghép theo TỪ
(`merge`): chỉ các cụm chữ cái được thay bằng bản VietOCR, và chỉ khi VietOCR THÊM nguyên âm; số, dấu câu, khoảng trắng,
URL giữ nguyên bản RapidOCR.

Chỉ cần onnxruntime, numpy, PIL, cv2 (có sẵn qua rapidocr). Model nạp lười ở dòng đầu tiên cần đọc lại.
"""

from __future__ import annotations

import difflib
import json
import math
import re
import unicodedata
from collections import defaultdict
from typing import Any

import numpy as np

from askfirst.domain.models import ArtifactStore

MODEL_FILES = ("cnn.onnx", "encoder.onnx", "decoder.onnx", "vocab.json")
SOS, EOS, MAX_LEN = 1, 2, 128
# Dòng có URL, tên miền, email: giữ bản RapidOCR (đọc URL tốt hơn VietOCR).
_PROTECT = re.compile(r"(?i)https?:|://|www\.|@|[a-z0-9-]+\.[a-z]{2,}(?![a-zà-ỹ])")
_VOWELS = set("aeiouyAEIOUY")
_LETTERS = re.compile(r"[^\W\d_]+")   # một cụm chữ cái liền nhau


def needs_reread(line: str) -> bool:
    """Dòng không có URL và RapidOCR đã ra ít nhất 1 chữ có dấu. Dòng thuần ASCII thường là tin không dấu: đọc lại không thêm được gì."""
    return not _PROTECT.search(line) and any(not c.isascii() and c.isalpha() for c in line)


def _key(s: str) -> str:
    """Bỏ dấu thanh/mũ và khoảng trắng; GIỮ hoa thường và dấu câu ASCII vì URL, mã phân biệt chúng."""
    s = unicodedata.normalize("NFD", s.replace("đ", "d").replace("Đ", "D"))
    return "".join(c for c in s if c.isascii() and not c.isspace())


def _skeleton(tok: str) -> str:
    """Bỏ dấu và nguyên âm: RapidOCR chỉ làm mất nguyên âm, nên cùng một từ thì hai bản có cùng bộ khung."""
    return "".join(c for c in _key(tok) if c not in _VOWELS)


def _only_adds_vowels(rapid: str, viet: str) -> bool:
    a, b = _key(rapid), _key(viet)
    ops = difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes()
    return all(op == "equal" or (op == "insert" and set(b[b0:b1]) <= _VOWELS) for op, _, _, b0, b1 in ops)


def merge(rapid: str, viet: str) -> str:
    """Ghép hai bản đọc của một dòng: giữ nguyên chuỗi RapidOCR, chỉ thay từng cụm chữ cái bằng cụm VietOCR khớp với nó
    (khớp bằng `_skeleton`) khi VietOCR chỉ thêm nguyên âm và giữ hoa thường. Cụm nằm trong token có URL/email (`/`, `@`,
    tên miền) không bao giờ bị thay. Cụm không khớp được (vd tách từ khác nhau: "viphm" / "vi phạm") giữ bản RapidOCR."""
    rm = list(_LETTERS.finditer(rapid))
    vw = _LETTERS.findall(viet)
    locked = set()
    for tok in re.finditer(r"\S+", rapid):
        if _PROTECT.search(tok.group()) or "/" in tok.group():
            locked.update(i for i, m in enumerate(rm) if tok.start() <= m.start() < tok.end())
    sm = difflib.SequenceMatcher(None, [_skeleton(m.group()) for m in rm], [_skeleton(w) for w in vw], autojunk=False)
    out, pos = [], 0
    for op, a0, a1, b0, _ in sm.get_opcodes():
        if op != "equal":
            continue
        for k in range(a1 - a0):
            i, w = a0 + k, vw[b0 + k]
            if i not in locked and _only_adds_vowels(rm[i].group(), w):
                out += [rapid[pos:rm[i].start()], w]
                pos = rm[i].end()
    return "".join(out) + rapid[pos:]


def crop(img: np.ndarray, box, pad: int = 3) -> Any:
    """Cắt vùng chữ (tứ giác của RapidOCR, ảnh BGR) thành ảnh PIL RGB, nới `pad` px; vùng dựng đứng thì xoay ngang."""
    import cv2
    from PIL import Image

    pts = np.float32(box) + np.float32([[-pad, -pad], [pad, -pad], [pad, pad], [-pad, pad]])
    pts[:, 0] = pts[:, 0].clip(0, img.shape[1] - 1)
    pts[:, 1] = pts[:, 1].clip(0, img.shape[0] - 1)
    w = max(int(max(np.linalg.norm(pts[0] - pts[1]), np.linalg.norm(pts[3] - pts[2]))), 1)
    h = max(int(max(np.linalg.norm(pts[0] - pts[3]), np.linalg.norm(pts[1] - pts[2]))), 1)
    m = cv2.getPerspectiveTransform(pts, np.float32([[0, 0], [w, 0], [w, h], [0, h]]))
    c = cv2.warpPerspective(img, m, (w, h), borderMode=cv2.BORDER_REPLICATE)
    if h / w >= 1.5:
        c = np.rot90(c)
    return Image.fromarray(cv2.cvtColor(np.ascontiguousarray(c), cv2.COLOR_BGR2RGB))


class VietOcr:
    """`reread(img, txts, boxes) -> txts`. Model lấy từ `artifacts.path(name)` lần đầu cần đọc lại. Nạp lỗi (vd artifact
    chưa có trên S3) thì lỗi được ném ra MỘT lần, sau đó `reread` trả nguyên bản RapidOCR tới hết vòng đời container,
    không gọi S3 lại cho từng ảnh."""

    def __init__(self, artifacts: ArtifactStore, *, name: str = "vietocr", version: str = "", threads: int | None = None) -> None:
        self.artifacts, self.name, self.version, self.threads = artifacts, name, version, threads
        self._s: tuple | None = None
        self._load_failed = False

    def _load(self) -> tuple:
        import onnxruntime as ort

        d = self.artifacts.path(self.name)
        so = ort.SessionOptions()
        if self.threads:
            so.intra_op_num_threads = self.threads
        cnn, enc, dec = (ort.InferenceSession(str(d / f), so, providers=["CPUExecutionProvider"]) for f in MODEL_FILES[:3])
        v = json.loads((d / "vocab.json").read_text(encoding="utf-8"))
        return cnn, enc, dec, {i + 4: c for i, c in enumerate(v["chars"])}, v["image_height"], v["image_min_width"], v["image_max_width"]

    def _prep(self, im: Any) -> np.ndarray:
        from PIL import Image

        h, wmin, wmax = self._s[4:]
        nw = min(max(math.ceil(h * im.width / im.height / 10) * 10, wmin), wmax)   # như vietocr.tool.translate.resize
        return np.asarray(im.resize((nw, h), Image.LANCZOS), dtype=np.float32).transpose(2, 0, 1) / 255.0

    def predict(self, images: list[Any]) -> list[str]:
        """Đọc từng ảnh dòng (PIL RGB), greedy như `vietocr.tool.translate`. Gom theo bề rộng đúng như vietocr, KHÔNG
        đệm cho bằng nhau: đệm làm model bịa chữ."""
        if self._s is None:
            try:
                self._s = self._load()
            except Exception:
                self._load_failed = True
                raise
        cnn, enc, dec, i2c = self._s[:4]
        groups: dict[int, list[tuple[int, np.ndarray]]] = defaultdict(list)
        for i, im in enumerate(images):
            x = self._prep(im)
            groups[x.shape[-1]].append((i, x))
        out = [""] * len(images)
        for items in groups.values():
            src = cnn.run(None, {"img": np.stack([x for _, x in items])})[0]
            hidden, eo, keys = enc.run(None, {"src": src})
            seq, done = [np.full(len(items), SOS, dtype=np.int64)], np.zeros(len(items), dtype=bool)
            for _ in range(MAX_LEN + 1):
                logits, hidden = dec.run(None, {"tgt": seq[-1], "hidden": hidden, "eo": eo, "keys": keys})
                seq.append(logits.argmax(1).astype(np.int64))
                done |= seq[-1] == EOS
                if done.all():
                    break
            for (i, _), ids in zip(items, np.stack(seq, 1), strict=True):
                chars = []
                for t in ids[1:]:
                    if t == EOS:
                        break
                    chars.append(i2c.get(int(t), ""))
                out[i] = "".join(chars)
        return out

    def reread(self, img: np.ndarray, txts: list[str], boxes) -> list[str]:
        """Bản sao `txts`; dòng nào `needs_reread` thì ghép với bản VietOCR theo từ (`merge`)."""
        idx = [i for i, t in enumerate(txts) if needs_reread(t)]
        if not idx or self._load_failed:
            return list(txts)
        new = list(txts)
        for i, v in zip(idx, self.predict([crop(img, boxes[i]) for i in idx]), strict=True):
            new[i] = merge(txts[i], v)
        return new
