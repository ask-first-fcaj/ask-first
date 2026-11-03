"""AI-24a: URL intel phần cơ bản, không gọi mạng."""

from __future__ import annotations

import pathlib

import pytest
import yaml

from askfirst.domain import models as m
from askfirst.pipeline import build_pipeline, fuse
from askfirst.pipeline.respond import KB_DIR, is_official, signal_texts, url_intel
from askfirst.pipeline.url_intel import WEIGHTS, parse_url

INTEL = url_intel()
CONFIG_DIR = pathlib.Path(__file__).resolve().parents[2] / "config"
ALLOWLIST = [d["domain"] for d in yaml.safe_load((KB_DIR / "allowlist.yaml").read_text(encoding="utf-8"))["domains"]]
CTX = m.AnalyzeContext(check_id="01J0", user_id="tg:42", flags=m.Flags(), deadline_s=85)


def signals(*urls: str, links: list[tuple[str, str]] = ()) -> list[m.Signal]:
    return INTEL(list(urls), links)


def codes(*urls: str, links: list[tuple[str, str]] = ()) -> set[str]:
    return {s.code for s in signals(*urls, links=links)}


# Tên miền giả nêu nguyên văn trong bài cảnh báo chính thức (máy đọc trang ngày 2026-11-03).
OFFICIAL_WARNINGS = [
    ("dichvucong-vn.com", "congan.nghean.gov.vn, congan.angiang.gov.vn, nhandan.vn"),
    ("dichvuconggov.vn", "nhandan.vn/can-trong-voi-tin-nhan-gia-mao-cong-dich-vu-cong-post964831.html"),
    ("dichvuconghanhchinh.com", "congan.ninhbinh.gov.vn (22/06/2026)"),
    ("https://v8.dichvuconggolvn.cc", "congan.camau.gov.vn (CAX Lương Thế Trân, 06/09/2026)"),
    ("vndichvucong-gov.com", "congan.angiang.gov.vn (12/05/2026)"),
    ("dichvuconghs.com", "congan.haiphong.gov.vn"),
    ("bcavneid.com", "bocongan.gov.vn (Phòng ANM CATP Hải Phòng)"),
    ("techcombank.cfd/com.vn", "congan.gialai.gov.vn (10/07/2026)"),
    ("tcb.lat/com.vn", "congan.gialai.gov.vn (10/07/2026)"),
    ("https://vietcombank-priority.com", "congan.ninhbinh.gov.vn (06/04/2026)"),
]


@pytest.mark.parametrize("url,source", OFFICIAL_WARNINGS)
def test_fake_domains_from_official_warnings_are_flagged(url: str, source: str) -> None:
    assert codes(url) == {"U_TYPOSQUAT"}


@pytest.mark.parametrize("domain", ALLOWLIST)
def test_allowlisted_domains_are_official_and_not_flagged(domain: str) -> None:
    assert codes(f"https://{domain}/dang-nhap") == {"U_OFFICIAL"}
    assert codes(f"https://www.{domain}") == {"U_OFFICIAL"}


@pytest.mark.parametrize("url,expected", [
    ("https://abc.gov.vn/x", {"U_OFFICIAL"}),                                  # hậu tố .gov.vn
    ("https://abcgov.vn", set()),                                              # không phải ranh giới nhãn ".gov.vn"
    # có dấu hiệu giả: sàn 80
    ("https://dichvucong.gov.vn.abc.com", {"U_TYPOSQUAT"}),                    # tên chính thức làm subdomain của tên miền lạ
    ("https://vietcombank.vn-yys.club", {"U_TYPOSQUAT"}),
    ("https://mb-bank.top", {"U_TYPOSQUAT"}),                                  # ghép gạch nối
    ("https://agr1bank-xacthuc.com", {"U_TYPOSQUAT"}),                         # chữ số leet
    ("https://vietconbank.com", {"U_TYPOSQUAT"}),                              # cách 1 ký tự
    ("https://vietcоmbank.com", {"U_TYPOSQUAT", "U_PUNYCODE"}),                # "о" Cyrillic
    ("https://xn--vietcmbank-ijb.com", {"U_TYPOSQUAT", "U_PUNYCODE"}),
    ("https://vietcombank.com.vn@evil.com/login", {"U_TYPOSQUAT"}),            # tên miền thật chèn trước "@"
    ("hxxps://v-acb[.]com", {"U_TYPOSQUAT"}),                                  # URL đã defang
    # tên miền thật của thương hiệu chưa có trong allowlist: chỉ cộng điểm, không nâng sàn
    ("https://my.viettel.vn", {"U_BRAND_UNVERIFIED"}),
    ("https://shopee.com/order/123", {"U_BRAND_UNVERIFIED"}),
    ("https://shopeefood.vn", {"U_BRAND_UNVERIFIED"}),
    ("https://tpbank.com.vn", {"U_BRAND_UNVERIFIED"}),
    # khác
    ("http://192.168.1.1/login", {"U_IP_URL"}),
    ("http://2130706433/", {"U_IP_URL"}),                                      # IP viết dạng số
    ("https://vneid-hotro.top/VNeID.apk?v=2", {"U_APK_LINK", "U_TYPOSQUAT"}),
    ("https://acbs.com.vn", set()),                                            # token ngắn nằm trong từ khác: không tính
    ("https://thue-xe-tu-lai.vn", set()),                                      # từ thông dụng, không phải thương hiệu
    ("https://www.google.com", set()),
    ("phạt.Hôm", set()),                                                       # chữ dính dấu chấm, không phải tên miền
])
def test_host_signals(url: str, expected: set[str]) -> None:
    assert codes(url) == expected


