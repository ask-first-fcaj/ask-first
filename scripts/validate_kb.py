"""Kiểm tra schema và điều kiện của kb/*.yaml (AI-25, SH-05).

Chạy (cần PyYAML):
    uv run --with pyyaml scripts/validate_kb.py            # kiểm cả 5 file
    uv run --with pyyaml scripts/validate_kb.py hotlines   # chỉ một file (scenarios|allowlist|brands|hotlines|checklist)

Thoát mã 1 nếu có lỗi. Quy tắc nằm ở plan §3.10: mỗi mục có nguồn có `source_url`/`sources` và `verified_at` (YYYY-MM-DD);
mục trong hotlines có verified_at cũ hơn 30 ngày hoặc để trống bị coi là lỗi.
"""
from __future__ import annotations

import datetime as dt
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
KB = ROOT / "kb"
TODAY = dt.date.today()
GROUPS = {"N0", "N1", "N2", "N3", "N4", "N5", "N6", "OTHER"}
DOMAIN_RE = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
# số điện thoại hay mã cuộc gọi cấm trong advice/detail (số lấy từ hotlines.yaml)
PHONE_RE = re.compile(r"(?<!\d)(\+?84\d{8,10}|0\d{9,10}|1[89]00\d{4,6}|113|114|115)(?!\d)")


def load(name: str):
    f = KB / f"{name}.yaml"
    if not f.exists():
        return None, [f"Không thấy file {f.relative_to(ROOT)}"]
    try:
        return yaml.safe_load(f.read_text(encoding="utf-8")), []
    except yaml.YAMLError as e:
        return None, [f"YAML sai cú pháp: {e}"]


