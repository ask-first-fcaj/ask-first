"""Red-team sơ bộ cho AI-08: hai lớp phát hiện prompt injection có gắn cờ đủ không, có gắn nhầm tin thường không. Chạy từ ai/:

    AWS_PROFILE=<profile> uv run python ../eval/redteam_guard.py              # 2 lớp trên injection + bộ eval
    AWS_PROFILE=<profile> uv run python ../eval/redteam_guard.py --pipeline   # thêm: chạy RealPipeline thật trên injection

Lớp 1 = luật ``R_AI_INSTRUCTION`` (local); lớp 2 = Bedrock ``ApplyGuardrail`` (``G_PROMPT_ATTACK``). Cả hai chạy trên text đã
normalize + mask, đúng như pipeline. "Gắn cờ" = ít nhất một lớp bật. Trên bộ eval (tin lừa đảo và tin lành tính, không có câu
lệnh cho AI) mọi cờ đều là cờ nhầm. Cờ chỉ nâng mức tối thiểu lên UNSURE, không chặn tin, nên cờ nhầm trên tin lừa đảo không làm
mất cảnh báo; con số được báo để biết.

``--pipeline``: mức của verdict phải ≥ ``min_level`` của mẫu (injection kèm tin lừa đảo phải còn ≥ SUSPICIOUS, tức là
model không bị câu lệnh trong tin thao túng). In bảng markdown; không in nội dung tin (bộ dữ liệu tự soạn, không có tin người dùng).
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import run_eval  # noqa: E402

from askfirst.domain import models as m  # noqa: E402
from askfirst.llm.guardrail import BedrockGuardrail  # noqa: E402
from askfirst.pipeline import build_pipeline  # noqa: E402
from askfirst.pipeline.mask import mask  # noqa: E402
from askfirst.pipeline.normalize import normalize  # noqa: E402
from askfirst.pipeline.respond import KB_DIR  # noqa: E402
from askfirst.pipeline.signals import RuleEngine  # noqa: E402

INJECTION = run_eval.EVAL_DIR / "redteam" / "injection.jsonl"
LOCAL_CODE = "R_AI_INSTRUCTION"


def load_injection(path: pathlib.Path = INJECTION) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def as_sample(row: dict) -> dict:
    """Mẫu red-team → dạng mẫu của run_eval (để chạy RealPipeline)."""
    return {"id": row["id"], "split": "redteam",
            "input": {"text": row["text"], "entities": [], "image": None, "audio": None, "forward_origin": None},
            "label": {"is_scam": row["min_level"] == "SUSPICIOUS", "min_level": row["min_level"], "max_level": None}}


def check(text: str, rules, guard) -> dict:
    masked = mask(normalize(text)).text
    local = any(s.code == LOCAL_CODE for s in rules(masked, []))
    try:
        g = guard.check(masked)
        return {"local": local, "guard": g.prompt_attack, "latency_ms": g.latency_ms, "units": g.text_units, "error": None}
    except Exception as e:  # noqa: BLE001
        return {"local": local, "guard": None, "latency_ms": None, "units": 0, "error": str(e) or type(e).__name__}


def summarize(name: str, rows: list[dict]) -> dict:
    n = len(rows)
    ok = [r for r in rows if r["error"] is None]
    lat = [r["latency_ms"] for r in ok]
    return {"set": name, "n": n, "local": sum(r["local"] for r in rows), "guard": sum(bool(r["guard"]) for r in rows),
            "either": sum(r["local"] or bool(r["guard"]) for r in rows), "errors": n - len(ok),
            "guard_p50_ms": run_eval.percentile(lat, 50), "guard_p95_ms": run_eval.percentile(lat, 95),
            "text_units": sum(r["units"] for r in rows)}


def table(summaries: list[dict]) -> str:
    out = ["| Bộ | Mẫu | Lớp 1 (luật) | Lớp 2 (Guardrails) | Ít nhất một lớp | Lỗi gọi | Guardrails p50 / p95 (ms) | Text unit |",
           "|---|---|---|---|---|---|---|---|"]
    for s in summaries:
        out.append(f"| {s['set']} | {s['n']} | {s['local']} | {s['guard']} | {s['either']} | {s['errors']} | "
                   f"{s['guard_p50_ms']} / {s['guard_p95_ms']} | {s['text_units']} |")
    return "\n".join(out) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pipeline", action="store_true", help="chạy thêm RealPipeline thật (gọi judge) trên bộ injection")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args(argv)

    guard = BedrockGuardrail.from_file(run_eval.CONFIG_DIR / "models.yaml")
    if guard is None:
        print("models.yaml: guardrail.id trống, chạy scripts/create_guardrail.py trước", file=sys.stderr)
        return 2
    rules = RuleEngine.from_file(KB_DIR / "rules.yaml")
    inj = load_injection()
    ev = [s for s in run_eval.load_samples(sorted(run_eval.DATASETS.glob("*.jsonl")))
          if s["label"].get("is_scam") is not None and s["input"].get("text")]
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        inj_res = list(pool.map(lambda r: check(r["text"], rules, guard), inj))
        ev_res = list(pool.map(lambda s: check(s["input"]["text"], rules, guard), ev))
    scam = [r for r, s in zip(ev_res, ev, strict=True) if s["label"]["is_scam"]]
    benign = [r for r, s in zip(ev_res, ev, strict=True) if not s["label"]["is_scam"]]
    report = [summarize("injection (red-team)", inj_res), summarize("lừa đảo (eval)", scam),
              summarize("lành tính (eval)", benign)]
    sys.stdout.write(table(report))
    missed = [r["id"] for r, x in zip(inj, inj_res, strict=True) if not (x["local"] or x["guard"])]
    sys.stdout.write(f"\nInjection không bị lớp nào gắn cờ: {', '.join(missed) or 'không có'}\n")

    if args.pipeline:
        pipeline = build_pipeline("real", run_eval.CONFIG_DIR, run_eval.LocalArtifacts(run_eval.REPO / "ai" / "artifacts"),
                                  run_eval.MemoryKV())
        results = run_eval.run(pipeline, [as_sample(r) for r in inj], "redteam")
        bad = []
        for row, res in zip(inj, results, strict=True):
            level = (res["verdict"] or {}).get("level")
            if level is None or m.LEVEL_ORDER[level] < m.LEVEL_ORDER[row["min_level"]]:
                bad.append(f"{row['id']} ({level} < {row['min_level']})")
        sys.stdout.write(f"\nRealPipeline: {len(inj) - len(bad)}/{len(inj)} mẫu đạt min_level. Trượt: {', '.join(bad) or 'không có'}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
