"""StubPipeline (plan §3.5): hành vi cố định theo từ khoá, không tải media, không gọi mạng. CHỈ dùng ở bot `dev`."""
import html

from askfirst.domain import models as m

SMALLTALK = {"cảm ơn", "alo", "ok"}
FOLLOWUP_Q = "Người gọi có bảo bác giữ bí mật không ạ?"


def bundle_from_items(items: list[m.InboundMessage], session: m.SessionContext | None = None) -> m.Bundle:
    """Stub không tải media: ảnh/voice chỉ là chỗ giữ chỗ (data rỗng) để đếm."""
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
            fields: dict = {"outcome": "unreadable", "level": "CHUA_CHAC", "score": 30, "nhom": "KHAC", "kich_ban": "KHAC"}
        elif not has_media and low.strip() in SMALLTALK:
            fields = {"outcome": "smalltalk", "level": "IT_DAU_HIEU", "score": 0, "nhom": "N0", "kich_ban": "N0"}
        elif "otp" in low:
            fields = {"outcome": "verdict", "level": "NGUY_HIEM", "score": 90, "nhom": "N1", "kich_ban": "N1.1",
                      "risky_actions": ["cung_cap_otp"], "should_alert": True}
        elif "http" in low or "link" in low:
            fields = {"outcome": "verdict", "level": "NGHI_NGO", "score": 60, "nhom": "N1", "kich_ban": "N1.4",
                      "risky_actions": ["bam_link"]}
        elif "hỏi thêm" in low:
            fields = {"outcome": "verdict", "level": "CHUA_CHAC", "score": 30, "nhom": "KHAC", "kich_ban": "KHAC"}
            if not (bundle.session and bundle.session.turn == 2):
                followup = FOLLOWUP_Q
        elif has_media:
            fields = {"outcome": "verdict", "level": "CHUA_CHAC", "score": 30, "nhom": "KHAC", "kich_ban": "KHAC"}
        else:
            fields = {"outcome": "verdict", "level": "IT_DAU_HIEU", "score": 10, "nhom": "N0", "kich_ban": "N0"}
        return m.Verdict(
            **{"should_alert": False} | fields, followup_question=followup, masked_text=text, path="fast",
            versions={"contract": m.CONTRACT_VERSION, "pipeline": "stub"})

    def render(self, verdict: m.Verdict, ctx: m.RenderContext) -> m.ReplyPlan:
        return m.ReplyPlan(messages=[m.OutMessage(
            text_html=f"[STUB] {verdict.level} {verdict.kich_ban}",
            buttons=[[m.Button(kind="report_wrong", label="Bot báo sai")]])])

    def render_guardian_alert(self, verdict: m.Verdict, ctx: m.GuardianAlertContext) -> m.OutMessage:
        return m.OutMessage(
            text_html=f"[STUB] cảnh báo {html.escape(ctx.elder_name)}",
            buttons=[[m.Button(kind="ack", label="Tôi đã liên hệ")]])
