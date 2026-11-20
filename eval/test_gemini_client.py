"""Test eval/gemini_client.py, không gọi mạng. Chạy từ ai/: uv run pytest ../eval/test_gemini_client.py"""
import io
import sys
import urllib.error
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import benchmark_models as bm  # noqa: E402
import gemini_client as G  # noqa: E402

from askfirst.llm.bedrock import LLMError  # noqa: E402

CFG = {"judge": {"model_id": "gemini-x", "max_tokens": 900, "temperature": 0, "timeout_s": 5, "max_attempts": 2},
       "backup": {"model_id": "", "max_tokens": 800, "timeout_s": 12},
       "pricing_usd_per_1m": {"gemini-x": {"in": 1.0, "out": 4.0}}}
MSGS = [{"role": "user", "content": [{"text": "tin cần kiểm"}]}]


def resp(text="{}", finish="STOP", thought=None, usage=(100, 20, 30)):
    parts = ([{"text": thought, "thought": True}] if thought else []) + [{"text": text}]
    return {"candidates": [{"content": {"role": "model", "parts": parts}, "finishReason": finish}],
            "usageMetadata": {"promptTokenCount": usage[0], "candidatesTokenCount": usage[1], "thoughtsTokenCount": usage[2]}}


class Post:
    def __init__(self, *outcomes):
        self.outcomes, self.calls = list(outcomes), []

    def __call__(self, url, payload, key, timeout):
        self.calls.append({"url": url, "payload": payload, "key": key, "timeout": timeout})
        out = self.outcomes.pop(0)
        if isinstance(out, Exception):
            raise out
        return out


def http_error(code):
    return urllib.error.HTTPError("https://x", code, "err", {}, io.BytesIO(b""))


def client(post, **kw):
    return G.GeminiClient(config=CFG, key="k", post=post, sleep=lambda s: None, rand=lambda: 0.0, **kw)


def test_to_contents_maps_roles_and_keeps_text_blocks():
    msgs = [{"role": "user", "content": [{"text": "a"}, {"image": {}}]}, {"role": "assistant", "content": [{"text": "b"}]}]
    assert G.to_contents(msgs) == [{"role": "user", "parts": [{"text": "a"}]}, {"role": "model", "parts": [{"text": "b"}]}]


def test_payload_has_system_generation_config_and_thinking():
    body = client(Post()).payload("judge", MSGS, "hệ thống")
    assert body["systemInstruction"] == {"parts": [{"text": "hệ thống"}]}
    assert body["generationConfig"] == {"maxOutputTokens": 900, "temperature": 0, "thinkingConfig": {"thinkingLevel": "low"}}
    assert "thinkingConfig" not in client(Post(), thinking_level=None).payload("judge", MSGS, None)["generationConfig"]


def test_converse_drops_thought_parts_and_counts_thinking_as_output():
    post = Post(resp('{"ok": 1}', thought="nghĩ thầm"))
    out = client(post).converse("judge", MSGS, system="s")
    assert out.text == '{"ok": 1}' and out.stop_reason == "STOP" and out.attempts == 1
    assert (out.input_tokens, out.output_tokens) == (100, 50)
    assert out.cost_usd == pytest.approx((100 * 1.0 + 50 * 4.0) / 1e6)
    assert post.calls[0]["url"].endswith("/models/gemini-x:generateContent") and "k" not in post.calls[0]["url"]
    assert post.calls[0]["key"] == "k" and post.calls[0]["timeout"] == 5


def test_converse_retries_rate_limit_then_succeeds():
    post = Post(http_error(429), resp())
    assert client(post).converse("judge", MSGS).attempts == 2 and len(post.calls) == 2


def test_converse_does_not_retry_client_error_and_gives_up_after_max_attempts():
    with pytest.raises(LLMError, match="HTTP 403") as e:
        client(Post(http_error(403))).converse("judge", MSGS)
    assert e.value.retryable is False
    with pytest.raises(LLMError, match="HTTP 503") as e:
        client(Post(http_error(503), http_error(503))).converse("judge", MSGS)
    assert e.value.retryable is True


@pytest.mark.parametrize("role, key, msg", [("backup", "k", "model_id is empty"), ("judge", "", "GEMINI_API_KEY")])
def test_converse_refuses_without_model_or_key(role, key, msg, monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    post = Post()
    with pytest.raises(LLMError, match=msg):
        G.GeminiClient(config=CFG, key=key, post=post).converse(role, MSGS)
    assert post.calls == []


def test_converse_without_candidates_is_an_error():
    with pytest.raises(LLMError, match="no candidates"):
        client(Post({"candidates": []})).converse("judge", MSGS)


def test_benchmark_gemini_candidate_config():
    cand = bm.CANDIDATES["gemini-3-8-flash"]
    cfg = bm.candidate_config({"judge": CFG["judge"], "backup": CFG["backup"]}, cand)
    assert cand["provider"] == "gemini" and cfg["judge"]["model_id"] == "gemini-3.8-flash"
    assert cfg["judge"]["max_tokens"] == 2000 and cfg["pricing_usd_per_1m"]["gemini-3.8-flash"] == cand["price"]
