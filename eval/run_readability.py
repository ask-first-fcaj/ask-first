"""Đo độ dễ đọc (AI-32, #92) trên lời giải thích và tin kết luận bot gửi. Chạy từ thư mục ai/:
  uv run python ../eval/run_readability.py [file.jsonl]   (mặc định docs/results/ai11-judge-scores.jsonl)
File .jsonl cần trường `explanation` (lời giải thích của LLM) và `reply` (các tin đã render, tin đầu là tin kết luận).
Thêm một lượt dựng tin kết luận từ template hiện tại trên eval/datasets bằng pipeline chỉ luật (không gọi mạng).
In bảng x/n kèm Wilson 95% theo từng ngưỡng tạm của readability.Thresholds và tối đa 10 ví dụ mỗi loại."""
import argparse
import collections
import json
import pathlib
import statistics

import readability as rd
from run_eval import CONFIG_DIR, DATASETS, REPO, LocalArtifacts, MemoryKV, build_bundle, load_samples
from run_judge_eval import plain
from run_otp_rules import wilson

from askfirst.domain import models as m
from askfirst.pipeline import build_pipeline

# Từ tiếng Anh mà chính template của bot dùng ("đọc OTP", "ĐỪNG bấm link"): tách riêng để thấy phần LLM tự thêm.
TEMPLATE_EN = {"otp", "link"}


def rules_only_conclusions() -> list[tuple[str, str]]:
    pipe = build_pipeline("real", CONFIG_DIR, LocalArtifacts(REPO / "ai" / "artifacts"), MemoryKV())
    actx = m.AnalyzeContext(check_id="readability", user_id="eval",
                            flags=m.Flags(llm_enabled=False, feature_rag=False), deadline_s=85)
    rctx = m.RenderContext(check_id="readability", address_form="elder", has_guardian=True)
    out = []
    for s in load_samples(sorted(DATASETS.glob("*.jsonl"))):
        v = pipe.analyze(build_bundle(s), actx)
        if v.outcome == "verdict":
            out.append((s["id"], plain(pipe.render(v, rctx).messages[0].text_html)))
    return out


def report(title: str, items: list[tuple[str, str]], t: rd.Thresholds) -> None:
    wl = rd.load_wordlist()
    reps = [(rid, text, rd.measure(text, wl)) for rid, text in items if text.strip()]
    n = len(reps)
    checks = {
        f"dài > {t.max_chars} ký tự": lambda r: r.chars > t.max_chars,
        f"câu dài TB > {t.max_words_per_sentence:g} tiếng": lambda r: r.words_per_sentence > t.max_words_per_sentence,
        f"> {t.max_hard_words} từ khó": lambda r: len(r.hard_words) > t.max_hard_words,
        f"có tiếng Anh (> {t.max_english_words})": lambda r: len(r.english_words) > t.max_english_words,
        "có tiếng Anh ngoài OTP/link": lambda r: bool(set(r.english_words) - TEMPLATE_EN),
        "vượt ít nhất một ngưỡng": lambda r: bool(r.flags(t)),
    }
    print(f"\n## {title}\n\n{n} tin (bỏ tin rỗng), {len({x for _, x, _ in reps})} nội dung khác nhau. "
          f"Ký tự trung vị {statistics.median(r.chars for *_, r in reps):.0f} (max {max(r.chars for *_, r in reps)}), "
          f"tiếng/câu trung vị {statistics.median(r.words_per_sentence for *_, r in reps):.1f} "
          f"(max {max(r.words_per_sentence for *_, r in reps):.1f}).\n")
    print("| Chỉ số | x/n | Wilson 95% |\n|---|---|---|")
    hits = {}
    for name, cond in checks.items():
        hits[name] = [(rid, text, r) for rid, text, r in reps if cond(r)]
        lo, hi = wilson(len(hits[name]), n)
        print(f"| {name} | {len(hits[name])}/{n} | {lo:.0%}–{hi:.0%} |")
    for kind, attr in (("Từ khó", "hard_words"), ("Tiếng Anh", "english_words")):
        c = collections.Counter(w for *_, r in reps for w in getattr(r, attr))
        print(f"\n{kind} hay gặp: " + (", ".join(f"{w} {k}" for w, k in c.most_common(10)) or "không có"))
    for name in list(checks)[:3] + ["có tiếng Anh ngoài OTP/link"]:
        if hits[name]:
            print(f"\n### {name} ({len(hits[name])}, tối đa 10)")
            for rid, text, r in hits[name][:10]:
                print(f"- `{rid}` ({r.chars} ký tự, {r.words_per_sentence:.1f} tiếng/câu, "
                      f"khó {r.hard_words}, Anh {r.english_words}): {' '.join(text.split())[:160]}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("data", nargs="?", type=pathlib.Path, default=REPO / "docs" / "results" / "ai11-judge-scores.jsonl")
    a = ap.parse_args()
    t = rd.Thresholds()
    rows = [json.loads(x) for x in a.data.read_text(encoding="utf-8").splitlines() if x.strip()]
    print(f"Ngưỡng tạm, chưa hiệu chỉnh: {t}. Dữ liệu LLM: {a.data.relative_to(REPO).as_posix()} ({len(rows)} dòng)")
    report("Lời giải thích của LLM (`explanation`)", [(r["id"], r.get("explanation") or "") for r in rows], t)
    report("Tin kết luận có lời giải thích LLM (`reply[0]`)", [(r["id"], r["reply"][0]) for r in rows if r.get("reply")], t)
    report("Tin kết luận khi chạy chỉ luật, template hiện tại (eval/datasets)", rules_only_conclusions(), t)


if __name__ == "__main__":
    main()
