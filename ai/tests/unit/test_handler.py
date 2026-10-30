import json
import logging
import pathlib

import pytest

from askfirst import handler as h
from askfirst.cache.dynamo import DynamoKV
from askfirst.domain import models as m
from askfirst.llm.bedrock import LLMError
from askfirst.pipeline.stub import StubPipeline, bundle_from_items

FIXTURES = pathlib.Path(__file__).resolve().parents[3] / "contracts" / "fixtures"
FIXTURE_FILES = sorted(FIXTURES.glob("*/*.json"))
TG_URL = "https://api.telegram.org/file/bot123456:SECRETTOKEN/photos/a.jpg"


def fixture(op: str, case: str) -> tuple[dict, dict]:
    d = json.loads((FIXTURES / op / f"{case}.json").read_text(encoding="utf-8"))
    return d["request"], d["response"]


class MemKV:
    def __init__(self) -> None:
        self.data: dict[str, dict] = {}
        self.puts = 0

    def get(self, key: str) -> dict | None:
        return self.data.get(key)

    def put(self, key: str, value: dict, ttl_s: int) -> None:
        assert ttl_s == h.CACHE_TTL_S
        self.puts += 1
        self.data[key] = value


class BrokenKV:
    def get(self, key: str) -> dict | None:
        raise RuntimeError("dynamo down")

    def put(self, key: str, value: dict, ttl_s: int) -> None:
        raise RuntimeError("dynamo down")


class Pipe(StubPipeline):
    """Stub có fingerprint thật + đếm số lần analyze + cho chèn verdict/lỗi."""

    def __init__(self, exc: Exception | None = None, **update) -> None:
        self.calls, self.exc, self.update = 0, exc, update

    def fingerprint(self, bundle: m.Bundle) -> str:
        return "fp-" + "|".join(t.text for t in bundle.texts)

    def analyze(self, bundle: m.Bundle, ctx: m.AnalyzeContext) -> m.Verdict:
        self.calls += 1
        if self.exc:
            raise self.exc
        v = super().analyze(bundle, ctx)
        return v.model_copy(update={"explanation": "giải thích", "signals": [m.Signal(code="R_OTP_REQUEST", weight=5, evidence="mã OTP")]}
                            | self.update)


def stub_bundle(items, session, flags):
    return bundle_from_items(items, session)


def rt(pipe=None, cache=None, build=stub_bundle) -> h.Runtime:
    return h.Runtime(pipe or Pipe(), build, cache)


def stub_rt() -> h.Runtime:
    return h.init_runtime({"PIPELINE_IMPL": "stub"})


def err(resp: dict) -> tuple[str, str]:
    m.AiResponse.model_validate(resp)
    return resp["error"]["kind"], resp["error"]["code"]


# ---------- contract fixtures: handler(stub) == fixture response ----------

@pytest.mark.parametrize("path", FIXTURE_FILES, ids=lambda p: f"{p.parent.name}/{p.stem}")
def test_fixture_roundtrip(path: pathlib.Path) -> None:
    d = json.loads(path.read_text(encoding="utf-8"))
    assert h.lambda_handler(d["request"], None, runtime=stub_rt()) == d["response"]


def test_fixtures_cover_all_ops() -> None:
    assert {p.parent.name for p in FIXTURE_FILES} == set(h.OPS)


