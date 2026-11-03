"""Kiểm tra schema và điều kiện của kb/*.yaml (AI-25, SH-05).

Chạy (cần PyYAML):
    uv run --with pyyaml scripts/validate_kb.py            # kiểm cả 5 file
    uv run --with pyyaml scripts/validate_kb.py hotlines   # chỉ một file (scenarios|allowlist|brands|hotlines|checklist)
    uv run --with pyyaml scripts/validate_kb.py --skip-age # bỏ kiểm hạn 30 ngày của hotline (CI của PR dùng cờ này)

Thoát mã 1 nếu có lỗi. Quy tắc nằm ở plan §3.10: mỗi mục có nguồn có `source_url`/`sources` và `verified_at` (YYYY-MM-DD);
mục trong hotlines có verified_at cũ hơn 30 ngày hoặc để trống bị coi là lỗi (hạn 30 ngày do job định kỳ .github/workflows/kb-freshness.yml kiểm).

Script KIỂM: cấu trúc, mã kịch bản (lấy từ eval/validate.py), ID trùng, `official_domains` có trong allowlist,
`kind` và `value` của hotline, host của `source_url` hotline thuộc allowlist hoặc .gov.vn, số điện thoại lọt vào
advice/checklist, `hotline_ref` trỏ đúng, nguồn `docs/...` có tồn tại, `verified_at` có mặt và đúng định dạng.
Hạn 30 ngày của `verified_at` hotline chỉ kiểm khi KHÔNG có cờ --skip-age (job định kỳ kb-freshness.yml chạy như vậy).
Script KHÔNG kiểm (không gọi mạng): link còn mở được không, số hotline có đúng trên trang nguồn không,
`example_phrases` có nằm trong nguồn không. Những việc này người xác minh làm tay và ghi `verified_at`.
"""
from __future__ import annotations

import datetime as dt
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

import yaml

ROOT = Path(__file__).resolve().parent.parent
KB = ROOT / "kb"
sys.path.insert(0, str(ROOT / "eval"))
import validate as eval_validate  # noqa: E402  (một nguồn duy nhất cho mã kịch bản và nhóm)

TODAY = dt.date.today()
GROUPS = eval_validate.GROUPS
SCENARIOS = eval_validate.SCENARIOS
HOTLINE_KINDS = {"phone", "app"}
DOMAIN_RE = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
# số điện thoại hay mã cuộc gọi cấm trong advice/detail (số lấy từ hotlines.yaml)
PHONE_RE = re.compile(r"(?<!\d)(\+?84\d{8,10}|0\d{9,10}|1[89]00\d{4,6}|113|114|115)(?!\d)")
# gộp dấu cách, chấm, gạch giữa hai chữ số để bắt "1900 545 415", "0912.345.678"
DIGIT_SEP_RE = re.compile(r"(?<=\d)[ .\-](?=\d)")


def has_phone(text: str) -> bool:
    return bool(PHONE_RE.search(DIGIT_SEP_RE.sub("", text)))


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
        if u.startswith("docs/"):
            if not (ROOT / u).is_file():
                errors.append(f"{label}: sources[{i}].url trỏ tới file không tồn tại: {u}")
        elif not u.startswith("https://"):
            errors.append(f"{label}: sources[{i}].url phải là https:// hoặc đường dẫn docs/ nội bộ, hiện là {u}")
        if "date" in s and s["date"] is not None and to_date(s["date"]) is None:
            errors.append(f"{label}: sources[{i}].date phải dạng YYYY-MM-DD")


def only_dicts(data, name: str, errors: list) -> list[dict]:
    """Lấy các phần tử là dict; phần tử khác thành lỗi thay vì làm script crash."""
    out = []
    for i, item in enumerate(data, 1):
        if isinstance(item, dict):
            out.append(item)
        else:
            errors.append(f"{name}[{i}]: phải là một mục dạng khóa: giá trị, hiện là {type(item).__name__}")
    return out


def host_allowed(url: str, allow: dict | None) -> bool:
    host = (urlparse(url).hostname or "").lower()
    if not host:
        return False
    suffixes = [s for s in (allow or {}).get("suffixes") or []]
    if any(host == s or host.endswith("." + s) for s in suffixes):
        return True
    domains = [d.get("domain") for d in (allow or {}).get("domains") or [] if isinstance(d, dict)]
    return any(d and (host == d or host.endswith("." + d)) for d in domains)


def v_scenarios(data):
    e, seen = [], set()
    if not isinstance(data, list) or not data:
        return ["File rỗng hoặc không phải danh sách"]
    for c in only_dicts(data, "scenarios", e):
        code = c.get("code", "?")
        lab = f"scenarios[{code}]"
        for k in ("code", "group", "title", "description", "example_phrases", "red_flags", "advice", "sources", "verified_at"):
            if k not in c or c[k] is None:
                e.append(f"{lab}: thiếu trường '{k}'")
        if code in seen:
            e.append(f"{lab}: trùng code")
        seen.add(code)
        if code not in SCENARIOS:
            e.append(f"{lab}: code không có trong bảng mã (eval/validate.py SCENARIOS, eval/LABELING.md §1)")
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
            if has_phone(str(a)):
                e.append(f"{lab}: 'advice' không được ghi số điện thoại: {a!r}")
        check_sources(lab, c.get("sources"), e)
        check_verified(lab, c.get("verified_at"), e)
    return e


