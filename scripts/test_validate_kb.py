"""Test scripts/validate_kb.py. Chạy từ gốc repo: python -m pytest scripts/test_validate_kb.py"""
import copy
import datetime as dt
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import validate_kb as v  # noqa: E402

TODAY = dt.date.today().isoformat()
OLD = (dt.date.today() - dt.timedelta(days=31)).isoformat()

ALLOW = {
    "domains": [{"domain": "vietcombank.com.vn", "owner": "Vietcombank", "source_url": "https://www.vietcombank.com.vn/", "verified_at": TODAY}],
    "suffixes": ["gov.vn"],
}
SCENARIO = {
    "code": "N1.4", "group": "N1", "title": "Phạt nguội giả", "description": "Mô tả.",
    "example_phrases": [], "red_flags": ["đòi nộp phạt qua link"], "advice": ["Tự tra cứu trên cổng chính thức."],
    "sources": [{"url": "https://bocongan.gov.vn/x", "publisher": "Bộ Công an"}], "verified_at": TODAY,
}
HOTLINE = {"id": "vcb", "name": "Vietcombank", "kind": "phone", "value": "1900545413",
           "source_url": "https://www.vietcombank.com.vn/lien-he", "verified_at": TODAY}
BRAND = {"name": "Vietcombank", "tokens": ["vietcombank", "vcb"], "official_domains": ["vietcombank.com.vn"]}
STEP = {"id": "s1", "phase": "0-5", "action": "Gọi ngân hàng", "detail": "Nói bị lừa.", "hotline_ref": "vcb",
        "sources": [{"url": "https://bocongan.gov.vn/x", "publisher": "Bộ Công an"}], "verified_at": TODAY}
CHECKLIST = {"id": "bilua_30p", "title": "30 phút đầu", "steps": [STEP]}


def with_(base, **kw):
    d = copy.deepcopy(base)
    d.update(kw)
    return d


def has(errors, text):
    return any(text in m for m in errors)


# --- scenarios ---
def test_scenario_ok():
    assert v.v_scenarios([SCENARIO]) == []


def test_scenario_unknown_code_rejected():
    assert has(v.v_scenarios([with_(SCENARIO, code="N3.9", group="N3")]), "không có trong bảng mã")


def test_scenario_group_mismatch():
    assert has(v.v_scenarios([with_(SCENARIO, group="N2")]), "không khớp group")


def test_scenario_duplicate_code():
    assert has(v.v_scenarios([SCENARIO, SCENARIO]), "trùng code")


def test_scenario_missing_field_and_empty_advice():
    s = copy.deepcopy(SCENARIO)
    del s["title"]
    s["advice"] = []
    errs = v.v_scenarios([s])
    assert has(errs, "thiếu trường 'title'") and has(errs, "'advice' không được rỗng")


def test_scenario_non_dict_item_reports_instead_of_crashing():
    assert has(v.v_scenarios([SCENARIO, "N1.4"]), "phải là một mục dạng khóa")


def test_scenario_advice_phone_with_spaces_rejected():
    for text in ("Gọi 1900 545 415 ngay", "Gọi 1900.545.415", "Gọi 1900545415", "Gọi 0912 345 678", "Gọi 113"):
        assert has(v.v_scenarios([with_(SCENARIO, advice=[text])]), "không được ghi số điện thoại"), text


def test_scenario_advice_other_numbers_allowed():
    assert v.v_scenarios([with_(SCENARIO, advice=["Trong 30 phút đầu, chuyển 2 triệu về 1 tài khoản."])]) == []


def test_scenario_missing_verified_and_future_date():
    assert has(v.v_scenarios([with_(SCENARIO, verified_at=None)]), "thiếu trường 'verified_at'")
    future = (dt.date.today() + dt.timedelta(days=1)).isoformat()
    assert has(v.v_scenarios([with_(SCENARIO, verified_at=future)]), "ở tương lai")


def test_source_rules():
    assert has(v.v_scenarios([with_(SCENARIO, sources=[{"url": "http://x.vn", "publisher": "X"}])]), "https://")
    assert has(v.v_scenarios([with_(SCENARIO, sources=[{"url": "https://x.vn"}])]), "'url' và 'publisher'")
    assert has(v.v_scenarios([with_(SCENARIO, sources=[])]), "thiếu 'sources'")
    assert has(v.v_scenarios([with_(SCENARIO, sources=[{"url": "docs/khong-co-file.md", "publisher": "Team"}])]), "không tồn tại")


def test_source_internal_doc_that_exists_ok():
    src = [{"url": "docs/research-concept.md", "publisher": "Team"}]
    assert v.v_scenarios([with_(SCENARIO, sources=src)]) == []


# --- allowlist ---
def test_allowlist_ok():
    assert v.v_allowlist(ALLOW) == []


