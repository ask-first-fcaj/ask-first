"""Bedrock ApplyGuardrail client (AI-08, ARCH §6.2 stage 7): hỏi Guardrails xem text có phải prompt attack không.

Guardrail tạo bằng ``ai/scripts/create_guardrail.py`` (input: chỉ PROMPT_ATTACK; output: denied topics), id/version ghi
vào ``config/models.yaml`` mục ``guardrail``. Không log, không giữ nội dung text (plan §3.1)."""
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import boto3
import yaml
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

from askfirst.llm.bedrock import DEFAULT_CONFIG, LLMError

DEFAULT_TIMEOUT_S = 1.0


@dataclass(frozen=True)
class GuardResult:
    prompt_attack: bool
    latency_ms: int
    text_units: int   # đơn vị tính tiền của Guardrails (1 unit = tối đa 1.000 ký tự)


@dataclass
class BedrockGuardrail:
    region: str
    guardrail_id: str
    version: str
    timeout_s: float = DEFAULT_TIMEOUT_S
    client: Any = None   # boto3 bedrock-runtime; test truyền fake

    @classmethod
    def from_config(cls, config: dict[str, Any], **kw: Any) -> "BedrockGuardrail | None":
        """``None`` khi ``guardrail.id`` còn trống: pipeline chạy không có Guardrails."""
        spec = config.get("guardrail") or {}
        if not spec.get("id"):
            return None
        return cls(config["bedrock_region"], spec["id"], str(spec.get("version") or "DRAFT"),
                   float(spec.get("timeout_s", DEFAULT_TIMEOUT_S)), **kw)

    @classmethod
    def from_file(cls, path: Path = DEFAULT_CONFIG, **kw: Any) -> "BedrockGuardrail | None":
        return cls.from_config(yaml.safe_load(Path(path).read_text(encoding="utf-8")), **kw)

    def _client(self) -> Any:
        if self.client is None:
            cfg = Config(region_name=self.region, connect_timeout=self.timeout_s, read_timeout=self.timeout_s,
                         retries={"total_max_attempts": 1})
            self.client = boto3.client("bedrock-runtime", config=cfg)
        return self.client

    def check(self, text: str) -> GuardResult:
        """Raise ``LLMError`` khi gọi lỗi (timeout, throttle, quyền); caller coi như không có tín hiệu."""
        started = time.perf_counter()
        try:
            resp = self._client().apply_guardrail(
                guardrailIdentifier=self.guardrail_id, guardrailVersion=self.version, source="INPUT",
                content=[{"text": {"text": text}}])
        except ClientError as e:
            raise LLMError(e.response.get("Error", {}).get("Code", "Unknown"), retryable=False) from e
        except BotoCoreError as e:   # timeout, lỗi mạng
            raise LLMError(type(e).__name__, retryable=True) from e
        latency_ms = int((time.perf_counter() - started) * 1000)
        return GuardResult(is_prompt_attack(resp), latency_ms, text_units(resp))


def is_prompt_attack(resp: dict[str, Any]) -> bool:
    """Có filter PROMPT_ATTACK nào bị phát hiện không, kể cả khi guardrail để action NONE (chế độ chỉ phát hiện)."""
    for a in resp.get("assessments") or []:
        for f in (a.get("contentPolicy") or {}).get("filters") or []:
            if f.get("type") == "PROMPT_ATTACK" and (f.get("detected") or f.get("action") == "BLOCKED"):
                return True
    return False


def text_units(resp: dict[str, Any]) -> int:
    usage = resp.get("usage") or {}
    return int(usage.get("contentPolicyUnits", 0)) + int(usage.get("topicPolicyUnits", 0))

