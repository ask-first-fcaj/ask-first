"""Test ``pipeline/fuse.py`` (AI-05, ARCH §6.7): điểm, mức, sàn cứng, giảm điểm N0, chỉ chạy luật, fast path, báo người thân.

Trọng số trong test theo bảng luật khởi điểm ARCH §6.4; điểm LLM là số bịa.
"""

from __future__ import annotations

import pytest

from askfirst.domain.models import Signal
from askfirst.pipeline import fuse as F
from askfirst.pipeline.judge import Judgement


def _s(code: str, weight: int = 0, hard: bool = False) -> Signal:
    return Signal(code=code, weight=weight, hard=hard)


def _j(**over) -> Judgement:
    base = {
        "group": "N1", "scenario": "N1.4", "risk_score": 60, "confidence": "high",
        "signals": [], "requested_actions": ["none"],
        "short_explanation": "Tin đòi nộp phạt qua trang lạ.", "followup_question": None,
    }
    return Judgement.model_validate(base | over)


def _fuse(signals=(), j=None, official=True, **kw):
    return F.fuse(list(signals), j, all_urls_official=official, **kw)


AUTHORITY = _s("R_AUTHORITY", 20)
TRANSFER = _s("R_MONEY_TRANSFER", 25)
SECRECY = _s("R_SECRECY", 35)
OTP_REQ = _s("R_OTP_REQUEST", 45, hard=True)
URGENCY = _s("R_URGENCY", 15)


# ---------------------------------------------------------------- điểm luật và sàn cứng

def test_rule_score_is_clamped_sum_of_weights() -> None:
    assert F.rule_score([URGENCY, TRANSFER]) == 40
    assert F.rule_score([SECRECY, SECRECY, SECRECY, SECRECY]) == 100
    assert F.rule_score([_s("R_OTP_GENUINE", -20)]) == 0


@pytest.mark.parametrize(
    ("signals", "floor"),
    [
        ([OTP_REQ], 80),
        ([_s("U_TYPOSQUAT", 10)], 80),
        ([_s("U_TEXT_LINK_MISMATCH")], 80),
        ([AUTHORITY, TRANSFER], 85),
        ([AUTHORITY, SECRECY], 85),
        ([AUTHORITY, _s("R_SAFE_ACCOUNT", 50, hard=True)], 85),
        ([AUTHORITY, URGENCY], 0),
        ([TRANSFER, SECRECY], 0),
        ([], 0),
    ],
)
def test_hard_floor(signals: list[Signal], floor: int) -> None:
    assert F.hard_floor(signals) == floor


def test_hard_rule_lifts_low_weight_sum_to_dangerous() -> None:
    v = _fuse([OTP_REQ], _j(risk_score=10))
    assert (v.score, v.level) == (80, "DANGEROUS")


# ---------------------------------------------------------------- max(luật, LLM)

def test_llm_cannot_lower_what_rules_caught() -> None:
    """Prompt injection làm LLM chấm 0 vẫn không hạ được điểm luật (ARCH §6.1 mục 3)."""
    v = _fuse([OTP_REQ, URGENCY], _j(risk_score=0, group="OTHER", scenario="OTHER"))
    assert v.score == 80 and v.level == "DANGEROUS"


def test_llm_score_wins_when_higher() -> None:
    v = _fuse([URGENCY], _j(risk_score=70))
    assert (v.score, v.level, v.path) == (70, "SUSPICIOUS", "llm")


@pytest.mark.parametrize(
    ("score", "level"),
    [(0, "LOW_RISK"), (24, "LOW_RISK"), (25, "UNSURE"), (49, "UNSURE"), (50, "SUSPICIOUS"),
     (74, "SUSPICIOUS"), (75, "DANGEROUS"), (100, "DANGEROUS")],
)
def test_level_thresholds(score: int, level: str) -> None:
    assert _fuse([], _j(risk_score=score)).level == level


def test_custom_thresholds_from_calibration() -> None:
    t = F.Thresholds(dangerous=70, suspicious=45, unsure=20)
    assert _fuse([], _j(risk_score=72), thresholds=t).level == "DANGEROUS"
    assert _fuse([], _j(risk_score=20), thresholds=t).level == "UNSURE"


# ---------------------------------------------------------------- giảm điểm khi LLM chắc là N0

def test_n0_high_confidence_caps_score_when_no_hard_rule_and_urls_official() -> None:
    v = _fuse([TRANSFER, URGENCY], _j(group="N0", scenario="N0", risk_score=5))
    assert v.score == 30 and v.level == "UNSURE"
    v = _fuse([URGENCY], _j(group="N0", scenario="N0", risk_score=5))
    assert v.score == 15 and v.level == "LOW_RISK"


