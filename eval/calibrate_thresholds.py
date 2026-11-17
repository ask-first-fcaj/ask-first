"""Hiệu chỉnh ngưỡng mức của fuse (AI-10, ADR-005). Chạy từ thư mục ai/:

    AWS_PROFILE=<profile> uv run python ../eval/calibrate_thresholds.py --capture ../eval/out/fuse_inputs.jsonl
    uv run python ../eval/calibrate_thresholds.py --inputs ../eval/out/fuse_inputs.jsonl   # chỉ quét lại, không gọi LLM

Profile tạo bằng ``aws login`` cần thêm ``uv run --with "botocore[crt]"``. Cần ``ai/artifacts/`` (embedder, kb_index,
xem ``ai/MODELS.md``) để RAG chạy như production. Kết quả chỉ dùng được khi "Đường xử lý" gần như không có ``rules_only``.

Bước 1 (--capture) chạy RealPipeline một lần trên bộ eval và ghi đầu vào của ``fuse`` cho từng mẫu (tín hiệu luật,
Judgement, URL chính thức). Bước 2 gọi lại ``fuse`` với từng bộ ngưỡng trên lưới. Kết quả khớp với chạy lại pipeline
bằng ngưỡng đó, vì các bước trước fuse (kể cả leo thang judge, ``judge.ESCALATE_*``) không dùng ngưỡng. Mẫu không qua
fuse (lỗi, xã giao/không đọc được) giữ nguyên mức.

Chọn bộ: báo nhầm benign_suspicious ≤ 10% trước; rồi recall cao nhất, báo nhầm thấp nhất, đúng mức nhãn cao nhất,
cuối cùng gần ngưỡng đang dùng (``F.Thresholds()``) nhất (không đổi khi không có lý do). AI-10 chạy lúc ngưỡng là 75/50/25.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import run_eval as E  # noqa: E402

from askfirst.domain import models as m  # noqa: E402
from askfirst.pipeline import build_pipeline  # noqa: E402
from askfirst.pipeline import fuse as F  # noqa: E402
from askfirst.pipeline.judge import Judgement  # noqa: E402

GRID_DANGEROUS = range(60, 91, 5)
GRID_SUSPICIOUS = range(35, 66, 5)
GRID_UNSURE = range(10, 41, 5)
START = F.Thresholds()


def capture(pipeline: m.Pipeline, samples: list[dict]) -> list[dict]:
    """Như ``run_eval.run`` (không cache), thêm trường "fuse" = đầu vào của ``F.fuse`` (None nếu mẫu không qua fuse)."""
    real_fuse = F.fuse
    rows = []
    for s in samples:
        if s["label"].get("is_scam") is None:
            continue
        seen: dict = {}

        def spy(signals, j, seen=seen, **kw):
            seen.update(signals=[x.model_dump(mode="json") for x in signals],
                        judgement=j.model_dump(mode="json") if j else None,
                        all_urls_official=kw["all_urls_official"], escalated=kw.get("escalated", False))
            return real_fuse(signals, j, **kw)

        F.fuse = spy   # RealPipeline gọi ``F.fuse`` qua module nên thay được
        try:
            res = E.run_sample(pipeline, s)
        finally:
            F.fuse = real_fuse
        rows.append(res | {"fuse": seen or None})
    return rows


def _parsed(rows: list[dict]) -> list[tuple | None]:
    out = []
    for r in rows:
        f = r.get("fuse")
        out.append(None if f is None else (
            [m.Signal.model_validate(x) for x in f["signals"]],
            Judgement.model_validate(f["judgement"]) if f["judgement"] else None,
            f["all_urls_official"], f["escalated"]))
    return out


def relevel(rows: list[dict], parsed: list[tuple | None], t: F.Thresholds) -> list[dict]:
    """Kết quả từng mẫu khi chạy với ngưỡng ``t``: chỉ ``level`` (và ``should_alert``) đổi, điểm giữ nguyên."""
    out = []
    for r, p in zip(rows, parsed, strict=True):
        if p is None or r["verdict"] is None:
            out.append(r)
            continue
        signals, j, official, escalated = p
        v = F.fuse(signals, j, all_urls_official=official, escalated=escalated, thresholds=t)
        out.append(r | {"verdict": r["verdict"] | {"level": v.level, "should_alert": v.should_alert}})
    return out


def grid() -> list[F.Thresholds]:
    return [F.Thresholds(d, s, u) for d in GRID_DANGEROUS for s in GRID_SUSPICIOUS for u in GRID_UNSURE if d > s > u]


def _dist(t: F.Thresholds) -> int:
    return abs(t.dangerous - START.dangerous) + abs(t.suspicious - START.suspicious) + abs(t.unsure - START.unsure)


def rank_key(t: F.Thresholds, mx: dict) -> tuple:
    fa = mx["false_alarm"] or 0.0
    return (fa > E.FALSE_ALARM_MAX, -(mx["recall"] or 0.0), fa, -(mx["level_pass"] or 0.0), _dist(t))


def sweep(samples: list[dict], rows: list[dict]) -> list[tuple[F.Thresholds, dict]]:
    """Mọi bộ trên lưới kèm chỉ số ``run_eval.compute_metrics``, bộ tốt nhất đứng đầu."""
    parsed = _parsed(rows)
    scored = [(t, E.compute_metrics(samples, relevel(rows, parsed, t))) for t in grid()]
    return sorted(scored, key=lambda x: rank_key(*x))


def _danger_on_benign(samples: list[dict], rows: list[dict]) -> int:
    benign = {s["id"] for s in samples if s["label"].get("is_scam") is False}
    return sum(1 for r in rows if r["id"] in benign and r["verdict"] and r["verdict"]["level"] == "DANGEROUS")


def render(samples: list[dict], rows: list[dict], ranked: list[tuple[F.Thresholds, dict]], top: int = 10) -> str:
    parsed = _parsed(rows)
    paths = E.compute_metrics(samples, rows)["path_counts"]

    def line(t: F.Thresholds, mx: dict, note: str = "") -> str:
        fa = mx["false_alarm_by_split"]
        return (f"| {t.dangerous}/{t.suspicious}/{t.unsure}{note} | {E._pct(mx['recall'])} | {E._pct(mx['unsure_rate'])} "
                f"| {E._pct(mx['false_alarm'])} | {E._pct(fa.get('benign_normal'))} | {E._pct(mx['level_pass'])} "
                f"| {_danger_on_benign(samples, relevel(rows, parsed, t))} |")

    start = next(mx for t, mx in ranked if t == START)
    return "\n".join([
        f"# Hiệu chỉnh ngưỡng (AI-10): {len(rows)} mẫu có nhãn, {len(ranked)} bộ ngưỡng",
        "",
        f"Đường xử lý lúc chạy: {paths}. Mẫu lỗi: {sum(1 for r in rows if r['error'])}.",
        "",
        "| Ngưỡng D/S/U | Recall | UNSURE trên lừa đảo | Báo nhầm (benign_suspicious) | Báo nhầm (benign_normal) "
        "| Đúng mức nhãn | Tin lành bị DANGEROUS |",
        "|---|---|---|---|---|---|---|",
        line(START, start, " (đang dùng)"),
        *[line(t, mx, " (chọn)" if i == 0 else "") for i, (t, mx) in enumerate(ranked[:top])],
    ]) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("datasets", nargs="*", type=pathlib.Path, help="file .jsonl (mặc định eval/datasets/*.jsonl)")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--capture", type=pathlib.Path, help="chạy RealPipeline, ghi đầu vào fuse ra file .jsonl này")
    src.add_argument("--inputs", type=pathlib.Path, help="đọc file .jsonl đã ghi bằng --capture, không gọi LLM")
    ap.add_argument("--out", type=pathlib.Path, default=E.EVAL_DIR / "out" / "thresholds.md")
    args = ap.parse_args(argv)

    samples = E.load_samples(args.datasets or sorted(E.DATASETS.glob("*.jsonl")))
    if args.capture:
        pipeline = build_pipeline("real", E.CONFIG_DIR, E.LocalArtifacts(E.REPO / "ai" / "artifacts"), E.MemoryKV())
        rows = capture(pipeline, samples)
        args.capture.parent.mkdir(parents=True, exist_ok=True)
        args.capture.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    else:
        rows = [json.loads(x) for x in args.inputs.read_text(encoding="utf-8").splitlines() if x.strip()]

    md = render(samples, rows, sweep(samples, rows))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(md, encoding="utf-8")
    sys.stdout.write(md)
    return 0


if __name__ == "__main__":
    sys.exit(main())
