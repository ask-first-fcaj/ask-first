"""Chấm chất lượng lời giải thích bằng LLM-as-judge (AI-11). Chạy từ thư mục ai/:

    AWS_PROFILE=<profile> OPENAI_API_KEY=... uv run python ../eval/run_judge_eval.py
    AWS_PROFILE=<profile> OPENAI_API_KEY=... uv run python ../eval/run_judge_eval.py --limit 5 --report ""
    uv run python ../eval/run_judge_eval.py --from ../eval/out/judge/scores.jsonl --human ../eval/human/ai11_scores.csv

Mỗi mẫu có nhãn của bộ eval chạy qua RealPipeline thật (judge Qwen3 32B trên Bedrock). Câu trả lời bot gửi được render như
người dùng thấy (xưng "bác", có người thân). Giám khảo là một model OpenAI khác họ (mặc định ``gpt-5.5-2026-04-23``,
pin snapshot để chạy lại ra cùng model). Giám khảo chấm 1–5 theo 4 tiêu chí trong ``RUBRIC``. Tiêu chí 4 còn được đo cứng:
tin kết luận ≤ 600 ký tự hiển thị.

Key OpenAI chỉ đọc từ biến môi trường ``OPENAI_API_KEY``, không ghi ra file hay log. Chỉ chạy trên bộ eval tự soạn
(``eval/datasets``), không gửi tin của người dùng thật ra API bên ngoài.

Ghi vào --out (mặc định eval/out/judge/, không commit): ``scores.jsonl`` (điểm từng mẫu). ``--report`` ghi báo cáo markdown
(mặc định ``eval/report.md``). ``--sheet`` ghi phiếu chấm tay 20 mẫu (ẩn điểm của LLM). ``--human`` đọc CSV điểm chấm tay
(cột ``id,grader,c1,c2,c3,c4``) rồi so với điểm LLM.
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import os
import pathlib
import random
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from typing import Any

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import run_eval  # noqa: E402

from askfirst.domain import models as m  # noqa: E402
from askfirst.pipeline import build_pipeline  # noqa: E402
from askfirst.pipeline.respond import CONCLUSION_MAX, visible_len  # noqa: E402

DEFAULT_MODEL = "gpt-5.5-2026-04-23"
OPENAI_URL = "https://api.openai.com/v1/chat/completions"
CRITERIA = {
    "c1": "Đúng trọng tâm",
    "c2": "Người 70 tuổi hiểu ngay",
    "c3": "Hướng dẫn hành động rõ",
    "c4": "Ngắn gọn",
}
PASS = 4  # điểm ≥ 4 tính là đạt
HUMAN_N, HUMAN_SEED = 20, 11

RUBRIC = """Bạn là giám khảo chấm câu trả lời của một bot Telegram giúp người cao tuổi Việt Nam kiểm tra tin nhắn có phải lừa đảo không.
Bạn nhận: tin nhắn gốc, nhãn đúng (lừa đảo hay không, mã kịch bản), mức bot kết luận và toàn bộ câu trả lời bot gửi.
Chấm từng tiêu chí 1–5 (5 tốt nhất). Chấm khắt khe: 5 chỉ khi không có gì để chê.

c1 Đúng trọng tâm: câu trả lời nói đúng kịch bản và đúng dấu hiệu chính của tin này (vd giả công an, xin OTP, link giả),
   không nói chung chung, không gán sai kịch bản. Tin lành tính: nói rõ vì sao an toàn, không doạ vô cớ.
   1 = sai trọng tâm hoặc kết luận ngược nhãn; 3 = đúng hướng nhưng chung chung; 5 = chỉ ra đúng chiêu và dấu hiệu.
c2 Người 70 tuổi đọc hiểu ngay: câu ngắn, từ thường ngày, không thuật ngữ kỹ thuật hay tiếng Anh không giải thích
   (vd "phishing", "domain", "malware"), giọng tôn trọng.
   1 = khó hiểu, nhiều thuật ngữ; 3 = hiểu được nhưng phải đọc lại; 5 = đọc một lần là hiểu.
