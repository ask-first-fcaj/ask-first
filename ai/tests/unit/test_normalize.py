import unicodedata

import pytest

from askfirst.pipeline.normalize import fold, normalize


def test_normalize_nfc_and_invisible_chars() -> None:
    nfd = unicodedata.normalize("NFD", "Chuyển tiền")
    assert normalize(f"  {nfd[:3]}\u200b{nfd[3:]}\u00ad ") == "Chuyển tiền"


@pytest.mark.parametrize("raw,folded", [
    ("Chuyển ti.ền ngay! Đọc m.ã 0TP cho c0ng an", "chuyen tien ngay . doc ma otp cho cong an"),
    ("O T P", "otp"),
    ("C-Ô-N-G A-N 💰thông báo", "cong an thong bao"),
    ("Nộp 500k hoặc 1tr, số dư 15.000.000đ lúc 15h", "nop 500k hoac 1tr so du 15000000d luc 15h"),   # số tiền không bị đổi leetspeak
    ("Mã [OTP_1].Vui lòng gửi [STK_1 • VCB]", "ma [otp_1] . vui long gui [stk_1 vcb]"),             # token mask giữ nguyên, câu mới
])
def test_fold(raw: str, folded: str) -> None:
    assert fold(raw).text == folded


def test_fold_maps_back_to_source() -> None:
    raw = "Đọc m.ã 0TP"
    f = fold(raw)
    i = f.text.index("otp")
    assert raw[f.src[i]:f.src[i + 2] + 1] == "0TP"