def v_allowlist(data):
    e = []
    if not isinstance(data, dict) or not data.get("domains"):
        return ["Cần mục 'domains' không rỗng"]
    seen = set()
    for d in only_dicts(data["domains"], "allowlist.domains", e):
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
    domains = {d.get("domain") for d in (allow or {}).get("domains", []) if isinstance(d, dict)}
    for b in only_dicts(data, "brands", e):
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


def v_hotlines(data, allow=None, max_age: int | None = 30):
    e, seen = [], set()
    if not isinstance(data, list) or not data:
        return ["File rỗng hoặc không phải danh sách"]
    for h in only_dicts(data, "hotlines", e):
        lab = f"hotlines[{h.get('id', '?')}]"
        for k in ("id", "name", "kind", "value", "source_url"):
            if not h.get(k):
                e.append(f"{lab}: thiếu '{k}'")
        if h.get("id") in seen:
            e.append(f"{lab}: trùng id")
        seen.add(h.get("id"))
        kind = h.get("kind")
        if kind and kind not in HOTLINE_KINDS:
            e.append(f"{lab}: kind {kind!r} không thuộc {sorted(HOTLINE_KINDS)}")
        if kind == "phone" and h.get("value") and not re.fullmatch(r"\d{3,12}", str(h["value"])):
            e.append(f"{lab}: value của phone chỉ gồm chữ số, không khoảng trắng, hiện là {h['value']!r}")
        url = h.get("source_url")
        if url:
            if not str(url).startswith("https://"):
                e.append(f"{lab}: source_url phải https://")
            elif not host_allowed(str(url), allow):
                e.append(f"{lab}: source_url {url} không thuộc allowlist.yaml hoặc .gov.vn (chỉ lấy hotline từ trang chính thức)")
        check_verified(lab, h.get("verified_at"), e, max_age=max_age)
    return e


def v_checklist(data, hotline_ids):
    e = []
    if not isinstance(data, list) or not data:
        return ["File rỗng hoặc không phải danh sách"]
    for lst in only_dicts(data, "checklist", e):
        for k in ("id", "title", "steps"):
            if not lst.get(k):
                e.append(f"checklist[{lst.get('id', '?')}]: thiếu '{k}'")
        steps = lst.get("steps")
        if steps and not isinstance(steps, list):
            e.append(f"checklist[{lst.get('id', '?')}]: 'steps' phải là danh sách")
            steps = []
        steps = steps or []
        for s in only_dicts(steps, f"checklist[{lst.get('id', '?')}].steps", e):
            lab = f"checklist[{lst.get('id')}].{s.get('id', '?')}"
            for k in ("id", "phase", "action", "detail"):
                if not s.get(k):
                    e.append(f"{lab}: thiếu '{k}'")
            for k in ("action", "detail"):
                if has_phone(str(s.get(k, ""))):
                    e.append(f"{lab}: '{k}' không được ghi số điện thoại, dùng hotline_ref")
            ref = s.get("hotline_ref")
            if ref and ref != "bank_of_victim" and ref not in hotline_ids:
                e.append(f"{lab}: hotline_ref {ref!r} không có trong hotlines.yaml")
            check_sources(lab, s.get("sources"), e)
            check_verified(lab, s.get("verified_at"), e)
    return e


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):  # Windows: in tiếng Việt qua pipe không lỗi mã hóa
        sys.stdout.reconfigure(encoding="utf-8")
    args = sys.argv[1:]
    skip_age = "--skip-age" in args  # PR thường bỏ kiểm hạn 30 ngày; job định kỳ kb-freshness.yml mới kiểm
    want = [a for a in args if a != "--skip-age"] or ["scenarios", "allowlist", "brands", "hotlines", "checklist"]
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
        "hotlines": lambda: v_hotlines(data["hotlines"], data["allowlist"], None if skip_age else 30),
        "checklist": lambda: v_checklist(data["checklist"], hotline_ids),
    }
    failed = 0
    for n in want:
        deps = {"brands": ["allowlist"], "hotlines": ["allowlist"], "checklist": ["hotlines"]}.get(n, [])
        dep_errs = [f"không đọc được kb/{d}.yaml nên không kiểm chéo được" for d in deps if load_err[d] or data[d] is None]
        errs = load_err[n] or dep_errs or runners[n]()
        count = len(data[n].get("domains", [])) if isinstance(data[n], dict) else len(data[n] or [])
        print(f"[{'ĐẠT' if not errs else 'LỖI'}] kb/{n}.yaml ({count} mục)")
        for m in errs:
            print(f"    - {m}")
        failed += bool(errs)
    print(f"\n{'Tất cả đạt.' if not failed else f'{failed}/{len(want)} file còn lỗi.'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