c3 Hướng dẫn hành động cụ thể, dứt khoát: nói rõ việc nên làm hoặc không làm ngay (vd không chuyển tiền, không đọc OTP,
   gọi số chính thức, hỏi con cháu). Tin lành tính: không bắt buộc có cảnh báo, nhưng nếu khuyên thì phải hợp lý.
   1 = không có hoặc mơ hồ; 3 = có nhưng chưa dứt khoát; 5 = rõ ràng, làm theo được ngay.
c4 Ngắn gọn, súc tích: tin đầu tiên không quá 600 ký tự, không lặp ý, không lan man.
   1 = dài, lặp nhiều; 3 = hơi dài; 5 = gọn, đủ ý.

Trả JSON đúng schema. Mỗi "reason" là một câu tiếng Việt ngắn, nêu điểm trừ chính (không trích lại cả câu trả lời)."""

SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": list(CRITERIA),
    "properties": {
        c: {"type": "object", "additionalProperties": False, "required": ["score", "reason"],
            "properties": {"score": {"type": "integer", "enum": [1, 2, 3, 4, 5]}, "reason": {"type": "string"}}}
        for c in CRITERIA
    },
}

_TAG = re.compile(r"<[^>]+>")


def plain(text_html: str) -> str:
    """HTML Telegram → chữ người đọc thấy."""
    return html.unescape(_TAG.sub("", text_html)).strip()


def reply_messages(pipeline: m.Pipeline, verdict: m.Verdict) -> list[str]:
    ctx = m.RenderContext(check_id="eval", address_form="elder", has_guardian=True)
    return [plain(msg.text_html) for msg in pipeline.render(verdict, ctx).messages]


def build_items(pipeline: m.Pipeline, samples: list[dict], results: list[dict]) -> list[dict]:
    """Ghép mẫu + verdict + câu trả lời đã render. Bỏ mẫu lỗi và mẫu không ra verdict (smalltalk, không đọc được)."""
    by_id = {s["id"]: s for s in samples}
    items = []
    for res in results:
        if res["error"] or not res["verdict"] or res["verdict"]["outcome"] != "verdict":
            continue
        verdict = m.Verdict.model_validate(res["verdict"])
        s = by_id[res["id"]]
        msgs = reply_messages(pipeline, verdict)
        items.append({
            "id": s["id"], "split": s["split"], "text": s["input"]["text"],
            "label": {k: s["label"].get(k) for k in ("is_scam", "group", "scenario")},
            "level": verdict.level, "scenario": verdict.scenario, "path": verdict.path,
            "explanation": verdict.explanation, "reply": msgs,
            "first_len": visible_len(msgs[0]) if msgs else 0,
        })
    return items


def judge_messages(item: dict) -> list[dict]:
    label = item["label"]
    body = "\n".join([
        f"Tin nhắn gốc:\n{item['text']}",
        "",
        f"Nhãn đúng: {'lừa đảo' if label['is_scam'] else 'lành tính'}, kịch bản {label['scenario']}",
        f"Bot kết luận: mức {item['level']}, kịch bản {item['scenario']}",
        "",
        "Câu trả lời bot gửi (mỗi đoạn --- là một tin Telegram):",
        "\n---\n".join(item["reply"]),
    ])
    return [{"role": "system", "content": RUBRIC}, {"role": "user", "content": body}]


Transport = Callable[[dict, float], dict]


def urllib_transport(api_key: str) -> Transport:
    def send(payload: dict, timeout: float) -> dict:
        req = urllib.request.Request(OPENAI_URL, data=json.dumps(payload).encode(), method="POST",
                                     headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.load(resp)
    return send


class JudgeError(Exception):
    pass


class OpenAIJudge:
    def __init__(self, transport: Transport, model: str = DEFAULT_MODEL, reasoning_effort: str | None = "low",
                 timeout_s: float = 60, max_attempts: int = 3, sleep: Callable[[float], None] = time.sleep):
        self.transport, self.model, self.reasoning_effort = transport, model, reasoning_effort
        self.timeout_s, self.max_attempts, self.sleep = timeout_s, max_attempts, sleep

    def payload(self, item: dict) -> dict:
        p = {"model": self.model, "messages": judge_messages(item),
             "response_format": {"type": "json_schema", "json_schema": {"name": "rubric", "strict": True, "schema": SCHEMA}}}
        if self.reasoning_effort:
            p["reasoning_effort"] = self.reasoning_effort
        return p

    def score(self, item: dict) -> dict:
        last = ""
        for attempt in range(self.max_attempts):
            try:
                resp = self.transport(self.payload(item), self.timeout_s)
                return parse_scores(resp)
            except urllib.error.HTTPError as e:
                last = f"HTTP {e.code}"
                if e.code not in (408, 409, 429) and e.code < 500:
                    break
            except (urllib.error.URLError, TimeoutError) as e:
                last = type(e).__name__
            except JudgeError as e:
                last = str(e)
            if attempt + 1 < self.max_attempts:
                self.sleep(2 ** attempt)
        raise JudgeError(last or "judge failed")


def parse_scores(resp: dict) -> dict:
    try:
        content = resp["choices"][0]["message"]["content"]
        data = json.loads(content)
        scores = {c: int(data[c]["score"]) for c in CRITERIA}
        reasons = {c: str(data[c]["reason"]) for c in CRITERIA}
    except (KeyError, IndexError, TypeError, ValueError) as e:
        raise JudgeError(f"bad judge response: {type(e).__name__}") from e
    if not all(1 <= v <= 5 for v in scores.values()):
        raise JudgeError("score out of range")
    usage = resp.get("usage") or {}
    return {"scores": scores, "reasons": reasons,
            "tokens": {"in": usage.get("prompt_tokens", 0), "out": usage.get("completion_tokens", 0)}}


def judge_all(judge: OpenAIJudge, items: list[dict], workers: int = 4) -> list[dict]:
    def one(item: dict) -> dict:
        try:
            return {**item, **judge.score(item), "judge_model": judge.model, "judge_error": None}
        except JudgeError as e:
            return {**item, "scores": None, "reasons": None, "tokens": {"in": 0, "out": 0}, "judge_model": judge.model,
                    "judge_error": str(e)}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        return list(pool.map(one, items))


def _mean(xs: list[float]) -> float | None:
    return round(statistics.mean(xs), 2) if xs else None


def aggregate(rows: list[dict]) -> dict:
    ok = [r for r in rows if r["scores"]]
    groups = {"tất cả": ok, "lừa đảo": [r for r in ok if r["label"]["is_scam"]],
              "lành tính": [r for r in ok if not r["label"]["is_scam"]]}
    out: dict[str, Any] = {"n": len(rows), "scored": len(ok), "errors": len(rows) - len(ok), "groups": {}}
    for name, rs in groups.items():
        out["groups"][name] = {
            "n": len(rs),
            "mean": {c: _mean([r["scores"][c] for r in rs]) for c in CRITERIA},
            "pass_rate": {c: run_eval._rate(sum(r["scores"][c] >= PASS for r in rs), len(rs)) for c in CRITERIA},
        }
    out["first_len_ok"] = run_eval._rate(sum(r["first_len"] <= CONCLUSION_MAX for r in rows), len(rows))
    out["first_len_p95"] = run_eval.percentile([r["first_len"] for r in rows], 95)
    out["tokens"] = {k: sum(r["tokens"][k] for r in rows) for k in ("in", "out")}
    return out


def worst(rows: list[dict], k: int = 5) -> list[dict]:
    """k mẫu tổng điểm thấp nhất, để đọc lý do bị trừ."""
    ok = [r for r in rows if r["scores"]]
    return sorted(ok, key=lambda r: (sum(r["scores"].values()), r["id"]))[:k]


def pick_human(rows: list[dict], n: int = HUMAN_N, seed: int = HUMAN_SEED) -> list[dict]:
    ok = sorted((r for r in rows if r["scores"]), key=lambda r: r["id"])
    return sorted(random.Random(seed).sample(ok, min(n, len(ok))), key=lambda r: r["id"])


def human_sheet(rows: list[dict], n: int = HUMAN_N, seed: int = HUMAN_SEED) -> str:
    """Phiếu chấm tay: không có điểm của LLM để người chấm không bị ảnh hưởng."""
    lines = ["# Chấm tay lời giải thích (AI-11)", "",
             f"{n} mẫu ngẫu nhiên (seed {seed}) trong lượt chấm LLM. Mỗi người chấm độc lập, 1–5 cho từng tiêu chí, ",
             "rồi ghi vào `eval/human/ai11_scores.csv` (cột `id,grader,c1,c2,c3,c4`, `grader` là mã người chấm như AI1, AI2).", "",
             "Tiêu chí (đọc đủ ở `eval/run_judge_eval.py`, biến `RUBRIC`):", ""]
    lines += [f"- **{c}** {name}" for c, name in CRITERIA.items()]
    lines.append("")
    for r in pick_human(rows, n, seed):
        lines += [f"## {r['id']}", "", "Tin gốc:", "", *[f"> {ln}" for ln in r["text"].splitlines()], "",
                  f"Nhãn: {'lừa đảo' if r['label']['is_scam'] else 'lành tính'} ({r['label']['scenario']}). "
                  f"Bot: {r['level']} ({r['scenario']}).", "", "Bot trả lời:", ""]
        for msg in r["reply"]:
            lines += ["```text", msg, "```", ""]
    return "\n".join(lines)


def load_human(path: pathlib.Path) -> list[dict]:
    with path.open(encoding="utf-8", newline="") as f:
        rows = [r for r in csv.DictReader(f) if r.get("id")]
    return [{"id": r["id"], "grader": r["grader"], **{c: int(r[c]) for c in CRITERIA}} for r in rows if all(r.get(c) for c in CRITERIA)]


def compare(rows: list[dict], human: list[dict]) -> dict:
    """So điểm LLM với trung bình điểm người chấm trên cùng mẫu: sai lệch tuyệt đối trung bình và tỉ lệ lệch ≤ 1 điểm."""
    llm = {r["id"]: r["scores"] for r in rows if r["scores"]}
    by_id: dict[str, list[dict]] = {}
    for h in human:
        if h["id"] in llm:
            by_id.setdefault(h["id"], []).append(h)
    out: dict[str, Any] = {"samples": len(by_id), "graders": len({h["grader"] for h in human}), "criteria": {}}
    for c in CRITERIA:
        diffs = [abs(llm[i][c] - statistics.mean(h[c] for h in hs)) for i, hs in by_id.items()]
        out["criteria"][c] = {
            "human_mean": _mean([statistics.mean(h[c] for h in hs) for hs in by_id.values()]),
            "llm_mean": _mean([llm[i][c] for i in by_id]),
            "mae": _mean(diffs), "within_1": run_eval._rate(sum(d <= 1 for d in diffs), len(diffs)),
        }
    return out


def _num(x: float | None) -> str:
    return "n/a" if x is None else f"{x:.2f}"


def render_report(agg: dict, rows: list[dict], model: str, cmp: dict | None) -> str:
    lines = ["# Báo cáo chất lượng lời giải thích (AI-11)", "",
             f"- Giám khảo: OpenAI `{model}`. Bot: RealPipeline thật (judge Qwen3 32B, `ai/config/models.yaml`).",
             "- Bộ dữ liệu: mẫu có nhãn của eval v0 (`eval/datasets/*.jsonl`), dữ liệu tự soạn.",
             f"- Mẫu chấm được: {agg['scored']}/{agg['n']} (lỗi gọi giám khảo: {agg['errors']}).",
             f"- Thang 1–5, đạt = điểm ≥ {PASS}. Tiêu chí đầy đủ: biến `RUBRIC` trong `eval/run_judge_eval.py`.", "",
             "## Điểm trung bình (tỉ lệ đạt)", "",
             "| Nhóm | Mẫu | " + " | ".join(CRITERIA.values()) + " |",
             "|---|---|" + "---|" * len(CRITERIA)]
    for name, g in agg["groups"].items():
        cells = [f"{_num(g['mean'][c])} ({run_eval._pct(g['pass_rate'][c])})" for c in CRITERIA]
        lines.append(f"| {name} | {g['n']} | " + " | ".join(cells) + " |")
    lines += ["", f"Đo cứng tiêu chí 4: tin kết luận ≤ {CONCLUSION_MAX} ký tự hiển thị ở {run_eval._pct(agg['first_len_ok'])} số mẫu "
              f"(p95 {agg['first_len_p95']} ký tự).", "",
              f"Token giám khảo: {agg['tokens']['in']} vào, {agg['tokens']['out']} ra.", "",
              "## 5 mẫu điểm thấp nhất", "", "| Mẫu | Mức bot | " + " | ".join(CRITERIA) + " | Lý do chính |",
              "|---|---|" + "---|" * len(CRITERIA) + "---|"]
    for r in worst(rows):
        low = min(CRITERIA, key=lambda c: r["scores"][c])
        lines.append(f"| {r['id']} | {r['level']} | " + " | ".join(str(r["scores"][c]) for c in CRITERIA)
                     + f" | {low}: {r['reasons'][low]} |")
    lines += ["", "## Chấm tay so với LLM", ""]
    if cmp and cmp["samples"]:
        lines += [f"{cmp['samples']} mẫu, {cmp['graders']} người chấm. Sai lệch = |điểm LLM − trung bình điểm người|.", "",
                  "| Tiêu chí | Người (TB) | LLM (TB) | Sai lệch TB | Lệch ≤ 1 điểm |", "|---|---|---|---|---|"]
        for c, v in cmp["criteria"].items():
            lines.append(f"| {CRITERIA[c]} | {_num(v['human_mean'])} | {_num(v['llm_mean'])} | {_num(v['mae'])} | "
                         f"{run_eval._pct(v['within_1'])} |")
    else:
        lines.append("Chưa có điểm chấm tay. Phiếu ở `docs/results/ai11-human-grading.md`, ghi điểm vào "
                     "`eval/human/ai11_scores.csv` rồi chạy lại với `--from ... --human ...` (không gọi API).")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--model", default=DEFAULT_MODEL, help="model OpenAI làm giám khảo")
    ap.add_argument("--reasoning-effort", default="low", help="'' để không gửi tham số này")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--out", type=pathlib.Path, default=run_eval.EVAL_DIR / "out" / "judge")
    ap.add_argument("--from", dest="from_", type=pathlib.Path, help="scores.jsonl có sẵn: bỏ qua bước chạy bot và gọi giám khảo")
    ap.add_argument("--report", default=str(run_eval.EVAL_DIR / "report.md"), help="'' để không ghi")
    ap.add_argument("--sheet", default=str(run_eval.REPO / "docs" / "results" / "ai11-human-grading.md"), help="'' để không ghi")
    ap.add_argument("--human", type=pathlib.Path, help="CSV điểm chấm tay")
    args = ap.parse_args(argv)

    if args.from_:
        rows = [json.loads(ln) for ln in args.from_.read_text(encoding="utf-8").splitlines() if ln.strip()]
    else:
        key = os.environ.get("OPENAI_API_KEY")
        if not key:
            print("thiếu OPENAI_API_KEY", file=sys.stderr)
            return 2
        samples = [s for s in run_eval.load_samples(sorted(run_eval.DATASETS.glob("*.jsonl")), limit=None)
                   if s["label"].get("is_scam") is not None]
        samples = samples[: args.limit] if args.limit else samples
        pipeline = build_pipeline("real", run_eval.CONFIG_DIR, run_eval.LocalArtifacts(run_eval.REPO / "ai" / "artifacts"),
                                  run_eval.MemoryKV())
        items = build_items(pipeline, samples, run_eval.run(pipeline, samples, "real"))
        judge = OpenAIJudge(urllib_transport(key), args.model, args.reasoning_effort or None)
        rows = judge_all(judge, items, args.workers)
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / "scores.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")

    agg = aggregate(rows)
    cmp = compare(rows, load_human(args.human)) if args.human else None
    model = next((r["judge_model"] for r in rows if r.get("judge_model")), args.model)
    report = render_report(agg, rows, model, cmp)
    if args.report:
        pathlib.Path(args.report).write_text(report, encoding="utf-8")
    if args.sheet and not args.from_:
        pathlib.Path(args.sheet).write_text(human_sheet(rows), encoding="utf-8")
    sys.stdout.write(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
