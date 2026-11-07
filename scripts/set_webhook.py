#!/usr/bin/env python3
"""
Script set_webhook.py: Cấu hình Webhook Telegram trỏ về AWS Lambda API Gateway.
Đáp ứng BE-20 (§6.2, ARCH §4.4).

Chạy (từ thư mục backend/ScamProtection):
  python scripts/set_webhook.py --url https://<host>/dev/webhook
  python scripts/set_webhook.py                 # lấy WEBHOOK_URL từ .env
  python scripts/set_webhook.py --drop-pending  # xóa update đang chờ (mặc định KHÔNG xóa)
  python scripts/set_webhook.py --delete        # gỡ webhook để dùng long polling

Cấu hình đọc từ .env (hoặc biến môi trường):
  TELEGRAM_BOT_TOKEN       bắt buộc
  TELEGRAM_WEBHOOK_SECRET  bắt buộc khi đăng ký; chỉ A-Za-z0-9_- , tối đa 256 ký tự
  WEBHOOK_URL              dùng khi không truyền --url; phải bắt đầu bằng https://

Không có cách đăng ký webhook mà thiếu secret: thiếu secret nghĩa là ai cũng POST được vào Lambda.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# Đảm bảo in tiếng Việt không lỗi mã hóa trên Windows console (cp1252)
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# my_chat_member: báo khi người dùng chặn/bỏ chặn bot. Thiếu dòng này thì bot không biết mình bị block.
ALLOWED_UPDATES = ["message", "callback_query", "my_chat_member"]
SECRET_RE = re.compile(r"^[A-Za-z0-9_-]{1,256}$")


def load_env_values() -> dict[str, str]:
    """Tìm nạp biến môi trường từ .env nếu chưa có trong os.environ."""
    result: dict[str, str] = {}
    search_dirs = [
        Path.cwd(),
        Path.cwd() / "backend" / "ScamProtection",
        Path(__file__).resolve().parent.parent,
        Path(__file__).resolve().parent.parent / "backend" / "ScamProtection",
    ]

    for directory in search_dirs:
        env_file = directory / ".env"
        if env_file.is_file():
            try:
                with open(env_file, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if "=" in line and not line.startswith("#"):
                            k, v = line.split("=", 1)
                            clean_k = k.strip()
                            clean_v = v.strip().strip('"').strip("'")
                            if clean_k not in result and clean_v:
                                result[clean_k] = clean_v
            except OSError:
                pass

    # Biến môi trường thật được ưu tiên hơn .env
    for k in ["TELEGRAM_BOT_TOKEN", "TELEGRAM_WEBHOOK_SECRET", "WEBHOOK_URL"]:
        if os.environ.get(k):
            result[k] = os.environ[k].strip()

    return result


def call_telegram_api(token: str, method: str, payload: dict | None = None) -> dict:
    """Gọi Bot API. Trả về JSON của Telegram, kể cả khi ok=false (HTTP 4xx)."""
    url = f"https://api.telegram.org/bot{token}/{method}"
    headers = {"Content-Type": "application/json; charset=utf-8"}
    data = json.dumps(payload).encode("utf-8") if payload is not None else None

    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            # Telegram trả {"ok": false, "description": "..."} kèm HTTP 400/401: đây là lỗi nghiệp vụ, không phải lỗi mạng
            return json.loads(body)
        except json.JSONDecodeError:
            raise RuntimeError(f"HTTP {e.code} khi gọi {method}: {body}") from e
    except (urllib.error.URLError, OSError) as e:
        raise RuntimeError(f"Lỗi kết nối Telegram ({method}): {e}") from e


def fail(message: str) -> None:
    print(f"LỖI: {message}", file=sys.stderr)
    sys.exit(1)


def main() -> int:
    parser = argparse.ArgumentParser(description="Cấu hình Webhook cho Telegram Bot")
    parser.add_argument("--url", help="URL Webhook công khai (HTTPS); mặc định lấy WEBHOOK_URL trong .env")
    parser.add_argument("--secret", help="Secret token; mặc định lấy TELEGRAM_WEBHOOK_SECRET trong .env")
    parser.add_argument("--token", help="Telegram Bot Token; mặc định lấy TELEGRAM_BOT_TOKEN trong .env")
    parser.add_argument("--drop-pending", action="store_true",
                        help="xóa các tin nhắn đang chờ khi đăng ký (mặc định: không xóa)")
    parser.add_argument("--delete", action="store_true",
                        help="gỡ webhook để chuyển sang chế độ Long Polling")
    args = parser.parse_args()

    try:
        env_vals = load_env_values()
        token = args.token or env_vals.get("TELEGRAM_BOT_TOKEN")
        if not token:
            fail("Chưa có TELEGRAM_BOT_TOKEN trong .env hoặc tham số --token")

        if args.delete:
            print("--- Đang gỡ Webhook để cho phép Long Polling ---")
            res = call_telegram_api(token, "deleteWebhook", {"drop_pending_updates": False})
            if not res.get("ok"):
                fail(f"deleteWebhook thất bại: {res.get('description', res)}")
            info = call_telegram_api(token, "getWebhookInfo").get("result", {})
            print(f"Trạng thái hiện tại: URL='{info.get('url', '')}'")
            return 0

        secret = args.secret or env_vals.get("TELEGRAM_WEBHOOK_SECRET")
        if not secret:
            fail("Thiếu TELEGRAM_WEBHOOK_SECRET trong .env hoặc tham số --secret. Webhook không có secret là không an toàn.")
        if not SECRET_RE.match(secret):
            fail("TELEGRAM_WEBHOOK_SECRET không hợp lệ: chỉ dùng A-Z a-z 0-9 _ - (tối đa 256 ký tự)")

        url = args.url or env_vals.get("WEBHOOK_URL")
        if not url:
            fail("Thiếu URL webhook (--url hoặc WEBHOOK_URL trong .env)")
        if not url.startswith("https://"):
            fail("Telegram yêu cầu Webhook URL phải bắt đầu bằng https://")

        payload = {
            "url": url,
            "secret_token": secret,
            "drop_pending_updates": args.drop_pending,
            "allowed_updates": ALLOWED_UPDATES,
        }

        print(f"--- Đang đăng ký Webhook tới: {url} ---")
        print(f"drop_pending_updates={str(args.drop_pending).lower()}, secret_token: đã cấu hình (không hiển thị)")

        res = call_telegram_api(token, "setWebhook", payload)
        if not res.get("ok"):
            fail(f"Đăng ký thất bại. Phản hồi Telegram: {res.get('description', res)}")
        print(f"setWebhook: {res.get('description', 'OK')}")

        print("--- Kiểm tra trạng thái Webhook (getWebhookInfo) ---")
        info = call_telegram_api(token, "getWebhookInfo").get("result", {})
        print(f"  URL: {info.get('url')}")
        print(f"  Has custom certificate: {info.get('has_custom_certificate')}")
        print(f"  Pending update count: {info.get('pending_update_count')}")
        print(f"  Allowed updates: {info.get('allowed_updates')}")
        if info.get("url") != url:
            print("  CẢNH BÁO: URL đã đăng ký khác với URL yêu cầu", file=sys.stderr)
        if "my_chat_member" not in (info.get("allowed_updates") or []):
            print("  CẢNH BÁO: thiếu my_chat_member trong allowed_updates", file=sys.stderr)
        if info.get("last_error_message"):
            print(f"  Lỗi gần nhất từ Telegram: {info.get('last_error_message')} "
                  f"(vào lúc {info.get('last_error_date')})")
        return 0
    except RuntimeError as e:
        fail(str(e))
        return 1


if __name__ == "__main__":
    sys.exit(main())
