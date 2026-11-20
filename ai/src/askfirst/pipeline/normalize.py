"""Chuẩn hoá văn bản (AI-23, ARCH §6.4).

* ``normalize(text)``: bản đưa vào mask, LLM và fingerprint. NFC, bỏ ký tự vô hình (zero-width, soft hyphen), giữ dấu.
* ``fold(text)``: bản **chỉ dùng để so khớp luật**. Chữ thường, bỏ dấu (``đ``→``d``), bỏ ký tự chèn giữa hai chữ cái
  ("ti.ền", "c-o-n-g", "ti💰en"), gộp chữ cái bị tách ("O T P"), đổi leetspeak trong từ ("0TP", "c0ng", "ch1a").
  Dấu câu khác thành khoảng trắng; dấu kết câu (``. ! ? ;`` và xuống dòng) thành ``.`` để luật không khớp xuyên câu.
  Giữ ``[`` ``]`` ``_`` để token mask (``[OTP_1]`` → ``[otp_1]``) còn nguyên. ``Folded.src[i]`` là vị trí trong
  ``text`` của ký tự thứ ``i``, để cắt bằng chứng từ bản gốc.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from functools import cache

_INVISIBLE = re.compile("[\u00ad\u180e\u200b-\u200f\u2060-\u2064\ufeff]")
_KEEP = set("abcdefghijklmnopqrstuvwxyz0123456789[]_@$")
_SYMBOL = "\0"      # ký tự không giữ: bỏ nếu nằm giữa hai chữ cái, còn lại thành khoảng trắng
_STOP = "\1"        # . ! ? ;  bỏ nếu nằm giữa hai chữ cái ("ti.ền"), còn lại thành dấu kết câu
_SPLIT = re.compile(r"(?<![a-z0-9])(?:[a-z] ){2,}[a-z](?![a-z0-9])")      # "o t p" → "otp"
_TOKEN = re.compile(r"(?<![a-z0-9@$])(?=[a-z0-9@$]*[a-z])[a-z0-9@$]*[0134@$][a-z0-9@$]*(?![a-z0-9@$])")   # từ có chữ lẫn số/@/$
_AMOUNT = re.compile(r"\d+(?:k|tr|trieu|ty|d|vnd|usd|h|p|s)")             # "500k", "1tr", "15h": không phải leetspeak
_LEET = str.maketrans("0134@$", "oieaas")
_SPACES = re.compile(r" *\.(?: *\.)* *| {2,}")


def normalize(text: str) -> str:
    return _INVISIBLE.sub("", unicodedata.normalize("NFC", text)).strip()


@dataclass(frozen=True)
class Folded:
    text: str
    src: list[int]


@cache
def _fold_char(ch: str) -> str:
    if ch == "\n":
        return "."
    if ch in ".!?;":
        return _STOP
    if ch.isspace():
        return " "
    base = unicodedata.normalize("NFKD", ch)[:1].lower()
    if base == "đ":
        base = "d"
    if base in _KEEP:
        return base
    return "" if _INVISIBLE.match(ch) else _SYMBOL


def _resub(rx: re.Pattern, fn, s: str, src: list[int]) -> tuple[str, list[int]]:
    """``rx.sub(fn, s)`` và giữ bản đồ vị trí. Ký tự thay thế trỏ về đoạn khớp (đủ để cắt bằng chứng)."""
    out, osrc, pos = [], [], 0
    for mt in rx.finditer(s):
        a, b = mt.span()
        r = fn(mt)
        out.append(s[pos:a])
        osrc += src[pos:a]
        out.append(r)
        osrc += src[a:a + len(r)] if len(r) <= b - a else src[a:b] + [src[b - 1]] * (len(r) - (b - a))
        pos = b
    out.append(s[pos:])
    osrc += src[pos:]
    return "".join(out), osrc


def _leet(mt: re.Match) -> str:
    tok = mt.group()
    letters = sum(c.isalpha() for c in tok)
    if letters and letters > len(tok) - letters and not _AMOUNT.fullmatch(tok):
        return tok.translate(_LEET)
    return tok


def _starts_sentence(text: str, i: int) -> bool:
    """``text[i]`` mở đầu câu mới viết liền sau dấu chấm: "OTP.Vui lòng" (hoa rồi thường)."""
    return text[i].isupper() and i + 1 < len(text) and text[i + 1].islower()


def fold(text: str) -> Folded:
    chars: list[str] = []
    src: list[int] = []
    for i, ch in enumerate(text):
        c = _fold_char(ch)
        if c:
            chars.append(c)
            src.append(i)
    n, k = len(chars), 0
    while k < n:                            # từng cụm ký tự lạ liền nhau
        if chars[k] not in (_SYMBOL, _STOP):
            k += 1
            continue
        j = k
        while j < n and chars[j] in (_SYMBOL, _STOP):
            j += 1
        between = 0 < k and j < n and chars[k - 1].isalpha() and chars[j].isalpha()
        number = 0 < k and j < n and chars[k - 1].isdigit() and chars[j].isdigit() and all(text[src[t]] in ".," for t in range(k, j))
        drop = number or (between and not (_STOP in chars[k:j] and _starts_sentence(text, src[j])))
        for t in range(k, j):
            chars[t] = "" if drop else ("." if chars[t] == _STOP else " ")
        k = j
    src = [p for c, p in zip(chars, src, strict=True) if c]
    s = "".join(chars)
    s, src = _resub(_SPLIT, lambda mt: mt.group().replace(" ", ""), s, src)
    s, src = _resub(_TOKEN, _leet, s, src)
    s, src = _resub(_SPACES, lambda mt: " . " if "." in mt.group() else " ", s, src)
    return Folded(s, src)
