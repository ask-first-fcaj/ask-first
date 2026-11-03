"""Test ``pipeline/respond.py`` (AI-06, ARCH §6.8, §5.5): snapshot 4 mức, nút, cảnh báo người thân, độ dài tin,
và test bảo mật duyệt mọi template: fail nếu URL nghi ngờ chưa defang hoặc chuỗi input chưa escape.

Snapshot ở ``tests/unit/snapshots/respond/``. Đổi câu chữ (SH-08) thì chạy lại với ``UPDATE_SNAPSHOTS=1`` rồi xem diff.
"""

from __future__ import annotations

import itertools
import os
import re
from datetime import UTC, datetime
from pathlib import Path

import pytest

from askfirst.domain import models as m
from askfirst.pipeline import respond as R

SNAP_DIR = Path(__file__).parent / "snapshots" / "respond"
AT = datetime(2026, 11, 3, 7, 5, tzinfo=UTC)   # 14:05 giờ Việt Nam


def _v(**over) -> m.Verdict:
    base = {"outcome": "verdict", "level": "LOW_RISK", "score": 10, "group": "OTHER", "scenario": "OTHER",
            "should_alert": False, "path": "llm"}
    return m.Verdict.model_validate(base | over)


def _ctx(**over) -> m.RenderContext:
    return m.RenderContext.model_validate({"check_id": "c1", "address_form": "elder", "has_guardian": True} | over)


def _gctx(level: int, **over) -> m.GuardianAlertContext:
    return m.GuardianAlertContext.model_validate(
        {"alert_id": "01J0", "elder_name": "Lan", "share_level": level, "at": AT} | over)


def _sig(code: str, weight: int = 0, hard: bool = False, evidence: str | None = None) -> dict:
    return {"code": code, "weight": weight, "hard": hard, "evidence": evidence}


CITE = {"title": "Cảnh giác bắt cóc online", "url": "https://bocongan.gov.vn/bai-viet/canh-giac-1791168262",
        "publisher": "Bộ Công an", "date": "2026-10-05"}

FAST = _v(level="DANGEROUS", score=85, group="N1", scenario="N1.1", path="fast", should_alert=True,
          risky_actions=["transfer_money", "keep_secret"], citations=[CITE],
          signals=[_sig("R_AUTHORITY", 20, evidence="Tôi là cán bộ công an quận"),
                   _sig("R_SECRECY", 35, evidence="không được nói với ai"), _sig("R_MONEY_TRANSFER", 25)])
FINE = _v(level="SUSPICIOUS", score=60, group="N1", scenario="N1.4",
          risky_actions=["transfer_money", "click_link"], signals=[_sig("R_TRAFFIC_FINE", 20, evidence="phạt nguội")],
          explanation="Tin báo phạt nguội và đòi nộp tiền qua trang https://dichvucong-vn.com/tra-cuu, không phải trang của Nhà nước.")
UNSURE = _v(level="UNSURE", score=30, path="rules_only", signals=[_sig("R_URGENCY", 15, evidence="trong hôm nay")],
            followup_question="Người gọi có bảo bác chuyển tiền hoặc giữ bí mật không ạ?")
OTP_N0 = _v(level="LOW_RISK", score=0, group="N0", scenario="N0", signals=[_sig("R_OTP_GENUINE", -20)],
            explanation="Tin OTP của ngân hàng, có dặn không chia sẻ mã.")
LOW = _v(explanation="Tin hỏi thăm bình thường.")


# ---------------------------------------------------------------- snapshot

def _dump_plan(plan: m.ReplyPlan) -> str:
    out = []
    for i, msg in enumerate(plan.messages):
        buttons = " | ".join(" ".join(f"[{b.kind}: {b.label}]" for b in row) for row in msg.buttons)
        out.append(f"--- message {i} (silent={msg.silent}) {buttons}\n{msg.text_html}")
    return "\n".join(out) + "\n"


def _check_snapshot(name: str, text: str) -> None:
    f = SNAP_DIR / f"{name}.txt"
    if os.environ.get("UPDATE_SNAPSHOTS") or not f.exists():
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")
        if not os.environ.get("UPDATE_SNAPSHOTS"):
            pytest.fail(f"Snapshot mới {f.name}, chạy lại test để so")
    assert text == f.read_text(encoding="utf-8")


