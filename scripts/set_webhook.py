#!/usr/bin/env python3
"""
Script set_webhook.py: Cấu hình Webhook Telegram trỏ về AWS Lambda API Gateway.
Đáp ứng BE-20 (§6.2, ARCH §4.4).

Chạy:
  python scripts/set_webhook.py --url https://<api-gateway-id>.execute-api.ap-southeast-1.amazonaws.com/dev/webhook
Hoặc tự động đọc TELEGRAM_BOT_TOKEN, TELEGRAM_WEBHOOK_SECRET, WEBHOOK_URL từ .env
"""

import argparse
import json
import os
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


def load_env_values() -> dict[str, str]:
    """Tìm nạp biến môi trường từ .env nếu chưa có trong os.environ."""
    result = {}
    search_dirs = [
        Path.cwd(),
        Path.cwd() / "backend" / "ScamProtection",
        Path(__file__).resolve().parent.parent,
        Path(__file__).resolve().parent.parent / "backend" / "ScamProtection"
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
            except Exception:
                pass

    for k in ["TELEGRAM_BOT_TOKEN", "TELEGRAM_WEBHOOK_SECRET", "WEBHOOK_URL"]:
        if os.environ.get(k):
            result[k] = os.environ[k].strip()

    return result


def call_telegram_api(token: str, method: str, payload: dict | None = None) -> dict:
    url = f"https://api.telegram.org/bot{token}/{method}"
    headers = {"Content-Type": "application/json; charset=utf-8"}
    data = json.dumps(payload).encode("utf-8") if payload else None

    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8")
        raise RuntimeError(f"HTTP {e.code}: {error_body}") from e
    except Exception as e:
        raise RuntimeError(f"Lỗi kết nối Telegram ({method}): {e}") from e


def main():
    parser = argparse.ArgumentParser(description="Cấu hình Webhook cho Telegram Bot")
    parser.add_argument("--url", help="URL Webhook công khai (HTTPS)")
    parser.add_argument("--secret", help="Secret token (X-Telegram-Bot-Api-Secret-Token)")
    parser.add_argument("--token", help="Telegram Bot Token")
    parser.add_argument("--drop-pending", action="store_true", default=True, help="Bỏ qua các tin nhắn chờ cũ")
    parser.add_argument("--delete", action="store_true", help="Xoá webhook để chuyển sang chế độ Long Polling")
    args = parser.parse_args()

    env_vals = load_env_values()
    token = args.token or env_vals.get("TELEGRAM_BOT_TOKEN")
    if not token:
        print("LỖI: Chưa có TELEGRAM_BOT_TOKEN trong .env hoặc tham số --token", file=sys.stderr)
        sys.exit(1)

    if args.delete:
        print("--- Đang xoá Webhook để cho phép Long Polling ---")
        res = call_telegram_api(token, "deleteWebhook", {"drop_pending_updates": False})
        print(f"deleteWebhook: {res}")
        info = call_telegram_api(token, "getWebhookInfo").get("result", {})
        print(f"Trạng thái hiện tại: URL='{info.get('url')}'")
        return

    secret = args.secret or env_vals.get("TELEGRAM_WEBHOOK_SECRET")
    url = args.url or env_vals.get("WEBHOOK_URL")

    if not url:
        print("LỖI: Thiếu tham số webhook URL (--url hoặc WEBHOOK_URL trong .env)", file=sys.stderr)
        sys.exit(1)

    if not url.startswith("https://"):
        print("CẢNH BÁO: Telegram yêu cầu Webhook URL phải bắt đầu bằng https://", file=sys.stderr)

    payload = {
        "url": url,
        "drop_pending_updates": args.drop_pending,
        "allowed_updates": ["message", "callback_query"]
    }
    if secret:
        payload["secret_token"] = secret

    print(f"--- Đang đăng ký Webhook tới: {url} ---")
    if secret:
        print("Mã secret_token đã được cấu hình bảo vệ.")

    res = call_telegram_api(token, "setWebhook", payload)
    if not res.get("ok"):
        print(f"LỖI: Đăng ký thất bại. Phản hồi: {res}", file=sys.stderr)
        sys.exit(1)

    print(f"setWebhook: {res.get('description', 'OK')}")
    print("--- Kiểm tra trạng thái Webhook (getWebhookInfo) ---")
    info = call_telegram_api(token, "getWebhookInfo").get("result", {})
    print(f"  URL: {info.get('url')}")
    print(f"  Has custom certificate: {info.get('has_custom_certificate')}")
    print(f"  Pending update count: {info.get('pending_update_count')}")
    print(f"  Allowed updates: {info.get('allowed_updates')}")
    if info.get("last_error_message"):
        print(f"  Lỗi gần nhất từ Telegram: {info.get('last_error_message')} (vào lúc {info.get('last_error_date')})")


if __name__ == "__main__":
    main()
