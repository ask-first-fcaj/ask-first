"""So sánh model judge trên bộ eval (AI-03, gate G1). Chạy từ thư mục ai/:

    AWS_PROFILE=<profile> uv run python ../eval/benchmark_models.py                  # mọi ứng viên, mọi file eval/datasets/*.jsonl
    AWS_PROFILE=<profile> uv run python ../eval/benchmark_models.py --only qwen3-32b --limit 5
    GEMINI_API_KEY=<key> uv run python ../eval/benchmark_models.py --only gemini-3-8-flash   # mốc tham chiếu, không cần AWS

Mỗi ứng viên chạy cùng RealPipeline thật (luật, URL intel, judge, fuse) với ``judge.model_id`` thay bằng model đó và
**không có model dự phòng** (``backup.model_id`` để trống), để số đo là của riêng model đó. Các ứng viên chạy song song,
trong một ứng viên các mẫu chạy tuần tự.

Ghi vào --out (mặc định eval/out/benchmark/, không commit):
- ``benchmark.json``: chỉ số của run_eval.compute_metrics cho từng ứng viên, thêm số đo từng lần gọi LLM
  (JSON hợp lệ, độ trễ, token, ``stopReason``).
- ``explanations.jsonl``: lời giải thích từng mẫu của từng ứng viên, để chấm tay (``--grading-sheet``).
Không in prompt hay câu trả lời ra màn hình (plan §3.1); bộ eval là dữ liệu tự soạn, không có tin của người dùng.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import random
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any

import yaml

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import run_eval  # noqa: E402
from gemini_client import GeminiClient  # noqa: E402

from askfirst.llm.bedrock import BedrockClient, LLMError  # noqa: E402
from askfirst.pipeline import build_pipeline  # noqa: E402
from askfirst.pipeline import judge as J  # noqa: E402

# Model ID lấy từ `aws bedrock list-foundation-models` / `list-inference-profiles` ở ap-southeast-2 (2026-11-11).
# Giá: AWS Price List API, on-demand "standard", ap-southeast-2 (USD / 1M token). Claude: giá chưa tra (account chưa có quyền).
CANDIDATES: dict[str, dict[str, Any]] = {
    "qwen3-32b": {"model_id": "qwen.qwen3-32b-v1:0", "price": {"in": 0.1545, "out": 0.618}},
    "qwen3-32b-thinking": {"model_id": "qwen.qwen3-32b-v1:0", "price": {"in": 0.1545, "out": 0.618},
                           "extra_fields": {"reasoning_effort": "high"}, "max_tokens": 2000, "timeout_s": 30},
    "gpt-oss-120b": {"model_id": "openai.gpt-oss-120b-1:0", "price": {"in": 0.1545, "out": 0.618},
                     "max_tokens": 800, "timeout_s": 12},
    "gemma-3-12b": {"model_id": "google.gemma-3-12b-it", "price": {"in": 0.0927, "out": 0.2987}},
    "claude-haiku-5-5": {"model_id": "au.anthropic.claude-haiku-5-5", "price": None},
    # Mốc tham chiếu thay Claude Haiku (AccessDenied): Gemini API, không qua Bedrock, cần GEMINI_API_KEY. Chỉ để so chất lượng,
    # không phải ứng viên production. Giá chuẩn trang giá Gemini API ngày 2026-11-11 (giá giới thiệu tới 2026-12-31).
    # max_tokens gồm cả token suy nghĩ của Gemini.
    "gemini-3-8-flash": {"provider": "gemini", "model_id": "gemini-3.8-flash", "price": {"in": 0.75, "out": 3.75},
                         "max_tokens": 2000, "timeout_s": 20},
}


def candidate_config(base: dict[str, Any], cand: dict[str, Any]) -> dict[str, Any]:
    """Bản sao models.yaml: ``judge`` dùng model của ứng viên, ``backup`` trống để không leo thang sang model khác."""
    cfg = json.loads(json.dumps(base))
    judge = {**cfg["judge"], "model_id": cand["model_id"], "max_attempts": 1}
    for key in ("max_tokens", "timeout_s"):
        if key in cand:
            judge[key] = cand[key]
    cfg["judge"] = judge
    cfg["backup"] = {**cfg["backup"], "model_id": ""}
    if cand.get("price"):
        cfg.setdefault("pricing_usd_per_1m", {})[cand["model_id"]] = cand["price"]
    return cfg


@dataclass
class Recorder:
    """Bọc client LLM: thêm ``extra_fields`` và ghi số đo từng lần gọi. Không giữ nội dung prompt/câu trả lời."""
    llm: Any
    extra_fields: dict | None = None
    calls: list[dict] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)

    def budget_s(self, role: str) -> float:
        return self.llm.budget_s(role)

    def model_id(self, role: str) -> str:
        return self.llm.model_id(role)

    def converse(self, role: str, messages: list[dict], system: str | None = None, extra_fields: dict | None = None) -> Any:
        if role != "judge":   # backup trống: để judge() giữ câu trả lời của judge như khi model dự phòng lỗi
            raise LLMError("benchmark: no backup", retryable=False)
        try:
            out = self.llm.converse(role, messages, system=system, extra_fields=extra_fields or self.extra_fields)
        except Exception as e:
            self.errors.append(str(e) if isinstance(e, LLMError) else type(e).__name__)
            raise
        try:
            J.parse_judgement(out.text)
            valid = True
        except J.JudgeParseError:
            valid = False
        self.calls.append({"json_valid": valid, "latency_ms": out.latency_ms, "input_tokens": out.input_tokens,
                           "output_tokens": out.output_tokens, "stop_reason": out.stop_reason, "cost_usd": out.cost_usd})
        return out


def call_stats(rec: Recorder) -> dict[str, Any]:
    calls = rec.calls
    if not calls:
        return {"n_calls": 0, "n_errors": len(rec.errors), "errors": sorted(set(rec.errors))}
    lat = [c["latency_ms"] for c in calls]
    stops: dict[str, int] = {}
    for c in calls:
        stops[c["stop_reason"]] = stops.get(c["stop_reason"], 0) + 1
    return {
        "n_calls": len(calls),
        "n_errors": len(rec.errors),
        "errors": sorted(set(rec.errors)),
        "json_valid_rate": sum(c["json_valid"] for c in calls) / len(calls),
        "llm_latency_ms": {"p50": run_eval.percentile(lat, 50), "p95": run_eval.percentile(lat, 95)},
        "input_tokens_median": statistics.median(c["input_tokens"] for c in calls),
        "output_tokens_median": statistics.median(c["output_tokens"] for c in calls),
        "stop_reasons": dict(sorted(stops.items())),
    }


def run_candidate(name: str, cand: dict[str, Any], base_cfg: dict[str, Any], samples: list[dict]) -> dict[str, Any]:
    pipeline = build_pipeline("real", run_eval.CONFIG_DIR, run_eval.LocalArtifacts(run_eval.REPO / "ai" / "artifacts"),
                              run_eval.MemoryKV())
    cfg = candidate_config(base_cfg, cand)
    client = GeminiClient(config=cfg) if cand.get("provider") == "gemini" else BedrockClient(config=cfg)
    rec = Recorder(client, cand.get("extra_fields"))
    pipeline.llm = rec
    started = time.perf_counter()
    results = run_eval.run(pipeline, samples, f"bench:{name}")
    metrics = run_eval.compute_metrics(samples, results)
    return {"name": name, "model_id": cand["model_id"], "extra_fields": cand.get("extra_fields"),
            "wall_s": round(time.perf_counter() - started, 1), "metrics": metrics, "llm": call_stats(rec),
            "results": results}


def grading_sheet(explanations: list[dict], samples: dict[str, dict], n: int, seed: int) -> str:
    """Bảng chấm tay: ``n`` mẫu ngẫu nhiên (seed cố định), mỗi mẫu đủ lời giải thích của mọi ứng viên, ẩn tên model."""
    by_sample: dict[str, dict[str, str]] = {}
    for e in explanations:
        by_sample.setdefault(e["id"], {})[e["model"]] = e["explanation"]
    ids = sorted(i for i, d in by_sample.items() if any(d.values()))
    rng = random.Random(seed)
    picked = sorted(rng.sample(ids, min(n, len(ids))))
    models = sorted({e["model"] for e in explanations if e["explanation"]})   # bỏ ứng viên không trả lời mẫu nào (vd AccessDenied)
    lines = ["# Chấm tay lời giải thích (AI-03)", "",
             "Mỗi lời giải thích chấm 1–5: tự nhiên, dễ hiểu với người lớn tuổi, đúng trọng tâm. Tên model ẩn sau chữ cái;",
             f"bảng giải mã nằm cuối file (seed {seed}).", ""]
    rng_models = random.Random(seed + 1)
    for sid in picked:
        order = models[:]
        rng_models.shuffle(order)
        lines += [f"## {sid}", "", f"> {samples[sid]['input']['text']}", ""]
        for k, model in enumerate(order):
            lines.append(f"- **{chr(65 + k)}** (điểm: __): {by_sample[sid].get(model) or '(không có)'}")
        lines.append("")
    lines += ["## Giải mã", ""]
    rng_models = random.Random(seed + 1)
    for sid in picked:
        order = models[:]
        rng_models.shuffle(order)
        lines.append(f"- {sid}: " + ", ".join(f"{chr(65 + k)}={m}" for k, m in enumerate(order)))
    return "\n".join(lines) + "\n"


def summary_table(rows: list[dict]) -> str:
    lines = ["| Ứng viên | Recall | False alarm | Đúng mức | Đúng kịch bản | JSON hợp lệ | LLM p50 / p95 (s) | Mẫu p95 (s) | USD/mẫu | Lỗi |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        m_, llm = r["metrics"], r["llm"]
        lat = llm.get("llm_latency_ms") or {}
        p = (lambda v: "n/a" if v is None else f"{v / 1000:.2f}")
        lines.append(f"| {r['name']} | {run_eval._pct(m_['recall'])} | {run_eval._pct(m_['false_alarm'])} | "
                     f"{run_eval._pct(m_['level_pass'])} | {run_eval._pct(m_['scenario_accuracy'])} | "
                     f"{run_eval._pct(llm.get('json_valid_rate'))} | {p(lat.get('p50'))} / {p(lat.get('p95'))} | "
                     f"{p(m_['latency_ms']['p95'])} | {m_['cost_usd_per_sample']} | {llm['n_errors']} |")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--only", nargs="*", choices=sorted(CANDIDATES), help="chỉ chạy các ứng viên này")
    ap.add_argument("--limit", type=int, help="chỉ chạy N mẫu đầu")
    ap.add_argument("--out", type=pathlib.Path, default=run_eval.EVAL_DIR / "out" / "benchmark")
    ap.add_argument("--grading-n", type=int, default=20)
    ap.add_argument("--seed", type=int, default=3)
    args = ap.parse_args(argv)

    samples = run_eval.load_samples(sorted(run_eval.DATASETS.glob("*.jsonl")), limit=args.limit)
    base_cfg = yaml.safe_load((run_eval.CONFIG_DIR / "models.yaml").read_text(encoding="utf-8"))
    names = args.only or list(CANDIDATES)
    with ThreadPoolExecutor(max_workers=len(names)) as pool:
        rows = list(pool.map(lambda n: run_candidate(n, CANDIDATES[n], base_cfg, samples), names))

    args.out.mkdir(parents=True, exist_ok=True)
    explanations = [{"model": r["name"], "id": x["id"], "explanation": (x["verdict"] or {}).get("explanation", ""),
                     "level": (x["verdict"] or {}).get("level")} for r in rows for x in r["results"]]
    (args.out / "explanations.jsonl").write_text(
        "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in explanations), encoding="utf-8")
    report = [{k: v for k, v in r.items() if k != "results"} for r in rows]
    for r in report:
        r["metrics"] = {k: v for k, v in r["metrics"].items() if k != "errors"} | {"n_errors": r["metrics"]["n_errors"]}
    (args.out / "benchmark.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    by_id = {s["id"]: s for s in samples}
    (args.out / "grading.md").write_text(grading_sheet(explanations, by_id, args.grading_n, args.seed), encoding="utf-8")
    sys.stdout.write(summary_table(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
