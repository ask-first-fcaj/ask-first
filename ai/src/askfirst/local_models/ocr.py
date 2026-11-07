"""OCR local cho ảnh chụp màn hình (AI-26, ADR-003, ARCH §6.3): RapidOCR PP-OCRv6 small (ONNX) + hậu xử lý + decode QR.

Ảnh chỉ nằm trong RAM của Lambda `ai`, không gửi đi đâu. Model nạp lười ở ảnh đầu tiên: container không nhận ảnh thì
không tải model từ S3. Hậu xử lý (`order_by_columns`, `repair_urls`, `repair_phones`) chuyển nguyên từ spike AI-20;
`eval/ocr_benchmark.py` import lại từ đây nên số đo trên CI là số của code production.

`rapidocr`, `cv2` chỉ import trong hàm: module này import được ở môi trường không cài OCR (vd benchmark chỉ chấm lại).
"""

from __future__ import annotations

import io
import re
from dataclasses import dataclass, field
from typing import Any

import numpy as np

from askfirst.domain.models import ArtifactStore

# Tên file trong artifact `ocr` (config/artifacts.yaml), giữ đúng tên upstream để đối chiếu sha256 với RapidOCR.
# Ảnh cuộn dài 1080×20000 ≈ 21,6 MP vẫn qua; ảnh lớn hơn (thường là ảnh "bom giải nén") bị từ chối trước khi giải mã.
MAX_PIXELS = 40_000_000
MODEL_FILES = {"det": "PP-OCRv6_det_small.onnx", "rec": "PP-OCRv6_rec_small.onnx", "cls": "ch_ppocr_mobile_v2.0_cls_mobile.onnx"}


@dataclass(frozen=True)
class ExtractedText:
    """Kết quả OCR một ảnh (ARCH §6.3). Chứa chữ trong ảnh của người dùng: KHÔNG log."""
    raw_text: str                # dòng theo thứ tự engine (đã sắp cột), trước khi sửa bằng regex
    text: str                    # sau repair_phones + repair_urls; pipeline lấy URL từ cả hai bản
    mean_conf: float = 0.0       # điểm nhận dạng trung bình của các dòng giữ lại; 0.0 khi không đọc được gì
    qr: list[str] = field(default_factory=list)   # nội dung các mã QR giải được


def order_by_columns(txts: list[str], boxes, min_gap_px: int = 12, min_boxes_per_side: int = 3) -> str:
    """Chỉ khi ảnh có dải dọc trống liên tục ở giữa (ảnh ghép 2 khung chat) thì đọc hết cột trái rồi cột phải.
    Ảnh không có dải trống giữ nguyên thứ tự gốc của engine."""
    if not txts:
        return ""
    boxes = np.asarray(boxes)
    x0, x1 = boxes[:, :, 0].min(1), boxes[:, :, 0].max(1)
    lo, hi = int(x1.max() * 0.3), int(x1.max() * 0.7)
    best, run = (0, 0), None
    for x in range(lo, hi + 1):
        free = not ((x0 < x) & (x1 > x)).any()
        if free and run is None:
            run = x
        if run is not None and (not free or x == hi):
            end = x if not free else x + 1
            best = max(best, (end - run, run), key=lambda t: t[0])
            run = None
    gap, start = best
    if gap < min_gap_px:
        return "\n".join(txts)
    cut = start + gap / 2
    centre_x = (x0 + x1) / 2
    left = [i for i in range(len(txts)) if centre_x[i] < cut]
    right = [i for i in range(len(txts)) if centre_x[i] >= cut]
    if min(len(left), len(right)) < min_boxes_per_side:
        return "\n".join(txts)
    key = lambda i: (boxes[i, :, 1].mean(), boxes[i, :, 0].mean())
    return "\n".join(txts[i] for i in sorted(left, key=key) + sorted(right, key=key))


