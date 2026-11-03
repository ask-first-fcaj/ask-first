"""Stage Respond (AI-06, ARCH §6.8, §5.5): ``Verdict`` → tin trả lời người cao tuổi (``ReplyPlan``) và tin cảnh báo người thân.

Câu chữ nằm ở ``kb/templates/`` (Jinja2, autoescape bật), LLM không viết tự do vào tin. Quy tắc an toàn:

* Mọi chuỗi lấy từ input (giải thích của LLM, đoạn trích, câu hỏi thêm, tên Telegram) đi qua ``untrusted``: escape HTML,
  URL và tên miền thành dạng defang ``hxxps://x[.]com`` trong ``<code>``. Biến nào quên filter vẫn bị autoescape.
* Chỉ link nguồn cảnh báo có tên miền thuộc ``kb/allowlist.yaml`` mới là ``<a href>``; nguồn khác chỉ hiện tên đơn vị.
* Tin kết luận là ``messages[0]`` (≤ 600 ký tự nhờ cắt độ dài từng chuỗi input); phần giải thích gộp vào nếu vừa,
  không thì thành tin sau. Mỗi tin ≤ 4.096 ký tự.
* Hàm thuần: chỉ đọc file trong ``kb/`` (cache trong process), không gọi mạng.

Nút là dạng trừu tượng (``Button.kind``), BE dựng callback. Câu chữ và nhãn nút là bản nháp, chốt theo SH-08.
Lambda phải đóng gói thư mục ``kb/`` và đặt ``ASKFIRST_KB_DIR`` nếu không chạy từ repo.
"""

from __future__ import annotations

import html
import os
import re
from datetime import UTC, timedelta, timezone
from functools import cache
from pathlib import Path
from urllib.parse import urlsplit

import yaml
from jinja2 import Environment, FileSystemLoader, StrictUndefined
from markupsafe import Markup

from askfirst.domain import models as m

KB_DIR = Path(os.environ.get("ASKFIRST_KB_DIR") or Path(__file__).resolve().parents[4] / "kb")

CONCLUSION_MAX = 600     # tin kết luận, tính theo ký tự hiển thị (ARCH §6.8)
MESSAGE_MAX = 4096       # giới hạn sendMessage của Telegram; kiểm trên chuỗi HTML (dài hơn chữ hiển thị nên an toàn)
MAX_EXPLANATION = 280    # = judge.MAX_EXPLANATION
MIN_LEAD = 80
MAX_QUOTE = 120
MAX_NAME = 32
MAX_REASONS = 4
MAX_SOURCES = 3
MAX_ADVICE = 3

VN_TZ = timezone(timedelta(hours=7))   # Việt Nam không đổi giờ theo mùa
ADDRESS = {"elder": "Bác", "aunt": "Cô", "uncle": "Chú"}
LEVEL_VN = {"DANGEROUS": "NGUY HIỂM", "SUSPICIOUS": "NGHI NGỜ", "UNSURE": "CHƯA CHẮC", "LOW_RISK": "ÍT RỦI RO"}

# URL có scheme (cả dạng đã defang) hoặc www., và tên miền trần (abc.com, dịchvụ.vn/x). Thà defang thừa ("TP.HCM") còn hơn sót.
URL_RE = re.compile(
    r"(?:\b(?:https?|hxxps?|ftp)://|\bwww\.)[^\s<>\"']+"
    r"|\b(?:[\w-]+\.)+[^\W\d_]{2,}\b(?:/[^\s<>\"']*)?",
    re.IGNORECASE,
)
_TAIL_RE = re.compile(r"[.,;:!?)\]…]+$")


def defang(url: str) -> str:
    """``https://a.com/x`` → ``hxxps://a[.]com/x``. Chạy lại trên chuỗi đã defang vẫn ra đúng một lần ``[.]``."""
    url = re.sub(r"^h(?:tt|xx)p", "hxxp", url, flags=re.IGNORECASE)
    return url.replace("[.]", ".").replace(".", "[.]")


def untrusted(text: str | None, limit: int | None = None) -> Markup:
    """Chuỗi lấy từ input → HTML an toàn: gộp xuống dòng, cắt ``limit`` ký tự, escape, URL defang trong ``<code>``."""
    if not text:
        return Markup("")
    text = " ".join(text.split())
    if limit and len(text) > limit:
        text = text[: limit - 1].rstrip() + "…"
    out: list[str] = []
    pos = 0
    for mt in URL_RE.finditer(text):
        url = mt.group()
        tail = _TAIL_RE.search(url)
        if tail:
            url = url[: tail.start()]
        out.append(html.escape(text[pos:mt.start()]))
        out.append(f"<code>{html.escape(defang(url))}</code>{html.escape(tail.group() if tail else '')}")
        pos = mt.end()
    out.append(html.escape(text[pos:]))
    return Markup("".join(out))


