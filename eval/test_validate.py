"""Test eval/validate.py. Chạy từ gốc repo: python -m pytest eval/test_validate.py"""
import copy
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import validate  # noqa: E402

SCAM = {
    "id": "rs-001",
    "split": "real_scam",
    "input": {"text": "Quý khách có biên bản phạt nguội chưa nộp, tra cứu tại dichvucong-phat[.]xyz", "entities": [], "image": None, "audio": None, "forward_origin": "hidden_user"},
    "label": {"is_scam": True, "group": "N1", "scenario": "N1.4", "min_level": "SUSPICIOUS", "max_level": None, "must_signals": ["U_TYPOSQUAT"], "risky_actions": ["click_link"]},
    "source_url": "https://bocongan.gov.vn/bai-viet/x",
    "author": "AI1",
    "notes": "",
}
BENIGN = {
    "id": "bs-001",
    "split": "benign_suspicious",
    "input": {"text": "[Ngân hàng A] Mã OTP của bạn là 000000. KHÔNG cung cấp mã này cho bất kỳ ai.", "entities": [], "image": None, "audio": None, "forward_origin": None},
    "label": {"is_scam": False, "group": "N0", "scenario": "N0", "min_level": None, "max_level": "UNSURE", "must_signals": [], "risky_actions": []},
    "source_url": None,
    "author": "AI1",
    "notes": "",
}


def rec(base, **over):
    r = copy.deepcopy(base)
    for k, v in over.items():
        if "." in k:
            head, tail = k.split(".", 1)
            r[head][tail] = v
        else:
            r[k] = v
    return r


def run(tmp_path, *records, target="v0"):
    p = tmp_path / "t.jsonl"
    p.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in records) + "\n", encoding="utf-8")
    return validate.run([p], target)


def errors(tmp_path, *records):
    return run(tmp_path, *records).errors


def test_valid_records_have_no_errors(tmp_path):
    assert errors(tmp_path, SCAM, BENIGN) == []


def test_bad_json_line_reported_with_line_number(tmp_path):
    p = tmp_path / "t.jsonl"
    p.write_text(json.dumps(SCAM) + "\n{not json}\n", encoding="utf-8")
    errs = validate.run([p]).errors
    assert any(e.startswith("t.jsonl:2") and "JSON" in e for e in errs)


def test_real_scam_requires_source_url(tmp_path):
    errs = errors(tmp_path, rec(SCAM, source_url=None))
    assert any("source_url" in e for e in errs)


def test_scam_needs_min_level_and_null_max_level(tmp_path):
    assert any("min_level" in e for e in errors(tmp_path, rec(SCAM, **{"label.min_level": None})))
    assert any("max_level" in e for e in errors(tmp_path, rec(SCAM, **{"label.max_level": "UNSURE"})))


def test_benign_needs_max_level(tmp_path):
    assert any("max_level" in e for e in errors(tmp_path, rec(BENIGN, **{"label.max_level": None})))


def test_group_rules(tmp_path):
    assert any("N0" in e for e in errors(tmp_path, rec(SCAM, **{"label.group": "N0", "label.scenario": "N0"})))
    assert any("N0" in e for e in errors(tmp_path, rec(BENIGN, **{"label.group": "N2", "label.scenario": "N2.1"})))
    assert any("label.group" in e for e in errors(tmp_path, rec(SCAM, **{"label.group": "KHAC", "label.scenario": "KHAC"})))  # tên cũ, trước khi đổi sang tiếng Anh
    assert errors(tmp_path, rec(SCAM, **{"label.group": "OTHER", "label.scenario": "OTHER"})) == []


def test_scenario_must_match_group(tmp_path):
    assert any("scenario" in e for e in errors(tmp_path, rec(SCAM, **{"label.scenario": "N2.1"})))


def test_split_and_is_scam_must_agree(tmp_path):
    assert any("real_scam" in e for e in errors(tmp_path, rec(BENIGN, split="real_scam", source_url="https://x.vn/a")))
    assert any("benign_suspicious" in e for e in errors(tmp_path, rec(SCAM, split="benign_suspicious")))


def test_unknown_enum_and_extra_fields(tmp_path):
    assert any("risky_actions" in e for e in errors(tmp_path, rec(SCAM, **{"label.risky_actions": ["chuyen_khoan"]})))
    assert any("split" in e for e in errors(tmp_path, rec(SCAM, split="nope")))
    assert any("trường lạ: extra" in e for e in errors(tmp_path, rec(SCAM, extra=1)))
    assert any("forward_origin" in e for e in errors(tmp_path, rec(SCAM, **{"input.forward_origin": "bot"})))


def test_text_link_entity_needs_url(tmp_path):
    ent = [{"kind": "text_link", "offset": 0, "length": 3}]
    assert any("text_link" in e for e in errors(tmp_path, rec(SCAM, **{"input.entities": ent})))


def test_missing_asset_and_path_traversal(tmp_path):
    assert any("không tồn tại" in e for e in errors(tmp_path, rec(SCAM, **{"input.image": "assets/none.png"})))
    assert any("tương đối" in e for e in errors(tmp_path, rec(SCAM, **{"input.image": "../README.md"})))


