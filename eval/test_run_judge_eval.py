"""Test eval/run_judge_eval.py (AI-11). Không gọi mạng: transport OpenAI là fake. Chạy từ ai/: uv run pytest ../eval/test_run_judge_eval.py"""
import io
import json
import sys
import urllib.error
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import run_judge_eval as J  # noqa: E402


def answer(scores=(5, 4, 3, 5), tokens=(100, 20)) -> dict:
    data = {c: {"score": s, "reason": f"lý do {c}"} for c, s in zip(J.CRITERIA, scores, strict=True)}
    return {"choices": [{"message": {"content": json.dumps(data, ensure_ascii=False)}}],
            "usage": {"prompt_tokens": tokens[0], "completion_tokens": tokens[1]}}


def item(sid="s1", is_scam=True, first_len=300, **extra) -> dict:
    return {"id": sid, "split": "real_scam", "text": "Cháu đây, gửi mã OTP cho cháu",
            "label": {"is_scam": is_scam, "group": "N1", "scenario": "N1.1"},
            "level": "DANGEROUS", "scenario": "N1.1", "path": "llm", "explanation": "x",
            "reply": ["Tin này nguy hiểm", "Đừng đọc mã OTP"], "first_len": first_len, **extra}


def scored(sid, scores, is_scam=True, first_len=300) -> dict:
    return item(sid, is_scam, first_len, scores=dict(zip(J.CRITERIA, scores, strict=True)),
                reasons={c: f"r{c}" for c in J.CRITERIA}, tokens={"in": 10, "out": 2}, judge_error=None)


class Transport:
    def __init__(self, *outcomes):
        self.outcomes, self.payloads = list(outcomes), []

    def __call__(self, payload, timeout):
        self.payloads.append(payload)
        out = self.outcomes.pop(0)
        if isinstance(out, Exception):
            raise out
        return out


def http_error(code: int) -> urllib.error.HTTPError:
    return urllib.error.HTTPError("https://x", code, "err", {}, io.BytesIO(b""))


def test_plain_strips_tags_and_unescapes() -> None:
    assert J.plain("<b>Nguy hiểm</b> &amp; <i>đừng</i> bấm &lt;link&gt;") == "Nguy hiểm & đừng bấm <link>"


def test_judge_messages_contains_label_reply_and_rubric() -> None:
    msgs = J.judge_messages(item())
    assert msgs[0] == {"role": "system", "content": J.RUBRIC}
    body = msgs[1]["content"]
    assert "lừa đảo, kịch bản N1.1" in body and "Tin này nguy hiểm\n---\nĐừng đọc mã OTP" in body


def test_payload_uses_strict_schema_and_optional_reasoning() -> None:
    p = J.OpenAIJudge(Transport(), "m1").payload(item())
    assert p["model"] == "m1" and p["reasoning_effort"] == "low"
    assert p["response_format"]["json_schema"]["strict"] is True
    assert set(p["response_format"]["json_schema"]["schema"]["required"]) == set(J.CRITERIA)
    assert "reasoning_effort" not in J.OpenAIJudge(Transport(), "m1", reasoning_effort=None).payload(item())


def test_score_parses_answer() -> None:
    res = J.OpenAIJudge(Transport(answer())).score(item())
    assert res["scores"] == {"c1": 5, "c2": 4, "c3": 3, "c4": 5}
    assert res["reasons"]["c3"] == "lý do c3" and res["tokens"] == {"in": 100, "out": 20}


def test_score_retries_rate_limit_then_succeeds() -> None:
    sleeps = []
    t = Transport(http_error(429), answer())
    res = J.OpenAIJudge(t, sleep=sleeps.append).score(item())
    assert res["scores"]["c1"] == 5 and len(t.payloads) == 2 and sleeps == [1]


def test_score_does_not_retry_client_error() -> None:
    t = Transport(http_error(401), answer())
    with pytest.raises(J.JudgeError, match="HTTP 401"):
        J.OpenAIJudge(t, sleep=lambda s: None).score(item())
    assert len(t.payloads) == 1


@pytest.mark.parametrize("resp", [
    {}, {"choices": []}, {"choices": [{"message": {"content": "không phải json"}}]},
    {"choices": [{"message": {"content": json.dumps({"c1": {"score": 5, "reason": ""}})}}]},
    answer(scores=(6, 4, 3, 5)),
])
def test_parse_scores_rejects_bad_answers(resp) -> None:
    with pytest.raises(J.JudgeError):
        J.parse_scores(resp)


