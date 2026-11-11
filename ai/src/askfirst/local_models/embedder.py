"""Embedding local cho RAG (AI-29, ARCH §6.5): ``intfloat/multilingual-e5-small`` ONNX int8, 384 chiều.

Tokenizer là SentencePiece của XLM-R (gói ``sentencepiece``, không kéo dependency phụ như ``tokenizers``). Id của
XLM-R = id SentencePiece + 1, riêng ``<unk>`` (id 0) thành 3; ``<s>`` = 0, ``</s>`` = 2, ``<pad>`` = 1. Đã đối chiếu
với ``tokenizer.json`` của model (ai/MODELS.md). e5 cần tiền tố ``query: `` cho câu hỏi và ``passage: `` cho tài liệu.
"""

from __future__ import annotations

from typing import Any

import numpy as np

from askfirst.domain.models import ArtifactStore

MODEL_FILES = {"model": "model.onnx", "spm": "sentencepiece.bpe.model"}   # tên file trong artifact `embedder`
MAX_TOKENS = 512   # giới hạn vị trí của model; tin dài hơn bị cắt đuôi
BOS, PAD, EOS, UNK = 0, 1, 2, 3


class E5Embedder:
    """``query(text)``/``passages(texts)`` trả vector đã chuẩn hoá L2 (cosine = tích vô hướng). Model nạp ở lần gọi đầu."""

    def __init__(self, artifacts: ArtifactStore, *, name: str = "embedder", version: str = "", threads: int | None = None) -> None:
        self.artifacts, self.name, self.version, self.threads = artifacts, name, version, threads
        self._sess: Any = None
        self._spm: Any = None

    def _load(self) -> None:
        import onnxruntime as ort
        import sentencepiece as spm

        d = self.artifacts.path(self.name)
        opts = ort.SessionOptions()
        if self.threads:
            opts.intra_op_num_threads = self.threads
        self._sess = ort.InferenceSession(str(d / MODEL_FILES["model"]), opts, providers=["CPUExecutionProvider"])
        self._spm = spm.SentencePieceProcessor(model_file=str(d / MODEL_FILES["spm"]))

    def ids(self, text: str) -> list[int]:
        if self._spm is None:
            self._load()
        body = [i + 1 if i else UNK for i in self._spm.encode(text)]
        return [BOS, *body[:MAX_TOKENS - 2], EOS]

    def _embed(self, texts: list[str]) -> np.ndarray:
        rows = [self.ids(t) for t in texts]
        width = max(len(r) for r in rows)
        ids = np.full((len(rows), width), PAD, dtype=np.int64)
        mask = np.zeros((len(rows), width), dtype=np.int64)
        for i, r in enumerate(rows):
            ids[i, :len(r)], mask[i, :len(r)] = r, 1
        feed = {"input_ids": ids, "attention_mask": mask, "token_type_ids": np.zeros_like(ids)}
        hidden = self._sess.run(None, {i.name: feed[i.name] for i in self._sess.get_inputs()})[0]
        m = mask[..., None].astype(np.float32)
        vec = (hidden * m).sum(axis=1) / m.sum(axis=1)   # mean pooling theo attention mask, như model card của e5
        return (vec / np.linalg.norm(vec, axis=1, keepdims=True)).astype(np.float32)

    def query(self, text: str) -> np.ndarray:
        return self._embed([f"query: {text}"])[0]

    def passages(self, texts: list[str], batch: int = 16) -> np.ndarray:
        return np.vstack([self._embed([f"passage: {t}" for t in texts[i:i + batch]]) for i in range(0, len(texts), batch)])
