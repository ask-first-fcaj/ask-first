"""Stage Judge (AI-04, ARCH §6.6): dựng prompt, gọi LLM, parse JSON và hậu kiểm (post-validation).

Chỉ import ``askfirst.domain`` và thư viện chuẩn/bên thứ ba (plan §3.5): client LLM được truyền vào qua
``LLM`` (``askfirst.llm.bedrock.BedrockClient`` khớp giao diện này).

Thứ tự khi gọi (ARCH §6.2 bước 8): model ``judge``, JSON hỏng thì hỏi lại **một lần** kèm lỗi; vẫn hỏng hoặc
client lỗi thì chuyển sang model ``backup``; cả hai không được thì ``JudgeResult.judgement = None`` để fuse
chạy **chế độ chỉ chạy luật**.

Hậu kiểm (ARCH §6.6):

* ``quote`` của mỗi tín hiệu phải là chuỗi con của tin đã gửi cho model (so trên bản NFKC, chữ thường, gộp
  khoảng trắng); không phải thì bỏ và đếm vào ``rejected_quotes`` (chỉ số "trích dẫn bị loại" của eval).
* ``scenario`` phải có trong taxonomy, không thì gán ``OTHER``; ``group`` lấy theo tiền tố của ``scenario``.
* ``short_explanation``: bỏ câu có URL, số điện thoại, hotline hoặc từ cấm; lẫn tiếng Anh/chữ Hán hoặc không
  dấu thì thay cả đoạn bằng giải thích ghép từ template. Cắt còn 280 ký tự.
* ``followup_question``: lượt 2 không được hỏi thêm (ARCH §6.12); câu có URL/số/từ cấm thì bỏ.

Không log nội dung prompt hay câu trả lời (plan §3.1). Lỗi parse gửi lại cho model chỉ có tên trường và loại lỗi.
"""

from __future__ import annotations

import json
import re
import unicodedata
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal, Protocol

from pydantic import BaseModel, ConfigDict, ValidationError

from askfirst.domain.models import ForwardOrigin, Group, RiskyAction

PROMPT_VERSION = "judge_v1"
DEFAULT_PROMPT = Path(__file__).resolve().parents[4] / "prompts" / f"{PROMPT_VERSION}.md"

# Mã kịch bản research-concept §2.2 (khớp eval/validate.py SCENARIOS). Khi kb/scenarios.yaml (AI-25) vào main
# thì truyền taxonomy đọc từ KB vào post_check thay cho hằng này.
TAXONOMY = frozenset({
    "N0", "N1.1", "N1.2", "N1.3", "N1.4", "N1.5", "N1.6", "N2.1", "N2.2", "N2.3",
    "N3.1", "N3.2", "N3.3", "N4.1", "N4.2", "N4.3", "N5.1", "N5.2", "N5.3", "N5.4", "N6", "OTHER",
})
MAX_SIGNALS = 5
MAX_EXPLANATION = 280
MAX_FOLLOWUP = 200

# Schema cho structured outputs của Bedrock (Converse outputConfig.textFormat). ⚠️ Chưa kiểm Qwen3 32B có hỗ trợ
# không (G1); trong lúc chờ, judge() parse + validate bằng Pydantic và hỏi lại một lần.
JUDGE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["group", "scenario", "risk_score", "confidence", "signals", "requested_actions", "short_explanation", "followup_question"],
    "properties": {
        "group": {"enum": ["N0", "N1", "N2", "N3", "N4", "N5", "N6", "OTHER"]},
        "scenario": {"type": "string"},
        "risk_score": {"type": "integer", "minimum": 0, "maximum": 100},
        "confidence": {"enum": ["high", "medium", "low"]},
        "signals": {"type": "array", "maxItems": MAX_SIGNALS, "items": {
            "type": "object", "additionalProperties": False, "required": ["code", "quote"],
            "properties": {"code": {"type": "string"}, "quote": {"type": "string"}}}},
        "requested_actions": {"type": "array", "items": {"enum": [
            "transfer_money", "share_otp", "share_password", "install_app", "share_screen", "click_link", "keep_secret", "share_id_card", "none"]}},
        "short_explanation": {"type": "string"},
        "followup_question": {"type": ["string", "null"]},
    },
}


