"""Kiểm tra bộ eval `eval/datasets/*.jsonl` (SH-03, schema ở plan §3.8). Chạy từ gốc repo:

    python -m eval.validate                 # mọi file trong eval/datasets/
    python -m eval.validate path/a.jsonl    # chỉ các file chỉ định
    python -m eval.validate --target v1     # đối chiếu số lượng với v1 (300 mẫu) thay vì v0
    python -m eval.validate --strict        # cảnh báo cũng làm lệnh thất bại

Lỗi (schema, trùng id, trùng nội dung, thiếu source_url...) làm lệnh trả mã 1. Cảnh báo (thiếu số
lượng so với mốc, mẫu gần trùng, nghi còn số điện thoại thật) chỉ in ra, trừ khi có --strict.
Chỉ dùng thư viện chuẩn để cả team chạy được trên Windows, macOS, Linux mà không cần cài thêm.
"""

from __future__ import annotations

import argparse
import difflib
import json
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
DATASETS = EVAL_DIR / "datasets"

SPLITS = {"real_scam", "benign_suspicious", "benign_normal", "hard", "ocr_images", "voice", "synthetic"}
NHOM = {"N0", "N1", "N2", "N3", "N4", "N5", "N6", "KHAC"}
LEVELS = {"NGUY_HIEM", "NGHI_NGO", "CHUA_CHAC", "IT_DAU_HIEU"}
RISKY_ACTIONS = {
    "chuyen_tien", "cung_cap_otp", "cung_cap_mat_khau", "cai_app",
    "chia_se_man_hinh", "bam_link", "giu_bi_mat", "cung_cap_cccd",
}
FORWARD_ORIGINS = {"user", "hidden_user", "chat", "channel"}
AUTHORS = {"AI1", "AI2", "BE1", "BE2"}
INPUT_KEYS = {"text", "entities", "image", "audio", "forward_origin"}
TOP_KEYS = {"id", "split", "input", "label", "source_url", "author", "notes"}

# Số lượng theo ARCH §6.11. v1 = 120/60/60/60; v0 chia cùng tỉ lệ cho đủ 100 mẫu (giả định của team).
TARGETS = {
    "v0": {"real_scam": 40, "benign_suspicious": 20, "benign_normal": 20, "hard": 20},
    "v1": {"real_scam": 120, "benign_suspicious": 60, "benign_normal": 60, "hard": 60},
}
PER_AUTHOR = {"v0": 25, "v1": 75}
COUNTED_SPLITS = set(TARGETS["v1"])  # ocr_images/voice/synthetic không tính vào 100/300 mẫu

NEAR_DUP_RATIO = 0.92
PHONE_RE = re.compile(r"(?<!\d)(?:\+?84|0)(?:[\s.\-]?\d){8,10}(?!\d)")
EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")


def normalize(text: str) -> str:
    """Chữ thường, bỏ dấu, chỉ giữ chữ-số, để so trùng không bị lệch dấu/dấu câu/khoảng trắng."""
    t = unicodedata.normalize("NFD", text.lower().replace("đ", "d"))
    t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", t).strip()


def is_placeholder_phone(raw: str) -> bool:
    """0000000000, 0901 000 000...: dãy số đã bịa/ẩn danh rõ ràng thì không báo."""
    digits = re.sub(r"\D", "", raw)
    return len(set(digits[-7:])) <= 1


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, where: str, msg: str) -> None:
        self.errors.append(f"{where}: {msg}")

    def warn(self, where: str, msg: str) -> None:
        self.warnings.append(f"{where}: {msg}")


def is_str_list(v: object) -> bool:
    return isinstance(v, list) and all(isinstance(x, str) for x in v)


