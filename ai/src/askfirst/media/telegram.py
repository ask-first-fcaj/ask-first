"""Dựng `Bundle` từ các `InboundMessage` và tải media Telegram vào RAM (AI-03b, plan §3.6).

* Ảnh (`photo`, `document` có mime `image/*`) và voice/audio được tải bằng `getFile` + `/file/bot<token>/…`,
  song song tối đa 4 file, chỉ giữ `bytes` trong RAM: không ghi đĩa, không ghi S3.
* URL của Bot API chứa bot token: **không** log, không đưa vào message của exception. Mọi lỗi tải (mạng, HTTP,
  `ok=false`, file > 20 MB, đọc token lỗi) đều thành `MediaDownloadError` chỉ ghi tên lớp lỗi; handler trả
  `AiError("retryable", "telegram_download")`.
* `feature_image=False` thì bỏ ảnh, `feature_voice=False` thì bỏ voice/audio (lớp thứ hai sau webhook).
* Bot token đọc lười từ SSM ở lần đầu cần tải, rồi giữ trong RAM của container.
"""

from __future__ import annotations

import json
import mimetypes
import urllib.parse
import urllib.request
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from typing import Literal

from askfirst.domain import models as m

API = "https://api.telegram.org"
MAX_PARALLEL = 4
TIMEOUT_S = 10
MAX_BYTES = 20 * 1024 * 1024        # Bot API getFile không cho tải file > 20 MB
DEFAULT_MIME = {"image": "image/jpeg", "audio": "audio/ogg"}   # photo của Telegram là JPEG, voice là OGG/Opus

Fetch = Callable[[str], bytes]


class MediaDownloadError(Exception):
    """Tải media lỗi. Message KHÔNG được chứa URL hay token."""


def http_get(url: str) -> bytes:
    # shortcut: timeout của urllib tính cho từng thao tác socket, không phải tổng thời gian; đủ với file ≤ 20 MB.
    with urllib.request.urlopen(url, timeout=TIMEOUT_S) as r:   # URL luôn là https://api.telegram.org
        data = r.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ValueError("file > 20 MB")
    return data


def ssm_token(param: str) -> Callable[[], str]:
    """Đọc bot token từ SSM Parameter Store (`BOT_TOKEN_PARAM`). boto3 import lười để image stub không cần nó."""
    def get() -> str:
        import boto3
        return boto3.client("ssm").get_parameter(Name=param, WithDecryption=True)["Parameter"]["Value"]
    return get


def _kind(item: m.InboundMessage, flags: m.Flags) -> Literal["image", "audio"] | None:
    if item.media is None:
        return None
    if item.kind == "photo" or (item.kind == "document" and (item.media.mime or "").startswith("image/")):
        return "image" if flags.feature_image else None
    if item.kind in ("voice", "audio"):
        return "audio" if flags.feature_voice else None
    return None


class TelegramMedia:
    def __init__(self, token: Callable[[], str], fetch: Fetch = http_get) -> None:
        self._get_token, self._fetch = token, fetch
        self._token: str | None = None

    def _download(self, token: str, ref: m.MediaRef) -> tuple[bytes, str]:
        """Trả (bytes, file_path). Mọi lỗi đổi thành MediaDownloadError không kèm URL."""
        try:
            q = urllib.parse.urlencode({"file_id": ref.file_id})
            res = json.loads(self._fetch(f"{API}/bot{token}/getFile?{q}"))
            if not res.get("ok"):
                raise ValueError("getFile ok=false")
            path = res["result"]["file_path"]
            return self._fetch(f"{API}/file/bot{token}/{path}"), path
        except Exception as e:  # noqa: BLE001
            raise MediaDownloadError(f"tải media lỗi: {type(e).__name__}") from None

    def build_bundle(self, items: list[m.InboundMessage], session: m.SessionContext | None, flags: m.Flags) -> m.Bundle:
        """Đúng chữ ký `BundleBuilder` của handler. `items` đã theo thứ tự `seq`."""
        texts = [m.TextPart(text=i.text, entities=i.entities) for i in items if i.text]
        jobs = [(i.media, k) for i in items if (k := _kind(i, flags))]
        images: list[m.ImageInput] = []
        audios: list[m.AudioInput] = []
        if jobs:
            if self._token is None:
                try:
                    self._token = self._get_token()
                except Exception as e:  # noqa: BLE001
                    raise MediaDownloadError(f"đọc bot token lỗi: {type(e).__name__}") from None
            token = self._token
            with ThreadPoolExecutor(max_workers=MAX_PARALLEL) as ex:
                files = list(ex.map(lambda job: self._download(token, job[0]), jobs))
            for (ref, k), (data, path) in zip(jobs, files, strict=True):
                mime = ref.mime or mimetypes.guess_type(path)[0] or DEFAULT_MIME[k]
                if k == "image":
                    images.append(m.ImageInput(data=data, file_unique_id=ref.file_unique_id, mime=mime))
                else:
                    audios.append(m.AudioInput(data=data, file_unique_id=ref.file_unique_id, mime=mime, duration_s=ref.duration or 0))
        origin = next((i.forward_origin for i in items if i.forward_origin), None)
        return m.Bundle(texts=texts, images=images, audios=audios, forward_origin=origin, session=session)