def test_judge_all_keeps_failed_items_with_error() -> None:
    judge = J.OpenAIJudge(Transport(answer(), http_error(400)), "m1", sleep=lambda s: None)
    rows = J.judge_all(judge, [item("a"), item("b")], workers=1)
    assert rows[0]["scores"]["c1"] == 5 and rows[0]["judge_model"] == "m1"
    assert rows[1]["scores"] is None and rows[1]["judge_error"] == "HTTP 400"


def test_aggregate_groups_pass_rate_and_length() -> None:
    rows = [scored("a", (5, 4, 4, 5)), scored("b", (3, 2, 4, 5)), scored("c", (4, 5, 2, 4), is_scam=False, first_len=700),
            {**item("d"), "scores": None, "reasons": None, "tokens": {"in": 0, "out": 0}, "judge_error": "HTTP 500"}]
    agg = J.aggregate(rows)
    assert (agg["n"], agg["scored"], agg["errors"]) == (4, 3, 1)
    assert agg["groups"]["lừa đảo"]["mean"]["c1"] == 4.0 and agg["groups"]["lừa đảo"]["pass_rate"]["c2"] == 0.5
    assert agg["groups"]["lành tính"]["n"] == 1 and agg["groups"]["tất cả"]["mean"]["c3"] == 3.33
    assert agg["first_len_ok"] == 0.75 and agg["tokens"] == {"in": 30, "out": 6}


def test_worst_sorts_by_total() -> None:
    rows = [scored("a", (5, 5, 5, 5)), scored("b", (1, 2, 1, 2)), scored("c", (3, 3, 3, 3))]
    assert [r["id"] for r in J.worst(rows, 2)] == ["b", "c"]


def test_human_sheet_is_deterministic_and_hides_llm_scores() -> None:
    rows = [scored(f"s{i:02}", (5, 4, 3, 2)) for i in range(30)]
    sheet = J.human_sheet(rows, n=5, seed=1)
    assert sheet == J.human_sheet(list(reversed(rows)), n=5, seed=1)
    assert sheet.count("\n## s") == 5 and "rc1" not in sheet and "Đừng đọc mã OTP" in sheet


def test_load_human_and_compare(tmp_path) -> None:
    csv_path = tmp_path / "h.csv"
    csv_path.write_text("id,grader,c1,c2,c3,c4\na,AI1,5,4,4,5\na,AI2,3,4,4,5\nb,AI1,1,1,1,1\nz,AI1,5,5,5,5\nb,AI2,,,,\n",
                        encoding="utf-8")
    human = J.load_human(csv_path)
    assert len(human) == 4
    cmp = J.compare([scored("a", (5, 4, 4, 5)), scored("b", (4, 1, 1, 1))], human)
    assert (cmp["samples"], cmp["graders"]) == (2, 2)
    assert cmp["criteria"]["c1"] == {"human_mean": 2.5, "llm_mean": 4.5, "mae": 2.0, "within_1": 0.5}
    assert cmp["criteria"]["c2"]["mae"] == 0.0


def test_render_report_without_and_with_human() -> None:
    rows = [scored("a", (5, 4, 4, 5)), scored("b", (2, 3, 1, 4))]
    md = J.render_report(J.aggregate(rows), rows, "m1", None)
    assert "`m1`" in md and "Chưa có điểm chấm tay" in md and "| b | DANGEROUS | 2 | 3 | 1 | 4 | c3: rc3 |" in md
    cmp = {"samples": 1, "graders": 2, "criteria": {c: {"human_mean": 4.0, "llm_mean": 5.0, "mae": 1.0, "within_1": 1.0}
                                                   for c in J.CRITERIA}}
    assert "| Đúng trọng tâm | 4.00 | 5.00 | 1.00 | 100.0% |" in J.render_report(J.aggregate(rows), rows, "m1", cmp)


def test_main_from_file_needs_no_key(tmp_path, monkeypatch, capsys) -> None:
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    src = tmp_path / "scores.jsonl"
    src.write_text(json.dumps({**scored("a", (5, 4, 4, 5)), "judge_model": "m9"}, ensure_ascii=False) + "\n", encoding="utf-8")
    report = tmp_path / "report.md"
    assert J.main(["--from", str(src), "--report", str(report), "--sheet", ""]) == 0
    assert "`m9`" in report.read_text(encoding="utf-8")


def test_main_without_key_exits_2(monkeypatch) -> None:
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    assert J.main(["--report", "", "--sheet", ""]) == 2