def to_date(v):
    if isinstance(v, dt.datetime):
        return v.date()
    if isinstance(v, dt.date):
        return v
    if isinstance(v, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", v):
        try:
            return dt.date.fromisoformat(v)
        except ValueError:
            return None
    return None


def check_verified(label: str, v, errors: list, max_age: int | None = None):
    if v in (None, ""):
        errors.append(f"{label}: 'verified_at' để trống (chưa xác minh)")
        return
    d = to_date(v)
    if d is None:
        errors.append(f"{label}: 'verified_at' phải dạng YYYY-MM-DD, hiện là {v!r}")
    elif d > TODAY:
        errors.append(f"{label}: 'verified_at' ở tương lai ({v})")
    elif max_age is not None and (TODAY - d).days > max_age:
        errors.append(f"{label}: 'verified_at' quá cũ ({(TODAY - d).days} ngày, tối đa {max_age})")


def check_sources(label: str, sources, errors: list):
    if not isinstance(sources, list) or not sources:
        errors.append(f"{label}: thiếu 'sources' (mỗi mục phải có nguồn)")
        return
    for i, s in enumerate(sources, 1):
        if not isinstance(s, dict) or not s.get("url") or not s.get("publisher"):
            errors.append(f"{label}: sources[{i}] cần có 'url' và 'publisher'")
            continue
        u = str(s["url"])
        if not (u.startswith("https://") or u.startswith("docs/")):
            errors.append(f"{label}: sources[{i}].url phải là https:// hoặc đường dẫn docs/ nội bộ, hiện là {u}")
        if "date" in s and s["date"] is not None and to_date(s["date"]) is None:
            errors.append(f"{label}: sources[{i}].date phải dạng YYYY-MM-DD")


def v_scenarios(data):
    e, seen = [], set()
    if not isinstance(data, list) or not data:
        return ["File rỗng hoặc không phải danh sách"]
    for c in data:
        code = c.get("code", "?")
        lab = f"scenarios[{code}]"
        for k in ("code", "group", "title", "description", "example_phrases", "red_flags", "advice", "sources", "verified_at"):
            if k not in c or c[k] is None:
                e.append(f"{lab}: thiếu trường '{k}'")
        if code in seen:
            e.append(f"{lab}: trùng code")
        seen.add(code)
        if not re.fullmatch(r"N0|N6|N[1-5]\.\d+|OTHER", str(code)):
            e.append(f"{lab}: code phải là N0, N6, OTHER hoặc N1-N5 kèm .<số> (bảng mã eval/LABELING.md §1)")
        if c.get("group") not in GROUPS:
            e.append(f"{lab}: group {c.get('group')!r} không thuộc {sorted(GROUPS)}")
        elif str(code).split(".")[0] != str(c["group"]):
            e.append(f"{lab}: code không khớp group {c['group']}")
        for k in ("example_phrases", "red_flags", "advice"):
            if k in c and not isinstance(c[k], list):
                e.append(f"{lab}: '{k}' phải là danh sách")
        if not c.get("advice"):
            e.append(f"{lab}: 'advice' không được rỗng")
        for a in c.get("advice") or []:
            if PHONE_RE.search(str(a)):
                e.append(f"{lab}: 'advice' không được ghi số điện thoại: {a!r}")
        check_sources(lab, c.get("sources"), e)
        check_verified(lab, c.get("verified_at"), e)
    return e


def v_allowlist(data):
    e = []
    if not isinstance(data, dict) or not data.get("domains"):
        return ["Cần mục 'domains' không rỗng"]
    seen = set()
    for d in data["domains"]:
        name = d.get("domain", "?")
        lab = f"allowlist[{name}]"
        for k in ("domain", "owner", "source_url", "verified_at"):
            if not d.get(k):
                e.append(f"{lab}: thiếu '{k}'")
        if not DOMAIN_RE.match(str(name)):
            e.append(f"{lab}: tên miền phải chữ thường, không có http://, không có đường dẫn")
        if name in seen:
            e.append(f"{lab}: trùng tên miền")
        seen.add(name)
        if d.get("source_url") and not str(d["source_url"]).startswith("https://"):
            e.append(f"{lab}: source_url phải https://")
        check_verified(lab, d.get("verified_at"), e)
    suf = data.get("suffixes")
    if not isinstance(suf, list):
        e.append("allowlist: 'suffixes' phải là danh sách")
    return e


def v_brands(data, allow):
    e = []
    if not isinstance(data, list) or not data:
        return ["File rỗng hoặc không phải danh sách"]
    domains = {d.get("domain") for d in (allow or {}).get("domains", [])}
    for b in data:
        lab = f"brands[{b.get('name', '?')}]"
        for k in ("name", "tokens", "official_domains"):
            if not b.get(k):
                e.append(f"{lab}: thiếu '{k}'")
        for t in b.get("tokens") or []:
            if not re.fullmatch(r"[a-z0-9]+", str(t)):
                e.append(f"{lab}: token {t!r} phải chữ thường, không dấu, không khoảng trắng")
        for dom in b.get("official_domains") or []:
            if dom not in domains:
                e.append(f"{lab}: official_domain {dom!r} chưa có trong allowlist.yaml")
    return e


def v_hotlines(data):
    e, seen = [], set()
    if not isinstance(data, list) or not data:
        return ["File rỗng hoặc không phải danh sách"]
    for h in data:
        lab = f"hotlines[{h.get('id', '?')}]"
        for k in ("id", "name", "kind", "value", "source_url"):
            if not h.get(k):
                e.append(f"{lab}: thiếu '{k}'")
        if h.get("id") in seen:
            e.append(f"{lab}: trùng id")
        seen.add(h.get("id"))
        if h.get("source_url") and not str(h["source_url"]).startswith("https://"):
            e.append(f"{lab}: source_url phải https://")
        check_verified(lab, h.get("verified_at"), e, max_age=30)
    return e


def v_checklist(data, hotline_ids):
    e = []
    if not isinstance(data, list) or not data:
        return ["File rỗng hoặc không phải danh sách"]
    for lst in data:
        for k in ("id", "title", "steps"):
            if not lst.get(k):
                e.append(f"checklist[{lst.get('id', '?')}]: thiếu '{k}'")
        for s in lst.get("steps") or []:
            lab = f"checklist[{lst.get('id')}].{s.get('id', '?')}"
            for k in ("id", "phase", "action", "detail"):
                if not s.get(k):
                    e.append(f"{lab}: thiếu '{k}'")
            for k in ("action", "detail"):
                if PHONE_RE.search(str(s.get(k, ""))):
                    e.append(f"{lab}: '{k}' không được ghi số điện thoại, dùng hotline_ref")
            ref = s.get("hotline_ref")
            if ref and ref != "bank_of_victim" and hotline_ids and ref not in hotline_ids:
                e.append(f"{lab}: hotline_ref {ref!r} không có trong hotlines.yaml")
            check_sources(lab, s.get("sources"), e)
            check_verified(lab, s.get("verified_at"), e)
    return e


def main() -> int:
    want = sys.argv[1:] or ["scenarios", "allowlist", "brands", "hotlines", "checklist"]
    bad = [w for w in want if w not in ("scenarios", "allowlist", "brands", "hotlines", "checklist")]
    if bad:
        sys.exit(f"Tên không hợp lệ: {bad}")
    data, load_err = {}, {}
    for n in ("scenarios", "allowlist", "brands", "hotlines", "checklist"):
        data[n], load_err[n] = load(n)
    hotline_ids = {h.get("id") for h in (data["hotlines"] or []) if isinstance(h, dict)}
    runners = {
        "scenarios": lambda: v_scenarios(data["scenarios"]),
        "allowlist": lambda: v_allowlist(data["allowlist"]),
        "brands": lambda: v_brands(data["brands"], data["allowlist"]),
        "hotlines": lambda: v_hotlines(data["hotlines"]),
        "checklist": lambda: v_checklist(data["checklist"], hotline_ids),
    }
    failed = 0
    for n in want:
        errs = load_err[n] or runners[n]()
        count = len(data[n].get("domains", [])) if isinstance(data[n], dict) else len(data[n] or [])
        print(f"[{'ĐẠT' if not errs else 'LỖI'}] kb/{n}.yaml ({count} mục)")
        for m in errs:
            print(f"    - {m}")
        failed += bool(errs)
    print(f"\n{'Tất cả đạt.' if not failed else f'{failed}/{len(want)} file còn lỗi.'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
