"""Stage Fuse (AI-05, ARCH §6.7): gộp điểm luật và điểm LLM thành ``Verdict`` (mức, mã kịch bản, có báo người thân không).

Chính sách (ARCH §6.1 mục 3–4, §6.4, §6.7):

* ``score = max(điểm luật, điểm LLM)``: LLM bị prompt injection cũng không hạ được mức mà luật đã bắt.
* Điểm luật = tổng trọng số (kẹp 0–100), rồi nâng lên **sàn cứng**: 85 cho tổ hợp ``R_AUTHORITY`` + (``R_MONEY_TRANSFER``
  | ``R_SECRECY`` | ``R_SAFE_ACCOUNT``), 80 khi có luật cứng hoặc tín hiệu URL mạnh (``U_TYPOSQUAT``…).
* Chỉ giảm điểm (về ≤ 30) khi: không có luật cứng, mọi URL đều chính thức, LLM nói N0 với độ chắc ``high``.
* Không có mức "an toàn": thấp nhất là ``LOW_RISK``. LLM ``low`` confidence ở vùng 25–74 thì về ``UNSURE``, nhưng không
  thấp hơn mức của riêng điểm luật.
* Có dấu hiệu injection (``R_AI_INSTRUCTION`` hoặc Guardrails prompt attack) thì mức tối thiểu ``UNSURE``.
* **Fast path**: sàn ≥ 85 thì không cần gọi LLM (``is_fast_path``), giải thích ghép từ template ở Respond.
* Không có ``Judgement`` (LLM lỗi, hoặc kill switch ``LLM_ENABLED=false``) mà không đi fast path: chế độ **chỉ chạy luật**,
  ``path="rules_only"``, mức tối thiểu ``UNSURE`` (ARCH §5.8).

Fuse chỉ quyết định phần kết luận. ``masked_text``, ``model_ids``, ``versions``, ``latency_ms``, ``cost_usd_est`` và
``citations`` do pipeline điền sau bằng ``verdict.model_copy(update=...)``. Tin xã giao/không đọc được
(``outcome`` smalltalk/unreadable) đi nhánh riêng ở pipeline, không qua đây.

Ngưỡng 75/50/25 chỉ là giá trị khởi điểm; AI-10 hiệu chỉnh trên bộ 300 mẫu (truyền ``thresholds``).
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass

from askfirst.domain.models import LEVEL_ORDER, Level, RiskyAction, Signal, Verdict
from askfirst.pipeline.judge import Judgement

FLOOR_COMBO = 85   # tổ hợp cứng, đi fast path
FLOOR_HARD = 80    # luật cứng hoặc tín hiệu URL mạnh
N0_CAP = 30        # trần điểm khi LLM rất chắc là N0
MAX_EVIDENCE = 120

COMBO_ANCHOR = "R_AUTHORITY"
COMBO_PARTNERS = frozenset({"R_MONEY_TRANSFER", "R_SECRECY", "R_SAFE_ACCOUNT"})
STRONG_URL = frozenset({"U_TYPOSQUAT", "U_APK_LINK", "U_TEXT_LINK_MISMATCH", "U_SAFEBROWSING_HIT"})
# "G_PROMPT_ATTACK": kết quả ApplyGuardrail (AI-08, ``pipeline/guard.py``).
INJECTION = frozenset({"R_AI_INSTRUCTION", "G_PROMPT_ATTACK"})

# Luật → hành động rủi ro mà tin đòi nạn nhân làm (ARCH §6.7 ``has_risky_action``: tiền/OTP/app/màn hình/bí mật).
RULE_ACTIONS: dict[str, RiskyAction] = {
    "R_OTP_REQUEST": "share_otp",
    "R_SAFE_ACCOUNT": "transfer_money",
    "R_MONEY_TRANSFER": "transfer_money",
    "R_SCREEN_SHARE": "share_screen",
    "R_INSTALL_APP": "install_app",
    "U_APK_LINK": "install_app",
    "R_SECRECY": "keep_secret",
}
_ACTION_ORDER: tuple[RiskyAction, ...] = (
    "transfer_money", "share_otp", "share_password", "install_app",
    "share_screen", "click_link", "keep_secret", "share_id_card",
)


@dataclass(frozen=True)
class Thresholds:
    dangerous: int = 75
    suspicious: int = 50
    unsure: int = 25

    def level(self, score: int) -> Level:
        if score >= self.dangerous:
            return "DANGEROUS"
        if score >= self.suspicious:
            return "SUSPICIOUS"
        if score >= self.unsure:
            return "UNSURE"
        return "LOW_RISK"


DEFAULT_THRESHOLDS = Thresholds()


def _codes(signals: Iterable[Signal]) -> set[str]:
    return {s.code for s in signals}


def hard_floor(signals: list[Signal]) -> int:
    """Sàn điểm theo luật cứng và tổ hợp cứng (ARCH §6.4). 0 nếu không có."""
    codes = _codes(signals)
    if COMBO_ANCHOR in codes and codes & COMBO_PARTNERS:
        return FLOOR_COMBO
    if any(s.hard for s in signals) or codes & STRONG_URL:
        return FLOOR_HARD
    return 0


def is_fast_path(signals: list[Signal]) -> bool:
    """Luật đã đủ chắc (sàn ≥ 85): pipeline không gọi LLM, ``fuse(signals, None, ...)`` trả ``path="fast"``."""
    return hard_floor(signals) >= FLOOR_COMBO


def rule_score(signals: list[Signal]) -> int:
    total = max(0, min(100, sum(s.weight for s in signals)))
    return max(total, hard_floor(signals))


def _max_level(a: Level, b: Level) -> Level:
    return a if LEVEL_ORDER[a] >= LEVEL_ORDER[b] else b


def risky_actions(signals: list[Signal], j: Judgement | None) -> list[RiskyAction]:
    found: set[str] = {RULE_ACTIONS[s.code] for s in signals if s.code in RULE_ACTIONS}
    if j is not None:
        found |= {a for a in j.requested_actions if a != "none"}
    return [a for a in _ACTION_ORDER if a in found]


def _merge_signals(signals: list[Signal], j: Judgement | None) -> list[Signal]:
    """Tín hiệu luật giữ nguyên; tín hiệu của LLM thêm vào với trọng số 0 (điểm LLM đã tính riêng), trùng mã thì bỏ."""
    out = list(signals)
    if j is None:
        return out
    seen = _codes(signals)
    for s in j.signals:
        if s.code not in seen:
            seen.add(s.code)
            out.append(Signal(code=s.code, weight=0, evidence=s.quote[:MAX_EVIDENCE] or None))
    return out


def fuse(signals: list[Signal], j: Judgement | None, *, all_urls_official: bool, escalated: bool = False,
         thresholds: Thresholds = DEFAULT_THRESHOLDS) -> Verdict:
    """Gộp tín hiệu luật (đã chạy trên text đã mask) và ``Judgement`` đã hậu kiểm (``None`` = không có LLM).

    ``all_urls_official``: mọi URL trong tin đều chính thức theo allowlist (không có URL nào thì ``True``).
    ``escalated``: kết quả đến từ model dự phòng (AI-07), ghi ``path="escalated"``.
    """
    fast = j is None and is_fast_path(signals)
    score = max(rule_score(signals), j.risk_score if j else 0)

    any_hard = hard_floor(signals) > 0
    if not any_hard and all_urls_official and j and j.group == "N0" and j.confidence == "high":
        score = min(score, N0_CAP)

    level = thresholds.level(score)
    if j and j.confidence == "low" and thresholds.unsure <= score < thresholds.dangerous:
        # Khác pseudo-code ARCH §6.7: LLM không chắc chỉ được hạ phần điểm của chính nó, không hạ mức luật đã bắt (§6.1 mục 3).
        level = _max_level("UNSURE", thresholds.level(rule_score(signals)))
    if _codes(signals) & INJECTION:
        level = _max_level(level, "UNSURE")
    if j is None and not fast:
        level = _max_level(level, "UNSURE")

    actions = risky_actions(signals, j)
    if j is not None:
        group, scenario = j.group, j.scenario
    elif hard_floor(signals) >= FLOOR_COMBO:
        group, scenario = "N1", "N1.1"   # tổ hợp giả công an (ARCH §6.4)
    else:
        group, scenario = "OTHER", "OTHER"

    if j is not None:
        path = "escalated" if escalated else "llm"
    else:
        path = "fast" if fast else "rules_only"

    return Verdict(
        outcome="verdict",
        level=level,
        score=score,
        group=group,
        scenario=scenario,
        signals=_merge_signals(signals, j),
        risky_actions=actions,
        should_alert=level == "DANGEROUS" and bool(actions),
        explanation=j.short_explanation if j else "",   # rỗng: Respond ghép từ template theo mã dấu hiệu
        followup_question=j.followup_question if j else None,
        path=path,
    )
