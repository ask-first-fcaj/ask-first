"""Chạy bộ eval qua pipeline và tính chỉ số (AI-01, plan §3.8, ARCH §6.11). Chạy từ thư mục ai/ để dùng venv của askfirst:

    uv run python ../eval/run_eval.py                          # pipeline stub, mọi file eval/datasets/*.jsonl
    uv run python ../eval/run_eval.py --pipeline real          # khi đã có RealPipeline (INT-01)
    uv run python ../eval/run_eval.py --split real_scam --limit 10
    uv run python ../eval/run_eval.py --baseline main.json --gate   # so với lần chạy trên main, trả mã 1 nếu trượt ngưỡng

Ghi report.json và report.md vào --out (mặc định eval/out/, không commit). Cách tính theo plan §3.8:
- "Bắt được" = level ≥ SUSPICIOUS. Recall tính trên mẫu is_scam=true; tỷ lệ UNSURE báo riêng ("tỷ lệ chưa chắc").
- False alarm = mẫu benign_suspicious bị bắt (các split lành khác báo riêng).
- Mẫu lỗi khi chạy tính là không bắt được (với mẫu lừa đảo) và được đếm riêng.
- Thiếu must_signals chỉ liệt kê, không tính là sai.
- Mẫu không có label.is_scam (ocr_images) bỏ qua và được đếm.
- JSON hợp lệ và trích dẫn bị loại: chưa có dữ liệu từ pipeline nên ghi n/a.
Có --cache thì kết quả mỗi mẫu được lưu theo hash(pipeline + code/config + input), chạy lại không gọi LLM lần nữa.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import pathlib
import sys
import time
from collections import Counter

from askfirst.domain import models as m
from askfirst.pipeline import build_pipeline

EVAL_DIR = pathlib.Path(__file__).resolve().parent
REPO = EVAL_DIR.parent
DATASETS = EVAL_DIR / "datasets"
CONFIG_DIR = REPO / "ai" / "config"
# Đổi bất kỳ file nào ở đây thì cache kết quả cũ không dùng lại nữa.
VERSIONED_DIRS = [REPO / "ai" / "src", REPO / "ai" / "config", REPO / "ai" / "prompts", REPO / "ai" / "kb", REPO / "kb", REPO / "prompts"]
GROUPS = ["N1", "N2", "N3", "N4", "N5", "N6"]
CAUGHT = m.LEVEL_ORDER["SUSPICIOUS"]
RECALL_MIN = 0.90
FALSE_ALARM_MAX = 0.10
MAX_DRIFT = 0.02  # 2 điểm phần trăm so với baseline (ARCH §6.11)


class MemoryKV:
    """KVCache trong RAM cho eval (không có TTL)."""

    def __init__(self) -> None:
        self._data: dict[str, dict] = {}

    def get(self, key: str) -> dict | None:
        return self._data.get(key)

    def put(self, key: str, value: dict, ttl_s: int) -> None:
        self._data[key] = value


class LocalArtifacts:
    """ArtifactStore đọc thẳng thư mục local, như CLI."""

    def __init__(self, root: pathlib.Path) -> None:
        self.root = root

    def path(self, name: str) -> pathlib.Path:
        return self.root / name


def load_samples(paths: list[pathlib.Path], split: str | None = None, limit: int | None = None) -> list[dict]:
    samples = []
    for p in paths:
        for line in p.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rec = json.loads(line)
                if split is None or rec["split"] == split:
                    samples.append(rec)
    return samples[:limit] if limit else samples


def _read_media(rel: str) -> tuple[bytes, str]:
    path = EVAL_DIR / rel
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    return path.read_bytes(), mime


def build_bundle(sample: dict) -> m.Bundle:
    inp = sample["input"]
    texts = [m.TextPart(text=inp["text"], entities=inp.get("entities") or [])] if inp.get("text") else []
    images, audios = [], []
    if inp.get("image"):
        data, mime = _read_media(inp["image"])
        images.append(m.ImageInput(data=data, file_unique_id=f"eval-{sample['id']}-img", mime=mime))
    if inp.get("audio"):
        data, mime = _read_media(inp["audio"])
        # Bộ eval chưa ghi thời lượng audio; 0 = chưa biết.
        audios.append(m.AudioInput(data=data, file_unique_id=f"eval-{sample['id']}-audio", mime=mime, duration_s=0))
    return m.Bundle(texts=texts, images=images, audios=audios, forward_origin=inp.get("forward_origin"))


def code_version(dirs: list[pathlib.Path] = VERSIONED_DIRS) -> str:
    h = hashlib.sha256()
    for d in dirs:
        if not d.is_dir():
            continue
        for f in sorted(p for p in d.rglob("*") if p.is_file() and "__pycache__" not in p.parts):
            h.update(f"{d.name}/{f.relative_to(d).as_posix()}".encode())
            h.update(f.read_bytes())
    return h.hexdigest()


def cache_key(impl: str, version: str, sample: dict) -> str:
    h = hashlib.sha256(f"{impl}\n{version}\n".encode())
    h.update(json.dumps(sample["input"], sort_keys=True, ensure_ascii=False).encode())
    for key in ("image", "audio"):
        if sample["input"].get(key):
            h.update((EVAL_DIR / sample["input"][key]).read_bytes())
    return h.hexdigest()


def run_sample(pipeline: m.Pipeline, sample: dict) -> dict:
    """Chạy một mẫu; không bao giờ raise, lỗi ghi vào trường "error"."""
    ctx = m.AnalyzeContext(check_id=f"eval-{sample['id']}", user_id="eval", flags=m.Flags(), deadline_s=85)
    start = time.perf_counter()
    try:
        verdict = pipeline.analyze(build_bundle(sample), ctx)
    except Exception as e:  # noqa: BLE001 — một mẫu lỗi không được làm dừng cả lượt eval
        return {"id": sample["id"], "verdict": None, "wall_ms": round((time.perf_counter() - start) * 1000), "error": f"{type(e).__name__}: {e}"}
    return {"id": sample["id"], "verdict": verdict.model_dump(mode="json", exclude={"masked_text"}),
            "wall_ms": round((time.perf_counter() - start) * 1000), "error": None}


def run(pipeline: m.Pipeline, samples: list[dict], impl: str, cache_dir: pathlib.Path | None = None) -> list[dict]:
    version = code_version() if cache_dir else ""
    results = []
    for s in samples:
        if s["label"].get("is_scam") is None:
            continue
        path = cache_dir / f"{cache_key(impl, version, s)}.json" if cache_dir else None
        if path and path.is_file():
            res = json.loads(path.read_text(encoding="utf-8")) | {"cached": True}
        else:
            res = run_sample(pipeline, s) | {"cached": False}
            if path and res["error"] is None:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(res, ensure_ascii=False), encoding="utf-8")
        results.append(res)
    return results


def _rate(num: int, den: int) -> float | None:
    return num / den if den else None


def percentile(values: list[float], q: float) -> float | None:
    """Nearest-rank, đủ cho p50/p95 trên vài trăm mẫu."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, -(-len(ordered) * q // 100))  # ceil(n*q/100)
    return ordered[int(rank) - 1]


