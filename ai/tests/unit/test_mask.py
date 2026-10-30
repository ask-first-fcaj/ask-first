"""Test bảo mật cho ``pipeline/mask.py`` (AI-22, ARCH §9.2).

Mọi số điện thoại, STK, CCCD, mã OTP, email trong test đều là số bịa.
"""

from __future__ import annotations

import json
import re
import unicodedata
from pathlib import Path

import pytest

from askfirst.pipeline.mask import MaskResult, mask


def masked(text: str) -> str:
    return mask(text).text


# ------------------------------------------------------------------ PII phải bị che


@pytest.mark.parametrize(
    ("text", "secrets", "tokens"),
    [
        # --- số điện thoại
        ("Gọi 0912345678 ngay", ["0912345678"], ["[SĐT_1]"]),
        ("Gọi 0912 345 678 ngay", ["0912 345 678"], ["[SĐT_1]"]),
        ("Gọi 0912.345.678 ngay", ["0912.345.678"], ["[SĐT_1]"]),
        ("Gọi 091-234-5678 ngay", ["091-234-5678"], ["[SĐT_1]"]),
        ("Gọi 09 1234 5678 ngay", ["09 1234 5678"], ["[SĐT_1]"]),
        ("Gọi 0835 123 456 ngay", ["0835 123 456"], ["[SĐT_1]"]),
        ("Zalo +84 912 345 678 nhé", ["+84 912 345 678"], ["[SĐT_1]"]),
        ("Zalo +84912345678 nhé", ["+84912345678"], ["[SĐT_1]"]),
        ("Zalo +84 76 9523456 nhé", ["+84 76 9523456"], ["[SĐT_1]"]),
        ("Zalo 84912345678 nhé", ["84912345678"], ["[SĐT_1]"]),
        ("Số 0 9 1 2 3 4 5 6 7 8 nhé", ["0 9 1 2 3 4 5 6 7 8"], ["[SĐT_1]"]),
        ("Gọi 0912\u200b345\u200b678 ngay", ["345", "0912"], ["[SĐT_1]"]),  # chèn ký tự vô hình để né regex
        ("Gọi ０９１２３４５６７８ ngay", ["０９１２３４５６７８", "0912345678"], ["[SĐT_1]"]),  # số toàn-rộng
        ("Liên hệ zalo.me/0912345678 nhé", ["0912345678"], ["zalo.me/[SĐT_1]"]),  # số điện thoại nằm trong link liên hệ
        ("sdt0912345678 gọi nhé", ["0912345678"], ["[SĐT_1]"]),  # dính liền chữ
        ("Số 09-12-34-56-78 nhé", ["09-12-34-56-78"], ["[SĐT_1]"]),
        ("Nhắn t.me/+84912345678 nhé", ["84912345678"], ["t.me/[SĐT_1]"]),
        ("CCCD: 0 1 2 3 4 5 6 7 8 9 0 1", ["0 1 2 3"], ["[CCCD_1]"]),
        # --- CCCD / CMND
        ("CCCD của tôi 012345678901", ["012345678901"], ["[CCCD_1]"]),
        ("Số định danh 012 345 678 901", ["012 345 678 901"], ["[CCCD_1]"]),
        ("CMND cũ 123456789", ["123456789"], ["[CCCD_1]"]),
        ("Căn cước công dân số 079201001234", ["079201001234"], ["[CCCD_1]"]),
        # --- số tài khoản
        ("Chuyển khoản vào STK 0123456789 nhé", ["0123456789"], ["[STK_1]"]),
        ("Số tài khoản: 19031234567890", ["19031234567890"], ["[STK_1]"]),
        ("TK: 070139333770", ["070139333770"], ["[STK_1]"]),
        ("STK Vietcombank 0123456789", ["0123456789"], ["[STK_1 • Vietcombank]"]),
        ("Chuyển vào 0123456789 tại Techcombank nhé", ["0123456789"], ["[STK_1 • Techcombank]"]),
        ("STK 1903 1234 5678 nhé", ["1903 1234 5678"], ["[STK_1]"]),
        ("STK 0912345678", ["0912345678"], ["[STK_1]"]),  # ngữ cảnh STK thắng dạng số điện thoại
        # --- OTP / mã xác thực / mật khẩu
        ("Mã OTP của bạn là 482913", ["482913"], ["[OTP_1]"]),
        ("Ma xac thuc OTP VieON cua ban la 9061. Xin vui long khong chia se.", ["9061"], ["[OTP_1]"]),
        ("Your verification code is 4951", ["4951"], ["[OTP_1]"]),
        ("OTP cua Quy Khach la: 62743042. Ma xac thuc giao dich co hieu luc 2 phut", ["62743042"], ["[OTP_1]"]),
        ("ZaloPay: 644496 la ma OTP dang nhap Zalopay", ["644496"], ["[OTP_1]"]),  # mã đứng trước từ khoá
        ("Mã Spotify: 572408. Có hiệu lực trong 5 phút", ["572408"], ["[OTP_1]"]),  # nhận ra nhờ cụm hiệu lực
        ("WhatsApp code: 491-028", ["491-028"], ["[OTP_1]"]),
        ("Mật khẩu của bạn là 123456", ["123456"], ["[OTP_1]"]),
        ("Nhập PIN 1234 để xác nhận", ["1234"], ["[OTP_1]"]),
        ("Bác đọc mã 482913 vừa nhận cho cháu nhé", ["482913"], ["[OTP_1]"]),
        ("Sử dụng 810814 dưới dạng mã dành cho Microsoft", ["810814"], ["[OTP_1]"]),
        ("G-028822 là mã xác minh Google của bạn", ["028822"], ["G-[OTP_1]"]),
        ("798272 is your Grab Activation Code (GAC)", ["798272"], ["[OTP_1]"]),
        ("Instagram: 709598 is your Instagram code. Don't share it.", ["709598"], ["[OTP_1]"]),
        ("OTP cua Quy Khach la:\n62743042. Ma xac thuc giao dich", ["62743042"], ["[OTP_1]"]),  # OCR ngắt dòng giữa câu
        ("Mã xác thực\n482913", ["482913"], ["[OTP_1]"]),
        ("OTP là 4 8 2 9 1 3", ["4 8 2 9 1 3"], ["[OTP_1]"]),  # từng chữ số cách nhau
        ("OTP la 482.913", ["482.913"], ["[OTP_1]"]),
        ("STK\n0123456789\nVietcombank", ["0123456789"], ["[STK_1 • Vietcombank]"]),
        # --- email
        ("Gửi về nguyen.van.a@gmail.com nhé", ["nguyen.van.a@gmail.com"], ["[EMAIL_1]"]),
        ("Gửi về NGUYEN.VAN.A@GMAIL.COM nhé", ["NGUYEN.VAN.A@GMAIL.COM"], ["[EMAIL_1]"]),
        ("Gửi về abc[at]gmail.com nhé", ["abc[at]gmail.com"], ["[EMAIL_1]"]),
        # --- số đọc từng chữ số (ASR)
        ("số tôi là không chín tám bảy sáu năm bốn ba hai một", ["không chín tám"], ["[SỐ_CHỮ_1]"]),
        ("khong chin tam bay sau nam bon ba hai mot", ["khong chin"], ["[SỐ_CHỮ_1]"]),
        ("không, chín, tám, bảy, sáu, năm, bốn", ["không, chín"], ["[SỐ_CHỮ_1]"]),
    ],
)
def test_pii_is_masked(text: str, secrets: list[str], tokens: list[str]) -> None:
    out = masked(text)
    for secret in secrets:
        assert secret not in out, f"lộ {secret!r} trong {out!r}"
    for token in tokens:
        assert token in out, f"thiếu {token!r} trong {out!r}"


