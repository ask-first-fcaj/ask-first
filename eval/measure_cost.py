"""Đo token Bedrock và đơn vị Guardrail của mỗi lượt kiểm tra, làm số đầu vào cho dự toán chi phí (SH-09, #160).
Chạy từ thư mục ai/:

    AWS_PROFILE=<profile> uv run python ../eval/measure_cost.py --out ../eval/out/cost   # mọi file eval/datasets/*.jsonl

Bọc client Bedrock và Guardrail của RealPipeline để đếm, không đổi ``Verdict`` hay contract. Lần gọi lỗi không có token
nên không được đếm. Ghi ``cost.json`` (số từng mẫu và tổng hợp) vào --out, không in tin nhắn hay câu trả lời của model.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from collections import Counter
from typing import Any

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import run_eval  # noqa: E402

from askfirst.pipeline import build_pipeline  # noqa: E402


class _Counting:
    def __init__(self, inner: Any) -> None:
        self.inner = inner
        self.calls: list[dict] = []

    def __getattr__(self, name: str) -> Any:
        return getattr(self.inner, name)


class CountingLLM(_Counting):
    def converse(self, role: str, messages: list[dict], system: str | None = None, extra_fields: dict | None = None) -> Any:
        out = self.inner.converse(role, messages, system=system, extra_fields=extra_fields)
        self.calls.append({"role": role, "in": out.input_tokens, "out": out.output_tokens, "usd": out.cost_usd})
        return out


class CountingGuard(_Counting):
    def check(self, text: str) -> Any:
        res = self.inner.check(text)
        self.calls.append({"units": res.text_units})
        return res


def measure(pipeline: Any, samples: list[dict]) -> list[dict]:
    llm, guard = CountingLLM(pipeline.llm), CountingGuard(pipeline.guard) if pipeline.guard else None
    pipeline.llm, pipeline.guard = llm, guard
    rows = []
    for s in samples:
        llm.calls.clear()
        if guard:
            guard.calls.clear()
        r = run_eval.run_sample(pipeline, s)
        g = guard.calls if guard else []
        rows.append({"id": s["id"], "split": s.get("split"), "path": (r["verdict"] or {}).get("path"), "error": r["error"],
                     "llm_calls": len(llm.calls), "roles": [c["role"] for c in llm.calls],
                     "in": sum(c["in"] for c in llm.calls), "out": sum(c["out"] for c in llm.calls),
                     "usd": sum(c["usd"] for c in llm.calls), "guard_calls": len(g), "guard_units": sum(c["units"] for c in g)})
    return rows


def summarize(rows: list[dict]) -> dict:
    def mean(rs: list[dict], k: str) -> float | None:
        return round(sum(r[k] for r in rs) / len(rs), 1) if rs else None

    keys = ("llm_calls", "in", "out", "guard_calls", "guard_units")
    with_llm = [r for r in rows if r["llm_calls"]]
    return {
        "n_checks": len(rows),
        "n_errors": sum(1 for r in rows if r["error"]),
        "n_with_llm": len(with_llm),
        "paths": dict(Counter(str(r["path"]) for r in rows)),
        "roles": dict(Counter(role for r in rows for role in r["roles"])),
        "mean_per_check": {k: mean(rows, k) for k in keys},
        "mean_per_llm_check": {k: mean(with_llm, k) for k in keys},
        "p95_per_llm_check": {k: run_eval.percentile([r[k] for r in with_llm], 95) for k in ("in", "out")},
        "usd_llm_total": round(sum(r["usd"] for r in rows), 6),
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("datasets", nargs="*", type=pathlib.Path, help="file .jsonl (mặc định eval/datasets/*.jsonl)")
    ap.add_argument("--limit", type=int, help="chỉ chạy N mẫu đầu")
    ap.add_argument("--out", type=pathlib.Path, default=run_eval.EVAL_DIR / "out" / "cost")
    args = ap.parse_args(argv)

    paths = args.datasets or sorted(run_eval.DATASETS.glob("*.jsonl"))
    samples = run_eval.load_samples(paths, None, args.limit)
    pipeline = build_pipeline("real", run_eval.CONFIG_DIR, run_eval.LocalArtifacts(run_eval.REPO / "ai" / "artifacts"), run_eval.MemoryKV())
    rows = measure(pipeline, samples)
    summary = summarize(rows)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "cost.json").write_text(json.dumps({"summary": summary, "rows": rows}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
