"""``RealPipeline`` cho văn bản (INT-01, ARCH §6.2): normalize → mask → signals → fast path → judge → fuse; render qua Respond.

Ghép các stage đã có (mask AI-22, judge AI-04/AI-07, fuse AI-05, respond AI-06). Hai stage còn chờ việc khác nên được
truyền vào, có bản mặc định tạm:

* ``normalize``: mặc định ``basic_normalize`` (NFC, bỏ ký tự zero-width). AI-23 thay bằng bản đầy đủ.
* ``rules(masked_text, urls) -> list[Signal]``: mặc định ``no_rules`` (không tín hiệu nào). AI-23 (rules engine trên
  ``kb/rules.yaml``) và AI-24a (URL intel) cắm vào đây; ``rules_version`` ghi vào ``Verdict.versions["rules"]``.

Chưa có: OCR/ASR (AI-26, ảnh/voice không kèm chữ trả ``unreadable``), Retrieve (AI-24b), Guard (AI-08), ghép câu trả lời
lượt hỏi thêm vào prompt (AI-12, hoãn; lượt 2 chỉ chặn hỏi tiếp).

Không bao giờ raise vì lỗi model/mạng (judge tự xuống chế độ chỉ chạy luật). Không log nội dung (plan §3.1).
"""

from __future__ import annotations

import hashlib
import json
import re
import time
import unicodedata
from collections.abc import Callable
from typing import Protocol

from askfirst.domain import models as m
from askfirst.pipeline import fuse as F
from askfirst.pipeline import judge as J
from askfirst.pipeline import respond as R
from askfirst.pipeline.mask import mask

SMALLTALK = {"cảm ơn", "cám ơn", "alo", "ok", "chào bot"}
_ZERO_WIDTH = re.compile("[​-‍⁠﻿]")
_TRIM = " \t\n.!?…"
_URL_TAIL = ".,;:!?)]…"

Rules = Callable[[str, list[str]], list[m.Signal]]


class JudgeLLM(J.LLM, Protocol):
    """``askfirst.llm.bedrock.BedrockClient`` khớp giao diện này."""

    def budget_s(self, role: str) -> float: ...

    def model_id(self, role: str) -> str: ...


def basic_normalize(text: str) -> str:
    return _ZERO_WIDTH.sub("", unicodedata.normalize("NFC", text)).strip()


def no_rules(masked_text: str, urls: list[str]) -> list[m.Signal]:
    return []


def _utf16_slice(text: str, offset: int, length: int) -> str:
    """``offset``/``length`` của entity Telegram tính theo đơn vị UTF-16."""
    return text.encode("utf-16-le")[2 * offset: 2 * (offset + length)].decode("utf-16-le", errors="ignore")


def entity_urls(texts: list[m.TextPart]) -> list[str]:
    """URL từ ``entities``: ``url`` là đoạn chữ trong tin, ``text_link`` là URL thật (không phải chữ hiển thị, ARCH §6.4)."""
    return [e.url if e.kind == "text_link" and e.url else _utf16_slice(t.text, e.offset, e.length)
            for t in texts for e in t.entities]


def extract_urls(text: str, texts: list[m.TextPart]) -> list[str]:
    """Gộp URL từ regex trên text và từ ``entities``, bỏ trùng, giữ thứ tự. Regex bắt cả tên miền trần (thà thừa)."""
    found = [u.group().rstrip(_URL_TAIL) for u in R.URL_RE.finditer(text)] + entity_urls(texts)
    return list(dict.fromkeys(u for u in found if u))


def all_official(urls: list[str]) -> bool:
    """Không có URL nào thì ``True``. URL không có scheme coi là không chính thức (``is_official`` đòi ``https``)."""
    return all(R.is_official(u) for u in urls)


def _ms(start: float) -> int:
    return int((time.perf_counter() - start) * 1000)


class RealPipeline:
    def __init__(self, llm: JudgeLLM, system_prompt: str, *, normalize: Callable[[str], str] = basic_normalize,
                 rules: Rules = no_rules, rules_version: str = "none", roles: tuple[str, str] = ("judge", "backup"),
                 thresholds: F.Thresholds = F.DEFAULT_THRESHOLDS) -> None:
        self.llm = llm
        self.system_prompt = system_prompt
        self.normalize = normalize
        self.rules = rules
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

    def _plain(self, outcome: m.Outcome, started: float) -> m.Verdict:
        level, score, group = ("LOW_RISK", 0, "N0") if outcome == "smalltalk" else ("UNSURE", 30, "OTHER")
        return m.Verdict(outcome=outcome, level=level, score=score, group=group, scenario=group, should_alert=False,
                         path="fast", versions=dict(self.versions), latency_ms={"total": _ms(started)})

    def analyze(self, bundle: m.Bundle, ctx: m.AnalyzeContext) -> m.Verdict:
        started = time.perf_counter()
        can_call = J.deadline_check(ctx.deadline_s, {r: self.llm.budget_s(r) for r in self.roles})
        text = self._text(bundle)
        if not text:
            return self._plain("unreadable", started)   # ảnh/voice chưa đọc được tới AI-26
        if bundle.session is None and not (bundle.images or bundle.audios) and text.lower().strip(_TRIM) in SMALLTALK:
            return self._plain("smalltalk", started)

        masked = mask(text).text
        urls = extract_urls(text, bundle.texts)
        t = time.perf_counter()
        signals = self.rules(masked, urls)
        latency = {"rules": _ms(t)}

        res = J.JudgeResult(None)
        if not F.is_fast_path(signals):
            t = time.perf_counter()
            user_message = J.build_user_message(masked, forward_origin=bundle.forward_origin,
                                                rule_signals=[s.code for s in signals])
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
            "versions": dict(self.versions),
            "latency_ms": latency,
            "cost_usd_est": res.cost_usd,
        })

    def render(self, verdict: m.Verdict, ctx: m.RenderContext) -> m.ReplyPlan:
        return R.render(verdict, ctx)

    def render_guardian_alert(self, verdict: m.Verdict, ctx: m.GuardianAlertContext) -> m.OutMessage:
        return R.render_guardian_alert(verdict, ctx)
