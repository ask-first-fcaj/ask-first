"""askfirst.domain.models: hợp đồng BE (Java) <-> AI (Python), plan §3.2.

Nguồn sự thật duy nhất: `uv run python -m askfirst.domain.export` xuất các model WIRE_MODELS ra contracts/schema/*.json, Java sinh class từ đó.
Sửa file này phải có 1 BE + 1 AI duyệt.
"""
from __future__ import annotations

import json
import pathlib
from datetime import datetime
from typing import Any, Literal, Protocol

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.json_schema import GenerateJsonSchema

CONTRACT_VERSION = "1.0"

Level = Literal["NGUY_HIEM", "NGHI_NGO", "CHUA_CHAC", "IT_DAU_HIEU"]
LEVEL_ORDER: dict[str, int] = {"IT_DAU_HIEU": 0, "CHUA_CHAC": 1, "NGHI_NGO": 2, "NGUY_HIEM": 3}
Nhom = Literal["N0", "N1", "N2", "N3", "N4", "N5", "N6", "KHAC"]
RiskyAction = Literal[
    "chuyen_tien", "cung_cap_otp", "cung_cap_mat_khau", "cai_app",
    "chia_se_man_hinh", "bam_link", "giu_bi_mat", "cung_cap_cccd",
]
Path = Literal["cache", "fast", "llm", "escalated", "rules_only"]
Outcome = Literal["verdict", "smalltalk", "unreadable"]
ForwardOrigin = Literal["user", "hidden_user", "chat", "channel"]
XungHo = Literal["bac", "co", "chu"]
Answer = Literal["yes", "no", "unknown"]


