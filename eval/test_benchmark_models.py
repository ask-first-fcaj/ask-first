"""Test eval/benchmark_models.py, không gọi mạng. Chạy từ ai/: uv run pytest ../eval/test_benchmark_models.py"""
import sys
from dataclasses import dataclass
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import benchmark_models as bm  # noqa: E402

from askfirst.llm.bedrock import LLMError  # noqa: E402

BASE = {"bedrock_region": "ap-southeast-2",
        "judge": {"model_id": "qwen.qwen3-32b-v1:0", "max_tokens": 600, "timeout_s": 8, "max_attempts": 2},
        "backup": {"model_id": "openai.gpt-oss-120b-1:0", "max_tokens": 800, "timeout_s": 12},
        "pricing_usd_per_1m": {"qwen.qwen3-32b-v1:0": {"in": 1, "out": 2}}}


@dataclass
class Out:
    text: str
    latency_ms: int = 100
    input_tokens: int = 10
    output_tokens: int = 5
    stop_reason: str = "end_turn"
    cost_usd: float = 0.001


class FakeLLM:
    def __init__(self, texts):
        self.texts = list(texts)
        self.extra = []

    def converse(self, role, messages, system=None, extra_fields=None):
        self.extra.append(extra_fields)
        return Out(self.texts.pop(0))

    def budget_s(self, role):
        return 8.0

    def model_id(self, role):
        return "m"


GOOD = ('{"group": "N1", "scenario": "N1.4", "risk_score": 70, "confidence": "high", "signals": [], '
        '"requested_actions": ["click_link"], "short_explanation": "Bác đừng bấm.", "followup_question": null}')


def test_candidate_config_swaps_judge_and_empties_backup():
    cfg = bm.candidate_config(BASE, {"model_id": "google.gemma-3-12b-it", "price": {"in": 3, "out": 4}, "max_tokens": 900})
    assert cfg["judge"]["model_id"] == "google.gemma-3-12b-it"
    assert cfg["judge"]["max_tokens"] == 900 and cfg["judge"]["timeout_s"] == 8
    assert cfg["judge"]["max_attempts"] == 1
    assert cfg["backup"]["model_id"] == ""
    assert cfg["pricing_usd_per_1m"]["google.gemma-3-12b-it"] == {"in": 3, "out": 4}
    assert BASE["judge"]["model_id"] == "qwen.qwen3-32b-v1:0"   # bản gốc không bị sửa


def test_recorder_counts_json_validity_and_passes_extra_fields():
    llm = FakeLLM([GOOD, "không phải JSON"])
    rec = bm.Recorder(llm, extra_fields={"reasoning_effort": "high"})
    rec.converse("judge", [], system="s")
    rec.converse("judge", [], system="s")
    stats = bm.call_stats(rec)
    assert stats["n_calls"] == 2 and stats["json_valid_rate"] == 0.5
    assert stats["stop_reasons"] == {"end_turn": 2}
    assert llm.extra == [{"reasoning_effort": "high"}] * 2


def test_recorder_refuses_backup_role():
    rec = bm.Recorder(FakeLLM([GOOD]))
    with pytest.raises(LLMError):
        rec.converse("backup", [])
    assert rec.calls == []


def test_recorder_records_client_errors_without_message_text():
    class Boom(FakeLLM):
        def converse(self, *a, **k):
            raise LLMError("AccessDeniedException", retryable=False)

    rec = bm.Recorder(Boom([]))
    with pytest.raises(LLMError):
        rec.converse("judge", [])
    assert bm.call_stats(rec) == {"n_calls": 0, "n_errors": 1, "errors": ["AccessDeniedException"]}


def test_grading_sheet_hides_model_names_until_key():
    expl = [{"model": m, "id": f"s{i}", "explanation": f"giải thích {m} {i}"} for m in ("qwen", "gemma") for i in range(5)]
    samples = {f"s{i}": {"input": {"text": f"tin {i}"}} for i in range(5)}
    sheet = bm.grading_sheet(expl, samples, n=3, seed=1)
    body, key = sheet.split("## Giải mã")
    assert body.count("## s") == 3
    assert "=qwen" not in body and "=qwen" in key
    assert bm.grading_sheet(expl, samples, n=3, seed=1) == sheet   # cùng seed, cùng bảng


def test_grading_sheet_drops_candidates_without_any_explanation():
    expl = [{"model": "qwen", "id": f"s{i}", "explanation": f"giải thích {i}"} for i in range(3)]
    expl += [{"model": "haiku", "id": f"s{i}", "explanation": None} for i in range(3)]
    sheet = bm.grading_sheet(expl, {f"s{i}": {"input": {"text": f"tin {i}"}} for i in range(3)}, n=3, seed=1)
    assert "haiku" not in sheet and "(không có)" not in sheet and "**B**" not in sheet