@pytest.mark.parametrize(
    ("signals", "official", "confidence"),
    [
        ([OTP_REQ], True, "high"),                 # có luật cứng
        ([_s("U_TYPOSQUAT", 10)], True, "high"),   # tín hiệu URL mạnh
        ([TRANSFER, URGENCY], False, "high"),      # có URL không chính thức
        ([TRANSFER, URGENCY], True, "medium"),     # LLM chưa đủ chắc
    ],
)
def test_n0_cap_does_not_apply(signals: list[Signal], official: bool, confidence: str) -> None:
    v = _fuse(signals, _j(group="N0", scenario="N0", risk_score=5, confidence=confidence), official=official)
    assert v.score > 30


# ---------------------------------------------------------------- LLM không chắc

def test_low_confidence_in_middle_band_becomes_unsure() -> None:
    assert _fuse([], _j(risk_score=60, confidence="low")).level == "UNSURE"


def test_low_confidence_does_not_lower_level_set_by_rules() -> None:
    v = _fuse([SECRECY, URGENCY, _s("R_PRIZE", 20)], _j(risk_score=40, confidence="low"))
    assert v.score == 70 and v.level == "SUSPICIOUS"


def test_low_confidence_keeps_dangerous_and_low_risk() -> None:
    assert _fuse([], _j(risk_score=80, confidence="low")).level == "DANGEROUS"
    assert _fuse([], _j(risk_score=10, confidence="low")).level == "LOW_RISK"


# ---------------------------------------------------------------- prompt injection

@pytest.mark.parametrize("code", ["R_AI_INSTRUCTION", "G_PROMPT_ATTACK"])
def test_injection_raises_level_to_at_least_unsure(code: str) -> None:
    v = _fuse([_s(code, 10)], _j(group="N0", scenario="N0", risk_score=0))
    assert v.level == "UNSURE"


# ---------------------------------------------------------------- fast path và chỉ chạy luật

def test_combo_goes_fast_path_without_llm() -> None:
    signals = [AUTHORITY, SECRECY, TRANSFER]
    assert F.is_fast_path(signals)
    v = _fuse(signals, None)
    assert (v.path, v.level, v.score, v.group, v.scenario) == ("fast", "DANGEROUS", 85, "N1", "N1.1")
    assert v.explanation == "" and v.followup_question is None


def test_single_hard_rule_is_not_fast_path() -> None:
    assert not F.is_fast_path([OTP_REQ])
    assert _fuse([OTP_REQ], None).path == "rules_only"


def test_rules_only_mode_has_minimum_unsure() -> None:
    """LLM lỗi hoặc kill switch: không bao giờ trả LOW_RISK khi chưa có LLM xem (ARCH §5.8, §6.7)."""
    v = _fuse([URGENCY], None)
    assert (v.path, v.level, v.group, v.scenario) == ("rules_only", "UNSURE", "OTHER", "OTHER")


def test_rules_only_mode_keeps_higher_rule_level() -> None:
    v = _fuse([OTP_REQ, URGENCY], None)
    assert (v.path, v.level, v.score) == ("rules_only", "DANGEROUS", 80)


def test_escalated_path_when_backup_model_answered() -> None:
    assert _fuse([], _j(), escalated=True).path == "escalated"


# ---------------------------------------------------------------- hành động rủi ro và báo người thân

def test_risky_actions_merge_rules_and_llm_in_fixed_order_without_none() -> None:
    v = _fuse([SECRECY, OTP_REQ], _j(requested_actions=["click_link", "share_otp", "none"]))
    assert v.risky_actions == ["share_otp", "click_link", "keep_secret"]


def test_should_alert_when_dangerous_and_risky_action() -> None:
    assert _fuse([OTP_REQ], None).should_alert is True


def test_no_alert_when_dangerous_without_risky_action() -> None:
    v = _fuse([_s("U_TYPOSQUAT", 10)], _j(risk_score=90, requested_actions=["none"]))
    assert v.level == "DANGEROUS" and v.risky_actions == [] and v.should_alert is False


def test_no_alert_below_dangerous_even_with_risky_action() -> None:
    v = _fuse([TRANSFER], _j(risk_score=55, requested_actions=["transfer_money"]))
    assert v.level == "SUSPICIOUS" and v.should_alert is False


# ---------------------------------------------------------------- trường còn lại của Verdict

def test_llm_signals_are_added_with_zero_weight_and_rule_signal_wins_on_same_code() -> None:
    j = _j(signals=[{"code": "R_URGENCY", "quote": "trong hôm nay"},
                    {"code": "U_TYPOSQUAT", "quote": "x" * 300}])
    v = _fuse([URGENCY], j)
    assert [(s.code, s.weight) for s in v.signals] == [("R_URGENCY", 15), ("U_TYPOSQUAT", 0)]
    assert v.signals[1].evidence is not None and len(v.signals[1].evidence) == F.MAX_EVIDENCE


def test_llm_fields_are_passed_through() -> None:
    v = _fuse([], _j(group="N5", scenario="N5.2", followup_question="Người đó có bảo bác cài ứng dụng không?"))
    assert (v.outcome, v.group, v.scenario) == ("verdict", "N5", "N5.2")
    assert v.explanation == "Tin đòi nộp phạt qua trang lạ."
    assert v.followup_question == "Người đó có bảo bác cài ứng dụng không?"
