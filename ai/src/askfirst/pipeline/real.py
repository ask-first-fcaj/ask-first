"""``RealPipeline`` cho văn bản (INT-01, ARCH §6.2): normalize → mask → signals → fast path → judge → fuse; render qua Respond.

Ghép các stage đã có (normalize/signals AI-23, mask AI-22, judge AI-04/AI-07, fuse AI-05, respond AI-06).
``rules(masked_text, urls) -> list[Signal]`` truyền vào (``build_pipeline`` dùng ``signals.RuleEngine`` trên
``kb/rules.yaml``; test truyền ``no_rules``); ``rules_version`` ghi vào ``Verdict.versions["rules"]``.
``url_intel(urls, links) -> list[Signal]`` (AI-24a, ``url_intel.UrlIntel``) chấm URL gốc (không mask) và các
``text_link`` (chữ hiển thị, URL thật); mặc định ``no_url_intel``.

Ảnh (AI-26, khi ``flags.feature_image`` bật và có ``ocr``): chữ đọc được (bản đã sửa URL/SĐT) và URL trong mã QR được ghép
sau chữ của tin rồi đi chung các stage trên với nguồn ``anh_chup``. URL lấy từ cả bản OCR gốc lẫn bản đã sửa (ARCH §6.3).
Ảnh lỗi khi đọc chỉ bị bỏ qua; không còn chữ nào thì trả ``unreadable``. Chưa có VLM fallback khi ``mean_conf`` thấp (AI-27).

Chưa có: ASR (voice không kèm chữ trả ``unreadable``), Retrieve (AI-24b), Guard (AI-08), ghép câu trả lời lượt hỏi thêm
vào prompt (AI-12, hoãn; lượt 2 chỉ chặn hỏi tiếp).

Không bao giờ raise vì lỗi model/mạng (judge tự xuống chế độ chỉ chạy luật). Không log nội dung (plan §3.1).
"""

from __future__ import annotations

import hashlib
import json
import logging
import time
from collections.abc import Callable
from typing import Protocol

from askfirst.domain import models as m
from askfirst.pipeline import fuse as F
from askfirst.pipeline import judge as J
from askfirst.pipeline import respond as R
from askfirst.pipeline.mask import mask
from askfirst.pipeline.normalize import normalize as default_normalize

SMALLTALK = {"cảm ơn", "cám ơn", "alo", "ok", "chào bot"}
_TRIM = " \t\n.!?…"
_URL_TAIL = ".,;:!?)]…"

QR_NOT_URL = "Mã QR (không phải đường link)"   # nội dung QR không phải URL (vd VietQR có số tài khoản) không đưa cho LLM

Rules = Callable[[str, list[str]], list[m.Signal]]
UrlSignals = Callable[[list[str], list[tuple[str, str]]], list[m.Signal]]
log = logging.getLogger("askfirst.pipeline")


class JudgeLLM(J.LLM, Protocol):
    """``askfirst.llm.bedrock.BedrockClient`` khớp giao diện này."""

    def budget_s(self, role: str) -> float: ...

    def model_id(self, role: str) -> str: ...


class Page(Protocol):
    """Chữ đọc từ một ảnh, khớp ``askfirst.local_models.ocr.ExtractedText`` (pipeline không import local_models)."""

    raw_text: str
    text: str
    mean_conf: float
    qr: list[str]


class Ocr(Protocol):
    """``askfirst.local_models.ocr.RapidOcr`` khớp giao diện này."""

    version: str

    def read(self, data: bytes) -> Page: ...


def no_rules(masked_text: str, urls: list[str]) -> list[m.Signal]:
    return []


def no_url_intel(urls: list[str], links: list[tuple[str, str]]) -> list[m.Signal]:
    return []


def _utf16_slice(text: str, offset: int, length: int) -> str:
    """``offset``/``length`` của entity Telegram tính theo đơn vị UTF-16."""
    return text.encode("utf-16-le")[2 * offset: 2 * (offset + length)].decode("utf-16-le", errors="ignore")


def entity_urls(texts: list[m.TextPart]) -> list[str]:
    """URL từ ``entities``: ``url`` là đoạn chữ trong tin, ``text_link`` là URL thật (không phải chữ hiển thị, ARCH §6.4)."""
    return [e.url if e.kind == "text_link" and e.url else _utf16_slice(t.text, e.offset, e.length)
            for t in texts for e in t.entities]


def text_links(texts: list[m.TextPart]) -> list[tuple[str, str]]:
    """``(chữ hiển thị, URL thật)`` của các entity ``text_link``, cho ``U_TEXT_LINK_MISMATCH``."""
    return [(_utf16_slice(t.text, e.offset, e.length), e.url) for t in texts for e in t.entities
            if e.kind == "text_link" and e.url]


def extract_urls(text: str, texts: list[m.TextPart]) -> list[str]:
    """Gộp URL từ regex trên text và từ ``entities``, bỏ trùng, giữ thứ tự. Regex bắt cả tên miền trần (thà thừa)."""
    found = [u.group().rstrip(_URL_TAIL) for u in R.URL_RE.finditer(text)] + entity_urls(texts)
    return list(dict.fromkeys(u for u in found if u))


def _is_url(s: str) -> bool:
    return R.URL_RE.fullmatch(s.strip()) is not None


def image_text(pages: list[Page], normalize: Callable[[str], str] = default_normalize) -> str:
    """Chữ của các ảnh đưa vào phân tích: bản đã sửa, thêm một dòng cho mỗi mã QR."""
    lines = []
    for p in pages:
        lines.append(normalize(p.text))
        lines += [f"Mã QR: {q.strip()}" if _is_url(q) else QR_NOT_URL for q in p.qr]
    return "\n".join(x for x in lines if x)


