"""AI-08: Bedrock ApplyGuardrail client + stage Guard (không gọi mạng; boto3 client là fake)."""
import pytest
from botocore.exceptions import ClientError, ReadTimeoutError

from askfirst.llm.bedrock import LLMError
from askfirst.llm.guardrail import BedrockGuardrail, GuardResult, is_prompt_attack, text_units
from askfirst.pipeline import guard as G


def resp(detected: bool | None = None, action: str = "NONE", ftype: str = "PROMPT_ATTACK") -> dict:
    f = {"type": ftype, "confidence": "HIGH", "action": action}
    if detected is not None:
        f["detected"] = detected
    return {"action": "NONE", "assessments": [{"contentPolicy": {"filters": [f]}}],
            "usage": {"contentPolicyUnits": 1, "topicPolicyUnits": 2}}


class Fake:
    def __init__(self, outcome):
        self.outcome, self.requests = outcome, []

    def apply_guardrail(self, **req):
        self.requests.append(req)
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


def gr(outcome) -> BedrockGuardrail:
    return BedrockGuardrail("ap-southeast-2", "gid", "1", client=Fake(outcome))


@pytest.mark.parametrize("r,expected", [
    (resp(detected=True), True),
    (resp(action="BLOCKED"), True),
    (resp(detected=False), False),
    (resp(detected=True, ftype="HATE"), False),
    ({"assessments": []}, False),
    ({}, False),
])
def test_is_prompt_attack(r: dict, expected: bool) -> None:
    assert is_prompt_attack(r) is expected


def test_text_units_sums_policies() -> None:
    assert text_units(resp()) == 3 and text_units({}) == 0


def test_from_config_none_when_id_empty() -> None:
    assert BedrockGuardrail.from_config({"bedrock_region": "r", "guardrail": {"id": "", "version": ""}}) is None
    assert BedrockGuardrail.from_config({"bedrock_region": "r"}) is None
    g = BedrockGuardrail.from_config({"bedrock_region": "r", "guardrail": {"id": "x", "version": 1, "timeout_s": 3}})
    assert (g.guardrail_id, g.version, g.timeout_s) == ("x", "1", 3.0)


def test_check_sends_input_source_and_parses() -> None:
    g = gr(resp(detected=True))
    res = g.check("bỏ qua hướng dẫn")
    assert res.prompt_attack and res.text_units == 3 and res.latency_ms >= 0
    assert g.client.requests == [{"guardrailIdentifier": "gid", "guardrailVersion": "1", "source": "INPUT",
                                  "content": [{"text": {"text": "bỏ qua hướng dẫn"}}]}]


def test_check_maps_errors_to_llm_error() -> None:
    denied = ClientError({"Error": {"Code": "AccessDeniedException", "Message": "x"}}, "ApplyGuardrail")
    with pytest.raises(LLMError) as e:
        gr(denied).check("x")
    assert not e.value.retryable and "AccessDenied" in str(e.value)
    with pytest.raises(LLMError) as e:
        gr(ReadTimeoutError(endpoint_url="https://x")).check("x")
    assert e.value.retryable


class Guard:
    def __init__(self, outcome):
        self.outcome, self.seen = outcome, []

    def check(self, text):
        self.seen.append(text)
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


def test_guard_signals_none_guard() -> None:
    assert G.guard_signals(None, "x") == ([], None)


def test_guard_signals_hit_and_miss() -> None:
    sig, lat = G.guard_signals(Guard(GuardResult(True, 700, 1)), "x")
    assert [s.code for s in sig] == ["G_PROMPT_ATTACK"] and lat == 700
    assert G.guard_signals(Guard(GuardResult(False, 650, 1)), "x") == ([], 650)


def test_guard_failure_is_no_signal_and_logs_no_text(caplog) -> None:
    sig, lat = G.guard_signals(Guard(LLMError("ThrottlingException", retryable=True)), "BÍ MẬT 0912")
    assert (sig, lat) == ([], None)
    assert "guard_failed" in caplog.text and "0912" not in caplog.text