def _level_ok(label: dict, level: str | None) -> bool:
    if level is None:
        return False
    if label.get("min_level") and m.LEVEL_ORDER[level] < m.LEVEL_ORDER[label["min_level"]]:
        return False
    return not (label.get("max_level") and m.LEVEL_ORDER[level] > m.LEVEL_ORDER[label["max_level"]])


def compute_metrics(samples: list[dict], results: list[dict]) -> dict:
    by_id = {r["id"]: r for r in results}
    scored = [s for s in samples if s["id"] in by_id]
    rows = []
    for s in scored:
        v = by_id[s["id"]]["verdict"]
        level = v["level"] if v else None
        rows.append({"sample": s, "label": s["label"], "v": v, "level": level,
                     "caught": level is not None and m.LEVEL_ORDER[level] >= CAUGHT})

    scams = [r for r in rows if r["label"]["is_scam"]]
    benign_by_split: dict[str, list[dict]] = {}
    for r in rows:
        if not r["label"]["is_scam"]:
            benign_by_split.setdefault(r["sample"]["split"], []).append(r)
    false_alarm = {split: _rate(sum(r["caught"] for r in rs), len(rs)) for split, rs in sorted(benign_by_split.items())}

    f1 = {}
    for g in GROUPS:
        tp = sum(1 for r in rows if r["label"]["group"] == g and r["v"] and r["v"]["group"] == g)
        fp = sum(1 for r in rows if r["label"]["group"] != g and r["v"] and r["v"]["group"] == g)
        fn = sum(1 for r in rows if r["label"]["group"] == g) - tp
        f1[g] = {"support": tp + fn, "f1": _rate(2 * tp, 2 * tp + fp + fn)}

    missing = []
    for r in rows:
        got = {sig["code"] for sig in r["v"]["signals"]} if r["v"] else set()
        lack = [c for c in r["label"].get("must_signals") or [] if c not in got]
        if lack:
            missing.append({"id": r["sample"]["id"], "missing": lack})

    ok = [r["v"] for r in rows if r["v"]]
    stage_ms: dict[str, list[int]] = {}
    for v in ok:
        for stage, ms in v["latency_ms"].items():
            stage_ms.setdefault(stage, []).append(ms)
    walls = [by_id[r["sample"]["id"]]["wall_ms"] for r in rows if r["v"]]
    paths = Counter(v["path"] for v in ok)
    cost = sum(v["cost_usd_est"] for v in ok)

    return {
        "n_samples": len(samples),
        "n_scored": len(rows),
        "n_skipped_no_label": len(samples) - len(rows),
        "n_errors": sum(1 for r in rows if r["v"] is None),
        "n_cached": sum(1 for r in results if r.get("cached")),
        "recall": _rate(sum(r["caught"] for r in scams), len(scams)),
        "n_scam": len(scams),
        "unsure_rate": _rate(sum(1 for r in scams if r["level"] == "UNSURE"), len(scams)),
        "false_alarm": false_alarm.get("benign_suspicious"),
        "false_alarm_by_split": false_alarm,
        "level_pass": _rate(sum(_level_ok(r["label"], r["level"]) for r in rows), len(rows)),
        "scenario_accuracy": _rate(sum(1 for r in rows if r["v"] and r["v"]["scenario"] == r["label"]["scenario"]), len(rows)),
        "f1_by_group": f1,
        "missing_must_signals": missing,
        "path_counts": dict(sorted(paths.items())),
        "fast_path_rate": _rate(paths.get("fast", 0), len(ok)),
        "latency_ms": {"p50": percentile(walls, 50), "p95": percentile(walls, 95)},
        "stage_latency_p95_ms": {k: percentile(vs, 95) for k, vs in sorted(stage_ms.items())},
        "cost_usd_total": round(cost, 6),
        "cost_usd_per_sample": round(cost / len(ok), 6) if ok else None,
        "json_valid_rate": None,          # cần RealPipeline báo số lần LLM trả JSON hỏng
        "rejected_citation_rate": None,   # cần RealPipeline báo số trích dẫn bị post-check loại
        "errors": [{"id": r["id"], "error": r["error"]} for r in results if r["error"]],
    }


