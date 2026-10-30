"""AI-02 spike: answers the 4 questions of ADR-001 against the real Bedrock model in config/models.yaml (judge).

Needs AWS credentials with Bedrock access in ap-southeast-2 (BE-05) and judge.model_id filled in from the console.
Usage: uv run python scripts/spike_bedrock.py [--runs 20] [--role judge]
Uses a synthetic prompt only (no user data). Prints a JSON report; paste the numbers into docs/decisions/ADR-001-bedrock-qwen3.md."""
import argparse
import json
import statistics

from askfirst.llm.bedrock import BedrockClient, LLMError

SYSTEM = "You judge whether a Vietnamese message is a scam. Answer ONLY with JSON: {\"level\": \"low|medium|high\", \"reason\": \"<short>\"}."
PROMPT = "Bạn đã vi phạm giao thông, nộp phạt nguội ngay tại http://phat-nguoi-gov.example/pay để tránh bị truy thu."
MSG = [{"role": "user", "content": [{"text": PROMPT}]}]
# Candidate ways to switch thinking off; which (if any) works is exactly what the spike must find out.
THINKING_VARIANTS = {
    "default": (None, ""),
    "no_think_suffix": (None, " /no_think"),
    "enable_thinking_false": ({"enable_thinking": False}, ""),
    "chat_template_kwargs": ({"chat_template_kwargs": {"enable_thinking": False}}, ""),
}


def valid_json(text: str) -> bool:
    try:
        d = json.loads(text.strip())
    except ValueError:
        return False
    return isinstance(d, dict) and d.get("level") in {"low", "medium", "high"} and isinstance(d.get("reason"), str)


def pct(xs: list[float], q: float) -> float:
    xs = sorted(xs)
    return xs[min(len(xs) - 1, round(q * (len(xs) - 1)))]


def call(c: BedrockClient, role: str, extra, suffix: str):
    msg = [{"role": "user", "content": [{"text": PROMPT + suffix}]}]
    return c.converse(role, msg, system=SYSTEM, extra_fields=extra)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=20)
    ap.add_argument("--role", default="judge")
    a = ap.parse_args()
    c = BedrockClient.from_file()
    report: dict = {"model_id": c.config[a.role]["model_id"], "region": c.config["bedrock_region"]}

    # Q1 + Q3: Converse works? which variant yields fewer output tokens / valid JSON?
    variants = {}
    for name, (extra, suffix) in THINKING_VARIANTS.items():
        try:
            r = call(c, a.role, extra, suffix)
            variants[name] = {"ok": True, "output_tokens": r.output_tokens, "latency_ms": r.latency_ms, "json_valid": valid_json(r.text)}
        except LLMError as e:
            variants[name] = {"ok": False, "error": str(e)}
    report["q1_q3_variants"] = variants

    # Q2: structured output via Converse toolConfig (forced tool) vs prompt-only JSON rate.
    tool = {"tools": [{"toolSpec": {"name": "verdict", "inputSchema": {"json": {
        "type": "object", "properties": {"level": {"enum": ["low", "medium", "high"]}, "reason": {"type": "string"}},
        "required": ["level", "reason"]}}}}], "toolChoice": {"tool": {"name": "verdict"}}}
    try:
        c._client(a.role).converse(modelId=c.config[a.role]["model_id"], messages=MSG, system=[{"text": SYSTEM}], toolConfig=tool)
        report["q2_tool_config"] = "accepted"
    except Exception as e:  # noqa: BLE001 - spike: record whatever the API says
        report["q2_tool_config"] = f"rejected: {type(e).__name__}"
    ok = 0
    for _ in range(a.runs):
        try:
            ok += valid_json(call(c, a.role, None, "").text)
        except LLMError:
            pass
    report["q2_prompt_only_json_valid"] = f"{ok}/{a.runs}"

    # Q4: latency with default config.
    lat = []
    for _ in range(a.runs):
        try:
            lat.append(call(c, a.role, None, "").latency_ms / 1000)
        except LLMError:
            pass
    if lat:
        report["q4_latency_s"] = {"n": len(lat), "p50": statistics.median(lat), "p95": pct(lat, 0.95)}
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
