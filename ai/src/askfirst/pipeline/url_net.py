r"""Mở link rút gọn an toàn (AI-24b, ARCH §6.4 `U_SHORTENER`): chống SSRF, cache kết quả theo `URL#<sha256>` 24 giờ.

ARCH ghi tối đa 5 redirect, timeout 2 giây; issue #70 ghi 3 redirect, 3 giây. Ở đây lấy bản chặt hơn của từng số:

* Chỉ gửi ``HEAD`` tới host trong ``SHORTENERS``. ``Location`` trỏ sang host khác thì dừng, ghi lại URL đích và **không** mở
  trang đó: không báo cho kẻ gian là link đã bị mở, không đốt link dùng một lần.
* Đi theo tối đa ``MAX_REDIRECTS`` = 3 lần chuyển hướng; mỗi request ``HOP_TIMEOUT_S`` = 2 giây tính **tổng** (kết nối, TLS,
  đọc header; server nhỏ giọt từng dòng header không kéo dài được), cả chuỗi ``DEADLINE_S`` = 3 giây.
* ``\`` trong URL đọc như ``/`` (cách trình duyệt đọc): ``http://evil.com\@bit.ly/`` là ``evil.com``, không phải ``bit.ly``.
* Chỉ ``http``/``https``, cổng 80/443. Resolve DNS trước, **mọi** IP trả về phải là IP công khai (``is_public``), rồi kết nối
  thẳng tới IP đã kiểm (chặn DNS rebinding); TLS vẫn kiểm chứng chỉ theo tên miền. Không qua proxy, không đọc body.
* URL dạng IP (``169.254.169.254``, ``2130706433``, ``[::ffff:127.0.0.1]``) bị chặn ngay khi IP không công khai, kể cả URL
  chỉ nằm trong ``Location`` và không định mở.
* Không log URL (có thể chứa mã định danh nạn nhân). Cache chỉ lưu host đích, lý do dừng và số redirect; khoá là sha256 của URL.

``expand`` không bao giờ raise: lỗi mạng hay input lạ trả ``Expansion`` với ``stop`` tương ứng (``timeout``, ``dns``, ``error``,
``bad_url``). Chỉ trả lời 2xx không có redirect mới cache được (``no_redirect``); 4xx/5xx (vd 429 khi bị giới hạn) là ``error``.
Chưa có giới hạn tần suất theo người dùng: đổi query (``bit.ly/a?1``, ``?2``…) vẫn gọi mạng lại mỗi lần.
"""

from __future__ import annotations

import hashlib
import http.client
import ipaddress
import json
import logging
import re
import socket
import ssl
import time
from collections.abc import Callable, Collection
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeout
from dataclasses import dataclass
from urllib.parse import quote, urljoin, urlsplit

from askfirst.domain.models import KVCache

log = logging.getLogger("askfirst.pipeline")

# Danh sách khởi điểm; chuyển sang kb/ cùng allowlist khi AI-24a xong.
SHORTENERS = frozenset({
    "bit.ly", "bitly.com", "tinyurl.com", "t.ly", "is.gd", "v.gd", "cutt.ly", "rb.gy", "s.id", "shorturl.at",
    "ow.ly", "tiny.cc", "rebrand.ly", "buff.ly", "t.co", "goo.gl", "lnkd.in", "bit.do", "qrco.de",
})
MAX_REDIRECTS = 3
HOP_TIMEOUT_S = 2.0
DEADLINE_S = 3.0
CACHE_TTL_S = 24 * 3600
PORTS = frozenset({80, 443})
MAX_URL_LEN = 2048
REDIRECT_CODES = frozenset({301, 302, 303, 307, 308})
USER_AGENT = "AskFirstLinkCheck/1.0"
# Kết quả còn đúng khi hỏi lại trong ngày; lỗi tạm (timeout, dns, error) không cache.
CACHEABLE = frozenset({"resolved", "no_redirect", "too_many_redirects", "blocked_ip", "blocked_scheme", "blocked_port"})

_NAT64 = ipaddress.ip_network("64:ff9b::/96")
# Dải mà `is_global` (khác nhau giữa các bản 3.12.x) có thể cho qua: IPv4-compatible ``::a.b.c.d``, SIIT ``::ffff:0:a.b.c.d``,
# site-local cũ, IETF protocol assignments, relay 6to4. Chặn cứng, không phụ thuộc bản Python.
_DENY = tuple(ipaddress.ip_network(n) for n in ("::/96", "::ffff:0:0:0/96", "fec0::/10", "192.0.0.0/24", "192.88.99.0/24"))
_HAS_SCHEME = re.compile(r"^[a-z][a-z0-9+.-]*://", re.IGNORECASE)
_TLS = ssl.create_default_context()
# DNS và HEAD chạy trong thread để chặn được tổng thời gian; thread quá hạn tự kết thúc theo timeout của socket.
_POOL = ThreadPoolExecutor(max_workers=16, thread_name_prefix="askfirst-urlnet")