def check_asset(rep: Report, where: str, field: str, value: object) -> None:
    if value is None:
        return
    if not isinstance(value, str) or not value:
        rep.error(where, f"input.{field} phải là null hoặc đường dẫn tương đối tới eval/")
        return
    rel = Path(value)
    if rel.is_absolute() or ".." in rel.parts:
        rep.error(where, f"input.{field} phải là đường dẫn tương đối trong eval/, không phải {value!r}")
    elif not (EVAL_DIR / rel).is_file():
        rep.error(where, f"input.{field} trỏ tới file không tồn tại: eval/{value}")


def check_input(rep: Report, where: str, rec: dict) -> None:
    inp = rec.get("input")
    if not isinstance(inp, dict):
        rep.error(where, "thiếu input (object)")
        return
    for k in sorted(set(inp) - INPUT_KEYS):
        rep.error(where, f"input có trường lạ: {k}")
    for k in sorted(INPUT_KEYS - set(inp)):
        rep.error(where, f"input thiếu trường: {k}")
    text = inp.get("text")
    if not isinstance(text, str):
        rep.error(where, "input.text phải là chuỗi (rỗng nếu chỉ có ảnh/voice)")
        text = ""
    if not isinstance(inp.get("entities"), list):
        rep.error(where, "input.entities phải là list")
    else:
        for i, e in enumerate(inp["entities"]):
            if not isinstance(e, dict) or e.get("kind") not in {"url", "text_link"} \
                    or not isinstance(e.get("offset"), int) or not isinstance(e.get("length"), int):
                rep.error(where, f"input.entities[{i}] sai dạng EntityRef (kind url|text_link, offset, length)")
            elif e["kind"] == "text_link" and not e.get("url"):
                rep.error(where, f"input.entities[{i}] là text_link nhưng thiếu url")
    check_asset(rep, where, "image", inp.get("image"))
    check_asset(rep, where, "audio", inp.get("audio"))
    fo = inp.get("forward_origin")
    if fo is not None and fo not in FORWARD_ORIGINS:
        rep.error(where, f"input.forward_origin={fo!r} không thuộc {sorted(FORWARD_ORIGINS)}")
    if not text and not inp.get("image") and not inp.get("audio"):
        rep.error(where, "mẫu rỗng: không có text, ảnh hay audio")


def check_label(rep: Report, where: str, rec: dict) -> None:
    split = rec.get("split")
    label = rec.get("label")
    if not isinstance(label, dict):
        rep.error(where, "thiếu label (object)")
        return
    if split == "ocr_images":  # nhãn của OCR là văn bản đọc được, không phải nhãn lừa đảo
        if not isinstance(label.get("ocr_text"), str):
            rep.error(where, "label.ocr_text phải là chuỗi")
        for k in ("urls", "phones"):
            if not is_str_list(label.get(k)):
                rep.error(where, f"label.{k} phải là list chuỗi")
        return
    is_scam = label.get("is_scam")
    if not isinstance(is_scam, bool):
        rep.error(where, "label.is_scam phải là true/false")
        return
    nhom = label.get("nhom")
    if nhom == "OTHER":
        rep.error(where, "label.nhom='OTHER' sai: dùng 'KHAC' (enum Nhom ở plan §3; OTHER chỉ là tên trong LABELING.md)")
    elif nhom not in NHOM:
        rep.error(where, f"label.nhom={nhom!r} không thuộc {sorted(NHOM)}")
    elif is_scam and nhom == "N0":
        rep.error(where, "is_scam=true nhưng nhom=N0 (N0 là lành tính)")
    elif not is_scam and nhom not in {"N0"}:
        rep.error(where, f"is_scam=false thì nhom phải là N0, đang là {nhom}")
    if not isinstance(label.get("kich_ban"), str) or not label["kich_ban"]:
        rep.error(where, "label.kich_ban phải là chuỗi không rỗng (vd N1.4, N0, KHAC)")
    elif nhom in NHOM and nhom != "KHAC" and label["kich_ban"] != "KHAC" and not label["kich_ban"].startswith(nhom):
        rep.error(where, f"kich_ban={label['kich_ban']!r} không thuộc nhom {nhom}")
    min_l, max_l = label.get("min_level"), label.get("max_level")
    if is_scam:
        if min_l not in LEVELS:
            rep.error(where, f"is_scam=true cần label.min_level thuộc {sorted(LEVELS)}")
        if max_l is not None:
            rep.error(where, "is_scam=true thì label.max_level phải là null")
    else:
        if max_l not in LEVELS:
            rep.error(where, f"is_scam=false cần label.max_level thuộc {sorted(LEVELS)} (thường CHUA_CHAC)")
        if min_l is not None:
            rep.error(where, "is_scam=false thì label.min_level phải là null")
    for k in ("must_signals", "risky_actions"):
        if not is_str_list(label.get(k)):
            rep.error(where, f"label.{k} phải là list chuỗi (có thể rỗng)")
    for a in label.get("risky_actions") or []:
        if isinstance(a, str) and a not in RISKY_ACTIONS:
            rep.error(where, f"risky_actions chứa {a!r} không thuộc {sorted(RISKY_ACTIONS)}")
    if split == "real_scam" and not is_scam:
        rep.error(where, "split real_scam phải có is_scam=true")
    if split in {"benign_suspicious", "benign_normal"} and is_scam:
        rep.error(where, f"split {split} phải có is_scam=false")


