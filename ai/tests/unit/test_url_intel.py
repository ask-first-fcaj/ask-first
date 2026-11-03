"""AI-24a: URL intel phần cơ bản, không gọi mạng."""

from __future__ import annotations

import pathlib

import pytest
import yaml

from askfirst.domain import models as m
from askfirst.pipeline import build_pipeline
from askfirst.pipeline.respond import KB_DIR
from askfirst.pipeline.url_intel import UrlIntel, parse_url

INTEL = UrlIntel.from_kb(KB_DIR)
CONFIG_DIR = pathlib.Path(__file__).resolve().parents[2] / "config"
ALLOWLIST = [d["domain"] for d in yaml.safe_load((KB_DIR / "allowlist.yaml").read_text(encoding="utf-8"))["domains"]]


def codes(*urls: str, links: list[tuple[str, str]] = ()) -> set[str]:
    return {s.code for s in INTEL(list(urls), links)}


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
    assert "U_TYPOSQUAT" in codes(url) and "U_OFFICIAL" not in codes(url)


@pytest.mark.parametrize("domain", ALLOWLIST)
def test_allowlisted_domains_are_official_and_not_flagged(domain: str) -> None:
    assert codes(f"https://{domain}/dang-nhap") == {"U_OFFICIAL"}
    assert codes(f"https://www.{domain}") == {"U_OFFICIAL"}


@pytest.mark.parametrize("url,expected", [
    ("https://abc.gov.vn/x", {"U_OFFICIAL"}),                                  # hậu tố .gov.vn
    ("https://dichvucong.gov.vn.abc.com", {"U_TYPOSQUAT"}),                    # chính thức nằm ở subdomain: không tính
    ("https://abcgov.vn", set()),                                              # không phải ranh giới nhãn ".gov.vn"
    ("https://mb-bank.top", {"U_TYPOSQUAT"}),                                  # token ngắn bằng đúng một phần
    ("https://acbs.com.vn", set()),                                            # token ngắn nằm trong từ khác: không tính
    ("https://agr1bank-xacthuc.com", {"U_TYPOSQUAT"}),                         # chữ số leet
    ("https://vietconbank.com", {"U_TYPOSQUAT"}),                              # cách 1 ký tự
    ("https://vietcоmbank.com", {"U_TYPOSQUAT", "U_PUNYCODE"}),                # "о" Cyrillic
    ("https://xn--vietcmbank-ijb.com", {"U_TYPOSQUAT", "U_PUNYCODE"}),
    ("http://192.168.1.1/login", {"U_IP_URL"}),
    ("http://2130706433/", {"U_IP_URL"}),                                      # IP viết dạng số
    ("https://vneid-hotro.top/VNeID.apk?v=2", {"U_APK_LINK", "U_TYPOSQUAT"}),
    ("hxxps://v-acb[.]com", {"U_TYPOSQUAT"}),                                  # URL đã defang
    ("https://thue-xe-tu-lai.vn", set()),                                      # từ thông dụng, không phải thương hiệu
    ("https://www.google.com", set()),
    ("phạt.Hôm", set()),                                                       # chữ dính dấu chấm, không phải tên miền
])
def test_host_signals(url: str, expected: set[str]) -> None:
    assert codes(url) == expected


@pytest.mark.parametrize("shown,url,mismatch", [
    ("dichvucong.gov.vn", "https://dichvucong-vn.com/tra-cuu", True),          # chữ là tên miền khác eTLD+1 của link thật
    ("https://www.vietcombank.com.vn", "https://vcb-xacthuc.top", True),
    ("Cổng Dịch vụ công", "https://dvc-online.xyz", True),                     # chữ nhắc thương hiệu, link không chính thức
    ("Vietcombank", "https://vietcombank.com.vn/khuyen-mai", False),
    ("dichvucong.gov.vn", "https://dichvucong.gov.vn/p/home", False),
    ("bấm vào đây", "https://example.com", False),
])
def test_text_link_mismatch(shown: str, url: str, mismatch: bool) -> None:
    assert ("U_TEXT_LINK_MISMATCH" in codes(links=[(shown, url)])) is mismatch


@pytest.mark.parametrize("url", ["", "://", "http://[::1", "https://a[b].com/x", "https://", "    "])
def test_odd_urls_do_not_raise(url: str) -> None:
    assert codes(url) <= {"U_OFFICIAL"}
    assert parse_url(url) is None or isinstance(parse_url(url)[0], str)


def test_one_signal_per_code_with_short_evidence() -> None:
    sig = INTEL(["https://dichvucong-vn.com", "https://vietcombank-priority.com/" + "x" * 300], [])
    assert [s.code for s in sig] == ["U_TYPOSQUAT"]
    assert sig[0].evidence.startswith("dichvucong-vn[.]com") and len(sig[0].evidence) <= 120


def test_real_pipeline_floors_text_link_to_a_fake_portal() -> None:
    """Chữ ghi dichvucong.gov.vn, link thật trỏ trang giả: sàn 80 dù LLM chưa có model (rules_only)."""
    p = build_pipeline("real", CONFIG_DIR, None, None)
    text = "Bạn có biên bản phạt nguội, tra cứu tại dichvucong.gov.vn"
    ent = m.EntityRef(kind="text_link", offset=text.index("dichvucong"), length=len("dichvucong.gov.vn"),
                      url="https://dichvucong-vn.com/tra-cuu")
    ctx = m.AnalyzeContext(check_id="01J0", user_id="tg:42", flags=m.Flags(), deadline_s=85)
    v = p.analyze(m.Bundle(texts=[m.TextPart(text=text, entities=[ent])]), ctx)
    assert {"U_TEXT_LINK_MISMATCH", "U_TYPOSQUAT"} <= {s.code for s in v.signals}
    assert v.score >= 80


def test_real_pipeline_handles_defanged_url() -> None:
    """``v-acb[.]com`` từng làm ``respond.is_official`` raise "Invalid IPv6 URL" (9 mẫu eval lỗi)."""
    p = build_pipeline("real", CONFIG_DIR, None, None)
    ctx = m.AnalyzeContext(check_id="01J0", user_id="tg:42", flags=m.Flags(), deadline_s=85)
    v = p.analyze(m.Bundle(texts=[m.TextPart(text="Vui lòng nhấp vào https://v-acb[.]com để hủy thanh toán")]), ctx)
    assert "U_TYPOSQUAT" in {s.code for s in v.signals}
