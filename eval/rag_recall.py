"""Recall@4 của thẻ kịch bản và thời gian truy vấn RAG (AI-29, plan M3). Chạy từ thư mục ai/:

    uv run python ../eval/rag_recall.py EMBEDDER_DIR INDEX_DIR [--split real_scam]

- Tin của mẫu đi qua đúng `normalize` + `mask` như pipeline rồi mới truy vấn.
- "Trúng" = mã `label.scenario` nằm trong top-4 thẻ (không tính thẻ N0 luôn được thêm). Mẫu có mã không có thẻ
  (`OTHER`) hoặc không có chữ thì bỏ qua và được đếm riêng.
- Thời gian = một lần `Retriever.search()` (embed tin + cosine), model đã nạp; lần nạp đầu đo riêng.
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import time

import numpy as np
from run_eval import DATASETS, load_samples

from askfirst.local_models.embedder import E5Embedder
from askfirst.pipeline import artifact_version
from askfirst.pipeline.mask import mask
from askfirst.pipeline.normalize import normalize
from askfirst.pipeline.retrieve import Retriever

MANIFEST = pathlib.Path(__file__).resolve().parents[1] / "ai" / "config" / "artifacts.yaml"


class _Dir:
    def __init__(self, d: pathlib.Path) -> None:
        self.d = d

    def path(self, name: str) -> pathlib.Path:
        return self.d


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("embedder", type=pathlib.Path)
    ap.add_argument("index", type=pathlib.Path)
    ap.add_argument("--split", default="real_scam")
    ap.add_argument("-v", "--verbose", action="store_true", help="in mã đúng và top-4 của mẫu trượt")
    a = ap.parse_args()
    emb = E5Embedder(_Dir(a.embedder), version=artifact_version(MANIFEST, "embedder"))
    t = time.perf_counter()
    r = Retriever.from_dir(emb, a.index)
    r.search("khởi động")
    load_ms = (time.perf_counter() - t) * 1000
    hits, ms, skipped = 0, [], 0
    for s in load_samples(sorted(DATASETS.glob("*.jsonl")), a.split):
        gold, text = s["label"].get("scenario"), mask(normalize(s["input"].get("text") or "")).text
        if gold not in r.cards or gold == "N0" or not text:
            skipped += 1
            continue
        t = time.perf_counter()
        top = [c["code"] for c in r.search(text).cards if c["code"] != "N0"]
        ms.append((time.perf_counter() - t) * 1000)
        hits += gold in top
        if a.verbose and gold not in top:
            print(f"  trượt {s['id']}: đúng {gold}, top-4 {top}")
    p50, p95 = np.percentile(ms, [50, 95])
    print(f"{a.split}, index {r.version} ({len(r.items)} mục), embedder {emb.version}")
    print(f"Recall@4 thẻ kịch bản: {hits}/{len(ms)} = {hits / len(ms):.1%} (bỏ qua {skipped} mẫu không có thẻ/chữ)")
    print(f"Truy vấn: p50 {p50:.1f} ms, p95 {p95:.1f} ms, max {max(ms):.1f} ms; nạp model + index + truy vấn đầu {load_ms:.0f} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
