"""Test eval/run_eval.py. Chạy từ ai/: uv run pytest ../eval/test_run_eval.py"""
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import run_eval  # noqa: E402

from askfirst.domain import models as m  # noqa: E402


def _sample(sid, text, *, is_scam=True, split="real_scam", group="N1", scenario="N1.4", min_level=None, max_level=None,
            must_signals=None, image=None):
    if is_scam:
        min_level = min_level or "SUSPICIOUS"
    else:
        group, scenario = "N0", "N0"
    return {"id": sid, "split": split,
            "input": {"text": text, "entities": [], "image": image, "audio": None, "forward_origin": None},
            "label": {"is_scam": is_scam, "group": group, "scenario": scenario, "min_level": min_level, "max_level": max_level,
                      "must_signals": must_signals or [], "risky_actions": []}}


# 10 mẫu giả: 6 lừa đảo, 2 benign_suspicious, 2 benign_normal. StubPipeline: "otp" → DANGEROUS N1.1,
# "http"/"link" → SUSPICIOUS N1.4, "hỏi thêm" → UNSURE, còn lại → LOW_RISK N0.
TEN = [
    _sample("s1", "Mã OTP của bác là gì, đọc cho cháu", scenario="N1.1"),
    _sample("s2", "Nộp phạt nguội tại http://abc[.]xyz"),
    _sample("s3", "Bấm link này để nhận quà"),
    _sample("s4", "Cho cháu hỏi thêm về khoản vay", group="N2", scenario="N2.1"),
    _sample("s5", "Con đang cấp cứu, mẹ chuyển tiền gấp", group="N3", scenario="N3.1", must_signals=["R_URGENT"]),
    _sample("s6", "OTP ngân hàng bị lỗi, gửi lại mã", scenario="N1.1", min_level="DANGEROUS"),
    _sample("b1", "[Ngân hàng A] Mã OTP của bạn là 000000, không đưa cho ai", is_scam=False, split="benign_suspicious", max_level="UNSURE"),
    _sample("b2", "Hóa đơn điện tháng 10 xem tại link trong app", is_scam=False, split="benign_suspicious", max_level="UNSURE"),
    _sample("n1", "Tối nay cả nhà ăn cơm sớm nhé", is_scam=False, split="benign_normal", max_level="LOW_RISK"),
    _sample("n2", "Mẹ nhớ uống thuốc đúng giờ", is_scam=False, split="benign_normal", max_level="LOW_RISK"),
]
OCR = {"id": "oi-1", "split": "ocr_images", "input": {"text": "", "entities": [], "image": None, "audio": None, "forward_origin": None},
       "label": {"ocr_text": "x", "urls": [], "phones": []}}


def _write(tmp_path, samples):
    p = tmp_path / "data.jsonl"
    p.write_text("\n".join(json.dumps(s, ensure_ascii=False) for s in samples) + "\n", encoding="utf-8")
    return p


def test_stub_pipeline_end_to_end_on_ten_samples(tmp_path):
    out = tmp_path / "out"
    code = run_eval.main([str(_write(tmp_path, [*TEN, OCR])), "--out", str(out)])
    assert code == 0
    report = json.loads((out / "report.json").read_text(encoding="utf-8"))
    mt = report["metrics"]
    assert (mt["n_samples"], mt["n_scored"], mt["n_skipped_no_label"], mt["n_errors"]) == (11, 10, 1, 0)
    assert mt["recall"] == pytest.approx(4 / 6)          # s1 s2 s3 s6 bị bắt; s4 UNSURE, s5 LOW_RISK
    assert mt["unsure_rate"] == pytest.approx(1 / 6)
    assert mt["false_alarm"] == 1.0                      # b1 (otp) và b2 (link) đều bị bắt
    assert mt["false_alarm_by_split"]["benign_normal"] == 0.0
    assert mt["level_pass"] == pytest.approx(6 / 10)     # s1 s2 s3 s6 n1 n2
    assert mt["scenario_accuracy"] == pytest.approx(6 / 10)  # s1 s2 s3 s6 n1 n2
    assert mt["missing_must_signals"] == [{"id": "s5", "missing": ["R_URGENT"]}]
    assert mt["fast_path_rate"] == 1.0
    assert mt["json_valid_rate"] is None
    md = (out / "report.md").read_text(encoding="utf-8")
    assert "Recall (6 mẫu lừa đảo) | 66.7%" in md
    assert "`s5`: R_URGENT" in md
    assert all("masked_text" not in r["verdict"] for r in report["results"])  # không ghi văn bản người dùng ra report


def test_f1_by_group():
    samples = TEN
    results = run_eval.run(m_stub(), samples, "stub")
    f1 = run_eval.compute_metrics(samples, results)["f1_by_group"]
    # N1: nhãn s1 s2 s3 s6, đoán N1 cho s1 s2 s3 s6 b1 b2 → tp=4 fp=2 fn=0
    assert f1["N1"] == {"support": 4, "f1": pytest.approx(8 / 10)}
    assert f1["N2"] == {"support": 1, "f1": 0.0}
    assert f1["N4"] == {"support": 0, "f1": None}


def m_stub():
    from askfirst.pipeline.stub import StubPipeline
    return StubPipeline()


class _Boom:
    def analyze(self, bundle, ctx):
        raise RuntimeError("bedrock timeout")


