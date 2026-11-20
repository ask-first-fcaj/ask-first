"""Hàm phụ của scripts/spike_bedrock.py (AI-02): nhận ra thinking và tính phân vị, không gọi mạng."""
import importlib.util
from pathlib import Path

_PATH = Path(__file__).resolve().parents[2] / "scripts" / "spike_bedrock.py"
_spec = importlib.util.spec_from_file_location("spike_bedrock", _PATH)
sp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sp)


def _resp(*blocks: dict) -> dict:
    return {"output": {"message": {"content": list(blocks)}}}


def test_reasoning_block_counts_as_thinking():
    assert sp.thinking_seen(_resp({"reasoningContent": {"reasoningText": {"text": "..."}}}, {"text": "{}"}))


def test_inline_think_tag_counts_only_when_not_empty():
    assert sp.thinking_seen(_resp({"text": "<think>cân nhắc</think>{}"}))
    assert not sp.thinking_seen(_resp({"text": "<think>\n\n</think>{}"}))
    assert not sp.thinking_seen(_resp({"text": '{"group": "N1"}'}))


def test_pct_picks_nearest_rank():
    xs = [float(i) for i in range(1, 21)]
    assert sp.pct(xs, 0.5) == 11.0
    assert sp.pct(xs, 0.95) == 19.0
    assert sp.pct([3.0], 0.95) == 3.0


def test_parses_rejects_non_json():
    assert not sp.parses("không có JSON")
