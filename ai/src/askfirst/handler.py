"""Entry point của Lambda `ai` (AI-03b, plan §3.2, §3.5). Worker (Java) gọi bằng InvokeFunction với 3 `op`.

**Không bao giờ raise:** mọi nhánh trả `AiResponse.model_dump(mode="json")`.

* `v` lạ → `fatal/unsupported_version`; `op` lạ → `fatal/unknown_op`; sai schema → `fatal/bad_request`.
* Tải media lỗi (`MediaDownloadError`) → `retryable/telegram_download`.
* Pipeline raise lỗi có `retryable=True` (vd. `LLMError` khi Bedrock throttle) → `retryable/llm_throttled`; lỗi khác
  → `fatal/pipeline_error`. Pipeline thật tự suy giảm về `rules_only`, nên nhánh này chỉ là lưới an toàn.
* Khởi tạo lỗi: cấu hình sai → `fatal/init`; lỗi khác (mạng, SSM, S3) → `retryable/init`.

`CACHE#` (TTL 24 giờ, bản `Verdict.for_cache()`): không dùng cho lượt hỏi thêm (có `session`) và không ghi
verdict `rules_only` (bản suy giảm không được dính 24 giờ). Đọc/ghi cache lỗi thì bỏ qua cache.

Log mỗi lần gọi đúng một dòng JSON: `trace_id` (= check_id), op, kết quả, `cache_hit`, độ trễ, tên lớp lỗi.
**Không** log nội dung tin, verdict, message của exception (có thể chứa URL tải Telegram kèm bot token).
"""

from __future__ import annotations

import json
import logging
import os
import pathlib
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from typing import Any

from pydantic import ValidationError

from askfirst.domain import models as m
from askfirst.pipeline import build_pipeline
from askfirst.pipeline.stub import bundle_from_items

log = logging.getLogger("askfirst.handler")
log.setLevel(logging.INFO)

OPS = ("analyze", "render_guardian_alerts", "render")
CACHE_TTL_S = 24 * 3600

BundleBuilder = Callable[[list[m.InboundMessage], m.SessionContext | None, m.Flags], m.Bundle]


class MediaDownloadError(Exception):
    """`media/telegram.py` (AI2) raise lỗi này khi getFile/tải file lỗi. Message KHÔNG được chứa URL hay token."""


class _Fail(Exception):
    def __init__(self, kind: str, code: str, cause: str | None = None) -> None:
        super().__init__(code)
        self.kind, self.code, self.cause = kind, code, cause


@dataclass
class Runtime:
    pipeline: m.Pipeline
    build_bundle: BundleBuilder
    cache: m.KVCache | None          # CACHE#; None = không cache


class _NoArtifacts:
    def path(self, name: str) -> pathlib.Path:
        raise RuntimeError("StubPipeline không dùng artifact")


class _NoKV:
    def get(self, key: str) -> dict | None:
        return None

    def put(self, key: str, value: dict, ttl_s: int) -> None:
        pass


def _stub_bundle(items: list[m.InboundMessage], session: m.SessionContext | None, flags: m.Flags) -> m.Bundle:
    return bundle_from_items(items, session)   # stub không tải media (plan §3.5)


def init_runtime(env: Mapping[str, str] = os.environ) -> Runtime:
    """Gọi MỘT lần mỗi cold start. Stub không cache vì `fingerprint` của stub là hằng số."""
    impl = env.get("PIPELINE_IMPL", "stub")
    config_dir = pathlib.Path(env.get("CONFIG_DIR", "/var/task/config"))
    if impl == "stub":
        return Runtime(build_pipeline("stub", config_dir, _NoArtifacts(), _NoKV()), _stub_bundle, None)
    # INT-01: RealPipeline cần media/telegram.build_bundle + S3ArtifactStore (AI2) và bảng DynamoDB (BE-03):
    # build_pipeline("real", config_dir, S3ArtifactStore(...), DynamoKV(table, "URL#", "INTEL")),
    # cache=DynamoKV(table, "CACHE#", "VERDICT").
    return Runtime(build_pipeline(impl, config_dir, _NoArtifacts(), _NoKV()), _stub_bundle, None)


_RUNTIME: Runtime | None = None


def _runtime() -> Runtime:
    global _RUNTIME
    if _RUNTIME is None:
        try:
            _RUNTIME = init_runtime()
        except (NotImplementedError, ValueError, KeyError) as e:
            raise _Fail("fatal", "init", type(e).__name__) from None
        except Exception as e:  # noqa: BLE001
            raise _Fail("retryable", "init", type(e).__name__) from None
    return _RUNTIME


def _validate(model: type[m._M], event: dict) -> Any:
    try:
        return model.model_validate(event)
    except ValidationError:
        raise _Fail("fatal", "bad_request") from None


