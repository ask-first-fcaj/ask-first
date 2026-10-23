"""StubPipeline (plan §3.5): fixed keyword-based behaviour, no media download, no network calls. ONLY for the `dev` bot."""
import html

from askfirst.domain import models as m

SMALLTALK = {"cảm ơn", "alo", "ok"}
FOLLOWUP_Q = "Người gọi có bảo bác giữ bí mật không ạ?"


def bundle_from_items(items: list[m.InboundMessage], session: m.SessionContext | None = None) -> m.Bundle:
    """The stub does not download media: photo/voice are placeholders (empty data), only counted."""
    texts = [m.TextPart(text=i.text, entities=i.entities) for i in items if i.text]
    images = [m.ImageInput(data=b"", file_unique_id=i.media.file_unique_id, mime=i.media.mime or "image/jpeg")
              for i in items if i.kind == "photo" and i.media]
    audios = [m.AudioInput(data=b"", file_unique_id=i.media.file_unique_id, mime=i.media.mime or "audio/ogg", duration_s=i.media.duration or 0)
              for i in items if i.kind in ("voice", "audio") and i.media]
    origin = next((i.forward_origin for i in items if i.forward_origin), None)
    return m.Bundle(texts=texts, images=images, audios=audios, forward_origin=origin, session=session)


class StubPipeline:
    def fingerprint(self, bundle: m.Bundle) -> str:
        return "stub"

    def analyze(self, bundle: m.Bundle, ctx: m.AnalyzeContext) -> m.Verdict:
        text = "\n".join(t.text for t in bundle.texts)
        low = text.lower()
        has_media = bool(bundle.images or bundle.audios)
        followup: str | None = None
        if not text and not has_media:
            fields: dict = {"outcome": "unreadable", "level": "UNSURE", "score": 30, "group": "OTHER", "scenario": "OTHER"}
        elif not has_media and low.strip() in SMALLTALK:
            fields = {"outcome": "smalltalk", "level": "LOW_RISK", "score": 0, "group": "N0", "scenario": "N0"}
        elif "otp" in low:
            fields = {"outcome": "verdict", "level": "DANGEROUS", "score": 90, "group": "N1", "scenario": "N1.1",
                      "risky_actions": ["share_otp"], "should_alert": True}
        elif "http" in low or "link" in low:
            fields = {"outcome": "verdict", "level": "SUSPICIOUS", "score": 60, "group": "N1", "scenario": "N1.4",
                      "risky_actions": ["click_link"]}
        elif "hỏi thêm" in low:
            fields = {"outcome": "verdict", "level": "UNSURE", "score": 30, "group": "OTHER", "scenario": "OTHER"}
            if not (bundle.session and bundle.session.turn == 2):
                followup = FOLLOWUP_Q
        elif has_media:
            fields = {"outcome": "verdict", "level": "UNSURE", "score": 30, "group": "OTHER", "scenario": "OTHER"}
        else:
            fields = {"outcome": "verdict", "level": "LOW_RISK", "score": 10, "group": "N0", "scenario": "N0"}
        return m.Verdict(
            **{"should_alert": False} | fields, followup_question=followup, masked_text=text, path="fast",
            versions={"contract": m.CONTRACT_VERSION, "pipeline": "stub"})

    def render(self, verdict: m.Verdict, ctx: m.RenderContext) -> m.ReplyPlan:
        return m.ReplyPlan(messages=[m.OutMessage(
            text_html=f"[STUB] {verdict.level} {verdict.scenario}",
            buttons=[[m.Button(kind="report_wrong", label="Bot báo sai")]])])

    def render_guardian_alert(self, verdict: m.Verdict, ctx: m.GuardianAlertContext) -> m.OutMessage:
        return m.OutMessage(
            text_html=f"[STUB] cảnh báo {html.escape(ctx.elder_name)}",
            buttons=[[m.Button(kind="ack", label="Tôi đã liên hệ")]])