def test_exact_brand_name_is_not_read_as_a_typo_of_another_brand() -> None:
    [s] = signals("https://tpbank.com.vn")
    assert "TPBank" in s.evidence and "VPBank" not in s.evidence


def test_trailing_dot_host_is_the_same_host() -> None:
    """AI-24b gọi thẳng ``host_signals`` với host sau redirect, có thể còn dấu chấm cuối."""
    assert INTEL.host_signals("hdbank.com.vn.") == INTEL.host_signals("hdbank.com.vn") == [("U_OFFICIAL", "hdbank[.]com[.]vn")]


@pytest.mark.parametrize("shown,url,expected", [
    ("dichvucong.gov.vn", "https://dichvucong-vn.com/tra-cuu", {"U_TEXT_LINK_MISMATCH"}),   # chữ là tên miền chính thức khác
    ("https://www.vietcombank.com.vn", "https://vcb-xacthuc.top", {"U_TEXT_LINK_MISMATCH"}),
    ("vietcombank-uudai.com", "https://abc.xyz", {"U_TEXT_LINK_MISMATCH"}),                # chữ là tên miền mang tên thương hiệu
    ("Cổng Dịch vụ công", "https://dvc-online.xyz", {"U_BRAND_UNVERIFIED"}),             # chữ nhắc thương hiệu: chỉ cộng điểm
    ("Cổng dịch vụ công", "https://chinhphu.vn", {"U_BRAND_UNVERIFIED"}),
    ("VNeID", "https://play.google.com/store/apps/details?id=vn.gov.vneid", set()),         # kho ứng dụng chính thức
    ("Vietcombank", "https://vietcombank.com.vn/khuyen-mai", set()),
    ("dichvucong.gov.vn", "https://dichvucong.gov.vn/p/home", set()),
    ("Tp.HCM", "https://example.com", set()),                                              # không phải tên miền
    ("Dr.Smith", "https://example.com", set()),
    ("bấm vào đây", "https://example.com", set()),
])
def test_text_link(shown: str, url: str, expected: set[str]) -> None:
    assert codes(links=[(shown, url)]) == expected


def test_evidence_has_no_unmasked_user_text_or_path() -> None:
    """Respond hiện ``evidence`` cho người dùng và người thân: không được có số điện thoại hay đường dẫn gốc."""
    apk = signals("https://abc.xyz/0912345678/hoso.apk")
    link = signals(links=[("Gọi 0912345678 hoặc vào vietcombank.com.vn", "https://vcb-x.top")])
    user = signals("https://0912345678.vietcombank@evil.com")
    for s in [*apk, *link, *user]:
        assert "0912345678" not in s.evidence and "hoso" not in s.evidence, s


def test_weak_brand_signal_does_not_lift_the_floor() -> None:
    weak = signals("https://shopee.com")
    strong = signals("https://shopee-hoantien.top")
    assert fuse.rule_score(weak) == WEIGHTS["U_BRAND_UNVERIFIED"] < fuse.FLOOR_HARD <= fuse.rule_score(strong)


def test_every_scoring_code_has_explanation_text() -> None:
    assert [c for c, w in WEIGHTS.items() if w > 0 and c not in signal_texts()] == []


@pytest.mark.parametrize("url", ["", "://", "http://[::1", "https://a[b].com/x", "https://", "    "])
def test_odd_urls_do_not_raise(url: str) -> None:
    assert codes(url) <= {"U_OFFICIAL"}
    assert not is_official(url)
    assert parse_url(url) is None or isinstance(parse_url(url).host, str)


def test_one_signal_per_code_with_short_evidence() -> None:
    sig = signals("https://dichvucong-vn.com", "https://vietcombank-priority.com/" + "x" * 300)
    assert [s.code for s in sig] == ["U_TYPOSQUAT"]
    assert sig[0].evidence.startswith("dichvucong-vn[.]com") and len(sig[0].evidence) <= 120


def test_is_official_shares_the_url_intel_allowlist() -> None:
    assert is_official("https://csgt.bocongan.gov.vn/x") and is_official("https://abc.gov.vn")
    assert not is_official("http://dichvucong.gov.vn")             # hạ điểm và <a href> vẫn đòi https
    assert not is_official("https://dichvucong.gov.vn.abc.com")


def test_real_pipeline_floors_text_link_to_a_fake_portal() -> None:
    """Chữ ghi dichvucong.gov.vn, link thật trỏ trang giả: sàn 80 dù LLM chưa có model (rules_only)."""
    p = build_pipeline("real", CONFIG_DIR, None, None)
    text = "Bạn có biên bản phạt nguội, tra cứu tại dichvucong.gov.vn"
    ent = m.EntityRef(kind="text_link", offset=text.index("dichvucong"), length=len("dichvucong.gov.vn"),
                      url="https://dichvucong-vn.com/tra-cuu")
    v = p.analyze(m.Bundle(texts=[m.TextPart(text=text, entities=[ent])]), CTX)
    assert {"U_TEXT_LINK_MISMATCH", "U_TYPOSQUAT"} <= {s.code for s in v.signals}
    assert v.score >= 80


def test_real_pipeline_handles_defanged_url() -> None:
    """``v-acb[.]com`` từng làm ``respond.is_official`` raise "Invalid IPv6 URL" (9 mẫu eval lỗi)."""
    p = build_pipeline("real", CONFIG_DIR, None, None)
    v = p.analyze(m.Bundle(texts=[m.TextPart(text="Vui lòng nhấp vào https://v-acb[.]com để hủy thanh toán")]), CTX)
    assert "U_TYPOSQUAT" in {s.code for s in v.signals}
