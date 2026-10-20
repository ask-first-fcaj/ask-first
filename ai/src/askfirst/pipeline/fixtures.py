"""Generates contracts/fixtures/<op>/<case>.json (request + response) from StubPipeline. Run inside ai/: `uv run python -m askfirst.pipeline.fixtures [out_dir]`."""
import json
import pathlib
import sys
from datetime import UTC, datetime

from askfirst.domain import models as m
from askfirst.pipeline.stub import StubPipeline, bundle_from_items

NOW = datetime(2026, 10, 14, 7, 5, tzinfo=UTC)
ULID = "01J" + "0" * 23
DEFAULT_OUT = pathlib.Path(__file__).resolve().parents[4] / "contracts" / "fixtures"


def _msg(kind: str = "text", text: str | None = None, **kw) -> m.InboundMessage:
    media = None
    if kind == "photo":
        media = m.MediaRef(file_id="AgAC", file_unique_id="AQAD", mime="image/jpeg", size=120_000)
    elif kind == "voice":
        media = m.MediaRef(file_id="AwAC", file_unique_id="AgAD", mime="audio/ogg", size=30_000, duration=12)
    return m.InboundMessage(channel="telegram", update_id="1", chat_id="42", user_id="tg:42", message_id="7", kind=kind,
                            text=text, media=media, received_at=NOW, **kw)


def _ctx() -> m.AnalyzeContext:
    return m.AnalyzeContext(check_id=ULID, user_id="tg:42", flags=m.Flags(), deadline_s=85)


def _session(turn: int) -> m.SessionContext:
    return m.SessionContext(prev_check_id=ULID, masked_text="hỏi thêm", question="Người gọi có bảo bác giữ bí mật không ạ?",
                            answer="yes", turn=turn)


def cases() -> dict[str, dict[str, tuple[m.BaseModel, m.BaseModel]]]:
    """{op: {case: (request, response)}}"""
    stub = StubPipeline()
    analyze_in: dict[str, tuple[list[m.InboundMessage], m.SessionContext | None]] = {
        "unreadable": ([], None),
        "smalltalk": ([_msg(text="Cảm ơn")], None),
        "otp": ([_msg(text="Gửi mã OTP cho tôi")], None),
        "link": ([_msg(text="Bấm link này nhé")], None),
        "followup": ([_msg(text="cần hỏi thêm")], None),
        "followup_turn2": ([], _session(2)),
        "photo": ([_msg("photo")], None),
        "voice": ([_msg("voice")], None),
        "default": ([_msg(text="Chào bạn")], None),
    }
    analyze = {}
    for name, (items, session) in analyze_in.items():
        req = m.AnalyzeRequest(items=items, session=session, ctx=_ctx())
        verdict = stub.analyze(bundle_from_items(items, session), req.ctx)
        analyze[name] = (req, m.AiResponse(op="analyze", analyze=m.AnalyzeResult(verdict=verdict, cache_hit=False)))
    verdicts = {name: res.analyze.verdict for name, (_, res) in analyze.items()}

    render = {}
    for name, address_form, guardian in (("otp", "elder", True), ("default", "aunt", False)):
        req = m.RenderRequest(verdict=verdicts[name], ctx=m.RenderContext(check_id=ULID, address_form=address_form, has_guardian=guardian))
        render[name] = (req, m.AiResponse(op="render", render=stub.render(req.verdict, req.ctx)))

    gctx = [m.GuardianAlertContext(alert_id=ULID, elder_name="Lan", share_level=2, at=NOW)]
    greq = m.RenderGuardianAlertsRequest(verdict=verdicts["otp"], guardians=gctx)
    alerts = [stub.render_guardian_alert(greq.verdict, g) for g in gctx]
    guardian = {"otp": (greq, m.AiResponse(op="render_guardian_alerts",
                                           render_guardian_alerts=m.RenderGuardianAlertsResult(alerts=alerts)))}
    return {"analyze": analyze, "render": render, "render_guardian_alerts": guardian}


def render_files() -> dict[str, str]:
    """{<op>/<case>.json: content}"""
    files = {}
    for op, by_case in cases().items():
        for name, (req, res) in by_case.items():
            doc = {"request": req.model_dump(mode="json"), "response": res.model_dump(mode="json")}
            files[f"{op}/{name}.json"] = json.dumps(doc, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    return files


def export_fixtures(out_dir: pathlib.Path) -> list[pathlib.Path]:
    written = []
    for rel, text in render_files().items():
        path = out_dir / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
        written.append(path)
    return written


if __name__ == "__main__":
    for path in export_fixtures(pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT):
        print(path)
