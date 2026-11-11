"""AI-02 spike: answers the 4 questions of ADR-001 against the real Bedrock model in config/models.yaml.

Needs AWS credentials with Bedrock access in ap-southeast-2 and `<role>.model_id` filled in models.yaml.
Usage: AWS_PROFILE=<profile> uv run python scripts/spike_bedrock.py [--runs 30] [--per-variant 5] [--role judge]

Uses the real judge system prompt (prompts/judge_v1.md) with SYNTHETIC messages only (no user data, no eval set).
Prints a JSON report of counts/tokens/latency only; never prints prompt or response text (plan §3.1).
Paste the numbers into docs/decisions/ADR-001-bedrock-qwen3.md."""
import argparse
import json
import statistics
import time
from typing import Any

from askfirst.llm.bedrock import BedrockClient
from askfirst.pipeline import judge as J

# Synthetic, hand-written messages (not from users or the eval set). Mix of scam and benign-but-suspicious.
MESSAGES = [
    "Lỗi phạt nguội của bạn chưa xử lý, nộp phạt ngay tại dichvucong-vn[.]com để tránh bị truy thu.",
    "Con đây, con bị tai nạn đang ở viện, mẹ chuyển gấp 20 triệu vào [STK_1] giúp con, đừng gọi lại con đang mổ.",
    "Vietcombank: Ma OTP cua quy khach la [OTP_1]. Tuyet doi khong cung cap ma nay cho bat ky ai.",
    "Chúc mừng quý khách trúng thưởng xe SH, liên hệ [SĐT_1] và đóng phí 2 triệu để nhận giải.",
]
# Candidate ways to switch Qwen3 thinking off. Which ones work is what the spike measures.
VARIANTS: dict[str, tuple[dict | None, str]] = {
    "default": (None, ""),
    "no_think_suffix": (None, "\n/no_think"),
    "enable_thinking_false": ({"enable_thinking": False}, ""),
    "chat_template_kwargs": ({"chat_template_kwargs": {"enable_thinking": False}}, ""),
}


def pct(xs: list[float], q: float) -> float:
    xs = sorted(xs)
    return xs[min(len(xs) - 1, round(q * (len(xs) - 1)))]


def user_msg(i: int, suffix: str = "") -> list[dict]:
    return [{"role": "user", "content": [{"text": J.build_user_message(MESSAGES[i % len(MESSAGES)]) + suffix}]}]


def raw_call(c: BedrockClient, role: str, i: int, system: str, suffix: str = "", **extra: Any) -> tuple[dict, float]:
    spec = c.config[role]
    req: dict[str, Any] = {"modelId": spec["model_id"], "messages": user_msg(i, suffix), "system": [{"text": system}],
                           "inferenceConfig": {"maxTokens": spec["max_tokens"], "temperature": spec.get("temperature", 0)}}
    req.update(extra)
    t = time.perf_counter()
    resp = c._client(role).converse(**req)
    return resp, time.perf_counter() - t


def text_of(resp: dict) -> str:
    return "".join(b["text"] for b in resp["output"]["message"]["content"] if "text" in b)


def thinking_seen(resp: dict) -> bool:
    blocks = resp["output"]["message"]["content"]
    if any("reasoningContent" in b for b in blocks):
        return True
    body = text_of(resp)
    start, end = body.find("<think>"), body.find("</think>")
    return start >= 0 and (end < 0 or body[start + 7:end].strip() != "")


def parses(text: str) -> bool:
    try:
        J.parse_judgement(text)
        return True
    except J.JudgeParseError:
        return False


def err(e: Exception) -> str:
    code = getattr(e, "response", {}).get("Error", {}).get("Code") if hasattr(e, "response") else None
    msg = getattr(e, "response", {}).get("Error", {}).get("Message", "") if hasattr(e, "response") else ""
    return f"{code or type(e).__name__}: {msg[:160]}"


