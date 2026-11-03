"""INT-01: ``RealPipeline`` cho văn bản, chạy đủ chuỗi normalize → mask → rules → judge → fuse → render.

LLM là ``BedrockClient`` thật với client boto3 giả, không gọi AWS. Rules là hàm giả cho tới khi có AI-23.
"""

from __future__ import annotations

import json
import pathlib
from datetime import UTC, datetime

import pytest
from botocore.exceptions import ClientError

from askfirst import handler as h
from askfirst.domain import models as m
from askfirst.llm.bedrock import BedrockClient
from askfirst.pipeline import build_pipeline
from askfirst.pipeline import real as P
from askfirst.pipeline.judge import PROMPT_VERSION

CONFIG_DIR = pathlib.Path(__file__).resolve().parents[2] / "config"
FIXTURES = pathlib.Path(__file__).resolve().parents[3] / "contracts" / "fixtures"
CONFIG = {
    "bedrock_region": "ap-southeast-2",
    "judge": {"model_id": "judge-model", "max_tokens": 100, "timeout_s": 8, "max_attempts": 2},
    "backup": {"model_id": "backup-model", "max_tokens": 100, "timeout_s": 12, "max_attempts": 1},
    "pricing_usd_per_1m": {"judge-model": {"in": 1.0, "out": 2.0}},
}
SCAM = "Công an đây, bác chuyển tiền vào tài khoản an toàn ngay, gọi lại 0912 345 678."
COMBO = [m.Signal(code="R_AUTHORITY", weight=20), m.Signal(code="R_SAFE_ACCOUNT", weight=50, hard=True)]


def answer(**over) -> dict:
    body = {"group": "N1", "scenario": "N1.1", "risk_score": 90, "confidence": "high",
            "signals": [{"code": "R_SAFE_ACCOUNT", "quote": "chuyển tiền vào tài khoản an toàn"}],
            "requested_actions": ["transfer_money"], "short_explanation": "Công an không bao giờ bảo chuyển tiền.",
            "followup_question": None} | over
    return {"output": {"message": {"content": [{"text": json.dumps(body, ensure_ascii=False)}]}},
            "usage": {"inputTokens": 1000, "outputTokens": 100}}


def throttle() -> ClientError:
    return ClientError({"Error": {"Code": "ThrottlingException", "Message": "x"}}, "Converse")


class Boto:
    def __init__(self, *outcomes):
        self.outcomes, self.requests = list(outcomes), []

    def converse(self, **req):
        self.requests.append(req)
        o = self.outcomes.pop(0)
        if isinstance(o, Exception):
            raise o
        return o


class Rules:
    """Ghi lại input để kiểm rules chỉ thấy bản đã mask."""

    def __init__(self, signals: list[m.Signal] | None = None):
        self.signals, self.seen = signals or [], []

    def __call__(self, masked_text: str, urls: list[str]) -> list[m.Signal]:
        self.seen.append((masked_text, urls))
        return self.signals


def pipe(judge: Boto | None = None, backup: Boto | None = None, rules=P.no_rules) -> P.RealPipeline:
    llm = BedrockClient(config=CONFIG, clients={"judge": judge or Boto(), "backup": backup or Boto()}, sleep=lambda s: None)
    return P.RealPipeline(llm, "SYS", rules=rules, rules_version="rules-test")


def bundle(*texts: str, entities: list[m.EntityRef] | None = None, **kw) -> m.Bundle:
    parts = [m.TextPart(text=t) for t in texts]
    if entities:
        parts[0] = m.TextPart(text=texts[0], entities=entities)
    return m.Bundle(texts=parts, **kw)


def ctx(llm_enabled: bool = True, deadline_s: float = 85.0) -> m.AnalyzeContext:
    return m.AnalyzeContext(check_id="01J0", user_id="tg:42", flags=m.Flags(llm_enabled=llm_enabled), deadline_s=deadline_s)


def render(v: m.Verdict) -> str:
    plan = pipe().render(v, m.RenderContext(check_id="01J0", address_form="elder", has_guardian=True))
    return "\n".join(msg.text_html for msg in plan.messages)


# ---------------------------------------------------------------- luồng chính

