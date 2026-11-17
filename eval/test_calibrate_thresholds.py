"""Test eval/calibrate_thresholds.py. Chạy từ ai/: uv run pytest ../eval/test_calibrate_thresholds.py"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import calibrate_thresholds as C  # noqa: E402
from test_run_eval import _sample  # noqa: E402

from askfirst.domain import models as m  # noqa: E402
from askfirst.pipeline import fuse as F  # noqa: E402
from askfirst.pipeline.judge import Judgement  # noqa: E402


def _j(score: int, group: str = "N2", confidence: str = "high") -> Judgement:
    return Judgement(group=group, scenario=f"{group}.1" if group != "N0" else "N0", risk_score=score, confidence=confidence,
                     signals=[], requested_actions=["none"], short_explanation="x", followup_question=None)


class FakePipeline:
    """Điểm LLM lấy từ đầu tin ("60 ..."), đi qua ``F.fuse`` như RealPipeline."""

    def analyze(self, bundle: m.Bundle, ctx: m.AnalyzeContext) -> m.Verdict:
        score = int(bundle.texts[0].text.split()[0])
        v = F.fuse([], _j(score), all_urls_official=True)
        return v.model_copy(update={"latency_ms": {"total": 1}, "cost_usd_est": 0.0})


# 4 lừa đảo điểm 80/60/45/40, 2 benign_suspicious điểm 45/30, 1 benign_normal điểm 10.
SAMPLES = [
    _sample("s1", "80 a", group="N2", scenario="N2.1"),
    _sample("s2", "60 b", group="N2", scenario="N2.1"),
    _sample("s3", "45 c", group="N2", scenario="N2.1"),
    _sample("s4", "40 d", group="N2", scenario="N2.1"),
    _sample("b1", "45 e", is_scam=False, split="benign_suspicious", max_level="UNSURE"),
    _sample("b2", "30 f", is_scam=False, split="benign_suspicious", max_level="UNSURE"),
    _sample("n1", "10 g", is_scam=False, split="benign_normal", max_level="LOW_RISK"),
]


def test_capture_records_fuse_inputs_and_restores_fuse():
    real = F.fuse
    rows = C.capture(FakePipeline(), SAMPLES)
    assert F.fuse is real
    assert [r["verdict"]["level"] for r in rows] == ["DANGEROUS", "SUSPICIOUS", "UNSURE", "UNSURE", "UNSURE", "UNSURE", "LOW_RISK"]
    assert rows[0]["fuse"]["judgement"]["risk_score"] == 80 and rows[0]["fuse"]["all_urls_official"] is True


def test_relevel_matches_running_with_those_thresholds():
    rows = C.capture(FakePipeline(), SAMPLES)
    t = F.Thresholds(55, 40, 20)
    got = [r["verdict"]["level"] for r in C.relevel(rows, C._parsed(rows), t)]
    assert got == [F.fuse([], _j(int(s["input"]["text"].split()[0])), all_urls_official=True, thresholds=t).level
                   for s in SAMPLES]


def test_row_without_fuse_keeps_its_level():
    row = {"id": "x", "verdict": {"level": "LOW_RISK"}, "error": None, "fuse": None}
    assert C.relevel([row], C._parsed([row]), F.Thresholds(60, 35, 10)) == [row]


def test_sweep_takes_best_recall_within_false_alarm_cap():
    rows = C.capture(FakePipeline(), SAMPLES)
    best, mx = C.sweep(SAMPLES, rows)[0]
    # S=40 bắt cả 4 lừa đảo nhưng b1 (45) thành báo nhầm 50% > 10%; S=50 (b1 lọt) chỉ bắt 2/4.
    # S=50..60 cùng bắt 2/4, không báo nhầm, nên hoà nhau và lấy bộ gần ngưỡng đang dùng (F.Thresholds()) nhất.
    assert mx["false_alarm"] == 0.0 and mx["recall"] == 0.5
    assert best == C.START == F.Thresholds()