def gate(metrics: dict, baseline: dict | None = None) -> list[str]:
    """Lý do trượt cổng CI (rỗng = đạt): recall < 90%, false alarm > 10%, hoặc tệ hơn baseline quá 2 điểm."""
    fails = []
    recall, fa = metrics["recall"], metrics["false_alarm"]
    if recall is None or recall < RECALL_MIN:
        fails.append(f"recall {_pct(recall)} < {RECALL_MIN:.0%}")
    if fa is not None and fa > FALSE_ALARM_MAX:
        fails.append(f"false alarm {_pct(fa)} > {FALSE_ALARM_MAX:.0%}")
    if baseline:
        if recall is not None and baseline.get("recall") is not None and baseline["recall"] - recall > MAX_DRIFT:
            fails.append(f"recall giảm {_pct(baseline['recall'] - recall)} so với baseline")
        if fa is not None and baseline.get("false_alarm") is not None and fa - baseline["false_alarm"] > MAX_DRIFT:
            fails.append(f"false alarm tăng {_pct(fa - baseline['false_alarm'])} so với baseline")
    return fails


def _pct(x: float | None) -> str:
    return "n/a" if x is None else f"{x:.1%}"


def _delta(cur: float | None, base: dict | None, key: str) -> str:
    if not base or cur is None or base.get(key) is None:
        return ""
    return f" ({(cur - base[key]) * 100:+.1f} điểm)"


