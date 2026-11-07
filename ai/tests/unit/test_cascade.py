"""AI-07 "Xong khi": Bedrock throttle (cả judge lẫn backup) thì pipeline vẫn ra ``Verdict`` với ``path=rules_only``.

Chạy đúng chuỗi ``BedrockClient`` → ``judge`` → ``fuse``; chỉ client boto3 là fake, không gọi AWS.
"""

from __future__ import annotations

import json

import pytest
from botocore.exceptions import ClientError, ReadTimeoutError

from askfirst.domain.models import Signal
from askfirst.llm.bedrock import BedrockClient
from askfirst.pipeline import fuse as F
from askfirst.pipeline import judge as J

TEXT = "Công an đây, bác phải chuyển tiền vào tài khoản an toàn ngay, không được nói với ai."
SIGNALS = [Signal(code="R_MONEY_TRANSFER", weight=25), Signal(code="R_URGENCY", weight=15)]
CONFIG = {
    "bedrock_region": "ap-southeast-2",
    "judge": {"model_id": "judge-model", "max_tokens": 100, "timeout_s": 8, "max_attempts": 2},
    "backup": {"model_id": "backup-model", "max_tokens": 100, "timeout_s": 12, "max_attempts": 1},
}
GOOD = {
    "output": {"message": {"content": [{"text": json.dumps({
        "group": "N1", "scenario": "N1.1", "risk_score": 90, "confidence": "high",
        "signals": [{"code": "R_MONEY_TRANSFER", "quote": "chuyển tiền vào tài khoản an toàn"}],
        "requested_actions": ["transfer_money"], "short_explanation": "Công an không bao giờ bảo chuyển tiền.",
        "followup_question": None}, ensure_ascii=False)}]}},
    "usage": {"inputTokens": 10, "outputTokens": 10},
}


def throttle() -> ClientError:
    return ClientError({"Error": {"Code": "ThrottlingException", "Message": "x"}}, "Converse")


def server_error() -> ClientError:
    return ClientError({"Error": {"Code": "InternalServerException", "Message": "x"}}, "Converse")


class Boto:
    def __init__(self, *outcomes):
        self.outcomes, self.calls = list(outcomes), 0

    def converse(self, **req):
        self.calls += 1
        o = self.outcomes.pop(0)
        if isinstance(o, Exception):
            raise o
        return o


def run(judge_boto: Boto, backup_boto: Boto, **kw):
    client = BedrockClient(config=CONFIG, clients={"judge": judge_boto, "backup": backup_boto}, sleep=lambda s: None)
    res = J.judge(client, "SYS", J.build_user_message(TEXT), TEXT, rule_score=F.rule_score(SIGNALS), **kw)
    return res, F.fuse(SIGNALS, res.judgement, all_urls_official=True, escalated=res.escalated)


@pytest.mark.parametrize("fail", [throttle, server_error, lambda: ReadTimeoutError(endpoint_url="u")])
def test_both_models_failing_gives_rules_only_verdict(fail):
    judge_boto, backup_boto = Boto(fail(), fail()), Boto(fail())
    res, v = run(judge_boto, backup_boto)
    assert v.path == "rules_only" and v.level == "UNSURE" and v.outcome == "verdict"
    assert (judge_boto.calls, backup_boto.calls) == (2, 1)   # judge thử lại 1 lần, backup 1 lần
    assert res.llm_errors == 2


def test_throttled_judge_then_backup_answers_gives_escalated_verdict():
    res, v = run(Boto(throttle(), throttle()), Boto(GOOD))
    assert v.path == "escalated" and v.level == "DANGEROUS" and res.escalation == "error"


def test_judge_recovers_on_retry_without_backup():
    backup_boto = Boto()
    _, v = run(Boto(throttle(), GOOD), backup_boto)
    assert v.path == "llm" and backup_boto.calls == 0


def test_kill_switch_gives_rules_only_without_calling_bedrock():
    judge_boto, backup_boto = Boto(), Boto()
    _, v = run(judge_boto, backup_boto, llm_enabled=False)
    assert v.path == "rules_only" and judge_boto.calls == backup_boto.calls == 0


def test_deadline_too_short_gives_rules_only_without_calling_bedrock():
    client = BedrockClient(config=CONFIG)
    can = J.deadline_check(10, {r: client.budget_s(r) for r in ("judge", "backup")})
    judge_boto = Boto()
    _, v = run(judge_boto, Boto(), can_call=can)
    assert v.path == "rules_only" and judge_boto.calls == 0
