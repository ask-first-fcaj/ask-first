"""Stage Retrieve (AI-29, ARCH §6.5): RAG local, cosine bằng numpy trên index nạp sẵn trong RAM.

Index (``index.npy`` + ``meta.json``, artifact ``kb_index``) do ``ai/scripts/build_index.py`` dựng từ ``kb/scenarios.yaml``
(thẻ kịch bản) và ``kb/alerts.yaml`` (cảnh báo chính thức). Hàng ``i`` của ``index.npy`` là vector đã chuẩn hoá L2 của
``meta["items"][i]``. Điểm của một mã kịch bản = trung bình cosine của 3 mục giống tin nhất trong các mục của mã đó (thẻ +
các cảnh báo): thẻ chỉ có vài câu còn cảnh báo kể đủ biến thể, và lấy trung bình thì một bài lạc đề không kéo cả mã lên.
Cách gộp này chọn bằng đo trên ``real_scam`` và ``hard`` (CHANGELOG AI-29). Thẻ ``N0`` luôn được đưa vào để model có điểm
so sánh (ARCH §6.5).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import numpy as np

from askfirst.domain import models as m

ALWAYS = "N0"
PER_CODE = 3


class Embedder(Protocol):
    """``askfirst.local_models.embedder.E5Embedder`` khớp giao diện này."""
    version: str

    def query(self, text: str) -> np.ndarray: ...


@dataclass(frozen=True)
class Retrieved:
    cards: list[dict]    # top thẻ kịch bản (mục ``kind: card`` của meta), N0 ở cuối
    alerts: list[dict]   # top cảnh báo (mục ``kind: alert``)

    def citations(self) -> list[m.Citation]:
        return [m.Citation(title=a["title"], url=a["url"], publisher=a["publisher"], date=a["date"]) for a in self.alerts]


class Retriever:
    def __init__(self, embedder: Embedder, vectors: np.ndarray, meta: dict, *, cards_k: int = 4, alerts_k: int = 3) -> None:
        if meta.get("embedder") != embedder.version:   # vector của model khác thì cosine vô nghĩa
            raise ValueError(f"index dựng bằng embedder {meta.get('embedder')!r}, đang dùng {embedder.version!r}")
        items = meta["items"]
        if len(items) != len(vectors):
            raise ValueError("index.npy và meta.json lệch số dòng")
        self.embedder, self.vectors, self.items = embedder, vectors, items
        self.version = meta["version"]
        self.cards_k, self.alerts_k = cards_k, alerts_k
        self.codes = np.array([it["code"] for it in items])
        self.cards = {it["code"]: it for it in items if it["kind"] == "card"}
        self._alert_rows = np.array([i for i, it in enumerate(items) if it["kind"] == "alert"], dtype=np.int64)

    @classmethod
    def from_dir(cls, embedder: Embedder, d: Path, **kw) -> Retriever:
        meta = json.loads((d / "meta.json").read_text(encoding="utf-8"))
        return cls(embedder, np.load(d / "index.npy"), meta, **kw)

    def search(self, text: str) -> Retrieved:
        """``text`` là bản đã chuẩn hoá và mask. Không có chữ thì chỉ trả thẻ N0."""
        if not text.strip():
            return Retrieved([self.cards[ALWAYS]] if ALWAYS in self.cards else [], [])
        sims = self.vectors @ self.embedder.query(text)
        best: dict[str, list[float]] = {}
        for i in np.argsort(-sims):
            near = best.setdefault(str(self.codes[i]), [])
            if len(near) < PER_CODE:
                near.append(float(sims[i]))
        ranked = sorted((c for c in best if c != ALWAYS and c in self.cards), key=lambda c: -sum(best[c]) / len(best[c]))
        top = ranked[:self.cards_k]
        cards = [self.cards[c] for c in top] + ([self.cards[ALWAYS]] if ALWAYS in self.cards else [])
        rows = self._alert_rows[np.argsort(-sims[self._alert_rows])[:self.alerts_k]]
        return Retrieved(cards, [self.items[i] for i in rows])


def _vn_date(d: str) -> str:
    y, mo, da = d.split("-")
    return f"{da}/{mo}/{y}"


def cards_prompt(cards: list[dict]) -> str:
    """Nội dung thẻ ``<related_scenarios>`` của prompt judge (ARCH §6.6)."""
    return "\n".join(f"- {c['code']}: {c['title']}. {c['description']} Dấu hiệu: {'; '.join(c['red_flags']) or 'không có'}."
                     for c in cards)


def alerts_prompt(alerts: list[dict]) -> str:
    """Nội dung thẻ ``<canh_bao_lien_quan>``: tiêu đề, tóm tắt, nguồn, ngày (ARCH §6.6). Không đưa URL cho model."""
    return "\n".join(f"- {a['publisher']} ngày {_vn_date(a['date'])}: {a['title']}. {a['summary']}" for a in alerts)