def repair_urls(text: str) -> str:
    """Sửa 3 lỗi ở URL thấy ở RapidOCR/Paddle: mất chữ `h` (`ttps://`), thừa dấu chấm sau `://`, và URL bị xuống dòng ngay sau `://`."""
    text = re.sub(r"(?<![A-Za-z])ttp(s?):", r"http\1:", text)
    text = re.sub(r"(https?:\s*/\s*/)\s*\.+", r"\1", text)
    return re.sub(r"(https?://)\s+(?=[A-Za-z0-9])", r"\1", text)


_PHONE_LIKE = re.compile(r"(?<![\w.])(\+?84|0|O)[\dOIl .\-]{8,13}(?!\w)")


def repair_phones(text: str) -> str:
    """Sửa nhầm `O`→`0`, `I`/`l`→`1` chỉ bên trong chuỗi giống SĐT (bắt đầu bằng 0/O/+84, có ≥ 8 chữ số thật); chữ thường không bị đụng."""
    def fix(m: re.Match) -> str:
        s = m.group(0)
        return s.translate(str.maketrans("OIl", "011")) if sum(c.isdigit() for c in s) >= 8 else s
    return _PHONE_LIKE.sub(fix, text)


def decode_qr(img: np.ndarray) -> list[str]:
    """Mọi mã QR đọc được trong ảnh BGR (OpenCV, có sẵn qua `opencv-python-headless` của rapidocr). Không đọc được thì `[]`.

    Dùng `QRCodeDetectorAruco` trước: `QRCodeDetector().detectAndDecodeMulti` bỏ sót cả mã ngắn rõ nét (vd `https://qr.top/x`,
    OpenCV 5.0); không thấy mã nào thì thử thêm bộ dò thường cho một mã."""
    import cv2

    try:
        ok, payloads, _, _ = cv2.QRCodeDetectorAruco().detectAndDecodeMulti(img)
        found = [p for p in payloads if p] if ok else []
        return found or [p for p in [cv2.QRCodeDetector().detectAndDecode(img)[0]] if p]
    except cv2.error:
        return []


class RapidOcr:
    """`read(bytes) -> ExtractedText`. Model lấy từ `artifacts.path(name)` ở lần đọc đầu; ``threads=None`` để onnxruntime tự chọn."""

    def __init__(self, artifacts: ArtifactStore, *, name: str = "ocr", version: str = "", threads: int | None = None) -> None:
        self.artifacts, self.name, self.version, self.threads = artifacts, name, version, threads
        self._engine: Any = None

    def _load(self) -> Any:
        from rapidocr import RapidOCR

        d = self.artifacts.path(self.name)
        params: dict[str, Any] = {f"{k.capitalize()}.model_path": str(d / f) for k, f in MODEL_FILES.items()}
        params["Global.log_level"] = "warning"
        if self.threads:
            params["EngineConfig.onnxruntime.intra_op_num_threads"] = self.threads
        return RapidOCR(params=params)

    def read(self, data: bytes) -> ExtractedText:
        """Ảnh hỏng, không giải mã được hoặc quá `MAX_PIXELS` thì raise; pipeline coi là không đọc được."""
        from PIL import Image
        from rapidocr.utils.load_image import LoadImage

        with Image.open(io.BytesIO(data)) as im:   # chỉ đọc header
            if im.width * im.height > MAX_PIXELS:
                raise ValueError("image too large")
        if self._engine is None:
            self._engine = self._load()
        img = LoadImage()(data)   # PIL + xoay theo EXIF + BGR: cùng đường benchmark dùng khi đưa đường dẫn ảnh
        res = self._engine(img)
        txts = list(res.txts or ())
        raw = order_by_columns(txts, res.boxes)
        conf = float(np.mean(res.scores)) if txts else 0.0
        return ExtractedText(raw_text=raw, text=repair_urls(repair_phones(raw)), mean_conf=round(conf, 4), qr=decode_qr(img))