def test_otp_masked_even_with_amount_and_time_between_keyword_and_code() -> None:
    out = masked("Ma OTP xac thuc giao dich 5,000,000 VND luc 14:22 ngay 05/10/2026 la 619284. Hieu luc 3 phut.")
    assert "619284" not in out
    assert "[OTP_1]" in out
    # số tiền, giờ, ngày giữ nguyên
    assert "5,000,000 VND" in out and "14:22" in out and "05/10/2026" in out


def test_realistic_scam_message_masks_everything_but_keeps_link_and_amount() -> None:
    text = (
        "Cán bộ ngân hàng đây. Tài khoản STK 0123456789 Vietcombank của bác bị khóa, bác đọc mã OTP 482913 "
        "cho cháu, hoặc gọi 0912 345 678, mail cb.nganhang@gmail.com. Chuyển 50 triệu vào "
        "https://vcb-xacthuc.example.xyz/otp/482913 trước 10:45."
    )
    out = masked(text)
    for secret in ("0123456789", "482913 cho", "0912 345 678", "cb.nganhang@gmail.com"):
        assert secret not in out
    assert "https://vcb-xacthuc.example.xyz/otp/482913" in out  # URL là bằng chứng, không mask
    assert "50 triệu" in out and "10:45" in out


# ------------------------------------------------------------------ mẫu bẫy: KHÔNG được che nhầm