# ---------------------------------------------------------------- KB và template

def _yaml(name: str):
    return yaml.safe_load((KB_DIR / name).read_text(encoding="utf-8"))


@cache
def _env() -> Environment:
    return Environment(loader=FileSystemLoader(KB_DIR / "templates"), autoescape=True, trim_blocks=True,
                       lstrip_blocks=True, undefined=StrictUndefined)


@cache
def _scenarios() -> dict[str, dict]:
    return {c["code"]: c for c in _yaml("scenarios.yaml")}


@cache
def _official() -> tuple[str, ...]:
    allow = _yaml("allowlist.yaml")
    return tuple([d["domain"] for d in allow["domains"]] + list(allow.get("suffixes") or []))


@cache
def signal_texts() -> dict[str, str]:
    """Mã dấu hiệu → câu giải thích (``kb/templates/signals.j2``)."""
    return dict(_env().get_template("signals.j2").module.SIGNALS)


def is_official(url: str) -> bool:
    """URL ``https`` có host thuộc allowlist (đúng tên miền hoặc subdomain của nó, so theo ranh giới nhãn)."""
    parts = urlsplit(url)
    host = (parts.hostname or "").lower()
    return parts.scheme == "https" and any(host == d or host.endswith("." + d) for d in _official())


def visible_len(text_html: str) -> int:
    """Số ký tự người đọc thấy (Telegram cũng đếm giới hạn độ dài sau khi bỏ thẻ và giải entity)."""
    return len(html.unescape(re.sub(r"<[^>]+>", "", text_html)))


def _render(name: str, **kw) -> str:
    text = _env().get_template(name).render(**kw)
    text = "\n".join(line.rstrip() for line in text.strip().splitlines())
    return re.sub(r"\n{3,}", "\n\n", text)


def split_message(text: str, limit: int = MESSAGE_MAX) -> list[str]:
    """Chia theo dòng để không cắt ngang thẻ HTML; dòng đơn dài hơn ``limit`` (không xảy ra nhờ cắt input) mới bị cắt cứng."""
    parts: list[str] = []
    cur = ""
    for line in text.split("\n"):
        while len(line) > limit:
            if cur:
                parts.append(cur)
                cur = ""
            parts.append(line[:limit])
            line = line[limit:]
        cand = f"{cur}\n{line}" if cur else line
        if len(cand) > limit:
            parts.append(cur)
            cand = line
        cur = cand
    if cur.strip():
        parts.append(cur)
    return [p.strip("\n") for p in parts if p.strip()]


# ---------------------------------------------------------------- phần ghép từ Verdict

def _card(verdict: m.Verdict) -> dict | None:
    return None if verdict.group in ("N0", "OTHER") else _scenarios().get(verdict.scenario)


def _reasons(signals: list[m.Signal]) -> list[dict]:
    """Dấu hiệu có câu giải thích, mạnh trước (luật cứng, trọng số cao), mỗi mã một lần."""
    texts = signal_texts()
    out: list[dict] = []
    seen: set[str] = set()
    for s in sorted(signals, key=lambda s: (not s.hard, -s.weight)):
        if s.code in texts and s.code not in seen:
            seen.add(s.code)
            out.append({"quote": untrusted(s.evidence, MAX_QUOTE), "text": texts[s.code]})
    return out[:MAX_REASONS]


def _sources(citations: list[m.Citation]) -> list[dict]:
    out = []
    for c in citations[:MAX_SOURCES]:
        name = untrusted(c.publisher or c.title, 80)
        link = Markup('<a href="{}">{}</a>').format(c.url, name) if is_official(c.url) else name
        d = re.match(r"(\d{4})-(\d{2})", c.date)
        out.append({"link": link, "date": f"{d.group(2)}/{d.group(1)}" if d else ""})
    return out


def _names(alerts: list[m.AlertResult], status: str) -> Markup:
    return Markup(", ").join(untrusted(a.guardian_name, MAX_NAME) for a in alerts if a.status == status)


def _buttons(level: str, ctx: m.RenderContext) -> list[list[m.Button]]:
    row: list[m.Button] = []
    if level == "DANGEROUS":
        row.append(m.Button(kind="checklist", label="📋 Việc cần làm ngay"))
    if ctx.has_guardian and level == "SUSPICIOUS" and not ctx.alerts:
        row.append(m.Button(kind="alert_guardian", label="📣 Báo cho con"))
    elif ctx.has_guardian and level in ("DANGEROUS", "SUSPICIOUS", "UNSURE"):
        row.append(m.Button(kind="call_guardian", label="📞 Gọi cho con"))
    return [r for r in (row, [m.Button(kind="report_wrong", label="👎 Bot báo sai")]) if r]