def test_errors_count_as_missed_and_do_not_stop_the_run():
    results = run_eval.run(_Boom(), TEN[:2], "stub")
    mt = run_eval.compute_metrics(TEN[:2], results)
    assert mt["n_errors"] == 2
    assert mt["recall"] == 0.0
    assert mt["errors"][0] == {"id": "s1", "error": "RuntimeError: bedrock timeout"}


class _Counting:
    def __init__(self):
        self.calls = 0
        self.inner = m_stub()

    def analyze(self, bundle, ctx):
        self.calls += 1
        return self.inner.analyze(bundle, ctx)


def test_cache_skips_pipeline_on_rerun(tmp_path):
    pipe = _Counting()
    first = run_eval.run(pipe, TEN, "stub", tmp_path / "cache")
    second = run_eval.run(pipe, TEN, "stub", tmp_path / "cache")
    assert pipe.calls == 10
    assert all(r["cached"] for r in second)
    assert [r["verdict"] for r in first] == [r["verdict"] for r in second]
    run_eval.run(pipe, TEN[:1], "real", tmp_path / "cache")  # đổi pipeline thì không dùng cache cũ
    assert pipe.calls == 11


def test_errors_are_not_cached(tmp_path):
    run_eval.run(_Boom(), TEN[:1], "stub", tmp_path / "cache")
    assert not (tmp_path / "cache").exists()


def test_code_version_changes_with_files(tmp_path):
    (tmp_path / "a.txt").write_text("1", encoding="utf-8")
    v1 = run_eval.code_version([tmp_path])
    (tmp_path / "a.txt").write_text("2", encoding="utf-8")
    assert run_eval.code_version([tmp_path]) != v1


def test_build_bundle_reads_image_relative_to_eval_dir():
    s = _sample("img", "", image="assets/ocr/001.png")
    if not (run_eval.EVAL_DIR / "assets/ocr/001.png").is_file():
        pytest.skip("thiếu ảnh mẫu")
    b = run_eval.build_bundle(s)
    assert b.texts == []
    assert b.images[0].mime == "image/png" and b.images[0].data[:4] == b"\x89PNG"


def test_build_bundle_keeps_entities_and_forward_origin():
    s = _sample("e", "Truy cập vneid.gov.vn")
    s["input"]["entities"] = [{"kind": "text_link", "offset": 9, "length": 12, "url": "https://vneid-xacthuc.example"}]
    s["input"]["forward_origin"] = "hidden_user"
    b = run_eval.build_bundle(s)
    assert b.texts[0].entities[0].url == "https://vneid-xacthuc.example"
    assert b.forward_origin == "hidden_user"


@pytest.mark.parametrize(("values", "q", "want"), [([], 50, None), ([5], 95, 5), ([1, 2, 3, 4], 50, 2), (list(range(1, 101)), 95, 95)])
def test_percentile_nearest_rank(values, q, want):
    assert run_eval.percentile(values, q) == want


def test_gate_thresholds_and_baseline_drift():
    good = {"recall": 0.95, "false_alarm": 0.05}
    assert run_eval.gate(good) == []
    assert run_eval.gate({"recall": 0.89, "false_alarm": 0.05}) == ["recall 89.0% < 90%"]
    assert run_eval.gate({"recall": 0.95, "false_alarm": 0.11}) == ["false alarm 11.0% > 10%"]
    assert run_eval.gate(good, {"recall": 0.96, "false_alarm": 0.04}) == []          # lệch 1 điểm: đạt
    fails = run_eval.gate(good, {"recall": 0.98, "false_alarm": 0.02})               # lệch 3 điểm mỗi bên
    assert len(fails) == 2 and "recall giảm" in fails[0] and "false alarm tăng" in fails[1]


def test_main_gate_exit_code_and_baseline_delta(tmp_path):
    data = _write(tmp_path, TEN)
    out = tmp_path / "out"
    assert run_eval.main([str(data), "--out", str(out), "--gate"]) == 1   # stub: recall 66.7% < 90%
    md = (out / "report.md").read_text(encoding="utf-8")
    assert "Trượt: recall 66.7% < 90%" in md
    base = tmp_path / "base.json"
    base.write_text(json.dumps({"metrics": {"recall": 0.5, "false_alarm": 1.0}}), encoding="utf-8")
    run_eval.main([str(data), "--out", str(out), "--baseline", str(base)])
    assert "66.7% (+16.7 điểm)" in (out / "report.md").read_text(encoding="utf-8")


def test_split_and_limit_filter(tmp_path):
    data = _write(tmp_path, TEN)
    assert [s["id"] for s in run_eval.load_samples([data], "benign_normal")] == ["n1", "n2"]
    assert len(run_eval.load_samples([data], None, 3)) == 3


def test_real_dataset_runs_through_stub():
    """Bộ eval thật trong repo chạy hết qua stub, không lỗi mẫu nào."""
    samples = run_eval.load_samples(sorted(run_eval.DATASETS.glob("*.jsonl")))
    mt = run_eval.compute_metrics(samples, run_eval.run(m_stub(), samples, "stub"))
    assert mt["n_errors"] == 0
    assert mt["n_scored"] + mt["n_skipped_no_label"] == mt["n_samples"]


def test_stub_verdict_is_valid_model():
    r = run_eval.run_sample(m_stub(), TEN[0])
    m.Verdict.model_validate(r["verdict"] | {"masked_text": ""})