@pytest.mark.parametrize(
    "text",
    [
        "Hẹn 10:45 ngày 23/10/2026 nhé",
        "Cuộc gọi kéo dài 3 phút lúc 05/10/2026 11:00",  # ngày + giờ không phải số điện thoại
        "Giao dịch 05.10.2026 08:25 thành công",
        "Bị trừ 50.000đ, còn 1.250.000 VND",
        "Giá 500000 đồng, ship 30k",
        "Còn 185.000d tiền cước",
        "Đơn hàng DH123456 đang giao",
        "Mã đơn hàng 123456 đã giao",  # "mã" không sát số
        "Mã vận đơn SPX123456789012 đang giao",  # 12 số dính chữ là mã, không phải CCCD
        "Tăng 15% trong năm 2026",
        "Số nhà 12, ngõ 345, phố Lê Lợi",
        "Hotline 1900545436 hoặc gọi 113",
        "Mật khẩu mới phải có ít nhất 8 ký tự",
        "Mã OTP có hiệu lực trong 3 phút",
        "Chuyển 5 triệu, rồi năm mươi triệu nữa",  # số tiền viết bằng chữ: không che (xem docstring mask.py)
        "Ba năm sau tôi về quê sáu tháng",  # từ chỉ số trong câu thường
        "Vào http://dichvucong-12345678.xyz/0912345678 ngay",  # URL giữ nguyên, kể cả số trong đường dẫn
        "Xem dichvucong.gov.vn/tracuu/012345678901 nhé",  # URL không có http
        "Truy cập vnpost.vn.dichvu-buuchinh[.]cc/tra-cuu/0912345678",  # URL đã defang
        "",
        "Chào bạn, hôm nay trời đẹp",
    ],
)
def test_non_pii_is_left_untouched(text: str) -> None:
    assert masked(text) == text


# ------------------------------------------------------------------ tính chất của token


def test_same_value_gets_same_token_and_new_values_count_up() -> None:
    out = masked("0912345678 rồi 0987654321 rồi lại 0912 345 678")
    assert out == "[SĐT_1] rồi [SĐT_2] rồi lại [SĐT_1]"


def test_same_phone_in_different_forms_gets_one_token() -> None:
    assert masked("0912345678 hay +84 912 345 678 hay 84912345678") == "[SĐT_1] hay [SĐT_1] hay [SĐT_1]"


def test_phone_inside_zalo_link_is_masked_but_other_links_are_kept() -> None:
    out = masked("Liên hệ https://zalo.me/0335364535 hoặc zalo.me/84927946049, xem https://example.vn/0335364535")
    assert out == "Liên hệ https://zalo.me/[SĐT_1] hoặc zalo.me/[SĐT_2], xem https://example.vn/0335364535"


def test_each_kind_is_numbered_independently_in_order_of_appearance() -> None:
    out = masked("STK 0123456789, SĐT 0912345678, STK 0987654321, OTP 482913")
    assert out == "STK [STK_1], SĐT [SĐT_1], STK [STK_2], OTP [OTP_1]"


def test_mapping_restores_originals_only_in_memory() -> None:
    result = mask("Gọi 0912 345 678 hoặc mail a@b.vn, OTP 482913")
    assert result.mapping() == {"[SĐT_1]": "0912 345 678", "[EMAIL_1]": "a@b.vn", "[OTP_1]": "482913"}
    assert {e.kind for e in result.entities} == {"phone", "email", "otp"}


