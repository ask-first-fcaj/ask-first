"""Test scripts/warmup.py (kh gọi AWS). Chạy từ gốc repo: python -m pytest scripts/test_warmup.py"""
import base64
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import warmup as w  # noqa: E402


def _tail(text: str) -> str:
    return base64.b64encode(text.encode()).decode()


class FakeClient:
    def __init__(self, resp=None, exc=None):
        self.resp, self.exc, self.calls = resp, exc, []

    def invoke(self, **kw):
        self.calls.append(kw)
        if self.exc:
            raise self.exc
        return self.resp


def test_init_ms():
    assert w.init_ms(_tail("REPORT RequestId: x\tDuration: 5.1 ms\tInit Duration: 6243.87 ms\t")) == 6243.87
    assert w.init_ms(_tail("REPORT RequestId: x\tDuration: 5.1 ms\t")) is None
    assert w.init_ms(None) is None


def test_invoke_ok_and_alias():
    c = FakeClient({"StatusCode": 200, "LogResult": _tail("Init Duration: 2210.5 ms")})
    r = w.invoke(c, "askfirst-dev-ai", "live", {"op": "warmup"})
    assert r["ok"] and r["init"] == 2210.5
    assert c.calls[0]["Qualifier"] == "live"
    c2 = FakeClient({"StatusCode": 200})
    w.invoke(c2, "askfirst-dev-webhook", None, {})
    assert "Qualifier" not in c2.calls[0]


def test_invoke_errors():
    assert not w.invoke(FakeClient({"StatusCode": 200, "FunctionError": "Unhandled"}), "f", None, {})["ok"]
    r = w.invoke(FakeClient(exc=TimeoutError()), "f", None, {})
    assert not r["ok"] and r["err"] == "TimeoutError"
