import json
import pathlib
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from askfirst.domain import models as m

NOW = datetime(2026, 10, 14, 7, 5, tzinfo=UTC)
ULID = "01J" + "0" * 23


def _verdict(**kw) -> m.Verdict:
    base = {"outcome": "verdict", "level": "DANGEROUS", "score": 90, "group": "N1", "scenario": "N1.4",
            "signals": [m.Signal(code="U_TEXT_LINK_MISMATCH", weight=40, hard=True, evidence="Bấm vào đây")],
            "risky_actions": ["click_link"], "should_alert": True, "explanation": "x", "masked_text": "Bấm vào đây",
            "path": "fast", "versions": {"contract": m.CONTRACT_VERSION}}
    return m.Verdict(**(base | kw))


def test_inbound_message_json_round_trip() -> None:   # BUFFER# stores it as JSON
    msg = m.InboundMessage(
        channel="telegram", update_id="1", chat_id="42", user_id="tg:42", message_id="7", kind="text",
        text="Bấm vào đây", entities=[m.EntityRef(kind="text_link", offset=0, length=3, url="https://dichvucong-vn.com")],
        received_at=NOW)
    assert m.InboundMessage.model_validate_json(msg.model_dump_json()) == msg


def test_text_link_requires_url() -> None:
    with pytest.raises(ValidationError):
        m.EntityRef(kind="text_link", offset=0, length=1)


def test_for_cache_strips_user_text() -> None:
    v = _verdict(followup_question="Có ai bảo giữ bí mật không?")
    c = v.for_cache()
    assert c.masked_text == "" and c.explanation == "" and c.followup_question is None
    assert all(s.evidence is None for s in c.signals)
    assert v.signals[0].evidence == "Bấm vào đây"          # the original is untouched
    assert m.Verdict.model_validate_json(c.model_dump_json()) == c


@pytest.mark.parametrize("bad", [
    lambda: _verdict(level="SAFE"),                       # there is no "safe" level
    lambda: _verdict(score=101),
    lambda: _verdict(risky_actions=["chuyen_khoan"]),        # invalid enum value
    lambda: m.ReplyPlan(messages=[]),                        # needs at least 1 message
    lambda: m.Flags(llm_enabled=True, typo=1),               # unknown fields are rejected
    lambda: m.SessionContext(prev_check_id=ULID, masked_text="x", question="?", answer="yes", turn=3),
])
def test_invalid_values_are_rejected(bad) -> None:
    with pytest.raises(ValidationError):
        bad()


def test_contexts_and_reply_plan_build() -> None:
    bundle = m.Bundle(texts=[m.TextPart(text="alo")],
                      images=[m.ImageInput(data=b"\x89PNG", file_unique_id="AQAD", mime="image/png")])
    ctx = m.AnalyzeContext(check_id=ULID, user_id="tg:42", flags=m.Flags(), deadline_s=85)
    plan = m.ReplyPlan(messages=[m.OutMessage(
        text_html="<b>x</b>", buttons=[[m.Button(kind="report_wrong", label="Bot báo sai")]])])
    rctx = m.RenderContext(check_id=ULID, address_form="elder", has_guardian=True,
                           alerts=[m.AlertResult(guardian_name="Nam", status="sent")])
    gctx = m.GuardianAlertContext(alert_id=ULID, elder_name="Lan", share_level=1, at=NOW)
    assert bundle.images[0].data and ctx.flags.llm_enabled and plan.messages and rctx.alerts and gctx.share_level == 1


def test_json_schema_generates() -> None:
    for model in (m.Bundle, m.Verdict, m.ReplyPlan, m.AnalyzeContext, m.RenderContext, m.GuardianAlertContext):
        assert model.model_json_schema()


def _msg() -> m.InboundMessage:
    return m.InboundMessage(
        channel="telegram", update_id="1", chat_id="42", user_id="tg:42", message_id="7", kind="photo",
        media=m.MediaRef(file_id="AgAC", file_unique_id="AQAD", mime="image/jpeg", size=120_000), received_at=NOW)


def test_wire_round_trip_all_ops() -> None:   # exactly what Java sends/receives
    ctx = m.AnalyzeContext(check_id=ULID, user_id="tg:42", flags=m.Flags(llm_enabled=False), deadline_s=85)
    reqs = [
        m.AnalyzeRequest(items=[_msg()], ctx=ctx),
        m.RenderGuardianAlertsRequest(verdict=_verdict(), guardians=[
            m.GuardianAlertContext(alert_id=ULID, elder_name="Lan", share_level=2, at=NOW)]),
        m.RenderRequest(verdict=_verdict(), ctx=m.RenderContext(check_id=ULID, address_form="aunt", has_guardian=False)),
    ]
    for req in reqs:
        assert type(req).model_validate_json(req.model_dump_json()) == req
        assert json.loads(req.model_dump_json())["v"] == m.CONTRACT_VERSION
    plan = m.ReplyPlan(messages=[m.OutMessage(text_html="x")])
    resps = [
        m.AiResponse(op="analyze", analyze=m.AnalyzeResult(verdict=_verdict(), cache_hit=False)),
        m.AiResponse(op="render_guardian_alerts", render_guardian_alerts=m.RenderGuardianAlertsResult(alerts=plan.messages)),
        m.AiResponse(op="render", render=plan),
        m.AiResponse(op="analyze", error=m.AiError(kind="retryable", code="telegram_download")),
    ]
    for r in resps:
        assert m.AiResponse.model_validate_json(r.model_dump_json()) == r


def test_wire_request_has_no_media_bytes() -> None:   # media only lives in the RAM of `ai` (ARCH §4.5)
    raw = m.AnalyzeRequest(items=[_msg()], ctx=m.AnalyzeContext(
        check_id=ULID, user_id="tg:42", flags=m.Flags(), deadline_s=85)).model_dump_json()
    assert "data" not in json.loads(raw)["items"][0]


@pytest.mark.parametrize("bad", [
    lambda: m.AiResponse(op="render"),                                                   # no result
    lambda: m.AiResponse(op="render", analyze=m.AnalyzeResult(verdict=_verdict(), cache_hit=True)),   # wrong op
    lambda: m.AiResponse(op="render", render=m.ReplyPlan(messages=[m.OutMessage(text_html="x")]),
                         error=m.AiError(kind="fatal", code="bad_request")),            # both a result and an error
    lambda: m.RenderGuardianAlertsRequest(verdict=_verdict(), guardians=[]),
    lambda: m.AnalyzeRequest.model_validate({"op": "analyse", "ctx": {}}),             # misspelled op
])
def test_invalid_wire_rejected(bad) -> None:
    with pytest.raises(ValidationError):
        bad()


def test_exported_schema_is_java_friendly(tmp_path: pathlib.Path) -> None:
    paths = m.export_json_schemas(tmp_path)
    assert {p.name for p in paths} == {f"{x.__name__}.json" for x in m.WIRE_MODELS}
    for p in paths:
        text = p.read_text(encoding="utf-8")
        assert '"anyOf"' not in text and '"$defs"' not in text and '"null"' not in text
        assert "#/definitions/" in text or p.name == "InboundMessage.json" or "definitions" not in text


def test_callback_data_fits_telegram_limit() -> None:  # callback_data ≤ 64 byte
    for data in (f"fb:wrong:{ULID}", f"fu:{ULID}:u", f"cg:{ULID}", f"ack:{ULID}", f"dn:{ULID}"):
        assert len(data.encode()) <= 64
