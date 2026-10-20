import pathlib

import pytest

from askfirst.domain import models as m
from askfirst.pipeline import build_pipeline
from askfirst.pipeline.fixtures import DEFAULT_OUT, ULID, cases, render_files
from askfirst.pipeline.stub import bundle_from_items

EXPECT = {  # case -> (outcome, level, score, scenario)
    "unreadable": ("unreadable", "UNSURE", 30, "OTHER"),
    "smalltalk": ("smalltalk", "LOW_RISK", 0, "N0"),
    "otp": ("verdict", "DANGEROUS", 90, "N1.1"),
    "link": ("verdict", "SUSPICIOUS", 60, "N1.4"),
    "followup": ("verdict", "UNSURE", 30, "OTHER"),
    "followup_turn2": ("unreadable", "UNSURE", 30, "OTHER"),
    "photo": ("verdict", "UNSURE", 30, "OTHER"),
    "voice": ("verdict", "UNSURE", 30, "OTHER"),
    "default": ("verdict", "LOW_RISK", 10, "N0"),
}


@pytest.mark.parametrize("case", EXPECT)
def test_stub_analyze_table(case: str) -> None:
    _, res = cases()["analyze"][case]
    v = res.analyze.verdict
    assert (v.outcome, v.level, v.score, v.scenario) == EXPECT[case]
    assert v.path == "fast" and v.versions == {"contract": m.CONTRACT_VERSION, "pipeline": "stub"}


def test_stub_followup_and_alert() -> None:
    a = cases()["analyze"]
    assert a["followup"][1].analyze.verdict.followup_question
    assert a["followup_turn2"][1].analyze.verdict.followup_question is None
    otp = a["otp"][1].analyze.verdict
    assert otp.should_alert and otp.risky_actions == ["share_otp"]
    assert a["link"][1].analyze.verdict.risky_actions == ["click_link"]


def test_followup_turn2_with_text() -> None:
    from datetime import UTC, datetime
    msg = m.InboundMessage(channel="telegram", update_id="1", chat_id="1", user_id="tg:1", message_id="1", kind="text",
                           text="hỏi thêm", received_at=datetime(2026, 10, 14, tzinfo=UTC))
    s = m.SessionContext(prev_check_id=ULID, masked_text="x", question="?", answer="no", turn=2)
    v = build_pipeline("stub", pathlib.Path("."), None, None).analyze(bundle_from_items([msg], s), None)
    assert v.level == "UNSURE" and v.followup_question is None


def test_stub_render_texts() -> None:
    _, res = cases()["render"]["otp"]
    msg = res.render.messages[0]
    assert msg.text_html == "[STUB] DANGEROUS N1.1" and msg.buttons[0][0].kind == "report_wrong"
    alert = cases()["render_guardian_alerts"]["otp"][1].render_guardian_alerts.alerts[0]
    assert alert.text_html == "[STUB] cảnh báo Lan" and alert.buttons[0][0].kind == "ack"


def test_build_pipeline_rejects_unknown() -> None:
    with pytest.raises(ValueError):
        build_pipeline("nope", pathlib.Path("."), None, None)


def test_fixtures_are_up_to_date() -> None:   # fails if stub/models changed without rerunning `python -m askfirst.pipeline.fixtures`
    for rel, text in render_files().items():
        assert (DEFAULT_OUT / rel).read_text(encoding="utf-8") == text, rel
    on_disk = {p.relative_to(DEFAULT_OUT).as_posix() for p in DEFAULT_OUT.rglob("*.json")}
    assert on_disk == set(render_files())