def _cache_get(cache: m.KVCache, key: str) -> m.Verdict | None:
    try:
        raw = cache.get(key)
        return m.Verdict.model_validate(raw) if raw else None
    except Exception:  # noqa: BLE001 — cache hỏng/không đọc được thì coi như miss
        return None


def _analyze(event: dict, rt: Runtime, info: dict) -> m.AiResponse:
    req: m.AnalyzeRequest = _validate(m.AnalyzeRequest, event)
    try:
        bundle = rt.build_bundle(req.items, req.session, req.ctx.flags)
    except MediaDownloadError:
        raise _Fail("retryable", "telegram_download") from None

    key = None
    if rt.cache is not None and req.session is None:
        try:
            key = rt.pipeline.fingerprint(bundle)
        except Exception:  # noqa: BLE001
            key = None
        hit = _cache_get(rt.cache, key) if key else None
        if hit is not None:
            info["cache_hit"] = True
            return m.AiResponse(op="analyze", analyze=m.AnalyzeResult(verdict=hit.model_copy(update={"path": "cache"}), cache_hit=True))

    try:
        verdict = rt.pipeline.analyze(bundle, req.ctx)
    except Exception as e:  # noqa: BLE001
        if getattr(e, "retryable", False):
            raise _Fail("retryable", "llm_throttled", type(e).__name__) from None
        raise _Fail("fatal", "pipeline_error", type(e).__name__) from None
    if req.session is not None and req.session.turn >= 2 and verdict.followup_question:
        verdict = verdict.model_copy(update={"followup_question": None})   # lượt 2 không hỏi thêm (ARCH §6.12)

    if key and verdict.path != "rules_only":
        try:
            rt.cache.put(key, verdict.for_cache().model_dump(mode="json"), CACHE_TTL_S)
        except Exception:  # noqa: BLE001 — ghi cache lỗi không làm hỏng câu trả lời
            info["cache_put_failed"] = True
    info["cache_hit"] = False
    return m.AiResponse(op="analyze", analyze=m.AnalyzeResult(verdict=verdict, cache_hit=False))


def _render_guardian_alerts(event: dict, rt: Runtime, info: dict) -> m.AiResponse:
    req: m.RenderGuardianAlertsRequest = _validate(m.RenderGuardianAlertsRequest, event)
    try:
        alerts = [rt.pipeline.render_guardian_alert(req.verdict, g) for g in req.guardians]
    except Exception as e:  # noqa: BLE001
        raise _Fail("fatal", "render_failed", type(e).__name__) from None
    return m.AiResponse(op="render_guardian_alerts", render_guardian_alerts=m.RenderGuardianAlertsResult(alerts=alerts))


def _render(event: dict, rt: Runtime, info: dict) -> m.AiResponse:
    req: m.RenderRequest = _validate(m.RenderRequest, event)
    try:
        plan = rt.pipeline.render(req.verdict, req.ctx)
    except Exception as e:  # noqa: BLE001
        raise _Fail("fatal", "render_failed", type(e).__name__) from None
    return m.AiResponse(op="render", render=plan)


_HANDLERS = {"analyze": _analyze, "render_guardian_alerts": _render_guardian_alerts, "render": _render}


def _trace_id(event: Any) -> str | None:
    ctx = event.get("ctx") if isinstance(event, dict) else None
    tid = ctx.get("check_id") if isinstance(ctx, dict) else None
    return tid[:64] if isinstance(tid, str) else None


def lambda_handler(event: Any, context: Any = None, runtime: Runtime | None = None) -> dict:
    """`runtime` chỉ để test truyền vào; Lambda dùng runtime khởi tạo một lần mỗi cold start."""
    started = time.perf_counter()
    op = event.get("op") if isinstance(event, dict) else None
    resp_op = op if op in OPS else "analyze"     # AiResponse.op bắt buộc là 1 trong 3 op
    info: dict[str, Any] = {"trace_id": _trace_id(event), "op": op if op in OPS else None}
    try:
        if not isinstance(event, dict):
            raise _Fail("fatal", "bad_request")
        if event.get("v") != m.CONTRACT_VERSION:
            raise _Fail("fatal", "unsupported_version")
        if op not in OPS:
            raise _Fail("fatal", "unknown_op")
        resp = _HANDLERS[op](event, runtime or _runtime(), info)
        info["result"] = "ok"
    except _Fail as f:
        resp = m.AiResponse(op=resp_op, error=m.AiError(kind=f.kind, code=f.code))
        info |= {"result": f"{f.kind}/{f.code}", "cause": f.cause}
    except Exception as e:  # noqa: BLE001 — lưới cuối: không bao giờ raise ra Lambda
        resp = m.AiResponse(op=resp_op, error=m.AiError(kind="fatal", code="internal"))
        info |= {"result": "fatal/internal", "cause": type(e).__name__}
    info["ms"] = round((time.perf_counter() - started) * 1000)
    log.info(json.dumps(info))
    return resp.model_dump(mode="json")