def test_default_runtime_is_stub_without_cache(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("PIPELINE_IMPL", raising=False)
    monkeypatch.setattr(h, "_RUNTIME", None)
    req, res = fixture("analyze", "otp")
    assert h.lambda_handler(req) == res
    assert h._RUNTIME is not None and h._RUNTIME.cache is None


# ---------- bad input → fatal, never raises ----------

@pytest.mark.parametrize("event", [None, [], "x", 42, {}, {"v": "1.0"}, {"op": "analyze"}, {"v": 1.0, "op": "analyze"}])
def test_weird_events_never_raise(event) -> None:
    kind, _ = err(h.lambda_handler(event, None, runtime=rt()))
    assert kind == "fatal"


def test_non_dict_is_bad_request() -> None:
    assert err(h.lambda_handler(["analyze"], None, runtime=rt())) == ("fatal", "bad_request")


def test_unsupported_version() -> None:
    req, _ = fixture("render", "default")
    resp = h.lambda_handler(req | {"v": "2.0"}, None, runtime=rt())
    assert err(resp) == ("fatal", "unsupported_version") and resp["op"] == "render"


def test_unknown_op_falls_back_to_analyze_op() -> None:
    resp = h.lambda_handler({"v": "1.0", "op": "delete_everything"}, None, runtime=rt())
    assert err(resp) == ("fatal", "unknown_op") and resp["op"] == "analyze"


@pytest.mark.parametrize("op,case", [("analyze", "default"), ("render", "default"), ("render_guardian_alerts", "otp")])
def test_bad_schema_is_bad_request(op: str, case: str) -> None:
    req, _ = fixture(op, case)
    bad = {k: v for k, v in req.items() if k not in ("ctx", "verdict")}
    resp = h.lambda_handler(bad, None, runtime=rt())
    assert err(resp) == ("fatal", "bad_request") and resp["op"] == op


def test_render_guardian_alerts_needs_a_guardian() -> None:
    req, _ = fixture("render_guardian_alerts", "otp")
    assert err(h.lambda_handler(req | {"guardians": []}, None, runtime=rt())) == ("fatal", "bad_request")


# ---------- media / pipeline errors ----------

def test_media_download_error_is_retryable() -> None:
    def build(items, session, flags):
        raise h.MediaDownloadError("getFile failed")
    req, _ = fixture("analyze", "photo")
    assert err(h.lambda_handler(req, None, runtime=rt(build=build))) == ("retryable", "telegram_download")


def test_other_bundle_error_is_fatal_internal() -> None:
    def build(items, session, flags):
        raise KeyError("bug")
    req, _ = fixture("analyze", "photo")
    assert err(h.lambda_handler(req, None, runtime=rt(build=build))) == ("fatal", "internal")


def test_retryable_llm_error_is_retryable() -> None:
    req, _ = fixture("analyze", "default")
    resp = h.lambda_handler(req, None, runtime=rt(Pipe(exc=LLMError("ThrottlingException", retryable=True))))
    assert err(resp) == ("retryable", "llm_throttled")


@pytest.mark.parametrize("exc", [LLMError("AccessDenied", retryable=False), RuntimeError("bug")])
def test_non_retryable_pipeline_error_is_fatal(exc: Exception) -> None:
    req, _ = fixture("analyze", "default")
    assert err(h.lambda_handler(req, None, runtime=rt(Pipe(exc=exc)))) == ("fatal", "pipeline_error")


@pytest.mark.parametrize("op,case,meth", [("render", "default", "render"), ("render_guardian_alerts", "otp", "render_guardian_alert")])
def test_render_error_is_fatal(monkeypatch: pytest.MonkeyPatch, op: str, case: str, meth: str) -> None:
    pipe = Pipe()
    monkeypatch.setattr(pipe, meth, lambda *a: (_ for _ in ()).throw(ValueError("x")))
    req, _ = fixture(op, case)
    assert err(h.lambda_handler(req, None, runtime=rt(pipe))) == ("fatal", "render_failed")


@pytest.mark.parametrize("impl,kind", [("real", "fatal"), ("bogus", "fatal")])
def test_init_errors(monkeypatch: pytest.MonkeyPatch, impl: str, kind: str) -> None:
    monkeypatch.setenv("PIPELINE_IMPL", impl)
    monkeypatch.setattr(h, "_RUNTIME", None)
    req, _ = fixture("analyze", "default")
    assert err(h.lambda_handler(req)) == (kind, "init")


def test_transient_init_error_is_retryable(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(env=None):
        raise ConnectionError("ssm")
    monkeypatch.setattr(h, "_RUNTIME", None)
    monkeypatch.setattr(h, "init_runtime", boom)
    req, _ = fixture("analyze", "default")
    assert err(h.lambda_handler(req)) == ("retryable", "init")


# ---------- CACHE# ----------

def test_cache_miss_then_hit() -> None:
    pipe, kv = Pipe(), MemKV()
    req, _ = fixture("analyze", "otp")
    first = h.lambda_handler(req, None, runtime=rt(pipe, kv))
    second = h.lambda_handler(req, None, runtime=rt(pipe, kv))
    assert pipe.calls == 1 and kv.puts == 1
    assert first["analyze"]["cache_hit"] is False and first["analyze"]["verdict"]["path"] == "fast"
    assert second["analyze"]["cache_hit"] is True
    v = second["analyze"]["verdict"]
    assert v["path"] == "cache" and v["level"] == "DANGEROUS" and v["should_alert"] is True
    m.AiResponse.model_validate(second)


def test_cache_stores_for_cache_copy_only() -> None:
    kv = MemKV()
    req, _ = fixture("analyze", "otp")
    first = h.lambda_handler(req, None, runtime=rt(Pipe(), kv))
    assert first["analyze"]["verdict"]["masked_text"] and first["analyze"]["verdict"]["explanation"]
    (stored,) = kv.data.values()
    assert stored["masked_text"] == "" and stored["explanation"] == "" and stored["followup_question"] is None
    assert all(s["evidence"] is None for s in stored["signals"])
    assert "OTP" not in json.dumps(stored, ensure_ascii=False).replace("R_OTP_REQUEST", "")


def test_different_text_is_different_key() -> None:
    pipe, kv = Pipe(), MemKV()
    req, _ = fixture("analyze", "otp")
    h.lambda_handler(req, None, runtime=rt(pipe, kv))
    other = json.loads(json.dumps(req))
    other["items"][0]["text"] = "nội dung khác"
    assert h.lambda_handler(other, None, runtime=rt(pipe, kv))["analyze"]["cache_hit"] is False
    assert pipe.calls == 2 and len(kv.data) == 2


def test_session_turn_skips_cache() -> None:
    pipe, kv = Pipe(), MemKV()
    req, _ = fixture("analyze", "followup_turn2")
    for _ in range(2):
        assert h.lambda_handler(req, None, runtime=rt(pipe, kv))["analyze"]["cache_hit"] is False
    assert pipe.calls == 2 and kv.puts == 0


def test_rules_only_not_cached() -> None:
    pipe, kv = Pipe(path="rules_only"), MemKV()
    req, _ = fixture("analyze", "otp")
    h.lambda_handler(req, None, runtime=rt(pipe, kv))
    h.lambda_handler(req, None, runtime=rt(pipe, kv))
    assert pipe.calls == 2 and kv.puts == 0


def test_broken_cache_is_ignored() -> None:
    pipe = Pipe()
    req, _ = fixture("analyze", "otp")
    resp = h.lambda_handler(req, None, runtime=rt(pipe, BrokenKV()))
    assert resp["error"] is None and resp["analyze"]["cache_hit"] is False and pipe.calls == 1


def test_corrupt_cache_value_is_a_miss() -> None:
    pipe, kv = Pipe(), MemKV()
    req, _ = fixture("analyze", "otp")
    key = pipe.fingerprint(bundle_from_items([m.InboundMessage.model_validate(i) for i in req["items"]]))
    kv.data[key] = {"level": "NOPE"}
    resp = h.lambda_handler(req, None, runtime=rt(pipe, kv))
    assert resp["analyze"]["cache_hit"] is False and pipe.calls == 1


def test_fingerprint_error_skips_cache(monkeypatch: pytest.MonkeyPatch) -> None:
    pipe, kv = Pipe(), MemKV()
    monkeypatch.setattr(pipe, "fingerprint", lambda b: (_ for _ in ()).throw(ValueError("x")))
    req, _ = fixture("analyze", "otp")
    assert h.lambda_handler(req, None, runtime=rt(pipe, kv))["error"] is None
    assert kv.puts == 0


# ---------- turn 2 never asks again ----------

def test_turn2_followup_is_stripped() -> None:
    req, _ = fixture("analyze", "followup_turn2")
    resp = h.lambda_handler(req, None, runtime=rt(Pipe(followup_question="Hỏi lần nữa?")))
    assert resp["analyze"]["verdict"]["followup_question"] is None


def test_turn1_followup_is_kept() -> None:
    req, _ = fixture("analyze", "followup")
    resp = h.lambda_handler(req, None, runtime=rt(Pipe()))
    assert resp["analyze"]["verdict"]["followup_question"]


# ---------- logs ----------

def test_log_has_no_content_or_url(caplog: pytest.LogCaptureFixture) -> None:
    def build(items, session, flags):
        raise h.MediaDownloadError(TG_URL)          # kể cả khi AI2 lỡ nhét URL vào message
    req, _ = fixture("analyze", "otp")
    with caplog.at_level(logging.INFO, logger="askfirst.handler"):
        h.lambda_handler(req, None, runtime=rt(Pipe(), MemKV()))
        h.lambda_handler(req, None, runtime=rt(build=build))
        h.lambda_handler(req, None, runtime=rt(Pipe(exc=RuntimeError(TG_URL))))
    lines = [r.getMessage() for r in caplog.records if r.name == "askfirst.handler"]
    assert len(lines) == 3
    text = "\n".join(lines)
    assert "OTP" not in text and "123456" not in text and "SECRETTOKEN" not in text and "telegram.org" not in text
    recs = [json.loads(x) for x in lines]
    assert recs[0]["trace_id"] == req["ctx"]["check_id"] and recs[0]["result"] == "ok" and recs[0]["cache_hit"] is False
    assert recs[1]["result"] == "retryable/telegram_download" and recs[1]["cause"] is None
    assert recs[2] == recs[2] | {"result": "fatal/pipeline_error", "cause": "RuntimeError"}
    assert all(isinstance(r["ms"], int) for r in recs)


def test_log_trace_id_is_truncated(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level(logging.INFO, logger="askfirst.handler"):
        h.lambda_handler({"v": "1.0", "op": "x", "ctx": {"check_id": "A" * 500}}, None, runtime=rt())
    assert json.loads(caplog.records[-1].getMessage())["trace_id"] == "A" * 64


# ---------- DynamoKV ----------

class FakeTable:
    def __init__(self) -> None:
        self.items: dict[tuple[str, str], dict] = {}

    def get_item(self, Key: dict) -> dict:  # noqa: N803 — tên tham số theo boto3
        item = self.items.get((Key["PK"], Key["SK"]))
        return {"Item": item} if item else {}

    def put_item(self, Item: dict) -> None:  # noqa: N803
        self.items[(Item["PK"], Item["SK"])] = Item


def test_dynamokv_roundtrip_and_layout() -> None:
    t, now = FakeTable(), [1_000.0]
    kv = DynamoKV(t, "CACHE#", "VERDICT", now=lambda: now[0])
    value = {"level": "DANGEROUS", "score": 90, "cost": 0.0012, "vi": "Tiếng Việt"}
    kv.put("abc", value, 86_400)
    item = t.items[("CACHE#abc", "VERDICT")]
    assert item["ttl"] == 87_400 and isinstance(item["value"], str)
    assert kv.get("abc") == value
    assert kv.get("missing") is None


def test_dynamokv_expired_item_is_a_miss() -> None:
    t, now = FakeTable(), [1_000.0]
    kv = DynamoKV(t, "URL#", "INTEL", ttl_attr="expires_at", now=lambda: now[0])
    kv.put("u", {"x": 1}, 60)
    assert t.items[("URL#u", "INTEL")]["expires_at"] == 1_060
    now[0] = 1_059.9
    assert kv.get("u") == {"x": 1}
    now[0] = 1_060
    assert kv.get("u") is None


def test_dynamokv_works_as_handler_cache() -> None:
    pipe = Pipe()
    kv = DynamoKV(FakeTable(), "CACHE#", "VERDICT")
    req, _ = fixture("analyze", "otp")
    h.lambda_handler(req, None, runtime=rt(pipe, kv))
    assert h.lambda_handler(req, None, runtime=rt(pipe, kv))["analyze"]["cache_hit"] is True
    assert pipe.calls == 1