class JudgeSignal(BaseModel):
    model_config = ConfigDict(extra="ignore")
    code: str
    quote: str


class Judgement(BaseModel):
    """Output của LLM sau khi parse. Không phải hợp đồng BE <-> AI; fuse (AI-05) đổi nó thành ``Verdict``."""
    model_config = ConfigDict(extra="ignore")
    group: Group
    scenario: str
    risk_score: int                  # kẹp 0–100 ở post_check, không làm hỏng cả câu trả lời
    confidence: Literal["high", "medium", "low"]
    signals: list[JudgeSignal]
    requested_actions: list[RiskyAction | Literal["none"]]
    short_explanation: str
    followup_question: str | None


class JudgeParseError(ValueError):
    """Câu trả lời không phải JSON hợp lệ theo schema. Message chỉ có tên trường + loại lỗi, không có nội dung."""


class LLM(Protocol):
    def converse(self, role: str, messages: list[dict], system: str | None = None, extra_fields: dict | None = None) -> Any:
        """Trả về đối tượng có ``text``, ``cost_usd``, ``latency_ms`` (``LLMResult``); lỗi thì raise."""
        ...


@dataclass
class PostCheck:
    judgement: Judgement
    rejected_quotes: int = 0
    fixes: list[str] = field(default_factory=list)   # mã việc đã sửa, để đếm trong eval; không chứa nội dung


@dataclass
class JudgeResult:
    judgement: Judgement | None      # None = chế độ chỉ chạy luật
    rejected_quotes: int = 0
    fixes: list[str] = field(default_factory=list)
    model_role: str | None = None    # "judge" | "backup"
    calls: int = 0                   # số lần gọi LLM thành công (có câu trả lời)
    json_errors: int = 0             # số câu trả lời JSON hỏng: tử số của "JSON hợp lệ" trong eval
    llm_errors: int = 0
    cost_usd: float = 0.0
    latency_ms: int = 0


# ------------------------------------------------------------------ prompt

def load_system_prompt(path: Path = DEFAULT_PROMPT) -> str:
    return path.read_text(encoding="utf-8")


_FORWARD = {"user": "nguoi_la", "hidden_user": "nguoi_an_danh", "chat": "nhom", "channel": "kenh"}


def _data(text: str) -> str:
    """Đổi ``<``/``>`` sang dạng toàn độ rộng để tin không đóng được thẻ ``</tin_nhan>`` và chèn lệnh.
    NFKC đổi lại đúng ký tự gốc, nên so trích dẫn vẫn khớp."""
    return text.replace("<", "＜").replace(">", "＞")


def build_user_message(text: str, *, source: Literal["van_ban", "anh_chup", "giong_noi"] = "van_ban",
                       forward_origin: ForwardOrigin | None = None, rule_signals: Iterable[str] = (),
                       scenario_cards: str = "", alerts: str = "") -> str:
    """Phần USER của prompt (ARCH §6.6). ``text`` phải là bản đã chuẩn hoá và mask."""
    fwd = f' chuyen_tiep_tu="{_FORWARD[forward_origin]}"' if forward_origin else ""
    parts = [f'<ngu_canh nguon="{source}"{fwd}/>']
    if scenario_cards:
        parts.append(f"<related_scenarios>\n{_data(scenario_cards)}\n</related_scenarios>")
    parts.append(f"<tin_nhan>\n{_data(text)}\n</tin_nhan>")
    codes = ", ".join(rule_signals)
    if codes:
        parts.append(f"<tin_hieu_tu_luat>{_data(codes)}</tin_hieu_tu_luat>")
    if alerts:
        parts.append(f"<canh_bao_lien_quan>\n{_data(alerts)}\n</canh_bao_lien_quan>")
    return "\n".join(parts)


# ------------------------------------------------------------------ parse

_THINK = re.compile(r"<think>.*?</think>", re.DOTALL)