def render_markdown(metrics: dict, impl: str, baseline: dict | None = None, fails: list[str] | None = None) -> str:
    lat = metrics["latency_ms"]
    lines = [
        f"# Báo cáo eval (pipeline `{impl}`)",
        "",
        f"Mẫu: {metrics['n_samples']} · chấm: {metrics['n_scored']} · bỏ qua (không có is_scam): {metrics['n_skipped_no_label']}"
        f" · lỗi: {metrics['n_errors']} · lấy từ cache: {metrics['n_cached']}",
        "",
        "| Chỉ số | Giá trị |",
        "|---|---|",
        f"| Recall ({metrics['n_scam']} mẫu lừa đảo) | {_pct(metrics['recall'])}{_delta(metrics['recall'], baseline, 'recall')} |",
        f"| Tỷ lệ chưa chắc (UNSURE trên mẫu lừa đảo) | {_pct(metrics['unsure_rate'])} |",
        f"| False alarm (benign_suspicious) | {_pct(metrics['false_alarm'])}{_delta(metrics['false_alarm'], baseline, 'false_alarm')} |",
        *[f"| False alarm ({k}) | {_pct(v)} |" for k, v in metrics["false_alarm_by_split"].items() if k != "benign_suspicious"],
        f"| Đúng mức nhãn (min/max_level) | {_pct(metrics['level_pass'])} |",
        f"| Đúng mã kịch bản | {_pct(metrics['scenario_accuracy'])} |",
        f"| Fast path | {_pct(metrics['fast_path_rate'])} |",
        f"| Độ trễ p50 / p95 | {lat['p50']} / {lat['p95']} ms |",
        f"| Chi phí | ${metrics['cost_usd_total']} (≈ ${metrics['cost_usd_per_sample']}/mẫu) |",
        f"| JSON hợp lệ | {_pct(metrics['json_valid_rate'])} |",
        f"| Trích dẫn bị loại | {_pct(metrics['rejected_citation_rate'])} |",
        "",
        "## F1 theo nhóm",
        "",
        "| Nhóm | Số mẫu | F1 |",
        "|---|---|---|",
        *[f"| {g} | {d['support']} | {_pct(d['f1'])} |" for g, d in metrics["f1_by_group"].items()],
        "",
        f"Đường xử lý: {metrics['path_counts'] or 'n/a'}. Độ trễ p95 theo bước: {metrics['stage_latency_p95_ms'] or 'n/a'}.",
    ]
    if metrics["missing_must_signals"]:
        lines += ["", "## Thiếu must_signals (chỉ báo, không tính sai)", ""]
        lines += [f"- `{x['id']}`: {', '.join(x['missing'])}" for x in metrics["missing_must_signals"]]
    if metrics["errors"]:
        lines += ["", "## Lỗi khi chạy", ""] + [f"- `{e['id']}`: {e['error']}" for e in metrics["errors"]]
    if fails is not None:
        lines += ["", "## Cổng CI", "", "Đạt." if not fails else "Trượt: " + "; ".join(fails) + "."]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("datasets", nargs="*", type=pathlib.Path, help="file .jsonl (mặc định eval/datasets/*.jsonl)")
    ap.add_argument("--pipeline", default="stub", choices=["stub", "real"])
    ap.add_argument("--split", help="chỉ chạy một split")
    ap.add_argument("--limit", type=int, help="chỉ chạy N mẫu đầu")
    ap.add_argument("--out", type=pathlib.Path, default=EVAL_DIR / "out", help="thư mục ghi report.json, report.md")
    ap.add_argument("--cache", type=pathlib.Path, help="thư mục cache kết quả từng mẫu")
    ap.add_argument("--baseline", type=pathlib.Path, help="report.json của lần chạy để so (vd. trên main)")
    ap.add_argument("--gate", action="store_true", help="trả mã 1 nếu trượt ngưỡng recall/false alarm hoặc lệch baseline")
    args = ap.parse_args(argv)

    paths = args.datasets or sorted(DATASETS.glob("*.jsonl"))
    samples = load_samples(paths, args.split, args.limit)
    pipeline = build_pipeline(args.pipeline, CONFIG_DIR, LocalArtifacts(REPO / "ai" / "artifacts"), MemoryKV())
    results = run(pipeline, samples, args.pipeline, args.cache)
    metrics = compute_metrics(samples, results)
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))["metrics"] if args.baseline else None
    fails = gate(metrics, baseline) if args.gate else None

    args.out.mkdir(parents=True, exist_ok=True)
    report = {"pipeline": args.pipeline, "datasets": [p.name for p in paths], "metrics": metrics, "results": results}
    (args.out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    md = render_markdown(metrics, args.pipeline, baseline, fails)
    (args.out / "report.md").write_text(md, encoding="utf-8")
    sys.stdout.write(md)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