@pytest.mark.parametrize(
    ("name", "verdict", "ctx"),
    [
        ("dangerous_fast", FAST, _ctx(alerts=[{"guardian_name": "Nam", "status": "sent"}])),
        ("suspicious_llm", FINE, _ctx()),
        ("unsure_rules_only", UNSURE, _ctx(address_form="aunt", maintenance=True)),
        ("low_risk_otp_genuine", OTP_N0, _ctx(has_guardian=False)),
        ("low_risk", LOW, _ctx(address_form="uncle")),
        ("smalltalk", _v(outcome="smalltalk", level="LOW_RISK", score=0, group="N0", scenario="N0"), _ctx()),
        ("unreadable", _v(outcome="unreadable", level="UNSURE", score=30), _ctx()),
    ],
)
def test_render_snapshot(name: str, verdict: m.Verdict, ctx: m.RenderContext) -> None:
    _check_snapshot(name, _dump_plan(R.render(verdict, ctx)))


@pytest.mark.parametrize("level", [1, 2, 3])
def test_guardian_alert_snapshot(level: int) -> None:
    msg = R.render_guardian_alert(FINE, _gctx(level))
    _check_snapshot(f"guardian_level_{level}", _dump_plan(m.ReplyPlan(messages=[msg])))


# ---------------------------------------------------------------- nội dung theo mức

def test_level_words_not_only_emoji() -> None:
    assert "RẤT GIỐNG LỪA ĐẢO" in R.render(FAST, _ctx()).messages[0].text_html
    assert "NGHI NGỜ" in R.render(FINE, _ctx()).messages[0].text_html


def test_fast_path_lead_comes_from_strongest_signal_template() -> None:
    first = R.render(FAST, _ctx()).messages[0].text_html
    assert R.signal_texts()["R_SECRECY"] in first.split("\n")[1]


def test_cached_verdict_without_evidence_does_not_repeat_lead() -> None:
    cached = FAST.for_cache()
    text = "\n".join(msg.text_html for msg in R.render(cached, _ctx()).messages)
    assert text.count(R.signal_texts()["R_SECRECY"]) == 1
    assert R.signal_texts()["R_AUTHORITY"] in text


def test_otp_genuine_text() -> None:
    assert "chỉ để bác tự nhập" in R.render(OTP_N0, _ctx()).messages[0].text_html


def test_short_reply_is_one_message_with_disclaimer_last() -> None:
    plan = R.render(LOW, _ctx())
    assert len(plan.messages) == 1
    assert plan.messages[0].text_html.endswith("Bot không bao giờ xin OTP, mật khẩu hay tiền.")


def test_long_reply_keeps_conclusion_first_and_details_after() -> None:
    plan = R.render(FAST, _ctx())
    assert len(plan.messages) == 2
    assert plan.messages[0].text_html.startswith("🔴")
    assert plan.messages[1].text_html.startswith("<b>Vì sao Bot nghi ngờ:</b>")


def test_guardian_status_line_is_honest() -> None:
    ctx = _ctx(alerts=[{"guardian_name": "Nam", "status": "sent"}, {"guardian_name": "Hoa", "status": "failed"}])
    first = R.render(FAST, ctx).messages[0].text_html
    assert "Cháu Nam đã được báo" in first
    assert "Bot chưa báo được cho Hoa" in first and "Hoa đã được báo" not in first


def test_followup_only_when_unsure() -> None:
    plan = R.render(UNSURE, _ctx())
    assert [b.kind for b in plan.messages[-1].buttons[0]] == ["answer_yes", "answer_no", "answer_unknown"]
    assert len(R.render(FINE.model_copy(update={"followup_question": "Có ai gọi không?"}), _ctx()).messages) == 2


def test_only_official_citations_are_links() -> None:
    v = FAST.model_copy(update={"citations": [
        m.Citation(title="t", url="https://dichvucong.gov.vn.abc.com/x", publisher="Trang lạ", date="2026-04-01"),
        m.Citation(**CITE)]})
    text = "\n".join(msg.text_html for msg in R.render(v, _ctx()).messages)
    assert "• Trang lạ (04/2026)" in text
    assert f'<a href="{CITE["url"]}">Bộ Công an</a> (10/2026)' in text


# ---------------------------------------------------------------- nút

def _kinds(plan: m.ReplyPlan) -> list[str]:
    return [b.kind for row in plan.messages[0].buttons for b in row]


