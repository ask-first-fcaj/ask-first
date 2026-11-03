"""URL intel, phần cơ bản (AI-24a, ARCH §6.4). **Không gọi mạng**: chỉ đọc URL, ``kb/allowlist.yaml`` và ``kb/brands.yaml``.

Tín hiệu (trọng số là giá trị khởi điểm, AI-10 hiệu chỉnh; sàn 80 cho tín hiệu mạnh nằm ở ``fuse.STRONG_URL``):

* ``U_OFFICIAL`` (0): host là tên miền trong allowlist hoặc subdomain của nó, hay có hậu tố ``suffixes`` (``.gov.vn``). So theo
  ranh giới nhãn: ``dichvucong.gov.vn.abc.com`` **không** chính thức.
* ``U_TYPOSQUAT`` (40, mạnh): host mượn tên thương hiệu **kèm dấu hiệu giả** (``_disguise``): tên thương hiệu làm subdomain của
  tên miền lạ (``vietcombank.vn-yys.club``), ghép gạch nối (``dichvucong-vn.com``), viết gần đúng (``vietconbank``), dùng chữ
  trông giống hoặc số leet (``vietcоmbank``, ``agr1bank``), tên cơ quan nhà nước ngoài ``.gov.vn`` (``bcavneid.com``), TLD rẻ
  hay dùng để lừa (``techcombank.cfd``); hoặc chèn tên miền trước ``@`` (``https://vietcombank.com.vn@evil.com``).
* ``U_BRAND_UNVERIFIED`` (15, yếu): host mang đúng tên thương hiệu mà không có dấu hiệu giả nhưng chưa có trong allowlist
  (``shopee.com``, ``my.viettel.vn``), hoặc ``text_link`` có chữ nhắc thương hiệu mà link thật không chính thức.
  Chỉ cộng điểm, không nâng sàn: allowlist chưa đủ tên miền thật của các thương hiệu.
* ``U_PUNYCODE`` (30): có nhãn ``xn--`` hoặc ký tự ngoài ASCII trong host.
* ``U_IP_URL`` (25): host là IP trần (kể cả dạng số ``2130706433``).
* ``U_APK_LINK`` (40): đường dẫn kết thúc ``.apk``.
* ``U_TEXT_LINK_MISMATCH`` (40, mạnh): chữ hiển thị của ``text_link`` là một tên miền thật (có hậu tố công khai) thuộc allowlist
  hoặc mang tên thương hiệu, mà eTLD+1 khác link thật.

Tên miền gốc lấy theo Public Suffix List đóng gói trong ``publicsuffixlist`` (không tải mạng). Host không có hậu tố công khai
(chữ dính dấu chấm như ``phạt.Hôm``, ``Tp.HCM``) không phải tên miền nên bỏ qua. ``host_signals(host)`` tách riêng để phần mạng
(AI-24b) chấm lại host đích sau khi mở link rút gọn. Không raise với URL lạ. Bằng chứng chỉ có host đã defang và chữ hiển thị đã
qua ``mask()``, không có đường dẫn hay mật khẩu trong URL. Không log.
"""

from __future__ import annotations

import ipaddress
import re
import unicodedata
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import NamedTuple
from urllib.parse import urlsplit

import yaml
from publicsuffixlist import PublicSuffixList

from askfirst.domain import models as m
from askfirst.pipeline.mask import mask

WEIGHTS = {"U_OFFICIAL": 0, "U_TYPOSQUAT": 40, "U_BRAND_UNVERIFIED": 15, "U_PUNYCODE": 30, "U_IP_URL": 25, "U_APK_LINK": 40,
           "U_TEXT_LINK_MISMATCH": 40}
MIN_CONTAIN = 5        # token ngắn hơn (mb, vib, evn, csgt…) chỉ khớp khi bằng đúng một phần của nhãn
MIN_EDIT = 6           # token từ độ dài này mới so Levenshtein (token ngắn so gần đúng thì báo nhầm nhiều)
MAX_EVIDENCE = 120
# TLD rẻ gặp trong tin lừa của bộ OCR và bài cảnh báo: thương hiệu thật không đặt trang ở đây.
CHEAP_TLDS = frozenset({"top", "xyz", "cc", "vip", "club", "life", "info", "cfd", "lat", "icu", "sbs", "cyou", "online", "site",
                        "shop", "help", "live", "buzz", "click"})
# Link thật là trang ứng dụng trên kho chính thức: chữ nhắc thương hiệu ("VNeID") là bình thường.
APP_STORES = frozenset({"play.google.com", "apps.apple.com", "itunes.apple.com"})

