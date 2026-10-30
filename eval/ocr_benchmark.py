"""Spike OCR (AI-20, Gate G2): so 4 cấu hình OCR local trên eval/datasets/ocr_images.jsonl.

Thước đo (ARCH §6.3): CER, tỷ lệ từ có dấu đọc đúng, tỷ lệ chép đúng URL (sai 1 ký tự
là hỏng), độ trễ CPU. Chạy:

    .venv/Scripts/python.exe eval/ocr_benchmark.py            # chạy OCR rồi chấm
    .venv/Scripts/python.exe eval/ocr_benchmark.py --rescore  # chỉ chấm lại từ kết quả đã lưu
    python eval/ocr_benchmark.py --engines rapidocr rapidocr_cols --dataset eval/testocr/test_images.jsonl

Kết quả thô của từng engine lưu ở --raw-dir, báo cáo sinh ra ở --out (cả hai mặc định thư mục tạm,
KHÔNG vào repo). Trên Linux/macOS thay `.venv/Scripts/python.exe` bằng `.venv/bin/python`.
Workflow `.github/workflows/ocr-bench.yml` chạy lại hai cấu hình RapidOCR trên cả hai bộ và lưu kết quả thô.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import math
import os
import platform
import random
import re
import statistics
import subprocess
import sys
import tempfile
import time
import unicodedata
from collections import Counter
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATASET = ROOT / "eval" / "datasets" / "ocr_images.jsonl"
BLUR = "[mờ]"
TESSERACT_EXE = os.environ.get("TESSERACT_EXE", r"C:\Program Files\Tesseract-OCR\tesseract.exe")
TESSDATA = os.environ.get("TESSDATA_PREFIX", str(Path.home() / "tessdata"))
ENGINE_ORDER = ["rapidocr", "rapidocr_cols", "rapidocr_x2", "rapidocr_latin", "rapidocr_latin_x2", "tesseract", "tesseract_vie_eng", "paddle_default", "paddle_cols", "paddle_vietocr"]
ENGINE_LABEL = {
    "rapidocr": "RapidOCR (PP-OCRv6 small, ONNX)",
    "rapidocr_cols": "RapidOCR + sắp dòng theo cột (ảnh ghép 2 khung)",
    "rapidocr_x2": "RapidOCR + phóng ×2 ảnh rộng < 800 px",
    "rapidocr_latin": "RapidOCR rec Latinh (PP-OCRv5 mobile)",
    "rapidocr_latin_x2": "RapidOCR rec Latinh + phóng ×2",
    "tesseract": "Tesseract 5.4 `vie` (tessdata_fast)",
    "tesseract_vie_eng": "Tesseract 5.4 `vie+eng` (tessdata_fast)",
    "paddle_default": "PaddleOCR (det + rec mặc định, PP-OCRv6 medium)",
    "paddle_cols": "PaddleOCR + sắp dòng theo cột (ảnh ghép 2 khung)",
    "paddle_vietocr": "Paddle det + VietOCR (vgg_transformer)",
}


def upscale_if_small(path: str, min_width: int = 800, factor: int = 2):
    """Phóng ×2 ảnh có chiều rộng < min_width (LANCZOS). Trả mảng BGR theo quy ước OpenCV."""
    import numpy as np
    from PIL import Image

    im = Image.open(path).convert("RGB")
    if im.width < min_width:
        im = im.resize((im.width * factor, im.height * factor), Image.LANCZOS)
    return np.asarray(im)[:, :, ::-1].copy()


# ----------------------------------------------------------------------------- adapters
class RapidOCREngine:
    upscale = False

    def _params(self) -> dict:
        return {}

    def load(self):
        from rapidocr import RapidOCR

        params = self._params()
        if os.environ.get("OCR_THREADS"):  # giả lập Lambda ít lõi: để onnxruntime tự chọn luồng thì chậm hơn nhiều
            params["EngineConfig.onnxruntime.intra_op_num_threads"] = int(os.environ["OCR_THREADS"])
        self.eng = RapidOCR(params=params)

    def read(self, path: str) -> str:
        res = self.eng(upscale_if_small(path) if self.upscale else path)
        return "\n".join(res.txts) if res.txts else ""


class RapidOCRx2Engine(RapidOCREngine):
    upscale = True


class RapidOCRLatinEngine(RapidOCREngine):
    def _params(self) -> dict:
        from rapidocr import LangRec, ModelType, OCRVersion

        return {"Rec.lang_type": LangRec.LATIN, "Rec.ocr_version": OCRVersion.PPOCRV5, "Rec.model_type": ModelType.MOBILE}


class RapidOCRLatinx2Engine(RapidOCRLatinEngine):
    upscale = True


def order_by_columns(txts: list[str], boxes, min_gap_px: int = 12, min_boxes_per_side: int = 3) -> str:
    """Chỉ khi ảnh có dải dọc trống liên tục ở giữa (ảnh ghép 2 khung chat) thì đọc hết cột trái rồi cột phải.
    Ảnh không có dải trống giữ nguyên thứ tự gốc của engine. Dùng chung cho mọi engine để so công bằng."""
    import numpy as np

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


class RapidOCRColumnsEngine(RapidOCREngine):
    def read(self, path: str) -> str:
        res = self.eng(path)
        return order_by_columns(list(res.txts), res.boxes) if res.txts else ""


class TesseractEngine:
    lang = "vie"

    def load(self):
        if not Path(TESSERACT_EXE).exists():
            raise RuntimeError(f"Không thấy tesseract.exe ở {TESSERACT_EXE}")
        self.env = dict(os.environ, TESSDATA_PREFIX=TESSDATA)

    def read(self, path: str) -> str:
        p = subprocess.run(
            [TESSERACT_EXE, path, "stdout", "-l", self.lang],
            capture_output=True, env=self.env, cwd=ROOT, check=True,
        )
        return p.stdout.decode("utf-8", errors="replace")


class TesseractVieEngEngine(TesseractEngine):
    """Cùng Tesseract nhưng cho phép cả tiếng Anh, để đọc URL (ký tự Latinh) không bị ép dấu tiếng Việt."""

    lang = "vie+eng"


class PaddleDefaultEngine:
    def load(self):
        from paddleocr import PaddleOCR

        self.eng = PaddleOCR(
            use_doc_orientation_classify=False, use_doc_unwarping=False,
            use_textline_orientation=False, enable_mkldnn=False,
        )

    def read(self, path: str) -> str:
        lines: list[str] = []
        for r in self.eng.predict(path):
            lines.extend(r["rec_texts"])
        return "\n".join(lines)


class PaddleColumnsEngine(PaddleDefaultEngine):
    def read(self, path: str) -> str:
        import numpy as np

        txts: list[str] = []
        boxes: list = []
        for r in self.eng.predict(path):
            txts.extend(r["rec_texts"])
            boxes.extend(np.array(p) for p in r["rec_polys"])
        return order_by_columns(txts, boxes)


class PaddleVietOCREngine:
    """Paddle chỉ tìm vùng chữ; VietOCR đọc từng vùng. Ghép vùng cùng hàng bằng toạ độ."""

    def load(self):
        from PIL import Image

        if not hasattr(Image, "ANTIALIAS"):  # vietocr gọi tên cũ, Pillow >= 10 đã bỏ
            Image.ANTIALIAS = Image.LANCZOS
        from paddleocr import TextDetection
        from vietocr.tool.config import Cfg
        from vietocr.tool.predictor import Predictor

        self.det = TextDetection(model_name="PP-OCRv6_medium_det", enable_mkldnn=False)
        cfg = Cfg.load_config_from_name("vgg_transformer")
        cfg["device"] = "cpu"
        cfg["predictor"]["beamsearch"] = False
        self.rec = Predictor(cfg)

    def read(self, path: str) -> str:
        from PIL import Image

        img = Image.open(path).convert("RGB")
        w, h = img.size
        boxes = []
        for poly in self.det.predict(path, batch_size=1)[0]["dt_polys"]:
            xs, ys = [int(p[0]) for p in poly], [int(p[1]) for p in poly]
            x0, y0, x1, y1 = max(min(xs) - 3, 0), max(min(ys) - 3, 0), min(max(xs) + 3, w), min(max(ys) + 3, h)
            if x1 - x0 >= 4 and y1 - y0 >= 4:
                boxes.append((x0, y0, x1, y1))
        boxes.sort(key=lambda b: ((b[1] + b[3]) / 2, b[0]))
        rows: list[list[tuple]] = []
        for b in boxes:  # cùng hàng nếu tâm dọc lệch < nửa chiều cao hộp
            cy, bh = (b[1] + b[3]) / 2, b[3] - b[1]
            if rows:
                last = rows[-1][-1]
                if abs(cy - (last[1] + last[3]) / 2) < 0.5 * max(bh, last[3] - last[1]):
                    rows[-1].append(b)
                    continue
            rows.append([b])
        out = []
        for row in rows:
            row.sort(key=lambda b: b[0])
            out.append(" ".join(self.rec.predict(img.crop(b)) for b in row))
        return "\n".join(out)


ENGINES = {
    "rapidocr": RapidOCREngine,
    "rapidocr_x2": RapidOCRx2Engine,
    "rapidocr_cols": RapidOCRColumnsEngine,
    "rapidocr_latin": RapidOCRLatinEngine,
    "rapidocr_latin_x2": RapidOCRLatinx2Engine,
    "tesseract": TesseractEngine,
    "tesseract_vie_eng": TesseractVieEngEngine,
    "paddle_default": PaddleDefaultEngine,
    "paddle_cols": PaddleColumnsEngine,
    "paddle_vietocr": PaddleVietOCREngine,
}


# ----------------------------------------------------------------------------- metrics
def norm(s: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", s)).strip()


def strip_diacritics(s: str) -> str:
    s = s.replace("đ", "d").replace("Đ", "D")
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


def edit_distance(a: str, b: str) -> int:
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    m = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - m) / d, (c + m) / d)


def ref_text(row: dict) -> str:
    return norm(row["label"]["ocr_text"].replace(BLUR, " "))


def word_key(w: str) -> str:
    return w.strip(".,:;!?()\"'“”‘’-–—•·")


def has_diacritic(w: str) -> bool:
    return any(c in "đĐ" or unicodedata.category(c) == "Mn" for c in unicodedata.normalize("NFD", w))


def diacritic_words(text: str) -> list[str]:
    out = []
    for w in text.split(" "):
        k = word_key(w)
        if k and has_diacritic(k) and not re.search(r"[./@:\d]", k):
            out.append(k)
    return out


def phone_ok(phone: str, hyp: str) -> bool:
    digits = re.sub(r"\D", "", hyp)
    return phone in digits or (phone.startswith("84") and "0" + phone[2:] in digits)


_URL_CHARS = r"A-Za-z0-9\-_~%/?#=&+@"


def _url_found(target: str, hyp: str) -> bool:
    """`target` xuất hiện trong `hyp` như một URL trọn vẹn: cho phép khoảng trắng/xuống dòng chen giữa (OCR ngắt dòng),
    nhưng hai đầu phải là ranh giới URL. Vì vậy `a.vn` KHÔNG khớp `a.vn.evil.com`, `xa.vn`, `a.vnx` hay `a.vn:8080`;
    dấu câu cuối câu (`a.vn.`) và dấu `/` cuối vẫn được chấp nhận."""
    target = re.sub(r"\s+", "", target).rstrip("/")
    if not target:
        return False
    body = r"\s*".join(re.escape(c) for c in target)
    # Có `http(s)://` thì scheme đã là mốc: OCR dính chữ trước (`vaohttps://…`) không làm hỏng URL, bộ tách URL vẫn bắt được.
    # Không có scheme thì cần ranh giới trái; `://.` là lỗi OCR hay gặp ở đầu URL, vẫn tính là ranh giới.
    left = "" if re.match(r"https?://", target) else rf"(?:(?<=://)|(?<=://\.)|(?<![{_URL_CHARS}.:]))"
    right = rf"/?(?![{_URL_CHARS}])(?!\.[{_URL_CHARS}])(?!:\d)"
    return re.search(left + body + right, hyp) is not None


def url_ok(url: str, hyp: str) -> bool:
    return _url_found(url, hyp)


def url_host_ok(url: str, hyp: str) -> bool:
    """Phụ: chỉ xét tên miền + đường dẫn, bỏ `http(s)://` (không thay ngưỡng chính)."""
    return _url_found(re.sub(r"^https?://", "", re.sub(r"\s+", "", url)), hyp)


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


def near_miss(url: str, hyp: str) -> str:
    flat, u = re.sub(r"\s+", "", hyp), re.sub(r"\s+", "", url)
    m = difflib.SequenceMatcher(None, u, flat, autojunk=False).find_longest_match(0, len(u), 0, len(flat))
    if m.size < 4:
        return "(không thấy đoạn nào giống)"
    start = max(m.b - m.a, 0)
    return flat[start : start + len(u) + 2]


def score(rows: list[dict], raw: dict[str, dict]) -> dict:
    """raw[id] = {"text": ..., "sec": ...}. Trả về số liệu tổng hợp + chi tiết từng ảnh."""
    per, edits, ref_len, edits_nd, ref_len_nd = [], 0, 0, 0, 0
    dia_k = dia_n = 0
    url_k = url_n = url_host_k = url_fix_k = ph_k = ph_fix_k = ph_n = 0
    url_fail, sec = [], []
    for row in rows:
        rid = row["id"]
        hyp = norm(raw[rid]["text"])
        ref = ref_text(row)
        e = edit_distance(ref, hyp)
        e_nd = edit_distance(strip_diacritics(ref), strip_diacritics(hyp))
        edits += e; ref_len += len(ref)
        edits_nd += e_nd; ref_len_nd += len(ref)
        ref_dw, hyp_dw = diacritic_words(ref), Counter(word_key(w) for w in hyp.split(" "))
        hit = sum((Counter(ref_dw) & hyp_dw).values())
        dia_k += hit; dia_n += len(ref_dw)
        for u in row["label"]["urls"]:
            url_n += 1
            url_host_k += url_host_ok(u, hyp)
            url_fix_k += url_ok(u, repair_urls(hyp))
            if url_ok(u, hyp):
                url_k += 1
            else:
                url_fail.append((rid, u, near_miss(u, hyp)))
        for p in row["label"]["phones"]:
            ph_n += 1
            ph_k += phone_ok(p, hyp)
            ph_fix_k += phone_ok(p, repair_phones(hyp))
        sec.append(raw[rid]["sec"])
        per.append({"id": rid, "cer": e / max(len(ref), 1), "blur": BLUR in row["label"]["ocr_text"],
                    "edits": e, "ref_len": len(ref)})
    sec_sorted = sorted(sec)
    p95 = sec_sorted[min(len(sec_sorted) - 1, math.ceil(0.95 * len(sec_sorted)) - 1)]
    rng = random.Random(0)
    boots = []
    for _ in range(2000):
        s = [per[rng.randrange(len(per))] for _ in per]
        boots.append(sum(x["edits"] for x in s) / max(sum(x["ref_len"] for x in s), 1))
    boots.sort()
    clean = [x for x in per if not x["blur"]]
    return {
        "cer": edits / ref_len, "cer_ci": (boots[50], boots[1949]),
        "cer_nodia": edits_nd / ref_len_nd,
        "cer_clean": sum(x["edits"] for x in clean) / max(sum(x["ref_len"] for x in clean), 1),
        "n_clean": len(clean), "cer_median": statistics.median(x["cer"] for x in per),
        "dia": (dia_k, dia_n), "url": (url_k, url_n), "url_host": (url_host_k, url_n), "url_fix": (url_fix_k, url_n), "phone": (ph_k, ph_n), "phone_fix": (ph_fix_k, ph_n),
        "p50": statistics.median(sec), "p95": p95, "mean": statistics.mean(sec),
        "per": per, "url_fail": url_fail,
    }


# ----------------------------------------------------------------------------- run
def load_rows(dataset: Path = DATASET) -> list[dict]:
    return [json.loads(l) for l in dataset.read_text(encoding="utf-8").splitlines() if l.strip()]


def run_engine(name: str, rows: list[dict], raw_dir: Path) -> dict:
    eng = ENGINES[name]()
    t0 = time.perf_counter()
    eng.load()
    load_s = time.perf_counter() - t0
    first = ROOT / "eval" / rows[0]["input"]["image"]
    eng.read(str(first.relative_to(ROOT)))  # làm nóng, không tính vào độ trễ
    out = {}
    for i, row in enumerate(rows, 1):
        rel = str((ROOT / "eval" / row["input"]["image"]).relative_to(ROOT))
        t = time.perf_counter()
        try:
            text, err = eng.read(rel), None
        except Exception as ex:  # ghi lại, không nuốt: ảnh lỗi tính là đọc rỗng
            text, err = "", f"{type(ex).__name__}: {ex}"
        out[row["id"]] = {"text": text, "sec": time.perf_counter() - t, "error": err}
        print(f"[{name}] {i}/{len(rows)} {row['id']} {out[row['id']]['sec']:.2f}s" + (f" ERR {err}" if err else ""), flush=True)
    (raw_dir / f"{name}.json").write_text(
        json.dumps({"load_s": load_s, "results": out}, ensure_ascii=False), encoding="utf-8")
    return {"load_s": load_s, "results": out}


# ----------------------------------------------------------------------------- report
def pct(k: int, n: int) -> str:
    lo, hi = wilson(k, n)
    return f"{k}/{n} ({100*k/max(n,1):.0f}%, CI {100*lo:.0f}–{100*hi:.0f}%)"


def git(*a: str) -> str:
    try:
        return subprocess.run(["git", *a], cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return "(không đọc được git)"


def recommend(S: dict[str, dict]) -> tuple[str, str]:
    rate = lambda e: S[e]["url"][0] / max(S[e]["url"][1], 1)
    ok = [e for e in S if S[e]["url"][1] and rate(e) >= 0.9]
    lenient = lambda e: S[e]["url_host"][0] / max(S[e]["url_host"][1], 1)
    soft = [e for e in S if S[e]["url_host"][1] and lenient(e) >= 0.9]
    if not ok:
        best, sbest = max(S, key=rate), max(S, key=lenient)
        text = (f"**Chưa có engine nào đạt ngưỡng G2 (chép đúng URL, nghiêm ngặt, ≥ 90%).** Cao nhất là {ENGINE_LABEL[best]} với "
                f"{S[best]['url'][0]}/{S[best]['url'][1]} URL.")
        fixr = lambda e: S[e]["url_fix"][0] / max(S[e]["url_fix"][1], 1)
        fixed = [e for e in S if S[e]["url_fix"][1] and fixr(e) >= 0.9]
        gains = "; ".join(f"{ENGINE_LABEL[e]} {S[e]['url'][0]}→{S[e]['url_fix'][0]}/{S[e]['url_fix'][1]}"
                          for e in S if S[e]["url_fix"][0] > S[e]["url"][0])
        text += f" Sau khi sửa lỗi đầu URL (`ttps://.` → `https://`) bằng regex: {gains or 'không engine nào cải thiện'}."
        if fixed:
            text += (f" Qua ngưỡng 90% sau sửa: {', '.join(ENGINE_LABEL[e] for e in fixed)}. **Lưu ý:** các số này lạc quan, vì quy tắc regex và quy tắc sắp dòng theo cột "
                     "được viết sau khi xem lỗi trên chính 50 ảnh này, chưa có tập kiểm tra giữ riêng, và `oi-015` (URL bị mờ đuôi) đã bị loại khỏi mẫu số "
                     "(số trước khi loại ở mục 6). Cách sắp dòng theo cột dùng cùng một hàm cho RapidOCR và Paddle nên hai con này so được với nhau; "
                     "Tesseract và VietOCR chưa được sắp theo cột. Xem mục 6.")
        else:
            sfix = max(S, key=fixr)
            text += f" Vẫn chưa engine nào qua 90%: cao nhất là {ENGINE_LABEL[sfix]} với {S[sfix]['url_fix'][0]}/{S[sfix]['url_fix'][1]}."
        return text, ("Chưa nên chốt ADR-003 chỉ bằng bảng này: các số dùng regex mới thử một kiểu lỗi, và 50 ảnh chưa đủ để phân biệt "
                      "các engine sát nhau. Cần thử thêm (VLM fallback hoặc tiền xử lý ảnh) rồi đo lại.")
    ranked = sorted(ok, key=lambda e: S[e]["cer"])
    top = ranked[0]
    text = (f"Qua ngưỡng G2 (URL ≥ 90%): {', '.join(ENGINE_LABEL[e] for e in ranked)}. "
            f"CER thấp nhất: **{ENGINE_LABEL[top]}** ({100*S[top]['cer']:.1f}%).")
    if len(ranked) > 1:
        a, b = S[ranked[0]], S[ranked[1]]
        if a["cer_ci"][1] >= b["cer_ci"][0]:
            text += f" Khoảng tin cậy CER của hạng 1 và hạng 2 ({ENGINE_LABEL[ranked[1]]}) chồng lên nhau, nên **chưa phân biệt được** hai con này chỉ bằng 50 ảnh."
    return text, f"Đề xuất đưa **{ENGINE_LABEL[top]}** vào ADR-003, cùng AI1 chốt sau khi xem thêm độ trễ và độ nặng khi đóng gói."


def adr_candidate(S: dict[str, dict]) -> list[str]:
    """Mục quyết định ứng viên cho ADR-003. Số liệu lấy từ kết quả chạy; phần lựa chọn là quyết định của người."""
    c, p = S.get("rapidocr_cols"), S.get("paddle_cols")
    if not c:
        return []
    out = [
        "### Quyết định đề xuất: ứng viên cho ADR-003", "",
        "**Trạng thái: ỨNG VIÊN, chưa phải ADR đã chốt.** AI2 đề xuất; AI1 chưa duyệt; **chưa có kiểm tra độc lập** (xem điều kiện bên dưới).", "",
        "**Hướng chọn:** RapidOCR (PP-OCRv6 small, ONNX, bộ đọc mặc định) + sắp dòng theo cột (`order_by_columns`) + sửa đầu URL bằng regex (`repair_urls`).", "",
        f"- Số đo hiện có: URL nghiêm ngặt {pct(*c['url'])}; sau regex {pct(*c['url_fix'])}; CER {100*c['cer']:.1f}%; p95 {c['p95']:.2f} giây trên máy dev.",
    ]
    if p:
        out.append(f"- Paddle với cùng cách sắp cột: {pct(*p['url_fix'])} sau regex, CER {100*p['cer']:.1f}%, p95 {p['p95']:.0f} giây. **Hòa về độ chính xác** với RapidOCR.")
    out += [
        "- **Lý do chọn RapidOCR thay vì Paddle:** độ chính xác ngang nhau, nhưng RapidOCR nhẹ hơn khoảng 5 lần (khoảng 105 MB so với 520 MB trong `.venv`) và nhanh hơn trên máy dev. Image `ai` bị giới hạn ≤ 2 GB (BE-06).",
        "- **Không chọn:** Tesseract và Paddle + VietOCR (chép đúng URL thấp, xem bảng); phóng ×2 và bộ đọc Latinh (không cải thiện, thậm chí tệ hơn).", "",
        "**Rủi ro đã biết:**", "",
        "- Quy tắc sắp cột, sửa URL (`repair_urls`) và sửa SĐT (`repair_phones`) đều **viết sau khi thấy lỗi, chưa kiểm tra độc lập**; số đo có thể lạc quan. Riêng `repair_phones` viết sau khi thấy lỗi `0`↔`O` ở ảnh ngoài bộ 50 (`eval/support/053.png`), nên không đổi số nào trong bảng này.",
        "- Mức đạt phụ thuộc regex: nếu không sửa đầu URL thì RapidOCR + cột chỉ đạt " + pct(*c["url"]) + ", chưa qua 90%.",
        "- Mẫu nhỏ (" + str(c["url"][1]) + " URL), khoảng tin cậy rộng.", "",
        "**Điều kiện trước khi chốt thật (chưa làm mục nào):**", "",
        "1. **Kiểm tra độc lập:** thêm 10–20 ảnh mới, không dùng để chỉnh regex hay quy tắc cột, chạy đúng một lần với ngưỡng chép đúng URL ≥ 90%.",
        "2. **AI1 xác nhận quy ước URL bị mờ** (`oi-015`). Nếu không đồng ý thì số URL đổi (xem mục 6).",
        "3. **Đo trên Lambda thật** (BE-06, AI-26): cold start, RAM đỉnh, p95 so với mục tiêu 3 giây ở 3–4 GB. Số trong báo cáo đo trên máy dev 18 luồng, không so trực tiếp được. "
        "Đã thử chạy trong Docker giả lập Lambda (3008 MB RAM, khoảng 1,7 vCPU, 2 lõi) với RapidOCR + sắp dòng theo cột: chạy được, khoảng 2 giây mỗi ảnh khi đặt số luồng onnxruntime bằng số lõi, nhưng khoảng 10 giây nếu để onnxruntime tự chọn luồng. Chỉ thử 4 ảnh, chưa tính p95, chưa phải Lambda thật.",
        "4. **Đưa quy tắc cột và regex vào code pipeline** kèm test (AI-26), giữ cả URL gốc và URL đã sửa như ARCH yêu cầu.", "",
        "**Nếu kiểm tra độc lập dưới 90%:** không coi kết quả hiện tại là đủ, phải xem lại (tiền xử lý ảnh, hoặc VLM fallback AI-27 đang hoãn).", "",
    ]
    return out


def write_report(path: Path, S: dict[str, dict], meta: dict, rows: list[dict], raw: dict[str, dict]) -> None:
    engines = [e for e in ENGINE_ORDER if e in S]
    concl, advice = recommend(S)
    L: list[str] = []
    L += ["# Kết quả Spike OCR (AI-20, Gate G2)", "",
          f"> Sinh tự động bởi `eval/ocr_benchmark.py`. Ngày chạy: {meta['date']}. Người chạy: AI2. **Bản nháp, AI1 chưa duyệt.**", "",
          *([(f"> **Bộ dữ liệu `{meta['dataset']}`, không phải bộ 50 ảnh phát triển:** chỉ đọc bảng số đo (mục 3–5) và bảng từng ảnh; "
              "các câu viết sẵn về \"50 ảnh\" ở phần kết luận, đề xuất và hạn chế là của bộ phát triển."), ""]
            if meta["dataset"] != DATASET.relative_to(ROOT).as_posix() else []),
          "## 1. Kết luận và đề xuất", "", concl, "", advice, "",
          "Việc chờ quyết định: AI1 cùng AI2 chốt ADR-003. Đọc kỹ mục 6 trước khi tin số liệu.", "",
          *adr_candidate(S),
          "## 2. Cách chạy lại", "",
          "```bash", "uv venv .venv --python 3.12", "uv pip install --python .venv/Scripts/python.exe rapidocr onnxruntime paddlepaddle paddleocr vietocr torch torchvision \"opencv-contrib-python==4.10.0.84\" \"setuptools<81\"",
          "# Tesseract: cài UB-Mannheim, đặt vie.traineddata (tessdata_fast) vào ~/tessdata",
          ".venv/Scripts/python.exe eval/ocr_benchmark.py", "```", "",
          "| Mục | Giá trị |", "|---|---|",
          f"| Commit code | `{meta['commit']}` {meta['dirty']} |",
          f"| Bộ dữ liệu | `{meta['dataset']}`, {len(rows)} dòng, sha256 `{meta['sha']}` |",
          f"| Python | {meta['py']} |", f"| Hệ điều hành | {meta['os']} |", f"| CPU | {meta['cpu']} |",
          f"| Phiên bản | {meta['libs']} |", "",
          "## 3. Bảng số đo", "",
          "Mọi tỷ lệ ghi dạng `x/n` kèm khoảng tin cậy Wilson 95%. CER là tổng số ký tự sai chia tổng số ký tự đáp án (gộp mọi ảnh), khoảng tin cậy lấy bằng bootstrap 2000 lần theo ảnh.", "",
          "| Engine | Chép đúng URL, **nghiêm ngặt** (mục tiêu ≥ 90%) | Sau sửa đầu URL bằng regex | Phụ: đúng tên miền + đường dẫn (bỏ `https://`) | Từ có dấu đọc đúng | SĐT đúng | SĐT sau sửa `0`/`O` | CER | CER bỏ dấu |", "|---|---|---|---|---|---|---|---|---|"]
    for e in engines:
        s = S[e]
        L.append(f"| {ENGINE_LABEL[e]} | {pct(*s['url'])} | {pct(*s['url_fix'])} | {pct(*s['url_host'])} | {pct(*s['dia'])} | {pct(*s['phone'])} | {pct(*s['phone_fix'])} | "
                 f"{100*s['cer']:.1f}% (CI {100*s['cer_ci'][0]:.1f}–{100*s['cer_ci'][1]:.1f}%) | {100*s['cer_nodia']:.1f}% |")
    L += ["", "CER trên riêng các ảnh **không có vùng `[mờ]`**, và CER trung vị từng ảnh:", "",
          f"| Engine | CER {S[engines[0]]['n_clean']} ảnh không `[mờ]` | CER trung vị theo ảnh |", "|---|---|---|"]
    for e in engines:
        L.append(f"| {ENGINE_LABEL[e]} | {100*S[e]['cer_clean']:.1f}% | {100*S[e]['cer_median']:.1f}% |")
    L += ["", "Độ trễ trên **CPU máy dev** (giây mỗi ảnh, đã làm nóng 1 lượt, không tính thời gian nạp model):", "",
          "| Engine | Nạp model | p50 | p95 | Trung bình |", "|---|---|---|---|---|"]
    for e in engines:
        s = S[e]
        L.append(f"| {ENGINE_LABEL[e]} | {meta['load'][e]:.1f}s | {s['p50']:.2f} | {s['p95']:.2f} | {s['mean']:.2f} |")
    L += ["", "Mục tiêu p95 ≤ 3 giây (AI-26) là trên **Lambda 3–4 GB**; số trên đây là máy dev, **không so trực tiếp** được.", "",
          "## 4. License", "",
          "| Thành phần | License | Nguồn |", "|---|---|---|",
          "| RapidOCR, PaddleOCR, VietOCR | Apache-2.0 | ARCH §6.3 [L4], [L5] |",
          "| Tesseract | Apache-2.0 | Chưa đối chiếu lại repo gốc: **not verified** |",
          "| Trọng số model (PP-OCRv6, VietOCR `vgg_transformer`, `tessdata_fast`) | **not verified** | Chưa đọc license riêng của từng file trọng số |", "",
          "## 5. Ví dụ lỗi", ""]
    for e in engines:
        L += [f"### {ENGINE_LABEL[e]}", ""]
        fails = S[e]["url_fail"][:10]
        if fails:
            L += [f"URL chép sai: {len(S[e]['url_fail'])}/{S[e]['url'][1]} (hiện tối đa 10).", "", "| Ảnh | Đáp án | Engine đọc ra (đoạn gần nhất) |", "|---|---|---|"]
            for rid, u, nm in fails:
                L.append(f"| {rid} | `{u}` | `{nm}` |")
        else:
            L.append("Không sai URL nào.")
        worst = sorted(S[e]["per"], key=lambda x: -x["cer"])[:5]
        L += ["", "5 ảnh CER cao nhất: " + ", ".join(f"{x['id']} ({100*x['cer']:.0f}%)" for x in worst), ""]
        errs = [(r, v["error"]) for r, v in raw[e].items() if v.get("error")]
        if errs:
            L += [f"**Có {len(errs)} ảnh lỗi khi chạy (tính là đọc rỗng):** " + "; ".join(f"{r}: {m}" for r, m in errs[:5]), ""]
    L += ["## 6. Chưa kiểm và hạn chế", "",
          "- **Cỡ mẫu nhỏ.** 50 ảnh, trong đó chỉ có " + str(S[engines[0]]["url"][1]) + " URL; chênh vài URL không đủ kết luận ai hơn ai. Xem khoảng tin cậy.",
          "- **Vùng `[mờ]`:** 16/50 ảnh có vùng bị làm mờ trong ảnh. Đáp án đã bỏ `[mờ]`, nhưng engine đôi khi vẫn đọc ra chữ trong vùng mờ và bị tính là chèn thừa. Ảnh hưởng như nhau với mọi engine; số CER ở bảng \"không `[mờ]`\" sạch hơn.",
          "- **Thứ tự dòng:** đáp án gõ tay theo thứ tự đọc của người; engine có thể ra thứ tự khác ở ảnh nhiều khung, làm CER cao dù chữ đúng.",
          "- **Cách tính URL:** khớp chính xác (phân biệt hoa thường), bỏ mọi khoảng trắng và dấu `/` cuối trước khi so, để không phạt việc engine ngắt dòng giữa URL. Chỉ tính URL trong nhãn `urls`; URL bị mờ không có trong nhãn.",
          "- **Cột \"sau sửa đầu URL bằng regex\" lạc quan:** hai quy tắc (`ttps:` → `https:`, bỏ dấu `.` thừa sau `://`) được viết **sau khi xem lỗi trên chính 50 ảnh này**, nên chưa phải số đo trên dữ liệu chưa thấy. Chưa thử sửa lỗi kiểu `https:/l` của Tesseract/VietOCR vì quy tắc đó dễ sửa nhầm.",
          "- **Nhãn `oi-015` đã đổi sau lần đo đầu:** đuôi URL sau `.xyz/` bị làm mờ nên `urls` chuyển từ 1 URL sang rỗng, theo cùng quy ước với `oi-020` và `oi-039` (URL bị mờ không dùng để đo exact-match). Việc này **đưa mẫu số từ 33 xuống 32** và làm RapidOCR, Paddle mặc định đổi từ 29/33 (88%) sang 29/32 (91%) ở cột sau regex. **Hai con tự nó qua ngưỡng 90% nhờ việc loại 1 URL, không nhờ OCR đọc tốt hơn**; số trước khi đổi nhãn là 29/33. Không có đổi nhãn nào khác.",
          "- **Sắp dòng theo cột áp dụng cho RapidOCR và Paddle (cùng một hàm `order_by_columns`), chưa áp dụng cho Tesseract và VietOCR:** nhiều ảnh trong bộ là ảnh ghép 2 khung chat, và thứ tự đọc là một nguồn lỗi lớn. Trên cùng 7 ảnh (`oi-006`, `014`, `022`, `023`, `034`, `046`, `049`), CER RapidOCR giảm từ 26,8% xuống 12,9% và Paddle từ 27,4% xuống 12,7%, không ảnh nào tệ đi. Số của Tesseract và VietOCR bị thiệt chưa rõ bao nhiêu nên không dùng bảng này để so chúng với hai con kia. Ngưỡng của quy tắc (dải trống ≥ 12 px trong 30–70% chiều rộng, ≥ 3 hộp mỗi bên) được chọn từ một ảnh (`oi-022`) và chưa chỉnh thêm; kết quả vẫn đo trên cùng 50 ảnh, chưa có tập kiểm tra giữ riêng.",
          "- **Từ có dấu đọc đúng:** so tập từ (không xét thứ tự), bỏ từ chứa số, URL, email.",
          "- **Độ trễ:** đo trên máy dev, một luồng chạy tuần tự, chưa đo Lambda, chưa đo cold start. Tesseract tính cả thời gian khởi động tiến trình.",
          "- **Dung lượng cài đặt (đo trong `.venv` trên Windows, chưa phải image Lambda):** RapidOCR gồm `rapidocr` 32 MB + `onnxruntime` 42 MB + model 31 MB; PaddleOCR gồm `paddle` 377 MB + `paddlex` 11 MB + model 133 MB, nặng gấp khoảng 3 lần. Đây là lý do thực tế để ưu tiên RapidOCR khi giới hạn image `ai` ≤ 2 GB (BE-06); chưa kiểm Paddle có chạy được trên Lambda hay không.",
          "- **Cấu hình chưa tối ưu:** mỗi engine chạy tham số mặc định (Tesseract `psm` mặc định; Paddle tắt mkldnn do lỗi oneDNN trên Windows, nên có thể chậm hơn mức thực). Đã thử phóng ×2 ảnh rộng < 800 px và bộ đọc Latinh PP-OCRv5 **chỉ với RapidOCR**: cả hai không cải thiện, nên chưa thử với Paddle (suy luận, chưa kiểm). Chưa thử tăng tương phản hay `tessdata_best`.",
          "- **Paddle + VietOCR là bản tự ghép:** cắt theo hộp Paddle phát hiện, ghép hộp cùng hàng bằng toạ độ. Cách ghép khác có thể cho kết quả khác.",
          "- **Dữ liệu:** ảnh từ báo/cảnh báo công khai, nhãn do AI1 và AI2 gõ tay, AI1 chưa review chéo nhãn của AI2.", "",
          "## 7. Phụ lục: CER từng ảnh", "",
          "| Ảnh | `[mờ]` | " + " | ".join(ENGINE_LABEL[e].split(" (")[0] for e in engines) + " |", "|---|---|" + "---|" * len(engines)]
    for i, row in enumerate(rows):
        cells = [f"{100*S[e]['per'][i]['cer']:.0f}%" for e in engines]
        L.append(f"| {row['id']} | {'có' if S[engines[0]]['per'][i]['blur'] else ''} | " + " | ".join(cells) + " |")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(L) + "\n", encoding="utf-8")


def lib_versions() -> str:
    from importlib.metadata import PackageNotFoundError, version

    out = []
    for p in ["rapidocr", "onnxruntime", "paddleocr", "paddlepaddle", "vietocr", "torch"]:
        try:
            out.append(f"{p} {version(p)}")
        except PackageNotFoundError:
            pass
    try:  # chỉ máy có cài tesseract mới đọc được phiên bản; thiếu thì bỏ qua, không làm hỏng báo cáo
        tv = subprocess.run([TESSERACT_EXE, "--version"], capture_output=True, text=True, check=False).stdout.splitlines()
        out.append(tv[0] if tv else "tesseract ?")
    except OSError:
        pass
    return ", ".join(out)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--engines", nargs="+", default=ENGINE_ORDER, choices=ENGINE_ORDER)
    ap.add_argument("--limit", type=int, default=0, help="chỉ chạy N ảnh đầu (thử nhanh)")
    ap.add_argument("--rescore", action="store_true", help="không chạy OCR, chấm lại từ --raw-dir")
    ap.add_argument("--raw-dir", type=Path, default=None, help="nơi lưu kết quả thô (mặc định: thư mục tạm)")
    ap.add_argument("--dataset", type=Path, default=DATASET,
                    help="file JSONL, đường dẫn ảnh tính từ eval/ (mặc định: eval/datasets/ocr_images.jsonl; "
                         "tập độc lập: eval/testocr/test_images.jsonl)")
    ap.add_argument("--out", type=Path, default=None,
                    help="file báo cáo sinh ra (mặc định: thư mục tạm; KHÔNG ghi đè docs/results/ocr-spike.md "
                         "vì file đó có phần viết tay)")
    a = ap.parse_args()
    a.dataset = a.dataset.resolve()
    full_raw = Path(tempfile.gettempdir()) / f"ask_first_ocr_raw_{a.dataset.stem}"
    if a.limit:  # chạy thử một phần: mặc định KHÔNG ghi đè kết quả đầy đủ hay báo cáo chính
        a.raw_dir = a.raw_dir or full_raw.with_name(full_raw.name + "_limit")
        a.out = a.out or full_raw.with_name(f"ocr_limit_{a.dataset.stem}.md")
    a.raw_dir = (a.raw_dir or full_raw).resolve()  # tuyệt đối trước khi đổi thư mục làm việc
    a.out = (a.out or full_raw.with_name(f"ocr_report_{a.dataset.stem}.md")).resolve()
    os.chdir(ROOT)  # các engine nhận đường dẫn ảnh tương đối từ gốc repo; chạy ở đâu cũng phải đúng
    rows = load_rows(a.dataset)
    if a.limit:
        rows = rows[: a.limit]
    a.raw_dir.mkdir(parents=True, exist_ok=True)
    raw, S, load = {}, {}, {}
    for name in a.engines:
        f = a.raw_dir / f"{name}.json"
        if a.rescore:
            if not f.exists():
                sys.exit(f"Không thấy kết quả thô của '{name}' ở {f}. Chạy engine này không kèm --rescore.")
            data = json.loads(f.read_text(encoding="utf-8"))
            missing = [r["id"] for r in rows if r["id"] not in data["results"]]
            if missing:
                sys.exit(f"Kết quả thô của '{name}' thiếu {len(missing)}/{len(rows)} ảnh (vd {missing[0]}). "
                         f"Chạy lại không kèm --rescore, ví dụ: --engines {name}")
        else:
            data = run_engine(name, rows, a.raw_dir)
        raw[name], load[name] = data["results"], data["load_s"]
        S[name] = score(rows, raw[name])
        print(f"== {name}: URL {S[name]['url']} dia {S[name]['dia']} CER {S[name]['cer']:.3f} p95 {S[name]['p95']:.2f}s", flush=True)
    meta = {
        "date": date.today().isoformat(), "commit": git("rev-parse", "--short", "HEAD"),
        "dirty": "(+ thay đổi chưa commit)" if git("status", "--porcelain") else "",
        "dataset": a.dataset.relative_to(ROOT).as_posix() if a.dataset.is_relative_to(ROOT) else str(a.dataset),
        "sha": hashlib.sha256(a.dataset.read_bytes()).hexdigest()[:16], "py": sys.version.split()[0],
        "os": platform.platform(), "cpu": f"{platform.processor()} ({os.cpu_count()} luồng)",
        "libs": lib_versions(), "load": load,
    }
    write_report(a.out, S, meta, rows, raw)
    print(f"Đã ghi {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