@pytest.mark.parametrize(
    ("verdict", "ctx", "kinds"),
    [
        (FAST, _ctx(), ["checklist", "call_guardian", "report_wrong"]),
        (FAST, _ctx(has_guardian=False), ["checklist", "report_wrong"]),
        (FINE, _ctx(), ["alert_guardian", "report_wrong"]),
        (FINE, _ctx(alerts=[{"guardian_name": "Nam", "status": "sent"}]), ["call_guardian", "report_wrong"]),
        (UNSURE, _ctx(), ["call_guardian", "report_wrong"]),
        (LOW, _ctx(), ["report_wrong"]),
    ],
)
def test_buttons(verdict: m.Verdict, ctx: m.RenderContext, kinds: list[str]) -> None:
    assert _kinds(R.render(verdict, ctx)) == kinds


def test_smalltalk_has_no_buttons() -> None:
    plan = R.render(_v(outcome="smalltalk"), _ctx())
    assert len(plan.messages) == 1 and plan.messages[0].buttons == []


# ---------------------------------------------------------------- cảnh báo người thân

def test_guardian_share_levels_add_detail_step_by_step() -> None:
    l1, l2, l3 = (R.render_guardian_alert(FINE, _gctx(n)).text_html for n in (1, 2, 3))
    assert "lúc 14:05 ngày 03/11" in l1 and "Kịch bản" not in l1 and "Tóm tắt" not in l1
    assert "Kịch bản: Giả dịch vụ công" in l2 and R.signal_texts()["R_TRAFFIC_FINE"] in l2 and "Tóm tắt" not in l2
    assert "Tóm tắt (đã che số)" in l3 and "<code>hxxps://dichvucong-vn[.]com/tra-cuu</code>" in l3


def test_guardian_alert_has_ack_button_and_naive_time_is_utc() -> None:
    msg = R.render_guardian_alert(FAST, _gctx(1, at=datetime(2026, 11, 3, 7, 5)))
    assert msg.buttons == [[m.Button(kind="ack", label="✅ Tôi đã liên hệ")]]
    assert "14:05" in msg.text_html


def test_guardian_level_3_falls_back_to_masked_evidence() -> None:
    text = R.render_guardian_alert(FAST, _gctx(3)).text_html
    assert "Tóm tắt (đã che số): Tôi là cán bộ công an quận; không được nói với ai" in text


# ---------------------------------------------------------------- defang, escape, allowlist

@pytest.mark.parametrize(
    ("url", "out"),
    [
        ("https://dichvucong-vn.com/pay", "hxxps://dichvucong-vn[.]com/pay"),
        ("HTTP://a.b.vn", "hxxp://a[.]b[.]vn"),
        ("hxxps://a[.]com", "hxxps://a[.]com"),
        ("evil.com", "evil[.]com"),
    ],
)
def test_defang(url: str, out: str) -> None:
    assert R.defang(url) == out


def test_untrusted_escapes_defangs_and_trims() -> None:
    s = R.untrusted('Vào https://x.com/a?b=1&c=2. Rồi <b>bấm</b>\n\n"evil-bank.com" & www.abc.vn!')
    assert s == ("Vào <code>hxxps://x[.]com/a?b=1&amp;c=2</code>. Rồi &lt;b&gt;bấm&lt;/b&gt; "
                 "&quot;<code>evil-bank[.]com</code>&quot; &amp; <code>www[.]abc[.]vn</code>!")
    assert R.untrusted("a" * 500, 10) == "a" * 9 + "…"
    assert R.untrusted(None) == ""


@pytest.mark.parametrize(
    ("url", "ok"),
    [
        ("https://bocongan.gov.vn/x", True),
        ("https://csgt.bocongan.gov.vn/x", True),
        ("https://abc.gov.vn/x", True),
        ("https://dichvucong.gov.vn.abc.com/x", False),
        ("https://dichvuconggov.vn/x", False),
        ("http://bocongan.gov.vn/x", False),
        ("javascript:alert(1)", False),
    ],
)
def test_is_official(url: str, ok: bool) -> None:
    assert R.is_official(url) is ok


POISON = ('<i>PWN</i> & bấm https://dichvucong-vn.com/pay?a=1&b=2 hoặc evil-bank.com/login '
          '<a href="https://evil.example/x">đây</a> "trích" \'nháy\'\n\nxuống dòng')
_TAG = re.compile(r"<(/?)([a-zA-Z]+)([^>]*)>")
_HREF = re.compile(r'<a href="([^"]*)">')
_RAW_AMP = re.compile(r"&(?!amp;|lt;|gt;|quot;|#\d+;|#x[0-9a-fA-F]+;)")   # Telegram nhận 4 entity tên này và entity số
_SCHEME = re.compile(r"(?i)\b(?:https?|ftp)://|\bwww\.")
_BARE = re.compile(r"(?:[\w-]+\.)+[^\W\d_]{2,}\b")


