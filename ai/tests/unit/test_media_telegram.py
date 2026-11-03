import json
import threading
import time
from datetime import UTC, datetime

import pytest

from askfirst import handler as h
from askfirst.domain import models as m
from askfirst.media import telegram as tg

TOKEN = "123456:SECRETTOKEN"


def msg(kind: str, text: str | None = None, media: dict | None = None, **kw) -> m.InboundMessage:
    return m.InboundMessage(channel="telegram", update_id="1", chat_id="c", user_id="tg:1", message_id="1", kind=kind, text=text,
                            media=m.MediaRef(**media) if media else None, received_at=datetime(2026, 11, 3, tzinfo=UTC), **kw)


class FakeTelegram:
    """getFile trả file_path = "<file_id>.<ext>"; nội dung file = b"data:<file_id>"."""

    def __init__(self, ext: dict[str, str] | None = None, fail: str | None = None, delay: float = 0.0) -> None:
        self.ext, self.fail, self.delay = ext or {}, fail, delay
        self.urls: list[str] = []
        self.active = self.peak = 0
        self.lock = threading.Lock()

    def __call__(self, url: str) -> bytes:
        assert TOKEN in url
        with self.lock:
            self.urls.append(url)
            self.active += 1
            self.peak = max(self.peak, self.active)
        try:
            time.sleep(self.delay)
            if "/getFile?" in url:
                fid = url.split("file_id=")[1]
                if fid == self.fail:
                    raise OSError(f"HTTP 502 for {url}")
                return json.dumps({"ok": True, "result": {"file_path": f"{fid}.{self.ext.get(fid, 'jpg')}"}}).encode()
            return b"data:" + url.rsplit("/", 1)[1].split(".")[0].encode()
        finally:
            with self.lock:
                self.active -= 1


def media(fid: str, **kw) -> dict:
    return {"file_id": fid, "file_unique_id": "u" + fid} | kw


def build(items, fake: FakeTelegram, flags: m.Flags | None = None, session=None) -> m.Bundle:
    return tg.TelegramMedia(lambda: TOKEN, fake).build_bundle(items, session, flags or m.Flags())


def test_builds_bundle_in_order_and_downloads_into_ram() -> None:
    link = m.EntityRef(kind="text_link", offset=0, length=3, url="https://x.example")
    origin = "channel"
    items = [
        msg("text", "bấm vào đây", entities=[link]),
        msg("photo", "ảnh 1", media("p1")),
        msg("document", None, media("d1", mime="image/png")),
        msg("document", None, media("pdf", mime="application/pdf")),
        msg("voice", None, media("v1", mime="audio/ogg", duration=7), forward_origin=origin),
    ]
    b = build(items, FakeTelegram(ext={"d1": "png", "v1": "oga"}))
    assert [(t.text, t.entities) for t in b.texts] == [("bấm vào đây", [link]), ("ảnh 1", [])]
    assert [(i.data, i.file_unique_id, i.mime) for i in b.images] == [(b"data:p1", "up1", "image/jpeg"), (b"data:d1", "ud1", "image/png")]
    assert [(a.data, a.mime, a.duration_s) for a in b.audios] == [(b"data:v1", "audio/ogg", 7)]
    assert b.forward_origin == origin


def test_mime_from_telegram_then_extension_then_default() -> None:
    items = [msg("photo", None, media("a")), msg("photo", None, media("b")), msg("audio", None, media("c", mime="audio/mpeg"))]
    b = build(items, FakeTelegram(ext={"a": "png", "b": "unknownext", "c": "bin"}))
    assert [i.mime for i in b.images] == ["image/png", "image/jpeg"]
    assert b.audios[0].mime == "audio/mpeg"


def test_flags_drop_media_without_downloading() -> None:
    fake = FakeTelegram()
    items = [msg("photo", "chú thích", media("p")), msg("voice", None, media("v", duration=3))]
    b = build(items, fake, m.Flags(feature_image=False, feature_voice=False))
    assert (b.images, b.audios, [t.text for t in b.texts]) == ([], [], ["chú thích"])
    assert fake.urls == []


def test_token_read_lazily_once() -> None:
    calls = []
    tm = tg.TelegramMedia(lambda: calls.append(1) or TOKEN, FakeTelegram())
    tm.build_bundle([msg("text", "alo")], None, m.Flags())
    assert calls == []
    for _ in range(2):
        tm.build_bundle([msg("photo", None, media("p"))], None, m.Flags())
    assert calls == [1]


def test_parallel_at_most_four_and_order_kept() -> None:
    fake = FakeTelegram(delay=0.05)
    items = [msg("photo", None, media(f"p{i}")) for i in range(8)]
    b = build(items, fake)
    assert [i.data for i in b.images] == [f"data:p{i}".encode() for i in range(8)]
    assert 1 < fake.peak <= tg.MAX_PARALLEL


def test_download_error_hides_url_and_token() -> None:
    with pytest.raises(tg.MediaDownloadError) as ei:
        build([msg("photo", None, media("ok")), msg("photo", None, media("bad"))], FakeTelegram(fail="bad"))
    assert TOKEN not in str(ei.value) and "api.telegram.org" not in str(ei.value)
    assert ei.value.__cause__ is None and ei.value.__suppress_context__


@pytest.mark.parametrize("body", [b'{"ok": false, "description": "file is too big"}', b"<html>", b'{"ok": true, "result": {}}'])
def test_bad_getfile_response_is_download_error(body: bytes) -> None:
    with pytest.raises(tg.MediaDownloadError):
        tg.TelegramMedia(lambda: TOKEN, lambda url: body).build_bundle([msg("photo", None, media("p"))], None, m.Flags())


def test_token_error_is_download_error() -> None:
    def boom() -> str:
        raise ConnectionError("ssm")
    with pytest.raises(tg.MediaDownloadError):
        tg.TelegramMedia(boom, FakeTelegram()).build_bundle([msg("photo", None, media("p"))], None, m.Flags())


def test_http_get_rejects_files_over_20mb(monkeypatch: pytest.MonkeyPatch) -> None:
    class Resp:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self, n: int) -> bytes:
            return b"x" * n

    monkeypatch.setattr(tg.urllib.request, "urlopen", lambda url, timeout: Resp())
    with pytest.raises(ValueError):
        tg.http_get("https://api.telegram.org/file/botX/big.jpg")


def test_handler_maps_media_error_to_retryable() -> None:
    req = json.loads((h.pathlib.Path(__file__).resolve().parents[3] / "contracts/fixtures/analyze/photo.json").read_text(encoding="utf-8"))["request"]
    build = tg.TelegramMedia(lambda: TOKEN, FakeTelegram(fail=req["items"][0]["media"]["file_id"])).build_bundle
    resp = h.lambda_handler(req, None, runtime=h.Runtime(h.init_runtime({"PIPELINE_IMPL": "stub"}).pipeline, build, None))
    assert resp["error"] == {"kind": "retryable", "code": "telegram_download"}