def test_llm_path_end_to_end():
    judge = Boto(answer())
    rules = Rules([m.Signal(code="R_MONEY_TRANSFER", weight=25)])
    v = pipe(judge, rules=rules).analyze(bundle(SCAM), ctx())
    assert (v.outcome, v.path, v.level, v.scenario) == ("verdict", "llm", "DANGEROUS", "N1.1")
    assert v.should_alert and v.risky_actions == ["transfer_money"]
    assert v.model_ids == ["judge-model"] and v.cost_usd_est == pytest.approx(0.0012)
    assert v.versions == {"contract": m.CONTRACT_VERSION, "pipeline": "real", "prompt": PROMPT_VERSION, "rules": "rules-test"}
    assert {"rules", "judge", "total"} <= v.latency_ms.keys()
    assert v.citations and all(c.title for c in v.citations)   # nguồn lấy từ thẻ N1.1
    # Model, rules và masked_text đều chỉ thấy bản đã mask; tín hiệu luật được gửi kèm cho model.
    prompt = judge.requests[0]["messages"][0]["content"][0]["text"]
    assert "0912" not in prompt and "0912" not in v.masked_text and "0912" not in rules.seen[0][0]
    assert "R_MONEY_TRANSFER" in prompt and judge.requests[0]["system"] == [{"text": "SYS"}]
    m.Verdict.model_validate(v.model_dump(mode="json"))
    assert "RẤT GIỐNG LỪA ĐẢO" in render(v)


def test_fast_path_skips_llm():
    judge = Boto()
    v = pipe(judge, rules=Rules(COMBO)).analyze(bundle(SCAM), ctx())
    assert v.path == "fast" and v.level == "DANGEROUS" and v.scenario == "N1.1"
    assert judge.requests == [] and v.model_ids == [] and "judge" not in v.latency_ms
    assert "RẤT GIỐNG LỪA ĐẢO" in render(v)


def test_backup_answers_when_judge_throttled():
    v = pipe(Boto(throttle(), throttle()), Boto(answer())).analyze(bundle(SCAM), ctx())
    assert v.path == "escalated" and v.model_ids == ["backup-model"] and v.level == "DANGEROUS"


@pytest.mark.parametrize("case", ["throttled", "kill_switch", "deadline"])
def test_rules_only_when_llm_unavailable(case):
    judge, backup = Boto(throttle(), throttle()), Boto(throttle())
    c = {"throttled": ctx(), "kill_switch": ctx(llm_enabled=False), "deadline": ctx(deadline_s=10)}[case]
    v = pipe(judge, backup).analyze(bundle(SCAM), c)
    assert v.path == "rules_only" and v.level == "UNSURE" and v.model_ids == [] and v.cost_usd_est == 0
    assert v.masked_text and "0912" not in v.masked_text
    if case != "throttled":
        assert judge.requests == backup.requests == []
    assert "bảo trì" in render(v)


def test_empty_model_ids_from_repo_config_gives_rules_only():
    """Cấu hình thật của repo chưa có model ID (chờ AI-02): pipeline vẫn trả Verdict, không raise."""
    p = build_pipeline("real", CONFIG_DIR, None, None)
    assert isinstance(p, P.RealPipeline)
    v = p.analyze(bundle("Chúc mừng bác trúng thưởng, bấm vào link để nhận quà"), ctx())
    assert v.path == "rules_only" and v.outcome == "verdict"


def test_factory_loads_kb_rules():
    """``build_pipeline("real")`` nạp ``kb/rules.yaml``: tổ hợp công an + tài khoản an toàn đi fast path, không gọi LLM."""
    v = build_pipeline("real", CONFIG_DIR, None, None).analyze(bundle(SCAM), ctx())
    assert v.path == "fast" and v.level == "DANGEROUS" and v.versions["rules"].startswith("rules-")
    assert {"R_AUTHORITY", "R_SAFE_ACCOUNT"} <= {s.code for s in v.signals}


# ---------------------------------------------------------------- nhánh xã giao / không đọc được

@pytest.mark.parametrize("text", ["Cảm ơn!", "  alo ", "OK.", "cám ơn"])
def test_smalltalk_skips_llm(text):
    judge = Boto()
    v = pipe(judge).analyze(bundle(text), ctx())
    assert (v.outcome, v.level, v.path) == ("smalltalk", "LOW_RISK", "fast") and judge.requests == []
    assert "Bot xem giúp" in render(v)


def test_smalltalk_with_photo_or_in_session_is_analyzed():
    photo = m.ImageInput(data=b"x", file_unique_id="f1", mime="image/jpeg")
    session = m.SessionContext(prev_check_id="01J", masked_text="x", question="q?", answer="yes", turn=2)
    for b in (bundle("ok", images=[photo]), bundle("ok", session=session)):
        v = pipe(Boto(answer(group="N0", scenario="N0", risk_score=5, signals=[], requested_actions=["none"]))).analyze(b, ctx())
        assert v.outcome == "verdict"


