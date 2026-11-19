"""Đo độ dễ đọc của lời giải thích gửi người cao tuổi (AI-32, #92, docs/ai-viec-nghien-cuu-phu.md "Việc C").

Chỉ dùng thư viện chuẩn. Đây là gate rẻ chạy trước khi nhờ người thật đọc thử; KHÔNG thay người đọc.
Ngưỡng mặc định dưới đây là số tạm, **chưa hiệu chỉnh**: chưa có nguồn khoa học cho ngưỡng nào, chỉnh sau khi có kết quả đọc thử (SH-08).

Dùng:
    python eval/readability.py "Bác đừng bấm link này nhé."           # một đoạn text
    python eval/readability.py --jsonl docs/results/ai11-judge-scores.jsonl --field explanation
Trong code: ``measure(text) -> Report``; ``Report.flags(Thresholds())`` trả danh sách lý do vượt ngưỡng.

"Từ" ở đây là tiếng (âm tiết) tách bằng khoảng trắng, vì tiếng Việt viết cách từng âm tiết; cụm nhiều tiếng trong danh sách
từ khó được khớp nguyên cụm.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import unicodedata
from dataclasses import asdict, dataclass, field
from pathlib import Path

WORDLIST = Path(__file__).with_name("readability_wordlist.txt")
_TAG = re.compile(r"<[^>]+>")
_SENT_END = re.compile(r"[.!?…]+(?:\s|$)|\n+")
_TOKEN = re.compile(r"[^\W_]+", re.UNICODE)


@dataclass(frozen=True)
class Thresholds:
    """Ngưỡng tạm, CHƯA hiệu chỉnh. Tin kết luận tối đa 600 ký tự là quy định của ARCH §6.8, các số còn lại là ước lượng."""

    max_chars: int = 600
    max_words_per_sentence: float = 20.0  # tiếng (âm tiết) trung bình mỗi câu
    max_hard_words: int = 2
    max_english_words: int = 0


@dataclass
class Report:
    chars: int
    sentences: int
    words: int
    words_per_sentence: float
    hard_words: list[str] = field(default_factory=list)
    english_words: list[str] = field(default_factory=list)

    def flags(self, t: Thresholds | None = None) -> list[str]:
        t = t or Thresholds()
        out: list[str] = []
        if self.chars > t.max_chars:
            out.append(f"dài {self.chars} ký tự (> {t.max_chars})")
        if self.words_per_sentence > t.max_words_per_sentence:
            out.append(f"câu dài TB {self.words_per_sentence:.1f} tiếng (> {t.max_words_per_sentence:g})")
        if len(self.hard_words) > t.max_hard_words:
            out.append(f"{len(self.hard_words)} từ khó: {', '.join(self.hard_words)}")
        if len(self.english_words) > t.max_english_words:
            out.append(f"có tiếng Anh: {', '.join(self.english_words)}")
        return out


def _norm(s: str) -> str:
    return unicodedata.normalize("NFC", s).casefold()


def load_wordlist(path: Path = WORDLIST) -> dict[str, list[str]]:
    """{'hv': [...], 'en': [...]} từ file (dòng '# ...' và dòng trống bị bỏ)."""
    out: dict[str, list[str]] = {"hv": [], "en": []}
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        word, _, kind = line.partition("\t")
        kind = kind.strip()
        if kind not in out or not word.strip():
            raise ValueError(f"{path.name}:{n}: cần '<từ><TAB>hv|en', gặp {line!r}")
        out[kind].append(_norm(word.strip()))
    return out


def strip_html(text: str) -> str:
    return html.unescape(_TAG.sub(" ", text))


def _find(words: list[str], text: str) -> list[str]:
    """Các mục trong danh sách có mặt trong text (nguyên từ/cụm), mỗi lần xuất hiện một mục; cụm dài khớp trước, không đếm trùng chồng lấn."""
    found: list[str] = []
    taken = [False] * len(text)
    for w in sorted(set(words), key=len, reverse=True):
        for m in re.finditer(rf"(?<!\w){re.escape(w)}(?!\w)", text):
            if not any(taken[m.start() : m.end()]):
                found.append(w)
                for i in range(m.start(), m.end()):
                    taken[i] = True
    return found


def measure(text: str, wordlist: dict[str, list[str]] | None = None) -> Report:
    wl = wordlist if wordlist is not None else load_wordlist()
    clean = strip_html(text).strip()
    norm = _norm(clean)
    sentences = [s for s in _SENT_END.split(clean) if _TOKEN.search(s)]
    words = len(_TOKEN.findall(clean))
    n = len(sentences)
    return Report(
        chars=len(clean),
        sentences=n,
        words=words,
        words_per_sentence=(words / n) if n else 0.0,
        hard_words=_find(wl["hv"], norm),
        english_words=_find(wl["en"], norm),
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("text", nargs="?", help="đoạn text cần đo (bỏ qua nếu dùng --jsonl)")
    ap.add_argument("--jsonl", type=Path, help="file .jsonl, đo trường --field của từng dòng")
    ap.add_argument("--field", default="explanation")
    ap.add_argument("--max-chars", type=int, default=Thresholds.max_chars)
    ap.add_argument("--max-wps", type=float, default=Thresholds.max_words_per_sentence)
    ap.add_argument("--max-hard", type=int, default=Thresholds.max_hard_words)
    ap.add_argument("--max-english", type=int, default=Thresholds.max_english_words)
    ap.add_argument("--json", action="store_true", help="in JSON thay vì chữ")
    a = ap.parse_args(argv)
    if (a.text is None) == (a.jsonl is None):
        ap.error("cần đúng một trong: text hoặc --jsonl")
    th = Thresholds(a.max_chars, a.max_wps, a.max_hard, a.max_english)

    if a.jsonl:
        rows = [json.loads(line) for line in a.jsonl.read_text(encoding="utf-8").splitlines() if line.strip()]
        items = [(str(r.get("id", i)), r.get(a.field) or "") for i, r in enumerate(rows)]
    else:
        items = [("text", a.text)]
    bad = 0
    for rid, text in items:
        rep = measure(text)
        fl = rep.flags(th)
        bad += bool(fl)
        if a.json:
            print(json.dumps({"id": rid, **asdict(rep), "flags": fl}, ensure_ascii=False))
        else:
            print(
                f"{rid}: {rep.chars} ký tự, {rep.sentences} câu, {rep.words_per_sentence:.1f} tiếng/câu"
                + (f" | VƯỢT: {'; '.join(fl)}" if fl else " | ổn")
            )
    if len(items) > 1 and not a.json:
        print(f"\n{bad}/{len(items)} vượt ngưỡng tạm (chưa hiệu chỉnh)")
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    raise SystemExit(main())
