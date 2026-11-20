"""Stage Guard (AI-08, ARCH §6.2 stage 7, §6.10): Bedrock Guardrails prompt attack → tín hiệu ``G_PROMPT_ATTACK``.

Hai lớp phát hiện injection, cả hai chỉ **nâng mức tối thiểu lên UNSURE** ở fuse (``fuse.INJECTION``), không chặn tin,
không hạ điểm (tin lừa đảo vốn đầy đe doạ nên chặn nhầm là mất cảnh báo):

1. Local: luật ``R_AI_INSTRUCTION`` trong ``kb/rules.yaml`` (regex "bỏ qua hướng dẫn", "ignore previous", "bạn là AI",
   "hãy kết luận ... an toàn"), chạy ở stage Signals cho mọi tin, không tốn tiền.
2. Bedrock ``ApplyGuardrail`` (file này): chỉ gọi khi tin sẽ tới LLM (không fast path, LLM bật).

Guardrails lỗi hoặc chưa cấu hình thì coi như không có tín hiệu (ARCH §6.2: "Coi như không có tín hiệu"); judge vẫn chạy.
Không log nội dung (plan §3.1).
"""

from __future__ import annotations

import json
import logging
from typing import Protocol

from askfirst.domain import models as m

log = logging.getLogger(__name__)

CODE = "G_PROMPT_ATTACK"
WEIGHT = 10   # bằng R_AI_INSTRUCTION; tác dụng chính là sàn UNSURE ở fuse


class Checked(Protocol):
    prompt_attack: bool
    latency_ms: int


class PromptGuard(Protocol):
    def check(self, text: str) -> Checked: ...


def guard_signals(guard: PromptGuard | None, text: str) -> tuple[list[m.Signal], int | None]:
    """``(signals, latency_ms)``; ``latency_ms`` là ``None`` khi không gọi được Guardrails."""
    if guard is None:
        return [], None
    try:
        res = guard.check(text)
    except Exception as e:   # timeout, throttle, quyền, lỗi mạng: bỏ qua Guardrails, không làm hỏng lượt kiểm
        log.warning(json.dumps({"event": "guard_failed", "error": type(e).__name__}))
        return [], None
    signals = [m.Signal(code=CODE, weight=WEIGHT)] if res.prompt_attack else []
    return signals, res.latency_ms
