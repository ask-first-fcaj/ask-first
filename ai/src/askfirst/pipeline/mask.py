"""Mask PII (AI-22, ARCH §9.2): che thông tin cá nhân trước mọi lời gọi ra ngoài và trước khi log.

Chạy local, chỉ dùng thư viện chuẩn. Đầu vào là **text** (text người dùng gõ, hoặc text do OCR/ASR đọc ra),
không xử lý pixel của ảnh.

Token giữ cấu trúc để LLM và luật vẫn hiểu ngữ cảnh:

=================  ==========================================  ====================
Loại               Ví dụ gốc                                   Token
=================  ==========================================  ====================
``phone``          ``0912 345 678``, ``+84 912345678``         ``[SĐT_1]``
``cccd``           ``012345678901``                            ``[CCCD_1]``
``stk``            ``STK 0123456789 Vietcombank``              ``[STK_1 • Vietcombank]``
``otp``            ``Mã OTP là 482913``                        ``[OTP_1]``
``email``          ``a.b@gmail.com``                           ``[EMAIL_1]``
``spoken_number``  ``không chín tám bảy sáu năm bốn``          ``[SỐ_CHỮ_1]``
=================  ==========================================  ====================

Quy ước:

* Cùng một giá trị thì cùng một token; chỉ số tăng theo thứ tự xuất hiện.
* **Không mask URL và tên miền** (ARCH §9.2): URL lừa đảo là bằng chứng, kể cả khi trong đường dẫn có số.
* Token ``[OTP_n]`` viết đúng dạng này vì luật ``R_OTP_*`` so khớp trên token sau khi hạ chữ thường (``[otp_1]``).
* Ưu tiên thiên về **che thừa hơn che sót**. Số tiền (``50.000đ``), giờ, ngày, mã đơn hàng không bị che.
* Số tiền viết bằng chữ ("năm mươi triệu") **không** bị che: ARCH §9.2 chỉ yêu cầu che số đọc từng chữ số
  ("không chín tám…"), và số tiền là tín hiệu để nhận diện lừa đảo, không phải dữ liệu định danh.
* ``MaskResult.entities`` giữ giá trị gốc **chỉ trong RAM**. Cấm log, cấm ghi đĩa (plan §3.1). ``original`` bị
  loại khỏi ``repr`` để lỡ log đối tượng cũng không lộ.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field
from functools import cache
from typing import Literal

Kind = Literal["phone", "cccd", "stk", "otp", "email", "spoken_number"]

_LABEL: dict[str, str] = {
    "phone": "SĐT",
    "cccd": "CCCD",
    "stk": "STK",
    "otp": "OTP",
    "email": "EMAIL",
    "spoken_number": "SỐ_CHỮ",
}

# Thứ tự ưu tiên khi hai luật cùng khớp một đoạn: ngữ cảnh rõ (OTP, STK) thắng khớp theo hình dạng (CCCD, SĐT).
_PRIO: dict[str, int] = {"email": 0, "otp": 1, "stk": 2, "cccd": 3, "phone": 4, "spoken_number": 5}

_INVISIBLE = str.maketrans(
    "",
    "",
    "\u00ad\u180e\u200b\u200c\u200d\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2060\u2061\u2062\u2063\u2064\u2066\u2067\u2068\u2069\ufeff",
)

_BANKS: dict[str, str] = {
    "vietcombank": "Vietcombank",
    "vcb": "Vietcombank",
    "techcombank": "Techcombank",
    "tcb": "Techcombank",
    "bidv": "BIDV",
    "agribank": "Agribank",
    "mbbank": "MB",
    "mb bank": "MB",
    "acb": "ACB",
    "vpbank": "VPBank",
    "tpbank": "TPBank",
    "sacombank": "Sacombank",
    "vietinbank": "VietinBank",
    "ctg": "VietinBank",
    "hdbank": "HDBank",
    "vib": "VIB",
    "shb": "SHB",
    "msb": "MSB",
    "ocb": "OCB",
    "lpbank": "LPBank",
    "seabank": "SeABank",
    "eximbank": "Eximbank",
    "scb": "SCB",
    "bacabank": "BacABank",
    "pvcombank": "PVcomBank",
    "kienlongbank": "Kienlongbank",
    "vietabank": "VietABank",
    "nam a bank": "Nam A Bank",
}
_BANK_ALT = "|".join(sorted((re.escape(b) for b in _BANKS), key=len, reverse=True))
_BANK_RE = re.compile(rf"\b(?:{_BANK_ALT})\b")

_D = "[0-9]"
_SEP = r"[ .\-]"
# Số tiền đi kèm đơn vị (đã hạ chữ thường, bỏ dấu): không phải mã.
_NOT_MONEY = r"(?!\s*(?:(?:d|vnd|dong|usd|k|tr|trieu|nghin|ty)\b|₫|%))"
_NOT_NUM_TAIL = r"(?![0-9])(?![.,:/][0-9])"
_NOT_NUM_HEAD = r"(?<![0-9])(?<![0-9][.,:/\-])"
_MONEY_LIKE = re.compile(r"[1-9][0-9]{0,2}(?:[ .,][0-9]{3})+")


@cache
def _fold_char(ch: str) -> str:
    base = unicodedata.normalize("NFD", ch)[0].lower()
    if base == "đ":
        base = "d"
    return base if len(base) == 1 else ch


def _fold(text: str) -> str:
    """Chữ thường, bỏ dấu, **cùng độ dài** với ``text`` để vị trí khớp dùng chung cho cả hai."""
    return "".join(_fold_char(c) for c in text)


# ---------------------------------------------------------------- URL (được bảo vệ, không mask)
_URL = re.compile(
    r"(?:(?:https?|hxxps?|ftp)://|www\.)[^\s,;\"'<>()]+"
    r"|(?<![\w@.\-])(?:(?=[0-9\-]*[a-z])[a-z0-9\-]+(?:\.|\[\.\]))+[a-z]{2,}(?:/[^\s,;\"'<>()]*)?"
)

# ---------------------------------------------------------------- email
_EMAIL = re.compile(r"(?<![a-z0-9._%+\-])[a-z0-9._%+\-]+(?:@|\[at\]|\(at\))[a-z0-9\-]+(?:(?:\.|\[\.\]|\[dot\]|\(dot\))[a-z0-9\-]+)+")

# ---------------------------------------------------------------- OTP
_OTP_KW = (
    r"(?:otp|one[- ]?time|verification code|security code|login code|activation code|auth(?:entication)? code|passcode|"
    r"\bma xac (?:thuc|nhan|minh)|\bma bao mat|\bma dang nhap|\bma kich hoat|\bma (?:vua )?(?:gui|nhan)(?: ve)?|\bma id|\bma pin|"
    r"\bpin\b|\bmat khau|\bpassword|\bcode\b)"
)
_OTP_NUM = (
    rf"{_NOT_NUM_HEAD}(?<!nam )(?<!year )(?!(?:1900|1800){_D}{{4}}(?![0-9]))"
    rf"(?:{_D}{{4,8}}|{_D}{{3}}[ .\-]{_D}{{3}}|{_D}{{4}}[ \-]{_D}{{4}}|{_D}(?: {_D}){{3,7}})"
    rf"{_NOT_NUM_TAIL}{_NOT_MONEY}"
)
_OTP_KW_RE = re.compile(_OTP_KW)
_OTP_NUM_RE = re.compile(_OTP_NUM)
_OTP_WINDOW = 120
# Mã đứng trước từ khoá: "482913 là mã xác thực", "798272 is your Grab Activation Code".
_OTP_BEFORE = re.compile(rf"(?P<c>{_OTP_NUM})\s+(?:la|is)\b[^0-9]{{0,40}}?{_OTP_KW}")
# Mã kèm cụm hiệu lực: "Mã Spotify: 572408. Có hiệu lực trong 5 phút".
_OTP_VALID = re.compile(rf"(?P<c>{_OTP_NUM})\W{{1,3}}(?:co hieu luc|hieu luc|het han|expires?|valid)")
_OTP_USE = re.compile(rf"\b(?:su dung|use)\s+(?P<c>{_OTP_NUM})\s+(?:duoi dang|as)\b")
# "mã 482913", "code: 4951": từ "mã" đứng sát số (không chen từ khác như "mã đơn hàng 123456").
_OTP_ADJ = re.compile(rf"\b(?:ma|code)\b(?:\s+so\b)?\s*[:\-]?\s*(?P<c>{_OTP_NUM})")

# ---------------------------------------------------------------- STK
_STK_KW = re.compile(
    rf"(?:\bstk\b|\bs\.t\.k\b|\bso tai khoan|\btai khoan|\bso tk\b|\btk\b|\baccount\b|\bacc\b|\bct (?:tu|den)\b|\b(?:{_BANK_ALT})\b)"
)
_STK_NUM = re.compile(rf"{_NOT_NUM_HEAD}(?P<c>{_D}{{6,16}}|{_D}{{3,5}}(?: {_D}{{3,5}}){{1,4}}){_NOT_NUM_TAIL}{_NOT_MONEY}")
_STK_WINDOW = 40
_STK_BANK_AFTER = re.compile(rf"{_NOT_NUM_HEAD}(?P<c>{_D}{{9,19}}){_NOT_NUM_TAIL}[^0-9]{{0,40}}?\b(?P<bank>{_BANK_ALT})\b")

# ---------------------------------------------------------------- CCCD / CMND
_CCCD_KW = re.compile(r"(?:cccd|cmnd|\bcmt\b|can cuoc(?: cong dan)?|chung minh(?: nhan dan)?|so dinh danh)")
_CCCD_CTX_NUM = re.compile(rf"(?<![0-9])(?P<c>{_D}(?:{_SEP}?{_D}){{11}}|{_D}(?:{_SEP}?{_D}){{8}})(?![0-9])")
_CCCD_PLAIN = re.compile(
    rf"(?<![a-z]){_NOT_NUM_HEAD}(?P<c>{_D}{{12}}|{_D}{{3}}{_SEP}{_D}{{3}}{_SEP}{_D}{{3}}{_SEP}{_D}{{3}}){_NOT_NUM_TAIL}{_NOT_MONEY}"
)

# ---------------------------------------------------------------- SĐT
_LOCAL = "|".join(
    [
        rf"0[35789]{_D}{{8}}",
        rf"0[35789]{_D}{{2}}{_SEP}{_D}{{3}}{_SEP}{_D}{{3}}",  # 0912 345 678
        rf"0[35789]{_D}{_SEP}{_D}{{3}}{_SEP}{_D}{{4}}",  # 091 234 5678
        rf"0[35789]{_D}{_SEP}{_D}{{4}}{_SEP}{_D}{{3}}",  # 091 2345 678
        rf"0[35789]{_SEP}{_D}{{4}}{_SEP}{_D}{{4}}",  # 09 1234 5678
        rf"0[35789]{_D}{_SEP}{_D}{{3}}{_SEP}{_D}{{2}}{_SEP}{_D}{{2}}",  # 091-234-56-78
        rf"0[35789]{_D}{{2}}{_SEP}{_D}{{6}}",  # 0912 345678
        rf"0[35789]{_D}{_SEP}{_D}{{7}}",  # 091 2345678
        rf"0[35789]{_SEP}{_D}{{8}}",  # 09 12345678
        rf"0{_SEP}[35789](?:{_SEP}{_D}){{8}}",  # 0 9 1 2 3 4 5 6 7 8 (từng chữ số cách nhau)
        rf"0[35789](?:{_SEP}{_D}{{2}}){{4}}",  # 09-12-34-56-78
        rf"0[35789]{_D}{{2}}{_SEP}{_D}{{2}}{_SEP}{_D}{{4}}",  # 0937 70 4636
    ]
)
# Sau "84" trần (không có "+") chỉ nhận các cách nhóm quen thuộc để khỏi nhầm với số khác.
_INTL = "|".join(
    [
        rf"[35789]{_D}{{8}}",
        rf"[35789]{_D}{{2}}{_SEP}{_D}{{3}}{_SEP}{_D}{{3}}",
        rf"[35789]{_D}{_SEP}{_D}{{3}}{_SEP}{_D}{{4}}",
        rf"[35789]{_SEP}{_D}{{4}}{_SEP}{_D}{{4}}",
    ]
)
# Có dấu "+" hoặc "0084" thì nhận mọi cách nhóm (một ký tự phân cách giữa các chữ số): không lẫn với ngày tháng.
_PLUS84 = rf"(?:\+\s?84|\(\+84\)|0084){_SEP}?[35789](?:{_SEP}?{_D}){{8}}"
_PHONE = re.compile(rf"(?<![0-9])(?P<c>(?:{_LOCAL})|{_PLUS84}|84{_SEP}?(?:{_INTL})){_NOT_NUM_TAIL}")
# Link liên hệ chứa thẳng số điện thoại ("zalo.me/0912345678"): đây là số điện thoại, không phải bằng chứng về tên miền.
_CONTACT_LINK = re.compile(rf"\b(?:zalo\.me/|wa\.me/|t\.me/(?=\+))(?P<c>\+?{_D}{{9,12}}){_NOT_NUM_TAIL}")
# Số quốc tế khác (+212 6 49 02 06 39): vẫn là số điện thoại của người gửi, nên che. Số tiền có dấu "+" (+50.000.000) bị loại ở _detect.
_PHONE_INTL = re.compile(rf"(?<![0-9+])(?P<c>\+\s?(?!84)[1-9]{_D}{{0,2}}(?:{_SEP}?{_D}){{7,11}}){_NOT_NUM_TAIL}{_NOT_MONEY}")


def _value_key(kind: str, folded_span: str) -> str:
    """Khoá gộp token: cùng giá trị thì cùng token, kể cả viết khác dạng (``+84 912…`` và ``0912…``)."""
    if kind == "email":
        return folded_span
    digits_or_letters = re.sub(r"[^0-9a-z]", "", folded_span)
    if kind == "phone":
        if digits_or_letters.startswith("0084"):
            return "0" + digits_or_letters[4:]
        if digits_or_letters.startswith("84") and len(digits_or_letters) == 11:
            return "0" + digits_or_letters[2:]
    return digits_or_letters


# ---------------------------------------------------------------- số đọc từng chữ số ("không chín tám …")
_DIGIT_WORD = r"(?:khong|mot|hai|ba|bon|tu|nam|lam|sau|bay|tam|chin)"
_SPOKEN = re.compile(rf"\b{_DIGIT_WORD}(?:[ ,.\-]+{_DIGIT_WORD}\b){{5,}}")


@dataclass(frozen=True, slots=True)
class MaskedEntity:
    kind: Kind
    token: str
    original: str = field(repr=False)


@dataclass(frozen=True, slots=True)
class MaskResult:
    """``text`` là bản đã che. ``entities`` giữ giá trị gốc trong RAM: **không log, không ghi đĩa**."""

    text: str
    entities: tuple[MaskedEntity, ...] = ()

    def mapping(self) -> dict[str, str]:
        """token -> giá trị gốc, để hoàn nguyên trong cùng một lần xử lý."""
        return {e.token: e.original for e in self.entities}


@dataclass(frozen=True, slots=True)
class _Hit:
    start: int
    end: int
    kind: Kind
    bank: str | None = None
    exempt: bool = False  # True: nằm trong URL vẫn phải che (email, số điện thoại trong link Zalo/WhatsApp)


def _scan_after(folded: str, kw: re.Pattern[str], num: re.Pattern[str], window: int):
    """Với mỗi từ khoá, yield các số khớp ``num`` nằm trong ``window`` ký tự sau từ khoá.

    Không dừng ở xuống dòng: text OCR ngắt dòng giữa câu ("OTP cua Quy Khach la:\\n62743042").
    """
    for k in kw.finditer(folded):
        limit = k.end() + window
        for m in num.finditer(folded, k.end()):
            if m.start() >= limit:
                break
            yield k, m


def _detect(folded: str) -> list[_Hit]:
    hits: list[_Hit] = []

    for m in _EMAIL.finditer(folded):
        hits.append(_Hit(m.start(), m.end(), "email", exempt=True))
    for m in _CONTACT_LINK.finditer(folded):
        hits.append(_Hit(*m.span("c"), "phone", exempt=True))

    for _, m in _scan_after(folded, _OTP_KW_RE, _OTP_NUM_RE, _OTP_WINDOW):
        hits.append(_Hit(m.start(), m.end(), "otp"))
    for rx in (_OTP_BEFORE, _OTP_VALID, _OTP_USE, _OTP_ADJ):
        for m in rx.finditer(folded):
            s, e = m.span("c")
            hits.append(_Hit(s, e, "otp"))

    stk_done: set[int] = set()  # mỗi từ khoá chỉ che số đầu tiên hợp lệ sau nó ("STK 0123…, SĐT 0912…" thì số sau là SĐT)
    for k, m in _scan_after(folded, _STK_KW, _STK_NUM, _STK_WINDOW):
        s, e = m.span("c")
        raw = folded[s:e]
        digits = sum(c.isdigit() for c in raw)
        if k.start() in stk_done or not 6 <= digits <= 16 or _MONEY_LIKE.fullmatch(raw):
            continue
        stk_done.add(k.start())
        near = _BANK_RE.search(folded, k.start(), s) or _BANK_RE.search(folded, e, min(e + 20, len(folded)))
        hits.append(_Hit(s, e, "stk", _BANKS[near.group()] if near else None))
    for m in _STK_BANK_AFTER.finditer(folded):
        hits.append(_Hit(*m.span("c"), "stk", _BANKS[m.group("bank")]))

    for _, m in _scan_after(folded, _CCCD_KW, _CCCD_CTX_NUM, 30):
        hits.append(_Hit(*m.span("c"), "cccd"))
    for m in _CCCD_PLAIN.finditer(folded):
        s, e = m.span("c")
        if not _MONEY_LIKE.fullmatch(folded[s:e]):
            hits.append(_Hit(s, e, "cccd"))

    for m in _PHONE.finditer(folded):
        hits.append(_Hit(*m.span("c"), "phone"))
    for m in _PHONE_INTL.finditer(folded):
        s, e = m.span("c")
        if not _MONEY_LIKE.fullmatch(folded[s + 1 : e].strip()):
            hits.append(_Hit(s, e, "phone"))

    for m in _SPOKEN.finditer(folded):
        hits.append(_Hit(m.start(), m.end(), "spoken_number"))

    return hits


def _resolve(hits: list[_Hit], protected: list[tuple[int, int]]) -> list[_Hit]:
    """Bỏ khớp nằm trong URL (trừ email), rồi chọn không chồng lấn theo độ ưu tiên."""
    kept = [h for h in hits if h.exempt or not any(h.start < pe and ps < h.end for ps, pe in protected)]
    # Cửa sổ OTP nhặt từng cụm số ("0912", "345 678"): nếu cả cụm là một SĐT/STK/CCCD dài hơn thì che cả số, không che từng mảnh.
    whole = [h for h in kept if h.kind in ("phone", "stk", "cccd")]
    kept = [
        h
        for h in kept
        if h.kind != "otp" or not any(w.start <= h.start and h.end <= w.end and w.end - w.start > h.end - h.start for w in whole)
    ]
    kept.sort(key=lambda h: (_PRIO[h.kind], h.start, h.start - h.end))
    chosen: list[_Hit] = []
    for h in kept:
        if all(h.end <= c.start or c.end <= h.start for c in chosen):
            chosen.append(h)
    chosen.sort(key=lambda h: h.start)
    return chosen


_DIGIT_RUN = re.compile(r"(?<![0-9])[0-9]{4,8}(?![0-9])")


def _otp_echoes(folded: str, hits: list[_Hit], protected: list[tuple[int, int]]) -> list[_Hit]:
    """Mã OTP đã nhận ra ngoài URL mà còn lặp lại trong URL (``?code=482913``) thì cũng phải che: giữ tên miền và
    phần còn lại của URL, chỉ thay đúng dãy số đó.
    """
    codes = {
        "".join(c for c in folded[h.start : h.end] if c.isdigit())
        for h in hits
        if h.kind == "otp" and not any(h.start < pe and ps < h.end for ps, pe in protected)
    }
    if not codes:
        return []
    return [
        _Hit(m.start(), m.end(), "otp", exempt=True)
        for ps, pe in protected
        for m in _DIGIT_RUN.finditer(folded, ps, pe)
        if m.group() in codes
    ]


def mask(text: str) -> MaskResult:
    """Che PII trong ``text``. Chuẩn hoá NFKC và bỏ ký tự vô hình trước, nên cũng bắt được số toàn-rộng
    (``０９１２…``) hoặc số chèn ``\\u200b`` để né regex. Văn bản trả về là bản đã chuẩn hoá (NFKC).
    """
    clean = unicodedata.normalize("NFKC", text.translate(_INVISIBLE))
    folded = _fold(clean)
    protected = [m.span() for m in _URL.finditer(folded)]
    hits = _detect(folded)
    hits += _otp_echoes(folded, hits, protected)
    hits = _resolve(hits, protected)

    index: dict[tuple[str, str], str] = {}
    counters: dict[str, int] = {}
    entities: list[MaskedEntity] = []
    out: list[str] = []
    pos = 0
    for h in hits:
        original = clean[h.start : h.end]
        value = _value_key(h.kind, folded[h.start : h.end])
        key = (h.kind, value)
        token = index.get(key)
        if token is None:
            counters[h.kind] = counters.get(h.kind, 0) + 1
            label = f"{_LABEL[h.kind]}_{counters[h.kind]}"
            token = f"[{label} • {h.bank}]" if h.bank else f"[{label}]"
            index[key] = token
            entities.append(MaskedEntity(h.kind, token, original))
        out.append(clean[pos : h.start])
        out.append(token)
        pos = h.end
    out.append(clean[pos:])
    return MaskResult("".join(out), tuple(entities))