def test_existing_asset_ok(tmp_path):
    img = next((validate.EVAL_DIR / "assets" / "ocr").glob("*.png"), None)
    if img is None:
        return
    rel = img.relative_to(validate.EVAL_DIR).as_posix()
    assert errors(tmp_path, rec(SCAM, **{"input.image": rel})) == []


def test_empty_sample_rejected(tmp_path):
    assert any("rỗng" in e for e in errors(tmp_path, rec(SCAM, **{"input.text": ""})))


def test_duplicate_id_and_text_ignoring_accents_and_punctuation(tmp_path):
    other = rec(SCAM, id="rs-002", **{"input.text": "QUY KHACH CO BIEN BAN PHAT NGUOI CHUA NOP,  tra cuu tai dichvucong-phat[.]xyz!!"})
    errs = errors(tmp_path, SCAM, other)
    assert any("nội dung trùng" in e for e in errs)
    assert any("id 'rs-001' trùng" in e for e in errors(tmp_path, SCAM, rec(SCAM, **{"input.text": "khác hẳn nội dung"})))


def test_near_duplicate_is_warning_not_error(tmp_path):
    near = rec(SCAM, id="rs-002", **{"input.text": SCAM["input"]["text"] + " ngay"})
    rep = run(tmp_path, SCAM, near)
    assert rep.errors == []
    assert any("gần trùng" in w for w in rep.warnings)


def test_distinct_samples_not_flagged(tmp_path):
    rep = run(tmp_path, SCAM, BENIGN)
    assert not any("trùng" in w for w in rep.warnings)


def test_real_phone_warns_placeholder_does_not(tmp_path):
    real = rec(BENIGN, id="bs-002", **{"input.text": "Shipper goi 0912 345 678 de giao hang"})
    fake = rec(BENIGN, id="bs-003", **{"input.text": "Chuyen vao STK 0000000000 cua cong ty"})
    assert any("số điện thoại" in w for w in run(tmp_path, real).warnings)
    assert not any("số điện thoại" in w for w in run(tmp_path, fake).warnings)


def test_counts_are_warnings_per_target(tmp_path):
    rep = run(tmp_path, SCAM, BENIGN)
    assert rep.errors == []
    assert any("real_scam: 1/40" in w for w in rep.warnings)
    assert any("tổng 2/100 mẫu (v0)" in w for w in rep.warnings)
    assert any("mới có 1/2 tác giả" in w for w in rep.warnings)
    assert any("real_scam: 1/120" in w for w in run(tmp_path, SCAM, BENIGN, target="v1").warnings)
    assert any("tổng 2/300 mẫu (v1)" in w for w in run(tmp_path, SCAM, BENIGN, target="v1").warnings)


def test_two_authors_satisfy_team_size(tmp_path):
    rep = run(tmp_path, SCAM, rec(BENIGN, author="minhvhq"))
    assert not any("tác giả" in w for w in rep.warnings)


def test_source_reuse_warns_above_two(tmp_path):
    def scam(n, text):
        return rec(SCAM, id=f"rs-{n}", **{"input.text": text})
    three = [scam(1, "Giả công an đòi nộp phạt qua link lạ"), scam(2, "Giả ngân hàng báo khóa thẻ, đăng nhập link"),
             scam(3, "Mời làm nhiệm vụ online nhận hoa hồng")]
    assert any("3 mẫu cùng dựa vào" in w for w in run(tmp_path, *three).warnings)
    assert not any("cùng dựa vào" in w for w in run(tmp_path, *three[:2]).warnings)


def test_ocr_split_not_counted_and_uses_ocr_label(tmp_path):
    ocr = {
        "id": "oi-001", "split": "ocr_images",
        "input": {"text": "", "entities": [], "image": None, "audio": None, "forward_origin": None},
        "label": {"ocr_text": "abc", "urls": [], "phones": []}, "source_url": None, "author": "AI2", "notes": "",
    }
    rep = run(tmp_path, ocr)
    assert any("rỗng" in e for e in rep.errors)  # chưa có ảnh nên rỗng; nhãn OCR thì hợp lệ
    assert not any("label" in e for e in rep.errors)
    assert any("real_scam: 0/40" in w for w in rep.warnings)


def test_main_exit_codes(tmp_path, capsys):
    p = tmp_path / "ok.jsonl"
    p.write_text(json.dumps(SCAM, ensure_ascii=False) + "\n", encoding="utf-8")
    assert validate.main([str(p)]) == 0  # chỉ có cảnh báo số lượng
    assert validate.main([str(p), "--strict"]) == 1
    bad = tmp_path / "bad.jsonl"
    bad.write_text(json.dumps(rec(SCAM, source_url=None)) + "\n", encoding="utf-8")
    assert validate.main([str(bad)]) == 1


def test_real_dataset_in_repo_has_no_errors():
    paths = sorted(validate.DATASETS.glob("*.jsonl"))
    assert validate.run(paths).errors == []
