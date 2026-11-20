"""Dựng RAG index (AI-29, ARCH §6.5) từ `kb/scenarios.yaml` + `kb/alerts.yaml` thành `index.npy` + `meta.json`.

    uv run python scripts/build_index.py EMBEDDER_DIR OUT_DIR [--version kb-2026.11.11]

`EMBEDDER_DIR` là thư mục artifact `embedder` (`scripts/fetch_embedder.py`). In sha256 của 2 file ra để ghi vào mục
`kb_index` của `config/artifacts.yaml`; upload `OUT_DIR/*` lên `s3://$ARTIFACT_BUCKET/<s3_prefix>` là việc của INT-03.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

import numpy as np
import yaml
from fetch_ocr_models import sha256

from askfirst.local_models.embedder import E5Embedder
from askfirst.pipeline import artifact_version

AI = Path(__file__).resolve().parents[1]
KB = AI.parent / "kb"


class _Dir:
    def __init__(self, d: Path) -> None:
        self.d = d

    def path(self, name: str) -> Path:
        return self.d


def items(kb: Path = KB) -> tuple[list[dict], list[str]]:
    """Mục của meta (để dựng prompt và trích nguồn) và đoạn văn đem embed, cùng thứ tự."""
    meta, texts = [], []
    for c in yaml.safe_load((kb / "scenarios.yaml").read_text(encoding="utf-8")):
        meta.append({"kind": "card", "code": c["code"], "title": c["title"], "description": c["description"],
                     "red_flags": c["red_flags"]})
        texts.append(" ".join([f"{c['title']}.", c["description"], *c["example_phrases"], *c["red_flags"]]))
    for a in yaml.safe_load((kb / "alerts.yaml").read_text(encoding="utf-8")):
        meta.append({"kind": "alert", "code": a["scenario"], "id": a["id"], "title": a["title"], "summary": a["summary"],
                     "url": a["url"], "publisher": a["publisher"], "date": str(a["date"])})
        texts.append(f"{a['title']}. {a['summary']}")
    return meta, texts


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("embedder", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--version", default=f"kb-{dt.date.today():%Y.%m.%d}")
    a = ap.parse_args()
    emb = E5Embedder(_Dir(a.embedder), version=artifact_version(AI / "config" / "artifacts.yaml", "embedder"))
    meta, texts = items()
    a.out.mkdir(parents=True, exist_ok=True)
    np.save(a.out / "index.npy", emb.passages(texts))
    (a.out / "meta.json").write_text(json.dumps({"version": a.version, "embedder": emb.version, "items": meta},
                                                ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{a.version}: {len(texts)} mục ({sum(x['kind'] == 'card' for x in meta)} thẻ), embedder {emb.version}")
    for f in ("index.npy", "meta.json"):
        print(f"  {f}: {sha256(a.out / f)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