@pytest.mark.parametrize("b", [m.Bundle(), bundle("​ "),
                               m.Bundle(images=[m.ImageInput(data=b"x", file_unique_id="f1", mime="image/jpeg")])])
def test_no_text_is_unreadable(b):
    v = pipe().analyze(b, ctx())
    assert (v.outcome, v.level) == ("unreadable", "UNSURE") and "chưa đọc được" in render(v)


# ---------------------------------------------------------------- URL và fingerprint

def test_urls_from_text_and_entities():
    text = "Xem https://dichvucong.gov.vn/a, và bấm vào đây nhé."
    link = m.EntityRef(kind="text_link", offset=text.index("đây"), length=3, url="https://dichvucong-vn.com/x")
    rules = Rules()
    pipe(Boto(answer()), rules=rules).analyze(bundle(text, entities=[link]), ctx())
    assert rules.seen[0][1] == ["https://dichvucong.gov.vn/a", "https://dichvucong-vn.com/x"]


def test_url_entity_offsets_are_utf16():
    text = "👵 xem bit.ly/abc"
    ent = m.EntityRef(kind="url", offset=7, length=10)   # emoji chiếm 2 đơn vị UTF-16
    assert P.entity_urls([m.TextPart(text=text, entities=[ent])]) == ["bit.ly/abc"]


@pytest.mark.parametrize("urls,official", [
    ([], True),
    (["https://dichvucong.gov.vn/x", "https://abc.gov.vn"], True),
    (["https://dichvucong.gov.vn/x", "https://dichvucong.gov.vn.abc.com"], False),
    (["dichvucong.gov.vn"], False),   # không scheme: không coi là chính thức
    (["http://dichvucong.gov.vn"], False),
])
def test_all_official(urls, official):
    assert P.all_official(urls) is official


def test_official_only_urls_let_confident_n0_cap_score():
    n0 = answer(group="N0", scenario="N0", risk_score=10, signals=[], requested_actions=["none"])
    weak = Rules([m.Signal(code="R_URGENCY", weight=40)])
    official = pipe(Boto(n0), rules=weak).analyze(bundle("Hạn chót hôm nay: https://dichvucong.gov.vn/x"), ctx())
    other = pipe(Boto(n0), rules=Rules([m.Signal(code="R_URGENCY", weight=40)])).analyze(
        bundle("Hạn chót hôm nay: https://dichvucong-gov.com/x"), ctx())
    assert official.score == 30 and other.score == 40


def test_fingerprint():
    p = pipe()
    base = p.fingerprint(bundle("Gửi mã OTP"))
    assert base == p.fingerprint(bundle("Gửi​ mã OTP "))   # chuẩn hoá trước khi băm
    assert base != p.fingerprint(bundle("Gửi mã OTP ngay"))
    link = [m.EntityRef(kind="text_link", offset=0, length=3, url="https://a.com")]
    assert p.fingerprint(bundle("Gửi mã OTP", entities=link)) != base
    imgs = [m.ImageInput(data=b"", file_unique_id=f, mime="image/jpeg") for f in ("a", "b")]
    assert p.fingerprint(m.Bundle(images=imgs)) == p.fingerprint(m.Bundle(images=imgs[::-1]))
    assert len(base) == 64


# ---------------------------------------------------------------- qua handler

def test_handler_with_real_pipeline(monkeypatch: pytest.MonkeyPatch):
    """``PIPELINE_IMPL=real`` khởi tạo được từ ``ai/config``; chưa có model ID nên ra rules_only, render vẫn chạy."""
    monkeypatch.setattr(h, "_RUNTIME", None)
    rt = h.init_runtime({"PIPELINE_IMPL": "real", "CONFIG_DIR": str(CONFIG_DIR)})
    assert isinstance(rt.pipeline, P.RealPipeline)
    req = json.loads((FIXTURES / "analyze" / "otp.json").read_text(encoding="utf-8"))["request"]
    resp = h.lambda_handler(req, None, runtime=rt)
    assert resp.get("error") is None and resp["analyze"]["verdict"]["path"] == "rules_only"
    m.AiResponse.model_validate(resp)


def test_guardian_alert_renders():
    v = pipe(rules=Rules(COMBO)).analyze(bundle(SCAM), ctx())
    out = pipe().render_guardian_alert(v, m.GuardianAlertContext(alert_id="01J1", elder_name="Bà Tư", share_level=2,
                                                                   at=datetime(2026, 11, 3, tzinfo=UTC)))
    assert "Bà Tư" in out.text_html
