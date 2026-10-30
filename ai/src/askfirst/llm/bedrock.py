"""Bedrock Converse client (AI-02): timeout, retry with backoff + jitter, token/cost accounting, models from config/models.yaml.

Never logs or stores prompt/response text (plan §3.1)."""
import random
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import boto3
import yaml
from botocore.config import Config
from botocore.exceptions import ClientError, ConnectTimeoutError, ReadTimeoutError

DEFAULT_CONFIG = Path(__file__).resolve().parents[3] / "config" / "models.yaml"
RETRYABLE_CODES = {"ThrottlingException", "ModelTimeoutException", "ServiceUnavailableException", "InternalServerException"}
MAX_ATTEMPTS = 3
BACKOFF_BASE_S = 0.5
BACKOFF_CAP_S = 4.0


class LLMError(Exception):
    """Raised when a call fails for good. `retryable` tells the caller whether trying again later could help."""

    def __init__(self, message: str, retryable: bool):
        super().__init__(message)
        self.retryable = retryable


@dataclass(frozen=True)
class LLMResult:
    text: str
    input_tokens: int
    output_tokens: int
    cost_usd: float
    latency_ms: int
    stop_reason: str
    attempts: int


@dataclass
class BedrockClient:
    config: dict[str, Any]
    clients: dict[str, Any] = field(default_factory=dict)  # role -> boto3 bedrock-runtime client; tests inject fakes here
    sleep: Callable[[float], None] = time.sleep
    rand: Callable[[], float] = random.random

    @classmethod
    def from_file(cls, path: Path = DEFAULT_CONFIG, **kw: Any) -> "BedrockClient":
        return cls(config=yaml.safe_load(Path(path).read_text(encoding="utf-8")), **kw)

    def _client(self, role: str) -> Any:
        if role not in self.clients:
            spec = self.config[role]
            timeout = spec["timeout_s"]
            cfg = Config(region_name=self.config["bedrock_region"], connect_timeout=timeout, read_timeout=timeout, retries={"total_max_attempts": 1})  # we retry ourselves; botocore's max_attempts=1 would still add one retry
            self.clients[role] = boto3.client("bedrock-runtime", config=cfg)
        return self.clients[role]

    def cost_usd(self, model_id: str, input_tokens: int, output_tokens: int) -> float:
        price = (self.config.get("pricing_usd_per_1m") or {}).get(model_id)
        if not price:
            return 0.0  # unknown price must not break an analysis; the spike/ADR records the real price
        return (input_tokens * price["in"] + output_tokens * price["out"]) / 1_000_000

    def converse(self, role: str, messages: list[dict], system: str | None = None, extra_fields: dict | None = None) -> LLMResult:
        """`role` is a key of models.yaml (judge/backup/vlm). `extra_fields` goes to additionalModelRequestFields (model-specific, e.g. thinking)."""
        spec = self.config[role]
        if not spec.get("model_id"):
            raise LLMError(f"models.yaml: {role}.model_id is empty", retryable=False)
        req: dict[str, Any] = {
            "modelId": spec["model_id"],
            "messages": messages,
            "inferenceConfig": {"maxTokens": spec["max_tokens"], "temperature": spec.get("temperature", 0)},
        }
        if system:
            req["system"] = [{"text": system}]
        if extra_fields:
            req["additionalModelRequestFields"] = extra_fields
        client = self._client(role)
        started = time.perf_counter()
        for attempt in range(1, MAX_ATTEMPTS + 1):
            try:
                resp = client.converse(**req)
                break
            except (ConnectTimeoutError, ReadTimeoutError):
                code = "Timeout"
            except ClientError as e:
                code = e.response.get("Error", {}).get("Code", "Unknown")
                if code not in RETRYABLE_CODES:
                    raise LLMError(code, retryable=False) from e
            if attempt == MAX_ATTEMPTS:
                raise LLMError(code, retryable=True)
            self.sleep(self.rand() * min(BACKOFF_CAP_S, BACKOFF_BASE_S * 2**attempt))  # full jitter
        latency_ms = int((time.perf_counter() - started) * 1000)
        usage = resp.get("usage", {})
        tin, tout = usage.get("inputTokens", 0), usage.get("outputTokens", 0)
        blocks = resp["output"]["message"]["content"]
        text = "".join(b["text"] for b in blocks if "text" in b)  # reasoningContent blocks (thinking) are dropped
        return LLMResult(text, tin, tout, self.cost_usd(spec["model_id"], tin, tout), latency_ms, resp.get("stopReason", ""), attempt)