def test_repr_never_contains_original_values() -> None:
    result = mask("Gọi 0912345678, OTP 482913")
    assert "0912345678" not in repr(result) and "482913" not in repr(result)
    assert all("0912345678" not in repr(e) for e in result.entities)


def test_result_type_and_empty_input() -> None:
    result = mask("")
    assert isinstance(result, MaskResult) and result.text == "" and result.entities == ()


def test_masking_is_idempotent() -> None:
    samples = [
        "Gọi 0912 345 678, STK 0123456789 Vietcombank, CCCD 012345678901, a@b.vn, mã OTP 482913",
        "644496 la ma OTP dang nhap. Lien he 1900545436",
        "không chín tám bảy sáu năm bốn ba hai một",
    ]
    for s in samples:
        once = masked(s)
        assert masked(once) == once


def test_handles_long_input_without_blowing_up() -> None:
    text = "Xin chào, đây là tin nhắn bình thường. " * 500 + "OTP 482913"
    assert masked(text).endswith("OTP [OTP_1]")


# ------------------------------------------------------------------ luật R_OTP_* chạy trên văn bản đã mask (ARCH §9.2)


def _rule_text(text: str) -> str:
    """Chuẩn hoá như plan §3.10 nói với luật: chữ thường, KHÔNG dấu (AI-23 sẽ viết ``normalize_text.py`` thật)."""
    folded = unicodedata.normalize("NFD", text.lower().replace("đ", "d"))
    return "".join(c for c in folded if unicodedata.category(c) != "Mn")


# Pattern ví dụ của R_OTP_REQUEST trong plan §3.10 (`kb/rules.yaml` chưa tồn tại; AI-23 sẽ viết bản thật).
_R_OTP_REQUEST = re.compile(r"(cung cap|doc|gui|nhap)\W+(\w+\W+){0,3}(ma )?(otp|xac thuc|xac nhan|\[otp_\d+\])")
# Bản phác theo mô tả ở docs/ai-viec-nghien-cuu-phu.md việc A: tin có mã kèm câu "không cung cấp mã cho bất kỳ ai".
_R_OTP_GENUINE = re.compile(r"\[otp_\d+\].*(khong|tuyet doi khong)\W+(\w+\W+){0,3}(cung cap|chia se)\W+(\w+\W+){0,4}(bat ky|ai)")


def test_r_otp_request_matches_on_token_not_on_digits() -> None:
    scam = "Bác đọc mã 482913 cho cháu"
    assert _R_OTP_REQUEST.search(_rule_text(scam)) is None  # trên chữ số gốc thì luật không thấy gì
    assert _R_OTP_REQUEST.search(_rule_text(masked(scam))) is not None  # trên token thì khớp


def test_r_otp_genuine_sees_token_next_to_the_warning_sentence() -> None:
    sms = "Mã OTP của bạn là 482913. Tuyệt đối không chia sẻ mã này cho bất kỳ ai."
    out = _rule_text(masked(sms))
    assert "[otp_1]" in out
    assert _R_OTP_GENUINE.search(out) is not None


def test_r_otp_genuine_does_not_fire_on_plain_text_without_a_code() -> None:
    assert _R_OTP_GENUINE.search(_rule_text(masked("Không chia sẻ mã này cho bất kỳ ai"))) is None


# ------------------------------------------------------------------ không mẫu nào trong bộ eval lọt số điện thoại / CCCD


_DATASETS = Path(__file__).resolve().parents[3] / "eval" / "datasets"


def _eval_texts() -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for path in sorted(_DATASETS.glob("*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            if row["input"].get("text"):
                out.append((row["id"], row["input"]["text"]))
            if row.get("label", {}).get("ocr_text"):  # ảnh: pipeline sẽ mask text do OCR đọc ra
                out.append((row["id"] + ":ocr", row["label"]["ocr_text"]))
    return out


_LEAK = re.compile(r"(?<![0-9])(?:0[35789][0-9]{8}|84[35789][0-9]{8}|[0-9]{12})(?![0-9])")


def test_no_eval_sample_leaks_phone_or_cccd_after_masking() -> None:
    texts = _eval_texts()
    if not texts:
        pytest.skip("không có eval/datasets/*.jsonl")
    leaked = [(rid, m.group()) for rid, t in texts for m in _LEAK.finditer(masked(t))]
    assert leaked == []
