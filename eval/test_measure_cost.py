"""Test eval/measure_cost.py, không gọi mạng. Chạy từ ai/: uv run pytest ../eval/test_measure_cost.py"""
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import measure_cost as mc  # noqa: E402


@dataclass
class Out:
    input_tokens: int
    output_tokens: int
    cost_usd: float


@dataclass
class Guarded:
    text_units: int


class FakeLLM:
    def converse(self, role, messages, system=None, extra_fields=None):
        return Out(1000, 200, 0.001)

    def model_id(self, role):
        return "m"


class FakeGuard:
    def check(self, text):
        return Guarded(2)


class FakeVerdict:
    def __init__(self, path):
        self.path = path

    def model_dump(self, **_):
        return {"path": self.path}


class FakePipeline:
    """Tin có chữ "luat" đi đường nhanh, không gọi LLM; tin khác gọi guard một lần và judge hai lần (thử lại)."""

    def __init__(self):
        self.llm, self.guard = FakeLLM(), FakeGuard()

    def analyze(self, bundle, ctx):
        if "luat" in bundle.texts[0].text:
            return FakeVerdict("fast")
        self.guard.check(bundle.texts[0].text)
        self.llm.converse("judge", [])
        self.llm.converse("judge", [])
        return FakeVerdict("llm")


def sample(i, text):
    return {"id": i, "split": "s", "input": {"text": text, "entities": [], "image": None, "audio": None, "forward_origin": None}}


def test_measure_counts_tokens_and_guard_units_per_sample_and_summarizes():
    p = FakePipeline()
    rows = mc.measure(p, [sample("a", "luat"), sample("b", "tin"), sample("c", "tin")])
    assert [r["llm_calls"] for r in rows] == [0, 2, 2]
    assert rows[1]["in"] == 2000 and rows[1]["out"] == 400 and rows[1]["guard_units"] == 2
    assert p.llm.model_id("judge") == "m"   # wrapper vẫn chuyển các hàm khác cho client gốc
    s = mc.summarize(rows)
    assert s["n_with_llm"] == 2 and s["paths"] == {"fast": 1, "llm": 2} and s["roles"] == {"judge": 4}
    assert s["mean_per_check"]["in"] == round(4000 / 3, 1)
    assert s["mean_per_llm_check"] == {"llm_calls": 2, "in": 2000, "out": 400, "guard_calls": 1, "guard_units": 2}
    assert s["usd_llm_total"] == 0.004


def test_measure_without_guard():
    p = FakePipeline()
    p.guard = None
    rows = mc.measure(p, [sample("a", "luat")])
    assert rows[0]["guard_calls"] == 0