def check_record(rep: Report, where: str, rec: object) -> None:
    if not isinstance(rec, dict):
        rep.error(where, "mỗi dòng phải là một JSON object")
        return
    for k in sorted(set(rec) - TOP_KEYS):
        rep.error(where, f"có trường lạ: {k}")
    for k in sorted(TOP_KEYS - set(rec)):
        rep.error(where, f"thiếu trường: {k}")
    if not isinstance(rec.get("id"), str) or not rec["id"]:
        rep.error(where, "id phải là chuỗi không rỗng")
    split = rec.get("split")
    if split not in SPLITS:
        rep.error(where, f"split={split!r} không thuộc {sorted(SPLITS)}")
    if not isinstance(rec.get("author"), str) or not rec["author"]:
        rep.error(where, "author phải là chuỗi không rỗng")
    elif rec["author"] not in AUTHORS:
        rep.warn(where, f"author={rec['author']!r} không thuộc {sorted(AUTHORS)}")
    if not isinstance(rec.get("notes"), str):
        rep.error(where, "notes phải là chuỗi (có thể rỗng)")
    src = rec.get("source_url")
    if src is not None and not (isinstance(src, str) and re.match(r"https?://\S+$", src)):
        rep.error(where, "source_url phải là null hoặc URL http(s)")
    if split == "real_scam" and not src:
        rep.error(where, "real_scam bắt buộc có source_url (viết lại từ cảnh báo chính thức, không dùng LLM sinh mẫu)")
    check_input(rep, where, rec)
    check_label(rep, where, rec)


def check_privacy(rep: Report, where: str, rec: dict) -> None:
    inp = rec.get("input")
    text = inp.get("text") if isinstance(inp, dict) else None
    if not isinstance(text, str):
        return
    for m in PHONE_RE.finditer(text):
        if not is_placeholder_phone(m.group()):
            rep.warn(where, f"nghi còn số điện thoại thật {m.group().strip()!r}; thay bằng số bịa (0000…) nếu là tin thật")
    if EMAIL_RE.search(text):
        rep.warn(where, "nghi còn email; ẩn danh trước khi đưa vào bộ eval")


