import ipaddress
import json
import logging
import threading
import time
from concurrent.futures import TimeoutError as FutureTimeout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from askfirst.pipeline import url_net as U

ip = ipaddress.ip_address


class Net:
    """DNS + web giả: `dns[host] = [ip…]`, `web[url] = (status, location)`; ghi lại mọi lần resolve/fetch."""

    def __init__(self, dns=None, web=None):
        self.dns, self.web, self.resolved, self.fetched = dns or {}, web or {}, [], []

    def resolve(self, host, port, timeout):
        self.resolved.append(host)
        if host not in self.dns:
            raise OSError("NXDOMAIN")
        return self.dns[host]

    def fetch(self, scheme, host, port, addr, target, timeout):
        url = f"{scheme}://{host}{target}"
        self.fetched.append((url, addr))
        return self.web.get(url, (404, None))


def net_with(**kw):
    n = Net(**kw)
    return n, U.UrlNet(resolver=n.resolve, fetch=n.fetch)


class KV:
    def __init__(self):
        self.d, self.ttl = {}, {}

    def get(self, key):
        return self.d.get(key)

    def put(self, key, value, ttl_s):
        self.d[key], self.ttl[key] = value, ttl_s


PUBLIC = ["67.199.248.10"]


# ---------- is_public / literal_ip ----------

@pytest.mark.parametrize("addr", [
    "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1",
    "255.255.255.255", "::1", "::", "fe80::1", "fc00::1", "fd00:ec2::254",
    "::ffff:127.0.0.1", "::ffff:169.254.169.254", "2002:7f00:1::1", "64:ff9b::a9fe:a9fe",
    "::127.0.0.1", "::7f00:1", "::ffff:0:7f00:1", "fec0::1", "192.0.0.9", "192.88.99.1",
])
def test_internal_and_metadata_ips_are_not_public(addr):
    assert not U.is_public(ip(addr))


@pytest.mark.parametrize("addr", ["8.8.8.8", "67.199.248.10", "2606:4700:4700::1111", "::ffff:8.8.8.8"])
def test_public_ips(addr):
    assert U.is_public(ip(addr))


@pytest.mark.parametrize("host", ["127.0.0.1", "2130706433", "0x7f.1", "127.1", "0177.0.0.1"])
def test_literal_ip_reads_the_short_ipv4_forms_resolvers_accept(host):
    assert U.literal_ip(host) == ip("127.0.0.1")


@pytest.mark.parametrize("host", ["bit.ly", "deadbeef.com", "", "1.2.3.4.5.6"])
def test_literal_ip_ignores_names(host):
    assert U.literal_ip(host) is None


# ---------- SSRF: chặn trước khi kết nối ----------

def test_shortener_resolving_to_loopback_is_blocked_without_connecting():
    n, net = net_with(dns={"bit.ly": ["127.0.0.1"]})
    out = net.expand("https://bit.ly/abc")
    assert (out.stop, out.blocked, out.final_host) == ("blocked_ip", True, "bit.ly")
    assert n.fetched == []


def test_any_internal_ip_in_the_dns_answer_blocks():
    n, net = net_with(dns={"bit.ly": [PUBLIC[0], "10.0.0.7"]})
    assert net.expand("https://bit.ly/abc").stop == "blocked_ip"
    assert n.fetched == []


