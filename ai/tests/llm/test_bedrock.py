import pytest
from botocore.exceptions import ClientError, ReadTimeoutError

from askfirst.llm.bedrock import BedrockClient, LLMError

CONFIG = {
    "bedrock_region": "ap-southeast-2",
    "judge": {"model_id": "m1", "max_tokens": 100, "temperature": 0, "timeout_s": 8},
    "backup": {"model_id": "", "max_tokens": 100, "timeout_s": 8},
    "pricing_usd_per_1m": {"m1": {"in": 1.0, "out": 2.0}},
}
OK = {
    "output": {"message": {"content": [{"reasoningContent": {"reasoningText": {"text": "hmm"}}}, {"text": "xin "}, {"text": "chào"}]}},
    "usage": {"inputTokens": 1000, "outputTokens": 500},
    "stopReason": "end_turn",
}


def err(code: str) -> ClientError:
    return ClientError({"Error": {"Code": code, "Message": "x"}}, "Converse")


class Fake:
    def __init__(self, *outcomes):
        self.outcomes = list(outcomes)
        self.calls: list[dict] = []

    def converse(self, **req):
        self.calls.append(req)
        o = self.outcomes.pop(0)
        if isinstance(o, Exception):
            raise o
        return o


def make(fake: Fake, sleeps: list[float] | None = None) -> BedrockClient:
    sleeps = sleeps if sleeps is not None else []
    return BedrockClient(config=CONFIG, clients={"judge": fake, "backup": fake}, sleep=sleeps.append, rand=lambda: 1.0)


def test_success_counts_tokens_and_cost_and_drops_reasoning():
    fake = Fake(OK)
    r = make(fake).converse("judge", [{"role": "user", "content": [{"text": "hi"}]}], system="sys")
    assert r.text == "xin chào"
    assert (r.input_tokens, r.output_tokens, r.attempts) == (1000, 500, 1)
    assert r.cost_usd == pytest.approx(0.002)
    req = fake.calls[0]
    assert req["modelId"] == "m1" and req["system"] == [{"text": "sys"}] and req["inferenceConfig"] == {"maxTokens": 100, "temperature": 0}


def test_extra_fields_passed_through():
    fake = Fake(OK)
    make(fake).converse("judge", [], extra_fields={"k": 1})
    assert fake.calls[0]["additionalModelRequestFields"] == {"k": 1}


def test_retries_throttle_with_jittered_backoff_then_succeeds():
    sleeps: list[float] = []
    r = make(Fake(err("ThrottlingException"), ReadTimeoutError(endpoint_url="u"), OK), sleeps).converse("judge", [])
    assert r.attempts == 3 and len(sleeps) == 2
    assert all(0 <= s <= 4.0 for s in sleeps) and sleeps[1] > sleeps[0]


def test_gives_up_after_max_attempts_as_retryable():
    with pytest.raises(LLMError) as e:
        make(Fake(*[err("ThrottlingException")] * 3)).converse("judge", [])
    assert e.value.retryable


def test_non_retryable_error_fails_fast():
    fake = Fake(err("AccessDeniedException"), OK)
    with pytest.raises(LLMError) as e:
        make(fake).converse("judge", [])
    assert not e.value.retryable and len(fake.calls) == 1


def test_empty_model_id_is_fatal():
    with pytest.raises(LLMError) as e:
        make(Fake()).converse("backup", [])
    assert not e.value.retryable


def test_unknown_price_gives_zero_cost():
    assert make(Fake()).cost_usd("other", 10, 10) == 0.0


def test_real_client_does_not_retry_inside_botocore():
    c = BedrockClient(config=CONFIG)._client("judge")  # builds a boto3 client, no network call
    assert c.meta.config.retries["total_max_attempts"] == 1
