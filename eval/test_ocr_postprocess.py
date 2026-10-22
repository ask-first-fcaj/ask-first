"""Test hậu xử lý OCR (repair_urls, repair_phones). Chạy: .venv/Scripts/python.exe -m pytest eval/test_ocr_postprocess.py"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from ocr_benchmark import repair_phones, repair_urls  # noqa: E402


def test_phone_o_to_zero_in_real_ocr_line():
    # dòng thật OCR đọc ở eval/support/053.png
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