@pytest.mark.parametrize("location", [
    "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
    "http://127.0.0.1:8080/admin",
    "http://2130706433/",
    "http://[::ffff:169.254.169.254]/",
    "http://[fd00:ec2::254]/latest/meta-data/",
])
def test_redirect_to_internal_ip_is_blocked(location):
    n, net = net_with(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/abc": (301, location)})
    out = net.expand("https://bit.ly/abc")
    assert (out.stop, out.hops) == ("blocked_ip", 1)
    assert [u for u, _ in n.fetched] == ["https://bit.ly/abc"]   # không chạm tới IP nội bộ


def test_redirect_to_another_shortener_that_resolves_internally_is_blocked():
    n, net = net_with(dns={"bit.ly": PUBLIC, "tinyurl.com": ["169.254.169.254"]},
                      web={"https://bit.ly/a": (302, "https://tinyurl.com/b")})
    out = net.expand("https://bit.ly/a")
    assert (out.stop, out.final_host, out.hops) == ("blocked_ip", "tinyurl.com", 1)
    assert [u for u, _ in n.fetched] == ["https://bit.ly/a"]


@pytest.mark.parametrize("location", ["file:///etc/passwd", "gopher://127.0.0.1:6379/_FLUSHALL", "ftp://bit.ly/x", "javascript:alert(1)"])
def test_redirect_to_other_schemes_is_blocked(location):
    n, net = net_with(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/a": (301, location)})
    assert net.expand("https://bit.ly/a").stop == "blocked_scheme"
    assert len(n.fetched) == 1


def test_shortener_on_a_non_web_port_is_blocked_before_dns():
    n, net = net_with(dns={"bit.ly": PUBLIC})
    assert net.expand("http://bit.ly:6379/x").stop == "blocked_port"
    assert n.resolved == [] and n.fetched == []


def test_connects_to_the_ip_that_was_checked():
    n, net = net_with(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/a": (200, None)})
    net.expand("https://bit.ly/a")
    assert n.fetched == [("https://bit.ly/a", PUBLIC[0])]


# ---------- mở link ----------

def test_expands_to_the_target_without_visiting_it():
    n, net = net_with(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/a": (301, "https://vcb-xacthuc.top/login?id=123")})
    out = net.expand("https://bit.ly/a")
    assert (out.stop, out.final_host, out.final_url, out.hops, out.blocked) == (
        "resolved", "vcb-xacthuc.top", "https://vcb-xacthuc.top/login?id=123", 1, False)
    assert n.resolved == ["bit.ly"]   # không resolve, không mở trang đích


def test_follows_a_chain_of_shorteners_and_relative_locations():
    n, net = net_with(dns={"bit.ly": PUBLIC, "tinyurl.com": PUBLIC},
                      web={"http://bit.ly/a": (301, "/b"), "http://bit.ly/b": (307, "https://tinyurl.com/c"),
                           "https://tinyurl.com/c": (302, "https://lua-dao.top/x")})
    out = net.expand("bit.ly/a")
    assert (out.stop, out.final_url, out.hops) == ("resolved", "https://lua-dao.top/x", 3)


def test_stops_after_max_redirects():
    web = {f"https://bit.ly/{i}": (301, f"https://bit.ly/{i + 1}") for i in range(10)}
    n, net = net_with(dns={"bit.ly": PUBLIC}, web=web)
    out = net.expand("https://bit.ly/0")
    assert (out.stop, out.hops) == ("too_many_redirects", U.MAX_REDIRECTS)
    assert len(n.fetched) == U.MAX_REDIRECTS + 1


@pytest.mark.parametrize("answer", [(429, None), (503, None), (405, None), (301, None)])
def test_error_answers_are_errors_and_not_cached(answer):
    kv = KV()
    n = Net(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/a": answer})
    assert U.UrlNet(kv, resolver=n.resolve, fetch=n.fetch).expand("https://bit.ly/a").stop == "error"
    assert kv.d == {}


def test_backslash_reads_like_a_browser():
    n, net = net_with(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/a": (301, "https:\\\\169.254.169.254\\x")})
    assert (net.expand("https://bit.ly/a").stop) == "blocked_ip"
    out = net.expand("http://evil.com\\@bit.ly/x")
    assert (out.stop, out.final_host) == ("not_shortener", "evil.com")


def test_cache_key_ignores_case_and_fragment():
    assert U.url_key("HTTPS://BIT.LY/a#x") == U.url_key("https://bit.ly/a") != U.url_key("https://bit.ly/A")


@pytest.mark.parametrize("url", ["\ud800", "http://bit.ly/\ud800", None, 42])
def test_odd_input_never_raises(url):
    assert U.UrlNet(resolver=lambda *_: PUBLIC, fetch=lambda *_: (200, None)).expand(url).stop in ("error", "bad_url")


def test_non_redirect_answer_ends_at_the_shortener():
    n, net = net_with(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/a": (200, None)})
    out = net.expand("https://bit.ly/a")
    assert (out.stop, out.final_host, out.hops) == ("no_redirect", "bit.ly", 0)


def test_non_shortener_is_never_contacted():
    n, net = net_with()
    out = net.expand("https://vcb-xacthuc.top/a")
    assert (out.stop, out.final_host) == ("not_shortener", "vcb-xacthuc.top")
    assert n.resolved == [] and n.fetched == []


def test_host_is_normalised():
    n, net = net_with(dns={"www.bit.ly": PUBLIC}, web={"http://www.bit.ly/a": (200, None)})
    assert net.expand("  HTTP://WWW.Bit.Ly./a ").stop == "no_redirect"
    assert n.resolved == ["www.bit.ly"]


def test_path_is_percent_encoded_before_sending():
    n, net = net_with(dns={"bit.ly": PUBLIC})
    net.expand("https://bit.ly/khuyến-mãi")
    assert n.fetched[0][0] == "https://bit.ly/khuy%E1%BA%BFn-m%C3%A3i"


@pytest.mark.parametrize("url", ["http://[::1", "http://" + "a" * 70 + ".bit.ly/", "https://bit.ly/" + "x" * 3000])
def test_bad_urls_do_not_raise(url):
    assert U.UrlNet(resolver=Net().resolve, fetch=Net().fetch).expand(url).stop == "bad_url"


# ---------- lỗi mạng và thời gian ----------

@pytest.mark.parametrize(("exc", "stop"), [(TimeoutError(), "timeout"), (ConnectionResetError(), "error"), (ValueError(), "error")])
def test_fetch_errors_never_raise(exc, stop):
    def fetch(*_):
        raise exc
    net = U.UrlNet(resolver=lambda *_: PUBLIC, fetch=fetch)
    assert net.expand("https://bit.ly/a").stop == stop


def test_dns_errors_never_raise():
    def slow(*_):
        raise FutureTimeout()
    assert U.UrlNet(resolver=slow).expand("https://bit.ly/a").stop == "timeout"
    assert U.UrlNet(resolver=lambda *_: []).expand("https://bit.ly/a").stop == "dns"
    assert U.UrlNet(resolver=lambda *_: ["not-an-ip"]).expand("https://bit.ly/a").stop == "dns"
    assert U.UrlNet(resolver=Net().resolve).expand("https://bit.ly/a").stop == "dns"


def test_whole_chain_shares_one_deadline():
    t = [0.0]
    timeouts = []

    def fetch(scheme, host, port, addr, target, timeout):
        timeouts.append(timeout)
        t[0] += 1.6
        return 301, "https://bit.ly/next"
    net = U.UrlNet(resolver=lambda *_: PUBLIC, fetch=fetch, clock=lambda: t[0])
    out = net.expand("https://bit.ly/a")
    assert out.stop == "timeout" and out.hops == 2
    assert timeouts == [U.HOP_TIMEOUT_S, pytest.approx(U.DEADLINE_S - 1.6)]


# ---------- cache URL# ----------

def test_cache_stores_only_host_and_reason_for_24h():
    kv = KV()
    n = Net(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/a": (301, "https://vcb-xacthuc.top/login?id=0912345678")})
    net = U.UrlNet(kv, resolver=n.resolve, fetch=n.fetch)
    first = net.expand("https://bit.ly/a")
    key = U.url_key("https://bit.ly/a")
    assert list(kv.d) == [key] and len(key) == 64 and kv.ttl[key] == 24 * 3600
    assert kv.d[key] == {"final_host": "vcb-xacthuc.top", "stop": "resolved", "hops": 1}

    again = net.expand("https://bit.ly/a")
    assert len(n.fetched) == 1   # lần hai không gọi mạng
    assert (again.final_host, again.stop, again.hops, again.cached, again.final_url) == ("vcb-xacthuc.top", "resolved", 1, True, None)
    assert not first.cached


def test_blocked_results_are_cached_but_transient_errors_are_not():
    kv = KV()
    U.UrlNet(kv, resolver=lambda *_: ["127.0.0.1"]).expand("https://bit.ly/a")
    U.UrlNet(kv, resolver=lambda *_: []).expand("https://bit.ly/b")
    assert [v["stop"] for v in kv.d.values()] == ["blocked_ip"]


def test_non_shortener_is_not_cached():
    kv = KV()
    U.UrlNet(kv).expand("https://vcb-xacthuc.top/")
    assert kv.d == {}


def test_broken_cache_does_not_break_expansion(caplog):
    class Broken:
        def get(self, key):
            raise RuntimeError("dynamo down")

        def put(self, key, value, ttl_s):
            raise RuntimeError("dynamo down")
    n = Net(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/a": (301, "https://x.top/")})
    with caplog.at_level(logging.WARNING, logger="askfirst.pipeline"):
        assert U.UrlNet(Broken(), resolver=n.resolve, fetch=n.fetch).expand("https://bit.ly/a").stop == "resolved"
    assert "dynamo down" not in caplog.text and "RuntimeError" in caplog.text


def test_logs_never_contain_the_url(caplog):
    n = Net(dns={"bit.ly": PUBLIC}, web={"https://bit.ly/secret-token": (301, "https://phish.top/0912345678")})
    with caplog.at_level(logging.INFO, logger="askfirst.pipeline"):
        U.UrlNet(resolver=n.resolve, fetch=n.fetch).expand("https://bit.ly/secret-token")
    events = [json.loads(r.getMessage()) for r in caplog.records]
    assert events and events[0]["event"] == "url_expand"
    assert "secret-token" not in caplog.text and "phish" not in caplog.text and "0912345678" not in caplog.text


# ---------- kết nối thật (http.client) ----------

def test_tls_connection_pins_ip_and_checks_cert_for_the_name(monkeypatch):
    seen = {}

    class Ctx:
        def wrap_socket(self, sock, server_hostname):
            seen["sni"] = server_hostname
            return sock
    monkeypatch.setattr(U.socket, "create_connection", lambda addr, timeout: seen.setdefault("addr", addr))
    U._PinnedHTTPS("bit.ly", 443, PUBLIC[0], 1.0, context=Ctx()).connect()
    assert seen == {"addr": (PUBLIC[0], 443), "sni": "bit.ly"}
    assert U._TLS.check_hostname and U._TLS.verify_mode.name == "CERT_REQUIRED"


@pytest.fixture
def server():
    """Shortener giả ở 127.0.0.1: `/s` chuyển sang metadata AWS."""
    hits = []

    class H(BaseHTTPRequestHandler):
        def do_HEAD(self):
            hits.append((self.command, self.path, self.headers["Host"]))
            self.send_response(301)
            self.send_header("Location", "http://169.254.169.254/latest/meta-data/")
            self.end_headers()

        def log_message(self, *a):
            pass
    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield srv.server_address[1], hits
    srv.shutdown()
    srv.server_close()


def test_real_request_to_loopback_is_never_sent(server):
    port, hits = server
    net = U.UrlNet(shorteners={"short.test"}, ports={port}, resolver=lambda *_: ["127.0.0.1"])
    assert net.expand(f"http://short.test:{port}/s").stop == "blocked_ip"
    assert hits == []


def test_real_head_request_and_redirect_to_metadata_is_blocked(server):
    port, hits = server
    net = U.UrlNet(shorteners={"short.test"}, ports={port}, resolver=lambda *_: ["127.0.0.1"],
                   allow_ip=lambda a: a == ip("127.0.0.1") or U.is_public(a))   # chỉ test này cho phép loopback
    out = net.expand(f"http://short.test:{port}/s?a=1")
    assert (out.stop, out.final_host, out.hops) == ("blocked_ip", "169.254.169.254", 1)
    assert hits == [("HEAD", "/s?a=1", f"short.test:{port}")]


def test_slow_drip_server_cannot_stretch_one_request():
    """Server gửi từng dòng header cách nhau 0,2 giây: tổng thời gian vẫn dừng ở `timeout`, không phải timeout × số dòng."""
    stop = threading.Event()

    class Drip(BaseHTTPRequestHandler):
        def do_HEAD(self):
            self.send_response(200)
            self.flush_headers()
            for i in range(50):
                if stop.is_set():
                    return
                self.wfile.write(f"X-{i}: y\r\n".encode())
                self.wfile.flush()
                stop.wait(0.2)

        def log_message(self, *a):
            pass
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Drip)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        t0 = time.monotonic()
        with pytest.raises(TimeoutError):
            U.head("http", "short.test", srv.server_address[1], "127.0.0.1", "/", 0.5)
        assert time.monotonic() - t0 < 1.5
    finally:
        stop.set()
        srv.shutdown()
        srv.server_close()