def test_allowlist_bad_domain_and_duplicate():
    bad = copy.deepcopy(ALLOW)
    bad["domains"].append(with_(bad["domains"][0], domain="https://Vietcombank.com.vn/x"))
    assert has(v.v_allowlist(bad), "tên miền phải chữ thường")
    dup = copy.deepcopy(ALLOW)
    dup["domains"].append(dup["domains"][0])
    assert has(v.v_allowlist(dup), "trùng tên miền")


def test_allowlist_suffixes_must_be_list():
    assert has(v.v_allowlist(with_(ALLOW, suffixes="gov.vn")), "'suffixes' phải là danh sách")


# --- brands ---
def test_brand_ok():
    assert v.v_brands([BRAND], ALLOW) == []


def test_brand_domain_not_in_allowlist_and_bad_token():
    assert has(v.v_brands([with_(BRAND, official_domains=["evil.com"])], ALLOW), "chưa có trong allowlist")
    assert has(v.v_brands([with_(BRAND, tokens=["Vietcombank"])], ALLOW), "token")


# --- hotlines ---
def test_hotline_ok():
    assert v.v_hotlines([HOTLINE], ALLOW) == []


def test_hotline_gov_vn_subdomain_allowed():
    assert v.v_hotlines([with_(HOTLINE, source_url="https://bocongan.gov.vn/bai-viet/x")], ALLOW) == []


def test_hotline_bad_kind_and_value():
    errs = v.v_hotlines([with_(HOTLINE, kind="zzz")], ALLOW)
    assert has(errs, "kind 'zzz'")
    assert has(v.v_hotlines([with_(HOTLINE, value="abc")], ALLOW), "chỉ gồm chữ số")
    assert has(v.v_hotlines([with_(HOTLINE, value="1900 545 413")], ALLOW), "chỉ gồm chữ số")


def test_hotline_app_value_free_text():
    assert v.v_hotlines([with_(HOTLINE, kind="app", value="VNeID > Phản ánh")], ALLOW) == []


def test_hotline_source_must_be_official_host():
    assert has(v.v_hotlines([with_(HOTLINE, source_url="https://facebook.com/fake")], ALLOW), "không thuộc allowlist")
    # tên miền giống nhưng không phải subdomain thật
    assert has(v.v_hotlines([with_(HOTLINE, source_url="https://evilvietcombank.com.vn/")], ALLOW), "không thuộc allowlist")
    assert has(v.v_hotlines([with_(HOTLINE, source_url="https://vietcombank.com.vn.evil.com/")], ALLOW), "không thuộc allowlist")
    assert has(v.v_hotlines([with_(HOTLINE, source_url="http://www.vietcombank.com.vn/")], ALLOW), "https://")


def test_hotline_stale_or_unverified():
    assert has(v.v_hotlines([with_(HOTLINE, verified_at=OLD)], ALLOW), "quá cũ")
    assert has(v.v_hotlines([with_(HOTLINE, verified_at=None)], ALLOW), "để trống")
    assert has(v.v_hotlines([with_(HOTLINE, verified_at="30/10/2026")], ALLOW), "YYYY-MM-DD")


def test_hotline_duplicate_id_and_non_dict():
    assert has(v.v_hotlines([HOTLINE, HOTLINE], ALLOW), "trùng id")
    assert has(v.v_hotlines([HOTLINE, 5], ALLOW), "phải là một mục dạng khóa")


# --- checklist ---
def test_checklist_ok():
    assert v.v_checklist([CHECKLIST], {"vcb"}) == []


def test_checklist_unknown_hotline_ref_but_bank_of_victim_ok():
    bad = with_(CHECKLIST, steps=[with_(STEP, hotline_ref="khong_co")])
    assert has(v.v_checklist([bad], {"vcb"}), "không có trong hotlines.yaml")
    ok = with_(CHECKLIST, steps=[with_(STEP, hotline_ref="bank_of_victim")])
    assert v.v_checklist([ok], {"vcb"}) == []


def test_checklist_phone_number_in_text_rejected():
    bad = with_(CHECKLIST, steps=[with_(STEP, detail="Gọi 1900 545 413 để khóa thẻ.")])
    assert has(v.v_checklist([bad], {"vcb"}), "không được ghi số điện thoại")


def test_checklist_steps_not_list_or_non_dict_step():
    assert has(v.v_checklist([with_(CHECKLIST, steps="abc")], set()), "'steps' phải là danh sách")
    assert has(v.v_checklist([with_(CHECKLIST, steps=[STEP, "x"])], {"vcb"}), "phải là một mục dạng khóa")


def test_hotline_age_check_can_be_skipped_but_empty_date_still_fails():
    assert v.v_hotlines([with_(HOTLINE, verified_at=OLD)], ALLOW, None) == []
    assert has(v.v_hotlines([with_(HOTLINE, verified_at=None)], ALLOW, None), "để trống")
