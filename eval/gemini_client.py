"""Client Gemini API cho eval (mốc tham chiếu AI-03 thay Claude Haiku khi account chưa có quyền Anthropic trên Bedrock).

Chỉ dùng trong eval/, không nằm trên đường chạy production (pipeline thật vẫn là Bedrock, ADR-001). Cùng giao diện với
``BedrockClient``: ``converse(role, messages, system)`` nhận messages kiểu Bedrock Converse và trả ``LLMResult``.
Key đọc từ biến môi trường ``GEMINI_API_KEY``, gửi qua header ``x-goog-api-key`` (không để trong URL).
Không log hay lưu nội dung prompt/câu trả lời (plan §3.1). Chỉ dùng key ở paid tier: free tier cho Google dùng nội dung để
cải thiện sản phẩm. Chỉ gửi bộ eval tự soạn, không gửi tin của người dùng.
"""

from __future__ import annotations

import json
import os
import random
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from askfirst.llm.bedrock import LLMError, LLMResult

API_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
RETRYABLE_HTTP = {429, 500, 502, 503, 504}
MAX_ATTEMPTS = 3
BACKOFF_BASE_S = 0.5
BACKOFF_CAP_S = 4.0


def _post(url: str, payload: dict, key: str, timeout: float) -> dict:
    req = urllib.request.Request(url, data=json.dumps(payload).encode("utf-8"), method="POST",
                                 headers={"Content-Type": "application/json", "x-goog-api-key": key})
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 (URL cố định https)
        return json.loads(resp.read().decode("utf-8"))


def to_contents(messages: list[dict]) -> list[dict]:
    """Messages Bedrock Converse → contents Gemini (role ``assistant`` đổi thành ``model``, chỉ giữ block text)."""
    return [{"role": "model" if m["role"] == "assistant" else "user",
             "parts": [{"text": b["text"]} for b in m["content"] if "text" in b]} for m in messages]


@dataclass
class GeminiClient:
    config: dict[str, Any]                       # dạng models.yaml: config[role] = {model_id, max_tokens, temperature, timeout_s, ...}
    key: str | None = None
    post: Callable[[str, dict, str, float], dict] = _post   # test thay bằng fake
    sleep: Callable[[float], None] = time.sleep
    rand: Callable[[], float] = random.random
    thinking_level: str | None = "low"            # Gemini 3.x: minimal | low | medium | high; None = mặc định của model
    clock: Callable[[], float] = field(default=time.perf_counter)

    def __post_init__(self) -> None:
        self.key = self.key or os.environ.get("GEMINI_API_KEY", "")

    def model_id(self, role: str) -> str:
        return self.config[role].get("model_id") or ""

    def max_attempts(self, role: str) -> int:
        return int(self.config[role].get("max_attempts", MAX_ATTEMPTS))

    def budget_s(self, role: str) -> float:
        n = self.max_attempts(role)
        return self.config[role]["timeout_s"] * n + sum(min(BACKOFF_CAP_S, BACKOFF_BASE_S * 2**a) for a in range(1, n))

    def cost_usd(self, model_id: str, input_tokens: int, output_tokens: int) -> float:
        price = (self.config.get("pricing_usd_per_1m") or {}).get(model_id)
        if not price:
            return 0.0
        return (input_tokens * price["in"] + output_tokens * price["out"]) / 1_000_000

    def payload(self, role: str, messages: list[dict], system: str | None) -> dict:
        spec = self.config[role]
        gen: dict[str, Any] = {"maxOutputTokens": spec["max_tokens"], "temperature": spec.get("temperature", 0)}
        if self.thinking_level:
            gen["thinkingConfig"] = {"thinkingLevel": self.thinking_level}
        body: dict[str, Any] = {"contents": to_contents(messages), "generationConfig": gen}
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        return body

    def converse(self, role: str, messages: list[dict], system: str | None = None, extra_fields: dict | None = None) -> LLMResult:
        """``extra_fields`` (tham số riêng của Bedrock) bị bỏ qua."""
        spec = self.config[role]
        model = spec.get("model_id")
        if not model:
            raise LLMError(f"models.yaml: {role}.model_id is empty", retryable=False)
        if not self.key:
            raise LLMError("GEMINI_API_KEY missing", retryable=False)
        url, body, attempts = API_URL.format(model=model), self.payload(role, messages, system), self.max_attempts(role)
        started = self.clock()
        for attempt in range(1, attempts + 1):
            try:
                resp = self.post(url, body, self.key, spec["timeout_s"])
                break
            except urllib.error.HTTPError as e:
                code = f"HTTP {e.code}"
                if e.code not in RETRYABLE_HTTP:
                    raise LLMError(code, retryable=False) from e
            except (TimeoutError, urllib.error.URLError):
                code = "Timeout"
            if attempt == attempts:
                raise LLMError(code, retryable=True)
            self.sleep(self.rand() * min(BACKOFF_CAP_S, BACKOFF_BASE_S * 2**attempt))
        latency_ms = int((self.clock() - started) * 1000)
        usage = resp.get("usageMetadata") or {}
        tin = usage.get("promptTokenCount", 0)
        tout = usage.get("candidatesTokenCount", 0) + usage.get("thoughtsTokenCount", 0)   # token suy nghĩ tính giá như output
        cands = resp.get("candidates") or []
        if not cands:
            raise LLMError("Gemini: no candidates", retryable=False)
        parts = (cands[0].get("content") or {}).get("parts") or []
        text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
        return LLMResult(text, tin, tout, self.cost_usd(model, tin, tout), latency_ms, cands[0].get("finishReason", ""), attempt)
