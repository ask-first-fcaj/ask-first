"""Test eval/readability.py (AI-32, #92). Chạy từ ai/: uv run pytest ../eval/test_readability.py (CI đã chạy cả thư mục ../eval)."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import readability as R  # noqa: E402


def test_simple_sentence_has_no_flags() -> None:
    r = R.measure("Bác đừng chuyển tiền nhé. Hãy hỏi con cháu trước.")
    assert r.sentences == 2
    assert r.words == 10
    assert r.words_per_sentence == 5.0
    assert r.hard_words == [] and r.english_words == []
    assert r.flags() == []


def test_hard_han_viet_words_are_counted_and_flagged() -> None:
    r = R.measure("Đối tượng mạo danh cơ quan chức năng để chiếm đoạt tiền.")
    assert set(r.hard_words) == {"đối tượng", "mạo danh", "cơ quan chức năng", "chiếm đoạt"}
    assert any("từ khó" in f for f in r.flags())


def test_english_words_are_detected_whole_word_only() -> None:
    r = R.measure("Đừng bấm link lạ, đừng cài app. Linkedin và application thì không tính.")
    assert r.english_words == ["link", "app"]
    assert any("tiếng Anh" in f for f in r.flags())


def test_matching_ignores_case_and_unicode_form() -> None:
    nfd = "ĐỐI TƯỢNG".encode().decode()  # chữ hoa
    assert R.measure(nfd + " lạ").hard_words == ["đối tượng"]
    decomposed = "mạo danh".replace("ạ", "a\u0323")  # a + dấu nặng rời (NFD)
    assert R.measure(decomposed).hard_words == ["mạo danh"]


def test_overlapping_phrases_are_not_double_counted() -> None:
    # "tài khoản thụ hưởng" chứa "thụ hưởng": chỉ đếm cụm dài nhất, một lần
    assert R.measure("Tài khoản thụ hưởng này lạ.").hard_words == ["tài khoản thụ hưởng"]


def test_empty_and_whitespace_only_text() -> None:
    for t in ("", "   \n\n  "):
        r = R.measure(t)
        assert (r.chars, r.sentences, r.words, r.words_per_sentence) == (0, 0, 0, 0.0)
        assert r.flags() == []


def test_newlines_split_sentences_and_emoji_only_lines_do_not_count() -> None:
    r = R.measure("🔴 NGUY HIỂM\nĐừng đọc mã cho ai\n👉\n")
    assert r.sentences == 2


def test_html_is_stripped_and_entities_decoded() -> None:
    r = R.measure("<b>Bác</b> đừng bấm &amp; đừng cài app.")
    assert "<" not in R.strip_html("<b>Bác</b>") and "&amp;" not in R.strip_html("a &amp; b")
    assert r.words == 6  # Bác đừng bấm đừng cài app ("&" không tính là chữ)
    assert r.english_words == ["app"]


def test_length_and_sentence_thresholds_are_parameters() -> None:
    long_sentence = " ".join(["tiếng"] * 30) + "."
    r = R.measure(long_sentence)
    assert any("câu dài" in f for f in r.flags())
    assert r.flags(R.Thresholds(max_words_per_sentence=40)) == []
    assert any("dài" in f and "ký tự" in f for f in R.measure("a " * 400).flags())
    assert R.measure("a " * 400).flags(R.Thresholds(max_chars=1000, max_words_per_sentence=1000)) == []


def test_wordlist_loads_and_rejects_malformed_lines(tmp_path: Path) -> None:
    wl = R.load_wordlist()
    assert "mạo danh" in wl["hv"] and "link" in wl["en"]
    bad = tmp_path / "w.txt"
    bad.write_text("# ghi chú\nlink\n", encoding="utf-8")  # thiếu loại
    with pytest.raises(ValueError, match="w.txt:2"):
        R.load_wordlist(bad)


def test_cli_reports_flagged_rows_from_jsonl(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    f = tmp_path / "x.jsonl"
    f.write_text('{"id":"a","explanation":"Bác đừng bấm link này."}\n{"id":"b","explanation":"Bác cứ yên tâm."}\n', encoding="utf-8")
    assert R.main(["--jsonl", str(f)]) == 0
    out = capsys.readouterr().out
    assert "a:" in out and "VƯỢT" in out and "b:" in out
    assert "1/2 vượt ngưỡng tạm" in out