def parse_judgement(raw: str) -> Judgement:
    """Lấy đối tượng JSON đầu tiên trong câu trả lời (bỏ ```json và khối <think> của model reasoning)."""
    body = _THINK.sub("", raw)
    start, end = body.find("{"), body.rfind("}")
    if start < 0 or end <= start:
        raise JudgeParseError("không có đối tượng JSON")
    try:
        data = json.loads(body[start:end + 1])
    except json.JSONDecodeError as e:
        raise JudgeParseError(f"JSON sai cú pháp ở vị trí {e.pos}") from None
    if not isinstance(data, dict):
        raise JudgeParseError("JSON không phải đối tượng")
    try:
        return Judgement.model_validate(data)
    except ValidationError as e:
        errs = "; ".join(f"{'.'.join(str(x) for x in err['loc'])}: {err['type']}" for err in e.errors()[:5])
        raise JudgeParseError(f"sai schema ({errs})") from None


# ------------------------------------------------------------------ hậu kiểm

_URL = re.compile(
    # Chỉ cần nhãn cuối + TLD ("a.b.gov.vn" khớp ở "gov.vn"); bắt đầu ở đầu token để không quét lại bậc hai.
    r"(?:https?|hxxps?)://|www\.|\[\.\]|(?<![\w-])[\w-]+\.(?:com|net|org|vn|xyz|cc|top|info|io|me|app|link|site|online|shop"
    r"|club|biz|tk|ml|ga|cf|gq|ru|cn|asia|live|pro|vip)\b", re.IGNORECASE)
_PHONE = re.compile(r"(?<!\d)(?:\+?84|0)\d(?:[\s.\-]?\d){7,9}(?!\d)")
_HOTLINE = re.compile(r"(?<!\d)(?:1[89]00(?:[\s.\-]?\d){4,6}|11[1345])(?!\d)")
FORBIDDEN = ("an toàn", "yên tâm", "không sao đâu", "chắc chắn không", "không phải lừa đảo", "không có gì đáng lo")
_CJK = re.compile(r"[぀-ヿ㐀-䶿一-鿿가-힯]")
# Từ tiếng Anh hay lọt vào câu của model nhỏ; tránh từ trùng với tiếng Việt không dấu ("link", "app", "an", "to").
_EN_WORDS = frozenset({
    "the", "and", "this", "that", "you", "your", "please", "scam", "scammer", "message", "account", "with", "from", "have",
    "is", "are", "was", "will", "should", "click", "money", "transfer", "suspicious", "fraud", "risk", "verify", "however",
    "because", "not", "it", "of", "for", "be",
})
_SENTENCE = re.compile(r"(?<=[.!?…])\s+")
_WS = re.compile(r"\s+")


def _norm(s: str) -> str:
    return _WS.sub(" ", unicodedata.normalize("NFKC", s).casefold()).strip()


def has_contact_or_url(s: str) -> bool:
    return bool(_URL.search(s) or _PHONE.search(s) or _HOTLINE.search(s))


def has_forbidden(s: str) -> bool:
    low = _norm(s)
    return any(w in low for w in FORBIDDEN)


def is_bad_language(s: str) -> bool:
    """Lẫn chữ Hán/Nhật/Hàn, ≥ 2 từ tiếng Anh thông dụng, hoặc câu dài mà không có chữ nào có dấu."""
    if _CJK.search(s):
        return True
    words = re.findall(r"[^\W\d_]+", s)
    if sum(1 for w in words if w.lower() in _EN_WORDS) >= 2:
        return True
    return len(words) >= 8 and not any(ord(c) > 127 for w in words for c in w)


def _clip(s: str, limit: int) -> str:
    if len(s) <= limit:
        return s
    cut = s[:limit - 1].rsplit(" ", 1)[0]
    return cut.rstrip(" ,;:") + "…"


def default_template(codes: list[str], group: str) -> str:
    """Giải thích dự phòng khi câu của model không dùng được. AI-07 thay bằng template theo mã trong kb/templates/."""
    if group == "N0" and not codes:
        return ("Bot chưa thấy dấu hiệu lừa đảo rõ ràng. Nhưng nếu ai bảo bác chuyển tiền, đọc OTP hay giữ bí mật "
                "thì bác hãy dừng lại.")
    return "Tin này có dấu hiệu hay gặp trong các vụ lừa đảo. Bác đừng làm theo yêu cầu trong tin, hỏi con cháu trước đã."