def check_duplicates(rep: Report, records: list[tuple[str, dict]]) -> None:
    seen_id: dict[str, str] = {}
    seen_text: dict[str, tuple[str, str]] = {}
    texts: list[tuple[str, str, str]] = []  # (where, id, normalized)
    for where, rec in records:
        rid = rec.get("id")
        if isinstance(rid, str):
            if rid in seen_id:
                rep.error(where, f"id {rid!r} trùng với {seen_id[rid]}")
            else:
                seen_id[rid] = where
        inp = rec.get("input")
        text = inp.get("text") if isinstance(inp, dict) else None
        if not isinstance(text, str) or not (norm := normalize(text)):
            continue
        if norm in seen_text:
            rep.error(where, f"nội dung trùng với {seen_text[norm][0]} (id {seen_text[norm][1]!r})")
        else:
            seen_text[norm] = (where, str(rid))
            texts.append((where, str(rid), norm))
    for i, (w1, id1, n1) in enumerate(texts):
        for w2, id2, n2 in texts[i + 1:]:
            if abs(len(n1) - len(n2)) > 0.2 * max(len(n1), len(n2)):
                continue
            sm = difflib.SequenceMatcher(None, n1, n2, autojunk=False)
            if sm.real_quick_ratio() >= NEAR_DUP_RATIO and sm.quick_ratio() >= NEAR_DUP_RATIO \
                    and sm.ratio() >= NEAR_DUP_RATIO:
                rep.warn(w2, f"gần trùng (>= {NEAR_DUP_RATIO:.0%}) với {w1} (id {id1!r}, {id2!r}); mẫu biến thể có chủ ý thì ghi rõ trong notes")


def check_counts(rep: Report, records: list[tuple[str, dict]], target: str) -> None:
    counted = [r for _, r in records if r.get("split") in COUNTED_SPLITS]
    by_split = Counter(r["split"] for r in counted)
    for split, need in TARGETS[target].items():
        if by_split[split] < need:
            rep.warn("số lượng", f"{split}: {by_split[split]}/{need} ({target})")
    by_author = Counter(r.get("author") for r in counted)
    for author in sorted(AUTHORS):
        if by_author[author] < PER_AUTHOR[target]:
            rep.warn("số lượng", f"{author}: {by_author[author]}/{PER_AUTHOR[target]} mẫu ({target})")


def load(paths: list[Path], rep: Report) -> list[tuple[str, dict]]:
    records: list[tuple[str, dict]] = []
    for p in paths:
        try:
            lines = p.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeDecodeError) as e:
            rep.error(p.name, f"không đọc được file: {e}")
            continue
        for n, line in enumerate(lines, 1):
            if not line.strip():
                continue
            where = f"{p.name}:{n}"
            try:
                rec = json.loads(line)
            except json.JSONDecodeError as e:
                rep.error(where, f"JSON sai cú pháp: {e.msg}")
                continue
            check_record(rep, where, rec)
            if isinstance(rec, dict):
                records.append((where, rec))
    return records


def run(paths: list[Path], target: str = "v0") -> Report:
    rep = Report()
    records = load(paths, rep)
    for where, rec in records:
        check_privacy(rep, where, rec)
    check_duplicates(rep, records)
    check_counts(rep, records, target)
    return rep


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Kiểm tra bộ eval JSONL (SH-03).")
    ap.add_argument("files", nargs="*", type=Path, help="mặc định: eval/datasets/*.jsonl")
    ap.add_argument("--target", choices=sorted(TARGETS), default="v0", help="mốc số lượng để đối chiếu")
    ap.add_argument("--strict", action="store_true", help="cảnh báo cũng làm lệnh thất bại")
    args = ap.parse_args(argv)
    paths = args.files or sorted(DATASETS.glob("*.jsonl"))
    if not paths:
        print(f"Không thấy file .jsonl nào trong {DATASETS}", file=sys.stderr)
        return 1
    rep = run(paths, args.target)
    for w in rep.warnings:
        print(f"WARN  {w}")
    for e in rep.errors:
        print(f"ERROR {e}")
    print(f"\n{len(paths)} file · {len(rep.errors)} lỗi · {len(rep.warnings)} cảnh báo")
    if rep.errors or (args.strict and rep.warnings):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