@dataclass(frozen=True)
class Expansion:
    """Kết quả mở một URL. ``final_url`` chỉ có trong RAM (None khi lấy từ cache): KHÔNG log."""
    final_host: str
    # not_shortener | resolved | no_redirect | too_many_redirects | blocked_ip | blocked_scheme | blocked_port
    # | timeout | dns | error | bad_url
    stop: str
    hops: int = 0                  # số lần chuyển hướng đã đi theo
    final_url: str | None = None
    cached: bool = False

    @property
    def blocked(self) -> bool:
        return self.stop.startswith("blocked_")

    def to_cache(self) -> dict:
        return {"final_host": self.final_host, "stop": self.stop, "hops": self.hops}


def is_public(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """IP được phép kết nối: định tuyến công khai, không multicast. IPv6 nhúng IPv4 (mapped, 6to4, NAT64) xét cả IPv4 bên trong."""
    if any(ip in n for n in _DENY if n.version == ip.version):
        return False
    if isinstance(ip, ipaddress.IPv6Address):
        inner = ip.ipv4_mapped or ip.sixtofour
        if inner is None and ip in _NAT64:
            inner = ipaddress.IPv4Address(int(ip) & 0xFFFFFFFF)
        if inner is not None and not is_public(inner):
            return False
    return ip.is_global and not ip.is_multicast


def literal_ip(host: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    """Host viết bằng IP, kể cả dạng IPv4 rút gọn mà resolver chấp nhận (``127.1``, ``0x7f.1``, ``2130706433``)."""
    try:
        return ipaddress.ip_address(host)
    except ValueError:
        pass
    if host and all(c in "0123456789abcdefx." for c in host) and any(c.isdigit() for c in host):
        try:
            return ipaddress.IPv4Address(socket.inet_aton(host))
        except OSError:
            return None
    return None


def normalize(url: str) -> str:
    """Thêm ``http://`` cho URL trần (``bit.ly/abc`` lấy từ text), bỏ khoảng trắng hai đầu."""
    url = url.strip().replace("\\", "/")
    return url if _HAS_SCHEME.match(url) else "http://" + url


def url_key(url: str) -> str:
    """Khoá cache `URL#<key>`: sha256 của URL đã chuẩn hoá (scheme, host viết thường, bỏ ``#fragment``)."""
    url = normalize(url)
    try:
        p = urlsplit(url)
        url = p._replace(scheme=p.scheme.lower(), netloc=p.netloc.lower(), fragment="").geturl()
    except ValueError:
        pass
    return hashlib.sha256(url.encode("utf-8", "surrogatepass")).hexdigest()


def resolve(host: str, port: int, timeout: float) -> list[str]:
    """Mọi IP của ``host``. ``getaddrinfo`` không có timeout nên chạy trong thread; quá hạn thì ``FutureTimeout``."""
    fut = _POOL.submit(socket.getaddrinfo, host, port, type=socket.SOCK_STREAM)
    try:
        infos = fut.result(timeout=timeout)
    except FutureTimeout:
        fut.cancel()
        raise
    return list(dict.fromkeys(i[4][0] for i in infos))


class _PinnedHTTP(http.client.HTTPConnection):
    """Kết nối tới IP đã kiểm, header ``Host`` vẫn là tên miền."""

    def __init__(self, host: str, port: int, ip: str, timeout: float) -> None:
        super().__init__(host, port, timeout=timeout)
        self.ip = ip

    def connect(self) -> None:
        self.sock = socket.create_connection((self.ip, self.port), self.timeout)


class _PinnedHTTPS(http.client.HTTPSConnection):
    """Như ``_PinnedHTTP``; SNI và kiểm chứng chỉ theo tên miền, không theo IP."""

    def __init__(self, host: str, port: int, ip: str, timeout: float, context: ssl.SSLContext = _TLS) -> None:
        super().__init__(host, port, timeout=timeout, context=context)
        self.ip = ip

    def connect(self) -> None:
        sock = socket.create_connection((self.ip, self.port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


def head(scheme: str, host: str, port: int, ip: str, target: str, timeout: float) -> tuple[int, str | None]:
    """Một request ``HEAD`` tới ``ip``; trả (status, Location). Không đọc body, không tự đi theo redirect.

    ``timeout`` là tổng cho cả request; quá hạn thì ngắt socket và raise ``TimeoutError``."""
    cls = _PinnedHTTPS if scheme == "https" else _PinnedHTTP
    conn = cls(host, port, ip, timeout)

    def run() -> tuple[int, str | None]:
        conn.request("HEAD", target, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
        resp = conn.getresponse()
        return resp.status, resp.getheader("Location")

    fut = _POOL.submit(run)
    try:
        return fut.result(timeout=timeout)
    except FutureTimeout:
        fut.cancel()
        try:
            if conn.sock is not None:
                conn.sock.shutdown(socket.SHUT_RDWR)   # recv đang chờ ở thread kia trả về ngay
        except OSError:
            pass
        raise TimeoutError("url_net: request quá hạn") from None
    finally:
        conn.close()


Resolver = Callable[[str, int, float], list[str]]
Fetch = Callable[[str, str, int, str, str, float], tuple[int, str | None]]


class UrlNet:
    """``expand(url) -> Expansion``. Mọi phần gọi mạng truyền vào được để test không cần mạng thật."""

    def __init__(
        self,
        kv: KVCache | None = None,
        *,
        shorteners: Collection[str] = SHORTENERS,
        ports: Collection[int] = PORTS,
        allow_ip: Callable[[ipaddress.IPv4Address | ipaddress.IPv6Address], bool] = is_public,
        resolver: Resolver = resolve,
        fetch: Fetch = head,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.kv, self.shorteners, self.ports, self.allow_ip = kv, frozenset(shorteners), frozenset(ports), allow_ip
        self.resolver, self.fetch, self.clock = resolver, fetch, clock

    def is_shortener(self, host: str) -> bool:
        return host in self.shorteners or host.removeprefix("www.") in self.shorteners

    def expand(self, url: str) -> Expansion:
        started = self.clock()
        try:
            key = url_key(url)
            hit = self._cache_get(key)
            if hit is not None:
                return hit
            out = self._walk(normalize(url), started)
        except Exception as e:   # input lạ (vd surrogate lẻ, không phải str): không được làm hỏng phân tích
            log.warning(json.dumps({"event": "url_expand_failed", "error": type(e).__name__}))
            return Expansion("", "error")
        if out.stop in CACHEABLE:
            self._cache_put(key, out)
        log.info(json.dumps({"event": "url_expand", "stop": out.stop, "hops": out.hops,
                             "ms": round((self.clock() - started) * 1000)}))
        return out

    def _walk(self, cur: str, started: float) -> Expansion:
        hops = 0
        while True:
            if len(cur) > MAX_URL_LEN:
                return Expansion("", "bad_url", hops)
            try:
                parts = urlsplit(cur)
                host = (parts.hostname or "").rstrip(".").encode("idna").decode("ascii")
                scheme, port = parts.scheme.lower(), parts.port
            except (ValueError, UnicodeError):
                return Expansion("", "bad_url", hops)
            if scheme not in ("http", "https"):
                return Expansion(host, "blocked_scheme", hops, cur)
            if not host:
                return Expansion("", "bad_url", hops)
            ip = literal_ip(host)
            if ip is not None and not self.allow_ip(ip):
                return Expansion(host, "blocked_ip", hops, cur)
            if not self.is_shortener(host):
                return Expansion(host, "resolved" if hops else "not_shortener", hops, cur)
            port = port or (443 if scheme == "https" else 80)
            if port not in self.ports:
                return Expansion(host, "blocked_port", hops, cur)

            remaining = DEADLINE_S - (self.clock() - started)
            if remaining <= 0:
                return Expansion(host, "timeout", hops, cur)
            try:
                ips = self.resolver(host, port, min(HOP_TIMEOUT_S, remaining))
            except FutureTimeout:
                return Expansion(host, "timeout", hops, cur)
            except OSError:
                return Expansion(host, "dns", hops, cur)
            if not ips:
                return Expansion(host, "dns", hops, cur)
            try:
                addrs = [ipaddress.ip_address(a.split("%", 1)[0]) for a in ips]
            except ValueError:
                return Expansion(host, "dns", hops, cur)
            if not all(self.allow_ip(a) for a in addrs):
                return Expansion(host, "blocked_ip", hops, cur)

            remaining = DEADLINE_S - (self.clock() - started)
            if remaining <= 0:
                return Expansion(host, "timeout", hops, cur)
            target = quote((parts.path or "/") + (f"?{parts.query}" if parts.query else ""), safe="/%?=&:@!$'()*+,;~")
            try:
                status, location = self.fetch(scheme, host, port, str(addrs[0]), target, min(HOP_TIMEOUT_S, remaining))
            except TimeoutError:
                return Expansion(host, "timeout", hops, cur)
            except (OSError, ValueError, http.client.HTTPException):
                return Expansion(host, "error", hops, cur)
            if status not in REDIRECT_CODES or not location:
                return Expansion(host, "no_redirect" if 200 <= status < 300 else "error", hops, cur)
            if hops == MAX_REDIRECTS:
                return Expansion(host, "too_many_redirects", hops, cur)
            hops += 1
            try:
                cur = urljoin(cur, location.strip().replace("\\", "/"))
            except ValueError:
                return Expansion("", "bad_url", hops)

    def _cache_get(self, key: str) -> Expansion | None:
        if self.kv is None:
            return None
        try:
            v = self.kv.get(key)
            return Expansion(v["final_host"], v["stop"], int(v["hops"]), cached=True) if v and "stop" in v else None
        except Exception as e:   # cache hỏng không được làm hỏng phân tích
            log.warning(json.dumps({"event": "url_cache_failed", "op": "get", "error": type(e).__name__}))
            return None

    def _cache_put(self, key: str, out: Expansion) -> None:
        if self.kv is None:
            return
        try:
            self.kv.put(key, out.to_cache(), CACHE_TTL_S)
        except Exception as e:
            log.warning(json.dumps({"event": "url_cache_failed", "op": "put", "error": type(e).__name__}))