def ocr_urls(pages: list[Page]) -> list[str]:
    """URL từ bản OCR gốc (trước khi sửa) và từ QR; URL của bản đã sửa được bắt ở chữ ghép."""
    raw = [u.group().rstrip(_URL_TAIL) for p in pages for u in R.URL_RE.finditer(p.raw_text)]
    return raw + [q.strip() for p in pages for q in p.qr if _is_url(q)]


def all_official(urls: list[str]) -> bool:
    """Không có URL nào thì ``True``. URL không có scheme coi là không chính thức (``is_official`` đòi ``https``)."""
    return all(R.is_official(u) for u in urls)


def _ms(start: float) -> int:
    return int((time.perf_counter() - start) * 1000)


class RealPipeline:
    def __init__(self, llm: JudgeLLM, system_prompt: str, *, normalize: Callable[[str], str] = default_normalize,
                 rules: Rules = no_rules, rules_version: str = "none", url_intel: UrlSignals = no_url_intel,
                 roles: tuple[str, str] = ("judge", "backup"), thresholds: F.Thresholds = F.DEFAULT_THRESHOLDS,
                 ocr: Ocr | None = None) -> None:
        self.llm = llm
        self.ocr = ocr
        self.system_prompt = system_prompt
        self.normalize = normalize
        self.rules = rules
        self.url_intel = url_intel
        self.roles = roles
        self.thresholds = thresholds
        self.versions = {"contract": m.CONTRACT_VERSION, "pipeline": "real", "prompt": J.PROMPT_VERSION,
                         "rules": rules_version}

    def _text(self, bundle: m.Bundle) -> str:
        return self.normalize("\n".join(t.text for t in bundle.texts))

    def fingerprint(self, bundle: m.Bundle) -> str:
        key = {"text": self._text(bundle),
               "files": sorted(x.file_unique_id for x in [*bundle.images, *bundle.audios]),
               "urls": entity_urls(bundle.texts)}
        return hashlib.sha256(json.dumps(key, ensure_ascii=False, sort_keys=True).encode()).hexdigest()

    def _read_images(self, bundle: m.Bundle, ctx: m.AnalyzeContext) -> list[Page]:
        if self.ocr is None or not ctx.flags.feature_image:
            return []
        pages = []
        for img in bundle.images:
            try:
                pages.append(self.ocr.read(img.data))
            except Exception as e:   # ảnh hỏng, artifact chưa có, model lỗi: bỏ ảnh này, không làm hỏng lượt kiểm
                log.warning(json.dumps({"event": "ocr_failed", "error": type(e).__name__}))   # không log message (plan §3.1)
        return pages

    def _plain(self, outcome: m.Outcome, started: float, versions: dict[str, str], latency: dict[str, int]) -> m.Verdict:
        level, score, group = ("LOW_RISK", 0, "N0") if outcome == "smalltalk" else ("UNSURE", 30, "OTHER")
        return m.Verdict(outcome=outcome, level=level, score=score, group=group, scenario=group, should_alert=False,
                         path="fast", versions=versions, latency_ms={**latency, "total": _ms(started)})

    def analyze(self, bundle: m.Bundle, ctx: m.AnalyzeContext) -> m.Verdict:
        started = time.perf_counter()
        can_call = J.deadline_check(ctx.deadline_s, {r: self.llm.budget_s(r) for r in self.roles})
        text = self._text(bundle)
        latency: dict[str, int] = {}
        versions = dict(self.versions)
        t = time.perf_counter()
        pages = self._read_images(bundle, ctx)
        if pages:
            latency["ocr"] = _ms(t)
            versions["local_models"] = f"ocr:{self.ocr.version}"
        seen = image_text(pages, self.normalize)
        if not text and not seen:
            return self._plain("unreadable", started, versions, latency)   # voice (chưa có ASR) hoặc ảnh không đọc được chữ nào
        if bundle.session is None and not (bundle.images or bundle.audios) and text.lower().strip(_TRIM) in SMALLTALK:
            return self._plain("smalltalk", started, versions, latency)

        full = "\n".join(x for x in (text, seen) if x)
        masked = mask(full).text
        urls = list(dict.fromkeys(extract_urls(full, bundle.texts) + ocr_urls(pages)))
        t = time.perf_counter()
        signals = self.rules(masked, urls) + self.url_intel(urls, text_links(bundle.texts))
        latency["rules"] = _ms(t)

        res = J.JudgeResult(None)
        if not F.is_fast_path(signals):
            t = time.perf_counter()
            user_message = J.build_user_message(masked, source="anh_chup" if seen else "van_ban",
                                                forward_origin=bundle.forward_origin, rule_signals=[s.code for s in signals])
            res = J.judge(self.llm, self.system_prompt, user_message, masked,
                          turn=bundle.session.turn if bundle.session else 1, rule_score=F.rule_score(signals),
                          llm_enabled=ctx.flags.llm_enabled, can_call=can_call, roles=self.roles)
            latency["judge"] = _ms(t)

        v = F.fuse(signals, res.judgement, all_urls_official=all_official(urls), escalated=res.escalated,
                   thresholds=self.thresholds)
        latency["total"] = _ms(started)
        return v.model_copy(update={
            "masked_text": masked,
            "citations": R.scenario_citations(v.group, v.scenario),
            "model_ids": [self.llm.model_id(res.model_role)] if res.model_role else [],
            "versions": versions,
            "latency_ms": latency,
            "cost_usd_est": res.cost_usd,
        })

    def render(self, verdict: m.Verdict, ctx: m.RenderContext) -> m.ReplyPlan:
        return R.render(verdict, ctx)

    def render_guardian_alert(self, verdict: m.Verdict, ctx: m.GuardianAlertContext) -> m.OutMessage:
        return R.render_guardian_alert(verdict, ctx)