def run_variant(c: BedrockClient, role: str, system: str, n: int, suffix: str = "", **extra: Any) -> dict:
    out: dict[str, Any] = {"n": n, "ok": 0, "json_valid": 0, "thinking_seen": 0, "stop_reasons": {}}
    toks, lat = [], []
    for i in range(n):
        try:
            resp, s = raw_call(c, role, i, system, suffix, **extra)
        except Exception as e:  # noqa: BLE001 - spike: record whatever the API says
            out["error"] = err(e)
            continue
        out["ok"] += 1
        out["json_valid"] += parses(text_of(resp))
        out["thinking_seen"] += thinking_seen(resp)
        sr = resp.get("stopReason", "")
        out["stop_reasons"][sr] = out["stop_reasons"].get(sr, 0) + 1
        toks.append(resp.get("usage", {}).get("outputTokens", 0))
        lat.append(s)
    if toks:
        out["output_tokens_median"] = statistics.median(toks)
        out["latency_s_median"] = round(statistics.median(lat), 2)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=30, help="calls for Q2 (prompt-only) and Q4 (latency)")
    ap.add_argument("--per-variant", type=int, default=5, help="calls per thinking variant / structured-output mode")
    ap.add_argument("--role", default="judge")
    a = ap.parse_args()
    c = BedrockClient.from_file()
    system = J.load_system_prompt()
    report: dict[str, Any] = {"model_id": c.model_id(a.role), "region": c.config["bedrock_region"],
                              "prompt_version": J.PROMPT_VERSION, "max_tokens": c.config[a.role]["max_tokens"]}

    # Q1 + Q3: does Converse work at all, and which variant switches thinking off?
    report["q1_q3_variants"] = {name: run_variant(c, a.role, system, a.per_variant, suffix,
                                                  **({"additionalModelRequestFields": ex} if ex else {}))
                                for name, (ex, suffix) in VARIANTS.items()}

    # Q2: structured output. (a) outputConfig json_schema, (b) forced tool, (c) prompt only.
    schema = J.Judgement.model_json_schema()
    q2: dict[str, Any] = {}
    q2["output_config_json_schema"] = run_variant(
        c, a.role, system, a.per_variant,
        outputConfig={"textFormat": {"type": "json_schema", "structure": {"jsonSchema": {"schema": json.dumps(schema), "name": "judgement"}}}})
    tool = {"tools": [{"toolSpec": {"name": "judgement", "inputSchema": {"json": schema}}}]}
    for choice in ("tool", "any"):
        cfg = {**tool, "toolChoice": {"tool": {"name": "judgement"}} if choice == "tool" else {"any": {}}}
        res: dict[str, Any] = {"n": a.per_variant, "ok": 0, "tool_use_valid": 0}
        for i in range(a.per_variant):
            try:
                resp, _ = raw_call(c, a.role, i, system, toolConfig=cfg)
            except Exception as e:  # noqa: BLE001
                res["error"] = err(e)
                continue
            res["ok"] += 1
            for b in resp["output"]["message"]["content"]:
                if "toolUse" in b:
                    try:
                        J.Judgement.model_validate(b["toolUse"]["input"])
                        res["tool_use_valid"] += 1
                    except Exception:  # noqa: BLE001
                        pass
        q2[f"tool_choice_{choice}"] = res
    q2["prompt_only"] = run_variant(c, a.role, system, a.runs)
    report["q2_structured_output"] = q2

    # Q4: latency end to end through BedrockClient (same path the pipeline uses), default request.
    lat, tin, tout, cost, fails = [], [], [], 0.0, 0
    for i in range(a.runs):
        try:
            r = c.converse(a.role, user_msg(i), system=system)
        except Exception:  # noqa: BLE001
            fails += 1
            continue
        lat.append(r.latency_ms / 1000)
        tin.append(r.input_tokens)
        tout.append(r.output_tokens)
        cost += r.cost_usd
    if lat:
        report["q4_latency_s"] = {"n": len(lat), "fails": fails, "p50": round(statistics.median(lat), 2),
                                  "p95": round(pct(lat, 0.95), 2), "max": round(max(lat), 2),
                                  "input_tokens_median": statistics.median(tin), "output_tokens_median": statistics.median(tout),
                                  "cost_usd_per_call_avg": round(cost / len(lat), 6)}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
