"""Stage Signals: rules engine v1 (AI-23, ARCH §6.4). Luật nằm ở ``kb/rules.yaml``.

``RuleEngine(masked_text, urls) -> list[Signal]`` khớp giao diện ``Rules`` của ``RealPipeline``. Chạy trên bản **đã
mask** (ARCH §9.2: mask trước signals), so khớp trên ``normalize.fold()`` của nó; bằng chứng cắt từ bản đã mask nên
không chứa số điện thoại, STK, OTP gốc. Sàn cứng, tổ hợp và điểm nằm ở ``fuse.py``. Không gọi mạng, không log.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import yaml

from askfirst.domain import models as m
from askfirst.pipeline.normalize import fold

MAX_EVIDENCE = 120
NEG_WINDOW = 60
# Phủ định chỉ tính khi đứng SÁT động từ của đoạn khớp: "tuyệt đối không cung cấp mã OTP", "không bao giờ yêu cầu
# quý khách cung cấp mã OTP", "không đọc mã OTP cho nhân viên". Giữa phủ định và đoạn khớp chỉ được có trạng từ
# (bao giờ, được, nên…), một lời yêu cầu kèm ≤ 3 từ đối tượng, và động từ cho/đọc mã. Câu trấn an hay doạ có chữ
# "không" ở xa ("Không ai biết đâu, đọc mã OTP đi", "nếu không đọc mã thì…") vì thế không tính là phủ định.
NEGATION = re.compile(
    r"(?<!neu )\b(?:khong|dung|nghiem cam|cam)"
    r"(?: (?:bao gio|duoc|nen|can|he|tung|the|tu y|hoan toan)){0,2}"
    r"(?: (?:yeu cau|de nghi|doi hoi|nho|bat)(?: [^ .]+){0,3})?"
    r"(?: (?:cung cap|chia se|tiet lo|doc|doc lai|gui|dua|bao|nhan tin|soan|chup|nhap)(?: (?:ma|lai|giup))?)? $")
_KEYS = {"code", "weight", "hard", "patterns", "requires", "unless", "negatable", "url", "unless_codes"}
_URL_MODES = {None, "required", "forbidden", "hard"}


@dataclass(frozen=True)
class Rule:
    code: str
    weight: int
    hard: bool
    patterns: tuple[re.Pattern, ...]
    requires: tuple[re.Pattern, ...] = ()
    unless: tuple[re.Pattern, ...] = ()
    negatable: bool = False
    url: str | None = None
    unless_codes: frozenset[str] = frozenset()

    @classmethod
    def parse(cls, d: dict) -> Rule:
        if extra := set(d) - _KEYS:
            raise ValueError(f"{d.get('code')}: khoá lạ {sorted(extra)}")
        if d.get("url") not in _URL_MODES or not d.get("patterns"):
            raise ValueError(f"{d.get('code')}: thiếu patterns hoặc url sai")
        def rx(key: str) -> tuple[re.Pattern, ...]:
            return tuple(re.compile(p) for p in d.get(key) or ())
        return cls(d["code"], int(d["weight"]), bool(d["hard"]), rx("patterns"), rx("requires"), rx("unless"),
                   bool(d.get("negatable")), d.get("url"), frozenset(d.get("unless_codes") or ()))

    def first_match(self, text: str) -> re.Match | None:
        for p in self.patterns:
            for mt in p.finditer(text):
                if not (self.negatable and _negated(text, mt.start())):
                    return mt
        return None


def _negated(text: str, start: int) -> bool:
    window = text[max(0, start - NEG_WINDOW):start]
    window = window[window.rfind(" . ") + 3:] if " . " in window else window
    return NEGATION.search(window) is not None


def excerpt(text: str, a: int, b: int, limit: int = MAX_EVIDENCE) -> str:
    """Đoạn ``text[a:b]`` kèm ngữ cảnh hai bên, cắt ở khoảng trắng, tổng ≤ ``limit`` ký tự (kể cả dấu "…")."""
    text = " ".join(text.split("\n"))
    if b - a > limit - 2:
        return text[a:a + limit - 1].rstrip() + "…"
    pad = (limit - (b - a) - 2) // 2
    s, e = max(0, a - pad), min(len(text), b + pad)
    if s > 0 and (sp := text.find(" ", s, a)) != -1:
        s = sp + 1
    if e < len(text) and (sp := text.rfind(" ", b, e)) != -1:
        e = sp
    return ("…" if s > 0 else "") + text[s:e].strip() + ("…" if e < len(text) else "")


class RuleEngine:
    def __init__(self, version: str, rules: list[Rule]) -> None:
        self.version, self.rules = version, rules

    @classmethod
    def from_file(cls, path: Path) -> RuleEngine:
        d = yaml.safe_load(path.read_text(encoding="utf-8"))
        rules = [Rule.parse(r) for r in d["rules"]]
        if len({r.code for r in rules}) != len(rules):
            raise ValueError("rules.yaml: trùng code")
        return cls(str(d["version"]), rules)

    def __call__(self, masked_text: str, urls: list[str]) -> list[m.Signal]:
        f = fold(masked_text)
        hits: list[tuple[Rule, re.Match]] = []
        for r in self.rules:
            if r.url == "required" and not urls or r.url == "forbidden" and urls:
                continue
            if r.requires and not any(p.search(f.text) for p in r.requires):
                continue
            if any(p.search(f.text) for p in r.unless):
                continue
            if mt := r.first_match(f.text):
                hits.append((r, mt))
        codes = {r.code for r, _ in hits}
        out = []
        for r, mt in hits:
            if r.unless_codes & codes:
                continue
            a, b = f.src[mt.start()], f.src[mt.end() - 1] + 1
            out.append(m.Signal(code=r.code, weight=r.weight, hard=r.hard or (r.url == "hard" and bool(urls)),
                                evidence=excerpt(masked_text, a, b)))
        return out
