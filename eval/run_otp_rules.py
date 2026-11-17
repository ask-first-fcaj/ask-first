"""Đo R_OTP_REQUEST / R_OTP_GENUINE trên bộ OTP thật vs lừa (AI-30, #90). Chạy từ thư mục ai/:
  uv run python ../eval/run_otp_rules.py [file.jsonl]   (mặc định ai/tests/data/otp_pairs.jsonl)
Chạy pipeline thật ở chế độ chỉ luật (llm_enabled=False): normalize → mask → rules → fuse. Không gọi mạng.
In bảng x/n kèm Wilson 95% và tối đa 10 ví dụ lỗi mỗi loại."""
import argparse
import math
import pathlib

from run_eval import CONFIG_DIR, REPO, LocalArtifacts, MemoryKV, build_bundle, load_samples

from askfirst.domain import models as m
from askfirst.pipeline import build_pipeline


def wilson(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    if n == 0:
        return (0.0, 0.0)
    p, d = k / n, 1 + z * z / n
    c, h = (p + z * z / (2 * n)) / d, z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("data", nargs="?", type=pathlib.Path, default=REPO / "ai" / "tests" / "data" / "otp_pairs.jsonl")
    a = ap.parse_args()
    pipe = build_pipeline("real", CONFIG_DIR, LocalArtifacts(REPO / "ai" / "artifacts"), MemoryKV())
    flags = m.Flags(llm_enabled=False, feature_rag=False)
    ctx = m.AnalyzeContext(check_id="otp-rules", user_id="eval", flags=flags, deadline_s=85)
    rows = []
    for s in load_samples([a.data]):
        v = pipe.analyze(build_bundle(s), ctx)
        rows.append((s, {x.code for x in v.signals}, v.level))

    def line(name: str, sel, cond) -> list:
        hit = [(s, c, lv) for s, c, lv in rows if sel(s) and cond(c, lv)]
        n = sum(1 for s, _, _ in rows if sel(s))
        lo, hi = wilson(len(hit), n)
        print(f"| {name} | {len(hit)}/{n} | {lo:.0%}–{hi:.0%} |")
        return hit

    benign = lambda s: not s["label"]["is_scam"]  # noqa: E731
    scam = lambda s: s["label"]["is_scam"]  # noqa: E731
    trap = lambda s: scam(s) and "neg-trap" in (s.get("notes") or "")  # noqa: E731
    flagged = lambda c, lv: m.LEVEL_ORDER[lv] >= m.LEVEL_ORDER["SUSPICIOUS"]  # noqa: E731
    print(f"Dữ liệu: {a.data.relative_to(REPO).as_posix()} ({len(rows)} mẫu), chỉ luật\n")
    print("| Chỉ số | x/n | Wilson 95% |\n|---|---|---|")
    errors = {
        "Tin thật bị bật R_OTP_REQUEST": line("Tin thật: bật `R_OTP_REQUEST` (báo nhầm)", benign, lambda c, lv: "R_OTP_REQUEST" in c),
        "Tin thật không bật R_OTP_GENUINE": line("Tin thật: không bật `R_OTP_GENUINE`", benign, lambda c, lv: "R_OTP_GENUINE" not in c),
        "Tin thật bị xếp ≥ SUSPICIOUS": line("Tin thật: mức ≥ SUSPICIOUS (báo nhầm)", benign, flagged),
        "Tin lừa không bật R_OTP_REQUEST": line("Tin lừa: không bật `R_OTP_REQUEST` (sót)", scam, lambda c, lv: "R_OTP_REQUEST" not in c),
        "Tin lừa bật R_OTP_GENUINE": line("Tin lừa: bật `R_OTP_GENUINE` (bị hạ điểm)", scam, lambda c, lv: "R_OTP_GENUINE" in c),
        "Tin lừa dưới SUSPICIOUS": line("Tin lừa: mức < SUSPICIOUS (sót)", scam, lambda c, lv: not flagged(c, lv)),
        "neg-trap bật R_OTP_GENUINE": line("neg-trap: bật `R_OTP_GENUINE`", trap, lambda c, lv: "R_OTP_GENUINE" in c),
        "neg-trap dưới SUSPICIOUS": line("neg-trap: mức < SUSPICIOUS (sót)", trap, lambda c, lv: not flagged(c, lv)),
    }
    for name, hit in errors.items():
        if hit:
            print(f"\n### {name} ({len(hit)}, tối đa 10)")
            for s, c, lv in hit[:10]:
                print(f"- `{s['id']}` {lv} {sorted(c)}: {s['input']['text'][:120]}")


if __name__ == "__main__":
    main()