_PSL = PublicSuffixList(accept_unknown=False)
_SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*://", re.IGNORECASE)
_NUMERIC_HOST = re.compile(r"(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+)){0,3}", re.IGNORECASE)
_DOMAIN_IN_TEXT = re.compile(r"(?:[a-z0-9-]+(?:\.|\[\.\]))+[a-z]{2,}", re.IGNORECASE)
# Chữ trông giống chữ Latin (Cyrillic, Hy Lạp, IPA) và chữ số kiểu leet.
_SKELETON = str.maketrans({
    "а": "a", "е": "e", "о": "o", "р": "p", "с": "c", "у": "y", "х": "x", "і": "i", "ј": "j", "ѕ": "s", "ԁ": "d", "һ": "h",
    "ӏ": "l", "к": "k", "м": "m", "т": "t", "в": "b", "н": "h", "α": "a", "ο": "o", "ν": "v", "ι": "i", "ρ": "p", "τ": "t",
    "ε": "e", "κ": "k", "ı": "i", "ɡ": "g", "ɑ": "a", "đ": "d", "0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t",
})


class ParsedUrl(NamedTuple):
    host: str
    path: str
    user: str          # phần trước "@" (không gồm mật khẩu), rỗng nếu không có


@dataclass(frozen=True)
class Brand:
    name: str
    tokens: tuple[str, ...]
    gov: bool          # mọi tên miền chính thức đều dưới .gov.vn: cơ quan nhà nước không dùng tên miền khác


def parse_url(url: str) -> ParsedUrl | None:
    """Host, đường dẫn, tên trước ``@`` của URL, kể cả URL đã defang (``hxxps://a[.]com``) hoặc không có scheme."""
    u = url.strip().replace("[.]", ".").replace("(.)", ".").replace("\\", "/")
    u = re.sub(r"^hxxp", "http", u, flags=re.IGNORECASE)
    if not _SCHEME.match(u):
        u = "http://" + u
    try:
        parts = urlsplit(u)
        host = parts.hostname
    except ValueError:            # "[" lạ trong host (URL defang kiểu khác, IPv6 hỏng)
        return None
    return ParsedUrl(host.rstrip("."), parts.path, parts.username or "") if host else None


def etld1(host: str) -> str:
    """Tên miền gốc theo Public Suffix List: ``csgt.bocongan.gov.vn`` → ``bocongan.gov.vn``."""
    return _PSL.privatesuffix(host) or host


def _split(host: str) -> tuple[list[str], str, str] | None:
    """``(nhãn subdomain, nhãn tên miền gốc, hậu tố)``; ``None`` khi không có hậu tố công khai (không phải tên miền)."""
    suffix, reg = _PSL.publicsuffix(host), _PSL.privatesuffix(host)
    if not suffix or not reg:
        return None
    sub = host[: -len(reg)].rstrip(".")
    return (sub.split(".") if sub else []), reg[: -len(suffix) - 1], suffix


def _unicode_label(label: str) -> str:
    try:
        return label.encode("ascii").decode("idna")    # xn--… → chữ thật để dựng bộ xương
    except UnicodeError:
        return label


def skeleton(text: str) -> str:
    """Chữ thường, bỏ dấu, đổi chữ trông giống và chữ số leet về chữ Latin: ``vіetc0mbạnk`` → ``vietcombank``."""
    t = unicodedata.normalize("NFKD", text.lower().translate(_SKELETON))
    return "".join(c for c in t if not unicodedata.combining(c)).translate(_SKELETON)


def _distance(a: str, b: str, limit: int) -> int:
    """Levenshtein, dừng sớm khi chắc chắn vượt ``limit``."""
    if abs(len(a) - len(b)) > limit:
        return limit + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        if min(cur) > limit:
            return limit + 1
        prev = cur
    return prev[-1]


def _defang(host: str) -> str:
    return host.replace(".", "[.]")