def render(verdict: m.Verdict, ctx: m.RenderContext) -> m.ReplyPlan:
    xh = ADDRESS[ctx.address_form]
    base = {"XH": xh, "xh": xh.lower()}
    if verdict.outcome != "verdict":
        return m.ReplyPlan(messages=[m.OutMessage(text_html=_render(f"verdict/{verdict.outcome}.j2", **base))])

    level = verdict.level
    card = _card(verdict)
    reasons = _reasons(verdict.signals) if level != "LOW_RISK" else []
    disclaimer = _render("verdict/disclaimer.j2")
    # Báo bảo trì cả khi LLM lỗi (path rules_only), không chỉ khi Java bật kill switch (ARCH §5.8, §6.7; AI-07).
    maintenance = ctx.maintenance or verdict.path == "rules_only"

    def conclusion_with(lead: str) -> str:
        return _render(
            "verdict/conclusion.j2", **base, level=level, lead=lead, actions=verdict.risky_actions,
            scenario_title=card["title"] if card else "",
            otp_genuine=verdict.group == "N0" and any(s.code == "R_OTP_GENUINE" for s in verdict.signals),
            alerted=_names(ctx.alerts, "sent"), failed=_names(ctx.alerts, "failed"), maintenance=maintenance)

    if verdict.explanation:
        # Cắt giải thích của LLM tới khi tin kết luận (kể cả disclaimer) không quá CONCLUSION_MAX ký tự hiển thị.
        # Đo sau khi render vì defang làm chuỗi dài thêm; giới hạn giảm dần nên vòng lặp luôn dừng (sàn MIN_LEAD).
        limit = MAX_EXPLANATION
        while True:
            conclusion = conclusion_with(untrusted(verdict.explanation, limit))
            over = visible_len(conclusion) + 2 + visible_len(disclaimer) - CONCLUSION_MAX
            if over <= 0 or limit <= MIN_LEAD:
                break
            limit = max(MIN_LEAD, limit - over)
    elif reasons:
        # Fast path hoặc verdict lấy từ cache: dòng 2 là câu của dấu hiệu mạnh nhất (không có trích dẫn thì khỏi lặp lại bên dưới).
        conclusion = conclusion_with(reasons[0]["text"])
        if not reasons[0]["quote"]:
            reasons = reasons[1:]
    else:
        conclusion = conclusion_with("")

    details = _render(
        "verdict/details.j2", **base, reasons=reasons,
        note=untrusted(verdict.explanation, MAX_EXPLANATION) if level == "UNSURE" else "",
        advice=(card or {}).get("advice", [])[:MAX_ADVICE] if level in ("DANGEROUS", "SUSPICIOUS") else [],
        sources=_sources(verdict.citations) if level != "LOW_RISK" else [])

    merged = "\n\n".join(p for p in (conclusion, details, disclaimer) if p)
    if visible_len(merged) <= CONCLUSION_MAX:
        first, rest = merged, []
    else:
        first, rest = f"{conclusion}\n\n{disclaimer}", split_message(details)
    messages = [m.OutMessage(text_html=first, buttons=_buttons(level, ctx))]
    messages += [m.OutMessage(text_html=t) for t in rest]

    if verdict.followup_question and level == "UNSURE":
        messages.append(m.OutMessage(
            text_html=_render("verdict/followup.j2", question=untrusted(verdict.followup_question, MAX_EXPLANATION)),
            buttons=[[m.Button(kind="answer_yes", label="Có"), m.Button(kind="answer_no", label="Không"),
                      m.Button(kind="answer_unknown", label="Không nhớ")]]))
    return m.ReplyPlan(messages=messages)


def render_guardian_alert(verdict: m.Verdict, ctx: m.GuardianAlertContext) -> m.OutMessage:
    card = _card(verdict)
    texts = signal_texts()
    signals = list(dict.fromkeys(texts[s.code] for s in verdict.signals if s.code in texts))[:MAX_REASONS]
    summary = untrusted(verdict.explanation, MAX_EXPLANATION) or Markup("; ").join(
        [untrusted(s.evidence, MAX_QUOTE) for s in verdict.signals if s.evidence][:2])
    at = ctx.at if ctx.at.tzinfo else ctx.at.replace(tzinfo=UTC)
    text = _render(
        "guardian/alert.j2", elder=untrusted(ctx.elder_name, MAX_NAME), level_vn=LEVEL_VN[verdict.level],
        at=at.astimezone(VN_TZ).strftime("%H:%M ngày %d/%m"), share_level=ctx.share_level,
        scenario_title=card["title"] if card else "", signals=signals, summary=summary)
    return m.OutMessage(text_html=text, buttons=[[m.Button(kind="ack", label="✅ Tôi đã liên hệ")]])
