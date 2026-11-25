"""Làm nóng Lambda trước demo (BE-10 #88): gọi thẳng từng function, kh qua Telegram, kh tốn Bedrock.

Chạy từ gốc repo, khoảng 5 phút trước demo (container nguội lại sau vài phút kh có tin):
    AWS_PROFILE=askfirst-team uv run --with "boto3[crt]" scripts/warmup.py          # 3 container mỗi function
    AWS_PROFILE=askfirst-team uv run --with "boto3[crt]" scripts/warmup.py -n 10    # 10 người gửi cùng lúc

(`[crt]` cần khi profile đăng nhập bằng `aws login`.)

Mỗi function nhận một event kh làm gì cả:
- webhook: event kh có secret header nên trả 401, kh ghi DynamoDB, kh enqueue.
- worker (alias `live`, alias mà SQS gọi): SQS event kh có record.
- ai (alias `live`, alias mà worker gọi): op lạ nên trả `unknown_op`, nma container đã chạy xong INIT (nạp RAG, OCR, VietOCR).
Gọi song song n lần để Lambda dựng n container. Cột `init` có số nghĩa là lần đó gặp cold start.
Thoát mã 1 nếu có lần gọi lỗi (gọi kh được, hoặc function raise).
"""
from __future__ import annotations

import argparse
import base64
import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import boto3
from botocore.config import Config

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# (tên function theo stage, alias, event)
TARGETS = [
    ("askfirst-{stage}-webhook", None, {}),
    ("askfirst-{stage}-worker", "live", {"Records": []}),
    ("askfirst-{stage}-ai", "live", {"op": "warmup"}),
]
INIT_RE = re.compile(r"Init Duration: ([\d.]+) ms")


def init_ms(log_tail_b64: str | None) -> float | None:
    """Lấy Init Duration từ dòng REPORT trong log tail; None nghĩa là container ấm sẵn."""
    m = INIT_RE.search(base64.b64decode(log_tail_b64 or "").decode("utf-8", "replace"))
    return float(m.group(1)) if m else None


def invoke(client, name: str, alias: str | None, event: dict) -> dict:
    kwargs = {"FunctionName": name, "Payload": json.dumps(event).encode(), "LogType": "Tail"}
    if alias:
        kwargs["Qualifier"] = alias
    started = time.perf_counter()
    try:
        r = client.invoke(**kwargs)
    except Exception as e:  # noqa: BLE001 — in lỗi rồi đi tiếp các lần gọi khác
        return {"fn": name, "ok": False, "ms": None, "init": None, "err": type(e).__name__}
    ms = round((time.perf_counter() - started) * 1000)
    err = r.get("FunctionError")
    return {"fn": name, "ok": r["StatusCode"] == 200 and not err, "ms": ms, "init": init_ms(r.get("LogResult")), "err": err}


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("-n", type=int, default=3, help="số lần gọi song song mỗi function (mặc định 3)")
    p.add_argument("--stage", default="dev")
    p.add_argument("--region", default="ap-southeast-1")
    a = p.parse_args(argv)

    # ai cold start có lần chạm mốc init 10 s rồi chạy lại (~24 s), nên timeout đọc để dư.
    client = boto3.client("lambda", region_name=a.region,
                          config=Config(read_timeout=90, retries={"max_attempts": 1}, max_pool_connections=3 * a.n))
    jobs = [(t.format(stage=a.stage), alias, ev) for t, alias, ev in TARGETS for _ in range(a.n)]
    with ThreadPoolExecutor(max_workers=len(jobs)) as pool:
        results = list(pool.map(lambda j: invoke(client, *j), jobs))

    for r in results:
        init = f"{r['init'] / 1000:.1f} s" if r["init"] is not None else "-"
        ms = f"{r['ms'] / 1000:.1f} s" if r["ms"] is not None else "-"
        print(f"{'ok ' if r['ok'] else 'LỖI'}  {r['fn']:<24} {ms:>7}  init {init:>7}  {r['err'] or ''}")
    bad = sum(not r["ok"] for r in results)
    print(f"{len(results) - bad}/{len(results)} lần gọi ok")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