def assert_safe(text: str) -> None:
    """Chỉ thẻ b/code/a; href chỉ tới allowlist; không còn URL hay tên miền nghi ngờ chưa defang; không còn ``&``, ``<`` thô."""
    assert len(text) <= R.MESSAGE_MAX
    for close, tag, attrs in _TAG.findall(text):
        assert tag in ("b", "code", "a"), f"thẻ lạ <{close}{tag}{attrs}> trong:\n{text}"
    for href in _HREF.findall(text):
        assert R.is_official(href), f"link bấm được không thuộc allowlist: {href}"
    visible = _HREF.sub("", text)
    assert not _RAW_AMP.search(visible), f"& chưa escape trong:\n{text}"
    assert not _SCHEME.search(visible), f"URL chưa defang trong:\n{text}"
    for host in _BARE.findall(visible):
        assert R.is_official(f"https://{host}/"), f"tên miền {host} chưa defang trong:\n{text}"


def _poisoned_verdicts():
    sigs = [_sig(code, 20, evidence=POISON) for code in R.signal_texts()] + [_sig("R_OTP_GENUINE", -20, evidence=POISON)]
    cites = [{"title": POISON, "url": "https://evil.example/x", "publisher": POISON, "date": "2026-04"},
             {"title": POISON, "url": CITE["url"], "publisher": POISON, "date": "bad"}, CITE, CITE]
    for level, group, scenario, outcome in itertools.product(
            ["DANGEROUS", "SUSPICIOUS", "UNSURE", "LOW_RISK"], ["N0", "N1", "OTHER"], ["N0", "N1.4", "OTHER"],
            ["verdict", "smalltalk", "unreadable"]):
        for explanation in (POISON * 20, ""):
            yield _v(outcome=outcome, level=level, group=group, scenario=scenario, signals=sigs,
                     risky_actions=list(m.RiskyAction.__args__), explanation=explanation, citations=cites,
                     followup_question=POISON * 20)


def test_every_template_escapes_and_defangs_input(monkeypatch: pytest.MonkeyPatch) -> None:
    """Test bắt buộc ARCH §6.8: duyệt mọi template (kết quả và cảnh báo người thân) với input độc."""
    env = R._env()
    used: set[str] = set()
    orig = env.get_template

    def spy(name, *a, **kw):
        used.add(name)
        return orig(name, *a, **kw)

    monkeypatch.setattr(env, "get_template", spy)
    R.signal_texts.cache_clear()
    alerts = [{"guardian_name": POISON, "status": "sent"}, {"guardian_name": POISON, "status": "failed"}]
    for v in _poisoned_verdicts():
        for form, has_g, maint, al in itertools.product(["elder", "aunt", "uncle"], [True, False], [True, False], [[], alerts]):
            plan = R.render(v, _ctx(address_form=form, has_guardian=has_g, maintenance=maint, alerts=al))
            assert R.visible_len(plan.messages[0].text_html) <= R.CONCLUSION_MAX
            for msg in plan.messages:
                assert_safe(msg.text_html)
        for n in (1, 2, 3):
            assert_safe(R.render_guardian_alert(v, _gctx(n, elder_name=POISON)).text_html)
    assert used >= set(env.list_templates()), f"template chưa được duyệt: {set(env.list_templates()) - used}"


def test_assert_safe_catches_unsafe_output() -> None:
    """Chính bộ kiểm cũng phải bắt được lỗi, không thì test trên pass vô nghĩa."""
    for bad in ("bấm https://evil.com", "vào evil-bank.com nhé", "<i>x</i>", "a & b", '<a href="https://evil.com">x</a>'):
        with pytest.raises(AssertionError):
            assert_safe(bad)
    assert_safe('<b>ok</b> <code>hxxps://evil[.]com</code> csgt.vn <a href="https://bocongan.gov.vn/x">BCA</a> &amp;')


# ---------------------------------------------------------------- chia tin

def test_split_message_by_lines_under_limit() -> None:
    text = "\n".join(f"• dòng {i} " + "x" * 50 for i in range(200))
    parts = R.split_message(text, 1000)
    assert all(len(p) <= 1000 for p in parts)
    assert "\n".join(parts) == text


def test_split_message_hard_cuts_overlong_line() -> None:
    parts = R.split_message("a\n" + "b" * 25, 10)
    assert parts == ["a", "b" * 10, "b" * 10, "b" * 5]
