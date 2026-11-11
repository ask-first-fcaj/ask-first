"""Retriever (AI-29): xếp hạng theo mã kịch bản, thẻ N0 luôn có, top cảnh báo. Embedder giả, không cần model;
model thật chỉ chạy khi có ``EMBEDDER_DIR`` (thư mục từ ``scripts/fetch_embedder.py``)."""

from __future__ import annotations

import os
import pathlib

import numpy as np
import pytest

from askfirst.pipeline.retrieve import Retriever, alerts_prompt, cards_prompt

AXES = ["phat", "otp", "deepfake", "viec", "chung"]


def vec(*words: str) -> np.ndarray:
    v = np.array([float(a in words) for a in AXES]) + 0.01
    return v / np.linalg.norm(v)


class FakeEmbedder:
    version = "fake-1"

    def query(self, text: str) -> np.ndarray:
        return vec(*text.split())


def card(code: str) -> dict:
    return {"kind": "card", "code": code, "title": f"Thẻ {code}", "description": "Mô tả.", "red_flags": ["a", "b"]}


def alert(code: str, i: int) -> dict:
    return {"kind": "alert", "code": code, "id": f"A{i:03d}", "title": f"Cảnh báo {i}", "summary": "Tóm tắt.",
            "url": f"https://congan.x.gov.vn/{i}", "publisher": "Công an tỉnh X", "date": "2026-04-09"}


ROWS = [  # (mục, vector)
    (card("N0"), vec("chung")),
    (card("N1.4"), vec("phat")),
    (card("N1.3"), vec("otp")),
    (card("N2.1"), vec("chung", "otp")),      # thẻ N2.1 viết chung chung, không giống tin deepfake
    (card("N3.1"), vec("viec")),
    (card("N5.2"), vec("chung", "viec")),
    (alert("N2.1", 1), vec("deepfake")),       # nhưng cảnh báo của N2.1 thì giống
    (alert("N1.4", 2), vec("phat", "otp")),
    (alert("N1.4", 3), vec("phat")),
    (alert("N3.1", 4), vec("viec", "otp")),
]


def retriever(**kw) -> Retriever:
    meta = {"version": "kb-test", "embedder": "fake-1", "items": [r[0] for r in ROWS]}
    return Retriever(FakeEmbedder(), np.vstack([r[1] for r in ROWS]).astype(np.float32), meta, **kw)


def codes(res) -> list[str]:
    return [c["code"] for c in res.cards]


def test_one_close_item_does_not_outrank_code_with_three_close_items():
    rows = [(card("X1"), vec("phat", "viec")), (alert("X1", 1), vec("deepfake")), (alert("X1", 2), vec("otp")),
            (card("Y1"), vec("phat")), (alert("Y1", 3), vec("phat")), (alert("Y1", 4), vec("viec"))]
    meta = {"version": "kb-test", "embedder": "fake-1", "items": [r[0] for r in rows]}
    r = Retriever(FakeEmbedder(), np.vstack([r[1] for r in rows]), meta, cards_k=1)
    assert codes(r.search("phat viec")) == ["Y1"]


def test_code_ranked_by_its_alerts_when_card_is_vague():
    assert codes(retriever(cards_k=1).search("deepfake")) == ["N2.1", "N0"]


def test_top_k_cards_then_n0_once_even_when_n0_matches_best():
    res = retriever(cards_k=2).search("chung")
    assert len(res.cards) == 3 and codes(res)[-1] == "N0" and codes(res).count("N0") == 1


def test_alerts_best_first_and_capped():
    res = retriever(alerts_k=2).search("phat")
    assert [a["id"] for a in res.alerts] == ["A003", "A002"]
    assert res.citations()[0].url == "https://congan.x.gov.vn/3"


def test_empty_text_only_n0_without_embedding():
    res = retriever().search("  \n")
    assert codes(res) == ["N0"] and res.alerts == []


def test_index_from_other_embedder_rejected():
    meta = {"version": "kb-test", "embedder": "e5-other", "items": [r[0] for r in ROWS]}
    with pytest.raises(ValueError, match="embedder"):
        Retriever(FakeEmbedder(), np.vstack([r[1] for r in ROWS]), meta)


def test_prompt_text_has_vn_date_and_no_url():
    res = retriever().search("phat")
    text = alerts_prompt(res.alerts)
    assert "ngày 09/04/2026" in text and "http" not in text
    assert cards_prompt(res.cards).startswith("- N1.4: Thẻ N1.4. Mô tả. Dấu hiệu: a; b.")


class _Dir:
    def __init__(self, d: pathlib.Path) -> None:
        self.d = d

    def path(self, name: str) -> pathlib.Path:
        return self.d


@pytest.mark.skipif(not os.environ.get("EMBEDDER_DIR"), reason="cần model thật (EMBEDDER_DIR)")
def test_real_e5_ids_and_similarity():
    from askfirst.local_models.embedder import MAX_TOKENS, E5Embedder

    e = E5Embedder(_Dir(pathlib.Path(os.environ["EMBEDDER_DIR"])))
    ids = e.ids("query: phạt nguội")
    assert ids[0] == 0 and ids[-1] == 2 and 3 not in ids
    assert len(e.ids("phạt nguội " * 1000)) == MAX_TOKENS
    q = e.query("Phương tiện của bạn có vi phạm giao thông, vui lòng nộp phạt tại link")
    fine, fake = e.passages(["Tin nhắn giả báo phạt nguội kèm đường link nộp phạt", "Video giả mặt người thân gọi vay tiền"])
    assert abs(np.linalg.norm(q) - 1) < 1e-5 and q @ fine > q @ fake
