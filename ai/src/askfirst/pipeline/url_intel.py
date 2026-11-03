"""URL intel, phần cơ bản (AI-24a, ARCH §6.4). **Không gọi mạng**: chỉ đọc URL, ``kb/allowlist.yaml`` và ``kb/brands.yaml``.

Tín hiệu (trọng số là giá trị khởi điểm, AI-10 hiệu chỉnh; sàn 80 cho tín hiệu mạnh nằm ở ``fuse.STRONG_URL``):

* ``U_OFFICIAL`` (0): host là tên miền trong allowlist hoặc subdomain của nó, hay có hậu tố ``suffixes`` (``.gov.vn``). So theo
  ranh giới nhãn: ``dichvucong.gov.vn.abc.com`` **không** chính thức.
* ``U_TYPOSQUAT`` (40): host không chính thức mà chứa token thương hiệu (``dichvucong-vn.com``, ``bcavneid.com``), có một phần
  bằng đúng token ngắn (``mb-bank.top``), hoặc cách token dài ≤ 1–2 ký tự (``vietconbank.com``). So trên "bộ xương" của host:
  bỏ dấu, đổi chữ trông giống (Cyrillic ``а`` → ``a``) và chữ số kiểu leet (``agr1bank`` → ``agribank``).
* ``U_PUNYCODE`` (30): có nhãn ``xn--`` hoặc ký tự ngoài ASCII trong host.
* ``U_IP_URL`` (25): host là IP trần (kể cả dạng số ``2130706433``).
* ``U_APK_LINK`` (40): đường dẫn kết thúc ``.apk``.
* ``U_TEXT_LINK_MISMATCH`` (40): ``text_link`` mà chữ hiển thị là một tên miền khác eTLD+1 của URL thật, hoặc chữ hiển thị nhắc
  thương hiệu trong ``brands.yaml`` còn URL thật không chính thức.

``host_signals(host)`` tách riêng để phần mạng (AI-24b) chấm lại host đích sau khi mở link rút gọn. Không raise với URL lạ
(defang ``[.]``, ``hxxp``, IPv6 hỏng): URL không lấy được host thì bỏ qua. Không log.
"""

from __future__ import annotations

import ipaddress
import re
import unicodedata
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

import tldextract
import yaml

from askfirst.domain import models as m

WEIGHTS = {"U_OFFICIAL": 0, "U_TYPOSQUAT": 40, "U_PUNYCODE": 30, "U_IP_URL": 25, "U_APK_LINK": 40, "U_TEXT_LINK_MISMATCH": 40}
MIN_CONTAIN = 5        # token ngắn hơn (mb, vib, evn, csgt…) chỉ khớp khi bằng đúng một phần của host
MIN_EDIT = 6           # token từ độ dài này mới so Levenshtein (token ngắn so gần đúng thì báo nhầm nhiều)
MAX_EVIDENCE = 120

# Bảng PSL đóng gói sẵn trong tldextract: không tải danh sách mới qua mạng, không ghi cache ra đĩa.
_EXTRACT = tldextract.TLDExtract(suffix_list_urls=(), cache_dir=None)
_SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*://", re.IGNORECASE)
_NUMERIC_HOST = re.compile(r"(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+)){0,3}", re.IGNORECASE)
_DOMAIN_IN_TEXT = re.compile(r"(?:[a-z0-9-]+(?:\.|\[\.\]))+[a-z]{2,}", re.IGNORECASE)
# Chữ trông giống chữ Latin (Cyrillic, Hy Lạp, IPA) và chữ số kiểu leet.
_SKELETON = str.maketrans({
    "а": "a", "е": "e", "о": "o", "р": "p", "с": "c", "у": "y", "х": "x", "і": "i", "ј": "j", "ѕ": "s", "ԁ": "d", "һ": "h",
    "ӏ": "l", "к": "k", "м": "m", "т": "t", "в": "b", "н": "h", "α": "a", "ο": "o", "ν": "v", "ι": "i", "ρ": "p", "τ": "t",
    "ε": "e", "κ": "k", "ı": "i", "ɡ": "g", "ɑ": "a", "đ": "d", "0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t",
})


@dataclass(frozen=True)
class Brand:
    name: str
    tokens: tuple[str, ...]


def parse_url(url: str) -> tuple[str, str] | None:
    """``(host, path)`` của URL, kể cả URL đã defang (``hxxps://a[.]com``) hoặc không có scheme. Không lấy được thì ``None``."""
    u = url.strip().replace("[.]", ".").replace("(.)", ".").replace("\\", "/")
    u = re.sub(r"^hxxp", "http", u, flags=re.IGNORECASE)
    if not _SCHEME.match(u):
        u = "http://" + u
    try:
        parts = urlsplit(u)
        host = parts.hostname
    except ValueError:            # "[" lạ trong host (URL defang kiểu khác, IPv6 hỏng)
        return None
    return (host.rstrip("."), parts.path) if host else None


def etld1(host: str) -> str:
    """Tên miền gốc theo Public Suffix List: ``csgt.bocongan.gov.vn`` → ``bocongan.gov.vn``."""
    r = _EXTRACT(host)
    return ".".join(p for p in (r.domain, r.suffix) if p) or host


def _unicode_host(host: str) -> str:
    try:
        return host.encode("ascii").decode("idna")    # xn--… → chữ thật để dựng bộ xương
    except UnicodeError:
        return host


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