class _M(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


# ---------- Đầu vào: BE (Java) tạo, gửi qua wire ----------

class EntityRef(_M):
    kind: Literal["url", "text_link"]
    offset: int                      # tính theo UTF-16 code unit, giữ nguyên như Telegram
    length: int
    url: str | None = None           # bắt buộc khi kind == "text_link" (URL thật, khác chữ hiển thị)

    @model_validator(mode="after")
    def _text_link_has_url(self) -> EntityRef:
        if self.kind == "text_link" and not self.url:
            raise ValueError("text_link phải có url")
        return self


class MediaRef(_M):
    file_id: str
    file_unique_id: str
    mime: str | None = None
    size: int | None = None          # byte
    duration: int | None = None      # giây, chỉ voice/audio
    file_name: str | None = None     # chỉ document


class InboundMessage(_M):
    """Adapter tạo từ một update. Lưu tạm trong BUFFER# (≤ 10 phút, xoá ngay sau xử lý)."""
    channel: Literal["telegram", "zalo"]
    update_id: str
    chat_id: str
    user_id: str                     # "tg:<telegram_user_id>"
    message_id: str
    kind: Literal["text", "photo", "voice", "audio", "document"]
    text: str | None = None          # text hoặc caption, đã cắt ở 4.000 ký tự
    entities: list[EntityRef] = Field(default_factory=list)
    media: MediaRef | None = None
    forward_origin: ForwardOrigin | None = None
    media_group_id: str | None = None
    received_at: datetime            # UTC


# ---------- Nội bộ AI: KHÔNG qua wire (Lambda `ai` tự dựng từ InboundMessage, plan §3.6) ----------

class TextPart(_M):
    text: str
    entities: list[EntityRef] = Field(default_factory=list)   # offset tính trong chính text này


class ImageInput(_M):
    data: bytes
    file_unique_id: str
    mime: str


class AudioInput(_M):
    data: bytes
    file_unique_id: str
    mime: str                        # "audio/ogg" (voice Telegram), "audio/mpeg"…
    duration_s: int


class SessionContext(_M):
    """Lượt hỏi thêm (ARCH §6.12). BE lấy từ SESSION# rồi gửi trong AnalyzeRequest, AI ghép vào Judge. Có qua wire."""
    prev_check_id: str
    masked_text: str                 # = Verdict.masked_text của lượt trước
    question: str                    # = Verdict.followup_question của lượt trước
    answer: Answer
    turn: int = Field(ge=1, le=2)


class Bundle(_M):
    """Input của pipeline: cả cụm tin đã gom (ARCH §5.2), media đã tải vào RAM của Lambda `ai`."""
    texts: list[TextPart] = Field(default_factory=list)
    images: list[ImageInput] = Field(default_factory=list)
    audios: list[AudioInput] = Field(default_factory=list)
    forward_origin: ForwardOrigin | None = None   # của tin đầu tiên có forward
    session: SessionContext | None = None


class Flags(_M):
    """BE đọc từ DynamoDB CONFIG#global/FLAGS (ARCH §5.8), cache 30 giây, gửi trong AnalyzeContext."""
    llm_enabled: bool = True
    feature_image: bool = True
    feature_voice: bool = True
    feature_rag: bool = True


class AnalyzeContext(_M):
    check_id: str                    # ULID do BE sinh (= job_id); cũng là trace_id trong log
    user_id: str
    flags: Flags
    deadline_s: float                # số giây BE còn chờ được (≤ timeout của `ai`); pipeline phải trả trước deadline_s - 5


# ---------- Đầu ra của analyze: AI tạo, BE nhận qua wire ----------

class Signal(_M):
    code: str                        # mã trong kb/rules.yaml, ví dụ "R_OTP_REQUEST", "U_TYPOSQUAT"
    weight: int
    hard: bool = False
    evidence: str | None = None      # đoạn đã mask, ≤ 120 ký tự; CẤM ghi log


class Citation(_M):
    title: str
    url: str
    publisher: str
    date: str                        # "YYYY-MM-DD"


class Verdict(_M):
    outcome: Outcome                 # smalltalk → level IT_DAU_HIEU; unreadable → level CHUA_CHAC
    level: Level
    score: int = Field(ge=0, le=100)
    nhom: Nhom
    kich_ban: str                    # mã taxonomy, ví dụ "N1.4"; không khớp thì "KHAC"
    signals: list[Signal] = Field(default_factory=list)
    risky_actions: list[RiskyAction] = Field(default_factory=list)
    should_alert: bool               # level == NGUY_HIEM và có risky_actions; BE vẫn phải kiểm người thân, consent, ALERTLOCK
    explanation: str = ""            # đã hậu kiểm, CHƯA escape HTML (render lo việc escape)
    citations: list[Citation] = Field(default_factory=list)
    followup_question: str | None = None
    masked_text: str = ""            # input đã mask; CHỈ dùng cho SESSION# (30 phút) và donated/ khi user đồng ý; CẤM log
    path: Path
    model_ids: list[str] = Field(default_factory=list)
    versions: dict[str, str] = Field(default_factory=dict)   # khoá: prompt, rules, kb, local_models, contract
    latency_ms: dict[str, int] = Field(default_factory=dict) # theo stage, ví dụ {"ocr": 1800, "judge": 2400}
    cost_usd_est: float = 0.0

    def for_cache(self) -> Verdict:
        """Bản để lưu CACHE# (24 giờ): bỏ mọi phần có chữ lấy từ input."""
        return self.model_copy(update={
            "masked_text": "", "explanation": "", "followup_question": None,
            "signals": [s.model_copy(update={"evidence": None}) for s in self.signals],
        })


# ---------- Đầu ra của render: AI tạo, BE gửi ----------

ButtonKind = Literal[
    "checklist",        # BE: callback "cl"           → gửi checklist /bilua
    "call_guardian",    # BE: callback "cg:<check_id>" → sendContact người thân
    "alert_guardian",   # BE: callback "ag:<check_id>" → người cao tuổi tự báo người thân (mức NGHI_NGO)
    "report_wrong",     # BE: callback "fb:wrong:<check_id>"
    "donate",           # BE: callback "dn:<check_id>" → hỏi đồng ý rồi lưu masked_text vào donated/
    "answer_yes", "answer_no", "answer_unknown",   # BE: callback "fu:<check_id>:y|n|u"
    "ack",              # BE: callback "ack:<alert_id>" (tin cảnh báo người thân)
]


class Button(_M):
    kind: ButtonKind
    label: str                       # chữ hiển thị, AI viết


class OutMessage(_M):
    text_html: str                   # ĐÃ escape HTML và defang URL; BE gửi parse_mode=HTML, luôn tắt link preview
    buttons: list[list[Button]] = Field(default_factory=list)   # danh sách hàng nút
    silent: bool = False             # True → disable_notification


class ReplyPlan(_M):
    messages: list[OutMessage] = Field(min_length=1)   # [0] = kết luận, sửa vào tin "⏳"; phần còn lại gửi tin mới


class AlertResult(_M):
    guardian_name: str
    status: Literal["sent", "failed"]   # "sent" chỉ khi Telegram trả message_id


class RenderContext(_M):
    check_id: str
    xung_ho: XungHo
    has_guardian: bool               # có ít nhất 1 người thân active
    alerts: list[AlertResult] = Field(default_factory=list)   # rỗng nếu không gửi cảnh báo
    maintenance: bool = False        # = not flags.llm_enabled


class GuardianAlertContext(_M):
    alert_id: str                    # ULID
    elder_name: str                  # tên hiển thị trên Telegram, chưa escape
    share_level: Literal[1, 2, 3]
    at: datetime                     # UTC; render hiển thị giờ Việt Nam


# ---------- Wire: Lambda `ai` (Java worker gọi bằng InvokeFunction, ARCH §4.5) ----------

class AnalyzeRequest(_M):
    v: str = CONTRACT_VERSION
    op: Literal["analyze"] = "analyze"
    items: list[InboundMessage] = Field(default_factory=list)   # chỉ metadata + file_id; `ai` tự tải media
    session: SessionContext | None = None                       # job "answer": items rỗng, có session
    ctx: AnalyzeContext


class AnalyzeResult(_M):
    verdict: Verdict
    cache_hit: bool


class RenderGuardianAlertsRequest(_M):
    v: str = CONTRACT_VERSION
    op: Literal["render_guardian_alerts"] = "render_guardian_alerts"
    verdict: Verdict
    guardians: list[GuardianAlertContext] = Field(min_length=1)


class RenderGuardianAlertsResult(_M):
    alerts: list[OutMessage]                                    # cùng thứ tự với request.guardians


class RenderRequest(_M):
    v: str = CONTRACT_VERSION
    op: Literal["render"] = "render"
    verdict: Verdict
    ctx: RenderContext


class AiError(_M):
    kind: Literal["retryable", "fatal"]   # retryable: BE để SQS retry; fatal: input sai/bug, BE trả câu suy giảm, không retry
    code: str                             # ví dụ "bad_request", "unsupported_version", "telegram_download", "deadline"


class AiResponse(_M):
    """Mọi op trả về đúng một trong các trường kết quả, hoặc error. Không dùng union để Java codegen dễ."""
    v: str = CONTRACT_VERSION
    op: Literal["analyze", "render_guardian_alerts", "render"]
    analyze: AnalyzeResult | None = None
    render_guardian_alerts: RenderGuardianAlertsResult | None = None
    render: ReplyPlan | None = None
    error: AiError | None = None

    @model_validator(mode="after")
    def _exactly_one(self) -> AiResponse:
        filled = [k for k in ("analyze", "render_guardian_alerts", "render", "error") if getattr(self, k) is not None]
        if len(filled) != 1 or (filled[0] != "error" and filled[0] != self.op):
            raise ValueError(f"op={self.op} phải có đúng một kết quả cùng tên hoặc error, đang có {filled}")
        return self


WIRE_MODELS: tuple[type[BaseModel], ...] = (
    AnalyzeRequest, RenderGuardianAlertsRequest, RenderRequest, AiResponse,
    InboundMessage,                                             # BE còn lưu dạng JSON trong BUFFER#
)


class _JavaFriendlySchema(GenerateJsonSchema):
    """`X | None` → chỉ X (trường không nằm trong `required`), để codegen Java ra field nullable thay vì anyOf."""

    def nullable_schema(self, schema: Any) -> dict[str, Any]:
        return self.generate_inner(schema["schema"])


def export_json_schemas(out_dir: pathlib.Path) -> list[pathlib.Path]:
    """Gọi từ askfirst.domain.export: ghi contracts/schema/<Model>.json. CI chạy lại rồi `git diff --exit-code contracts/`."""
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for model in WIRE_MODELS:
        schema = model.model_json_schema(schema_generator=_JavaFriendlySchema, ref_template="#/definitions/{model}")
        if "$defs" in schema:
            schema["definitions"] = schema.pop("$defs")
        schema["$id"] = f"askfirst/{CONTRACT_VERSION}/{model.__name__}.json"
        path = out_dir / f"{model.__name__}.json"
        path.write_text(json.dumps(schema, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        written.append(path)
    return written


# ---------- Interface nội bộ Python (không qua wire) ----------

class KVCache(Protocol):
    """Lambda `ai` cài bằng DynamoDB (URL#…, CACHE#…); CLI và eval dùng bản trong RAM."""
    def get(self, key: str) -> dict | None: ...
    def put(self, key: str, value: dict, ttl_s: int) -> None: ...


class ArtifactStore(Protocol):
    """Trả thư mục local chứa artifact (weights model, index RAG) theo config/artifacts.yaml.
    Lambda `ai` cài bản S3: lần đầu gọi thì tải s3://…/<prefix>/ về /tmp/artifacts/<name>/<version>/ và kiểm sha256.
    CLI và eval dùng bản đọc thẳng thư mục local."""
    def path(self, name: str) -> pathlib.Path: ...


class Pipeline(Protocol):
    """Lambda `ai` (handler.py) gọi các hàm này theo `op`; CLI và eval gọi thẳng. Java không thấy interface này."""

    def fingerprint(self, bundle: Bundle) -> str:
        """Khoá cache (ARCH §6.2): sha256 của text đã chuẩn hoá + mọi file_unique_id + URL từ entities."""
        ...

    def analyze(self, bundle: Bundle, ctx: AnalyzeContext) -> Verdict:
        """Không raise vì lỗi model/mạng (tự suy giảm về rules_only). Có exception nghĩa là bug → SQS retry."""
        ...

    def render(self, verdict: Verdict, ctx: RenderContext) -> ReplyPlan:
        """Hàm thuần, không gọi mạng. Verdict lấy từ cache (explanation rỗng) thì dùng template theo mã dấu hiệu."""
        ...

    def render_guardian_alert(self, verdict: Verdict, ctx: GuardianAlertContext) -> OutMessage:
        """Nội dung theo share_level (ARCH §5.5); luôn có nút "ack"."""
        ...
