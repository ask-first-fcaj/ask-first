"""Test hậu xử lý và cách chấm OCR (repair_urls, repair_phones, url_ok, order_by_columns).
Chạy: uv run --project ai --with numpy pytest eval/test_ocr_postprocess.py"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ocr_benchmark import (
    order_by_columns,
    repair_phones,
    repair_urls,
    url_host_ok,
    url_ok,
)


def test_phone_o_to_zero_in_real_ocr_line():
    # dòng OCR thật từ một ảnh SMS (ảnh không nằm trong repo)
    assert repair_phones("Đang nhån tin vi O583 039 867 (SMS/MMS)") == "Đang nhån tin vi 0583 039 867 (SMS/MMS)"


def test_phone_l_and_I_to_one():
    assert repair_phones("liên hệ 0l23 456 78I9") == "liên hệ 0123 456 7819"


def test_phone_plus84_untouched_when_clean():
    assert repair_phones("+84 56 4010205") == "+84 56 4010205"


def test_words_and_short_numbers_untouched():
    for s in ["Online Ill look", "OTP 956897", "id=19558240", "Mã OK1234", "1.880.000 VND+ 59 vong", "09:14, Th 2", "IOIOIOIOIOI"]:
        assert repair_phones(s) == s


def test_url_wrapped_after_scheme():
    assert repair_urls("long vao https://\ndichvucongs.top/vn de") == "long vao https://dichvucongs.top/vn de"


def test_url_wrapped_with_stray_dot():
    assert repair_urls("truy cp https://.\ndichvucongs.top/vn") == "truy cp https://dichvucongs.top/vn"


def test_ttps_prefix_fixed_but_words_kept():
    assert repair_urls("ttps://a.vn") == "https://a.vn"
    assert repair_urls("matter ttpsx bottps: x") == "matter ttpsx bottps: x"


def test_valid_url_and_text_after_scheme_word_untouched():
    for s in ["https://ok8858.com/index?id=1", "xem https: đây là link", "http://a.vn  http://b.vn"]:
        assert repair_urls(s) == s


def test_url_ok_accepts_url_wrapped_by_ocr_and_trailing_punctuation():
    assert url_ok("https://dichvucongs.top/vn", "vao https://\ndichvucongs.top/vn de")
    assert url_ok("https://a.vn", "xem https://a.vn.")
    assert url_ok("https://a.vn/", "(https://a.vn)")
    assert url_ok("tvkqz.nmiu.online", "D'en{tvkqz.nmiu.online},To")


def test_url_ok_rejects_longer_or_different_domain():
    # trước đây so chuỗi con nên mấy ca này bị tính là đọc đúng
    assert not url_ok("https://a.vn", "https://a.vn.evil.com")
    assert not url_ok("abc.vn", "xabc.vn")
    assert not url_ok("abc.vn", "abc.vn:8080")
    assert not url_ok("https://a.vn/x", "tai https://a.vn/x?id=1")
    assert not url_ok("https://dichvucongs.top/vn", "https://dichvucongs.top/vnde")
    assert not url_ok("a.vn", "evil.com/a.vn")


def test_url_host_ok_ignores_scheme_but_keeps_boundaries():
    assert url_host_ok("https://a.vn/x", "vao a.vn/x nhe")
    assert url_host_ok("https://a.vn", "ttps://.a.vn")
    assert not url_host_ok("https://a.vn", "a.vn.evil.com")


def _box(x0, x1, y):
    return [[x0, y], [x1, y], [x1, y + 10], [x0, y + 10]]


def test_order_by_columns_reads_left_column_then_right():
    txts, boxes = [], []
    for row in range(3):  # engine trả theo hàng ngang: trái, phải, trái, phải...
        txts += [f"L{row}", f"R{row}"]
        boxes += [_box(0, 80, row * 20), _box(120, 200, row * 20)]
    assert order_by_columns(txts, boxes) == "L0\nL1\nL2\nR0\nR1\nR2"


def test_order_by_columns_keeps_order_without_middle_gap():
    txts = ["a", "b", "c", "d"]
    boxes = [_box(0, 200, i * 20) for i in range(4)]
    assert order_by_columns(txts, boxes) == "a\nb\nc\nd"


def test_order_by_columns_needs_enough_boxes_per_side():
    txts = ["L0", "R0", "L1", "L2"]
    boxes = [_box(0, 80, 0), _box(120, 200, 0), _box(0, 80, 20), _box(0, 80, 40)]
    assert order_by_columns(txts, boxes) == "L0\nR0\nL1\nL2"
    assert order_by_columns([], []) == ""