class UrlIntel:
    def __init__(self, domains: Iterable[str], suffixes: Iterable[str], brands: Iterable[Brand]) -> None:
        self.domains = frozenset(d.lower() for d in domains)
        self.suffixes = tuple(s.lower() for s in suffixes)
        self.brands = tuple(brands)
        self.tokens = frozenset(t for b in self.brands for t in b.tokens)

    @classmethod
    def from_kb(cls, kb_dir: Path) -> UrlIntel:
        allow = yaml.safe_load((kb_dir / "allowlist.yaml").read_text(encoding="utf-8"))
        brands = yaml.safe_load((kb_dir / "brands.yaml").read_text(encoding="utf-8"))
        return cls([d["domain"] for d in allow["domains"]], allow.get("suffixes") or [],
                   [Brand(b["name"], tuple(b["tokens"]), all(d.endswith(".gov.vn") for d in b["official_domains"]))
                    for b in brands])

    def official_host(self, host: str) -> bool:
        """Host là tên miền trong allowlist, subdomain của nó, hoặc có hậu tố trong ``suffixes`` (so theo ranh giới nhãn)."""
        host = host.lower().rstrip(".")
        labels = host.split(".")
        return (any(".".join(labels[i:]) in self.domains for i in range(len(labels)))
                or any(host.endswith("." + s) for s in self.suffixes))

    def is_official(self, url: str) -> bool:
        p = parse_url(url)
        return p is not None and self.official_host(p.host)

    def _label_hit(self, label: str) -> tuple[Brand, bool] | None:
        """Thương hiệu mà một nhãn mượn tên và nhãn có bị ngụy trang không (chữ trông giống, leet, gạch nối, viết gần đúng)."""
        raw = _unicode_label(label)
        sk = skeleton(raw)
        parts = [p for p in re.split(r"[-_]", sk) if p]
        joined = "".join(parts)
        disguised = sk != raw.lower() or len(parts) > 1
        for b in self.brands:
            for t in b.tokens:
                if t in parts or (len(t) >= MIN_CONTAIN and t in joined):
                    # ghép thêm chữ vào tên cơ quan nhà nước ("dichvuconggov") cũng là giả: cơ quan thật chỉ dùng .gov.vn
                    return b, disguised or (b.gov and joined != t)
        if any(x in self.tokens for x in [*parts, joined]):
            return None    # nhãn là đúng tên một thương hiệu khác: "tpbank" không phải viết sai của "vpbank"
        for b in self.brands:
            for t in b.tokens:
                limit = 1 if len(t) <= 7 else 2
                if len(t) >= MIN_EDIT and any(_distance(t, x, limit) <= limit for x in [*parts, joined]):
                    return b, True
        return None

    def brand_in(self, host: str) -> tuple[Brand, bool] | None:
        """``(thương hiệu, có dấu hiệu giả)`` mà host mượn tên; ``None`` nếu không mượn tên nào."""
        split = _split(host)
        if split is None:
            return None
        subs, reg, suffix = split
        hit = self._label_hit(reg)
        for s in subs:
            if hit is None and (h := self._label_hit(s)):
                hit = (h[0], True)    # tên thương hiệu làm subdomain của một tên miền lạ
        if hit and suffix in CHEAP_TLDS:
            hit = (hit[0], True)
        return hit

    def host_signals(self, host: str, path: str = "", user: str = "") -> list[tuple[str, str]]:
        """``(mã, bằng chứng)`` cho một host. AI-24b gọi lại với host đích của link rút gọn."""
        host = host.lower().rstrip(".")
        shown = _defang(host)
        out: list[tuple[str, str]] = []
        if path.lower().endswith(".apk"):
            out.append(("U_APK_LINK", f"{shown} (tệp .apk)"))
        if user and ("." in user or self._label_hit(user.lower())):
            out.append(("U_TYPOSQUAT", f"{mask(_defang(user[:40].lower())).text}@{shown}"))   # trình duyệt bỏ phần trước "@"
        try:
            ipaddress.ip_address(host)
            is_ip = True
        except ValueError:
            is_ip = bool(_NUMERIC_HOST.fullmatch(host))
        if is_ip:
            return [*out, ("U_IP_URL", shown)]
        if self.official_host(host):
            return [*out, ("U_OFFICIAL", shown)]
        if _split(host) is None:
            return out    # không có hậu tố công khai: chữ dính dấu chấm mà regex URL bắt thừa
        if not host.isascii() or any(x.startswith("xn--") for x in host.split(".")):
            out.append(("U_PUNYCODE", shown))
        if hit := self.brand_in(host):
            brand, fake = hit
            out.append(("U_TYPOSQUAT" if fake else "U_BRAND_UNVERIFIED", f"{shown} (giống {brand.name})"))
        return out

    def link_signals(self, shown_text: str, url: str) -> list[tuple[str, str]]:
        """Tín hiệu cho một ``text_link`` (chữ hiển thị, URL thật)."""
        p = parse_url(url)
        if p is None:
            return []
        real = p.host.lower()
        ev = f"{mask(shown_text.strip()[:40]).text} → {_defang(real)}"
        domains = [h.host.lower() for d in _DOMAIN_IN_TEXT.finditer(shown_text) if (h := parse_url(d.group()))]
        domains = [h for h in domains if _split(h) is not None]    # "Tp.HCM", "Dr.Smith" không phải tên miền
        if domains:
            claimed = domains[0]
            if etld1(claimed) != etld1(real) and (self.official_host(claimed) or self.brand_in(claimed)):
                return [("U_TEXT_LINK_MISMATCH", ev)]
            return []
        if real in APP_STORES or self.official_host(real):
            return []
        words = set(re.findall(r"[a-z0-9]+", skeleton(shown_text)))
        squashed = "".join(re.findall(r"[a-z0-9]+", skeleton(shown_text)))
        for b in self.brands:
            if any(t in words if len(t) < MIN_CONTAIN else t in squashed for t in b.tokens):
                return [("U_BRAND_UNVERIFIED", ev)]
        return []

    def __call__(self, urls: Iterable[str], links: Iterable[tuple[str, str]] = ()) -> list[m.Signal]:
        """Tín hiệu ``U_*`` cho mọi URL của tin; ``links`` là các ``(chữ hiển thị, URL thật)`` của ``text_link``. Mỗi mã một lần."""
        found: dict[str, str] = {}
        for u in urls:
            if p := parse_url(u):
                for code, ev in self.host_signals(*p):
                    found.setdefault(code, ev)
        for shown_text, url in links:
            for code, ev in self.link_signals(shown_text, url):
                found.setdefault(code, ev)
        return [m.Signal(code=c, weight=WEIGHTS[c], evidence=ev[:MAX_EVIDENCE]) for c, ev in found.items()]