def post_check(j: Judgement, text: str, *, turn: int = 1, taxonomy: frozenset[str] = TAXONOMY,
               template: Callable[[list[str], str], str] = default_template) -> PostCheck:
    """Sửa ``j`` theo ARCH §6.6. ``text`` là đúng bản đã gửi cho model (đã chuẩn hoá và mask)."""
    fixes: list[str] = []
    score = min(100, max(0, j.risk_score))
    if score != j.risk_score:
        fixes.append("score_clamped")

    haystack = _norm(text)
    signals, rejected = [], 0
    for s in j.signals:
        quote = _norm(s.quote).strip("\"'“”‘’ ")
        if quote and quote in haystack:
            signals.append(s)
        else:
            rejected += 1
    if len(signals) > MAX_SIGNALS:
        signals = signals[:MAX_SIGNALS]
        fixes.append("signals_truncated")
    if rejected:
        fixes.append("quote_rejected")

    scenario, group = j.scenario.strip(), j.group
    if scenario not in taxonomy:
        scenario = "OTHER"
        fixes.append("scenario_unknown")
    if scenario != "OTHER" and group != scenario.split(".")[0]:
        group = scenario.split(".")[0]
        fixes.append("group_from_scenario")

    actions = list(dict.fromkeys(a for a in j.requested_actions if a != "none"))

    explanation = j.short_explanation.strip()
    if is_bad_language(explanation):
        explanation = ""
        fixes.append("explanation_language")
    else:
        kept = [s for s in _SENTENCE.split(explanation) if s and not has_contact_or_url(s) and not has_forbidden(s)]
        if len(kept) != len([s for s in _SENTENCE.split(explanation) if s]):
            fixes.append("explanation_sentence_dropped")
        explanation = " ".join(kept)
    if not explanation:
        explanation = template([s.code for s in signals], group)
    explanation = _clip(explanation, MAX_EXPLANATION)

    followup = (j.followup_question or "").strip() or None
    if followup and turn >= 2:
        followup = None
        fixes.append("followup_turn2")
    elif followup and (has_contact_or_url(followup) or has_forbidden(followup) or is_bad_language(followup)
                       or len(followup) > MAX_FOLLOWUP):
        followup = None
        fixes.append("followup_dropped")

    fixed = j.model_copy(update={"risk_score": score, "signals": signals, "scenario": scenario, "group": group,
                                 "requested_actions": actions, "short_explanation": explanation, "followup_question": followup})
    return PostCheck(fixed, rejected, fixes)


# ------------------------------------------------------------------ gọi model

_RETRY_NOTE = "\n\nCâu trả lời trước chưa hợp lệ: {err}. Chỉ trả về đúng MỘT đối tượng JSON theo schema, không có chữ nào khác."


def judge(llm: LLM, system_prompt: str, user_message: str, text: str, *, turn: int = 1,
          roles: tuple[str, ...] = ("judge", "backup"), taxonomy: frozenset[str] = TAXONOMY) -> JudgeResult:
    """Không bao giờ raise vì lỗi LLM: trả ``judgement=None`` để fuse chạy chế độ chỉ chạy luật."""
    res = JudgeResult(None)
    for role in roles:
        prompt = user_message
        for _ in range(2):   # lần đầu + hỏi lại một lần khi JSON hỏng
            try:
                out = llm.converse(role, [{"role": "user", "content": [{"text": prompt}]}], system=system_prompt)
            except Exception:  # noqa: BLE001 — LLMError hoặc lỗi bất kỳ của client: chuyển model dự phòng
                res.llm_errors += 1
                break
            res.calls += 1
            res.cost_usd += out.cost_usd
            res.latency_ms += out.latency_ms
            try:
                parsed = parse_judgement(out.text)
            except JudgeParseError as e:
                res.json_errors += 1
                prompt = user_message + _RETRY_NOTE.format(err=e)
                continue
            pc = post_check(parsed, text, turn=turn, taxonomy=taxonomy)
            res.judgement, res.rejected_quotes, res.fixes, res.model_role = pc.judgement, pc.rejected_quotes, pc.fixes, role
            return res
    return res