class UrlIntel:
    def __init__(self, domains: Iterable[str], suffixes: Iterable[str], brands: Iterable[Brand]) -> None:
        self.domains = frozenset(d.lower() for d in domains)
        self.suffixes = tuple(s.lower() for s in suffixes)
        self.brands = tuple(brands)

    @classmethod
    def from_kb(cls, kb_dir: Path) -> UrlIntel:
        allow = yaml.safe_load((kb_dir / "allowlist.yaml").read_text(encoding="utf-8"))
        brands = yaml.safe_load((kb_dir / "brands.yaml").read_text(encoding="utf-8"))
        return cls([d["domain"] for d in allow["domains"]], allow.get("suffixes") or [],
                   [Brand(b["name"], tuple(b["tokens"])) for b in brands])

    def official_host(self, host: str) -> bool:
        labels = host.split(".")
        return (any(".".join(labels[i:]) in self.domains for i in range(len(labels)))
                or any(host.endswith("." + s) for s in self.suffixes))

    def is_official(self, url: str) -> bool:
        p = parse_url(url)
        return p is not None and self.official_host(p[0])

    def all_official(self, urls: Iterable[str]) -> bool:
        """Không có URL nào thì ``True`` (``fuse`` chỉ hạ điểm khi mọi URL đều chính thức)."""
        return all(self.is_official(u) for u in urls)

    def brand_in(self, host: str) -> Brand | None:
        """Thương hiệu mà phần trước hậu tố công khai của host mượn tên (chứa, bằng đúng, hoặc gần giống token)."""
        r = _EXTRACT(host)
        labels = [x for x in [*r.subdomain.split("."), r.domain] if x] if r.suffix else host.split(".")[:-1]
        labels = [skeleton(_unicode_host(x)) for x in labels]
        parts = {p for x in labels for p in re.split(r"[-_]", x) if p}
        joined = [x.replace("-", "").replace("_", "") for x in labels]
        for b in self.brands:
            for t in b.tokens:
                if len(t) < MIN_CONTAIN:
                    if t in parts:
                        return b
                elif any(t in x for x in joined):
                    return b
                elif len(t) >= MIN_EDIT:
                    limit = 1 if len(t) <= 7 else 2
                    if any(_distance(t, x, limit) <= limit for x in [*parts, *joined]):
                        return b
        return None

    def host_signals(self, host: str, path: str = "") -> list[tuple[str, str]]:
        """``(mã, bằng chứng)`` cho một host (và đường dẫn). AI-24b gọi lại với host đích của link rút gọn."""
        host = host.lower()
        shown = host.replace(".", "[.]")
        out: list[tuple[str, str]] = []
        if path.lower().endswith(".apk"):
            out.append(("U_APK_LINK", f"{shown}{path}"))
        try:
            ipaddress.ip_address(host)
            is_ip = True
        except ValueError:
            is_ip = bool(_NUMERIC_HOST.fullmatch(host))
        if is_ip:
            return [*out, ("U_IP_URL", shown)]
        if self.official_host(host):
            return [*out, ("U_OFFICIAL", shown)]
        if not _EXTRACT(host).suffix:
            # Không có hậu tố công khai thật: chữ dính dấu chấm ("phạt.Hôm", "thong.bao") mà regex URL bắt thừa, không phải tên miền.
            return out
        if not host.isascii() or any(x.startswith("xn--") for x in host.split(".")):
            out.append(("U_PUNYCODE", shown))
        if b := self.brand_in(host):
            out.append(("U_TYPOSQUAT", f"{shown} (giống {b.name})"))
        return out

    def link_mismatch(self, shown_text: str, url: str) -> str | None:
        """Bằng chứng ``U_TEXT_LINK_MISMATCH`` cho một ``text_link`` (chữ hiển thị, URL thật), không lệch thì ``None``."""
        p = parse_url(url)
        if p is None:
            return None
        real = p[0].lower()
        ev = f"{shown_text.strip()[:40]} → {real.replace('.', '[.]')}"
        if (d := _DOMAIN_IN_TEXT.search(shown_text)) and (sp := parse_url(d.group())):
            return ev if etld1(sp[0].lower()) != etld1(real) else None
        if self.official_host(real):
            return None
        words = set(re.findall(r"[a-z0-9]+", skeleton(shown_text)))
        flat = "".join(sorted(words, key=shown_text.lower().find))
        squashed = skeleton(shown_text).replace(" ", "")
        for b in self.brands:
            if any(t in words if len(t) < MIN_CONTAIN else t in squashed or t in flat for t in b.tokens):
                return ev
        return None

    def __call__(self, urls: Iterable[str], links: Iterable[tuple[str, str]] = ()) -> list[m.Signal]:
        """Tín hiệu ``U_*`` cho mọi URL của tin; ``links`` là các ``(chữ hiển thị, URL thật)`` của ``text_link``. Mỗi mã một lần."""
        found: dict[str, str] = {}
        for u in urls:
            if p := parse_url(u):
                for code, ev in self.host_signals(*p):
                    found.setdefault(code, ev)
        for shown_text, url in links:
            if ev := self.link_mismatch(shown_text, url):
                found.setdefault("U_TEXT_LINK_MISMATCH", ev)
        return [m.Signal(code=c, weight=WEIGHTS[c], evidence=ev[:MAX_EVIDENCE]) for c, ev in found.items()]
