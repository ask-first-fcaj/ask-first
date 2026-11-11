#!/usr/bin/env python3
"""
Script set_commands.py: Cài đặt danh sách lệnh hiển thị ở nút Menu Telegram Bot.
Đáp ứng BE-20 (§6.2, ARCH §5.3, §5.9).

Chạy:
  python scripts/set_commands.py
Hoặc:
  python scripts/set_commands.py --token <TELEGRAM_BOT_TOKEN>
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

# Danh sách lệnh chuẩn theo kế hoạch triển khai BE-20
BOT_COMMANDS = [
    {
        "command": "batdau",
        "description": "Bắt đầu sử dụng bot và tìm hiểu tính năng"
    },
    {
        "command": "huongdan",
        "description": "Hướng dẫn cách kiểm tra tin nhắn lừa đảo"
    },
    {
        "command": "caidat",
        "description": "Cài đặt cách xưng hô và chia sẻ"
    },
    {
        "command": "nguoithan",
        "description": "Quản lý danh sách người thân kết nối"
    },
    {
        "command": "lichsu",
        "description": "Xem lại lịch sử các tin đã kiểm tra"
    },
    {
        "command": "bilua",
        "description": "Hướng dẫn xử lý khẩn cấp khi nghi bị lừa"
    }
]


def load_env_token() -> str:
    """Đọc token từ biến môi trường hoặc các file .env phổ biến."""
    token = os.environ.get("TELEGRAM_BOT_TOKEN")
    if token and token.strip():
        return token.strip()

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
                        if line.startswith("TELEGRAM_BOT_TOKEN="):
                            val = line.split("=", 1)[1].strip().strip('"').strip("'")
                            if val:
                                return val
            except Exception:
                pass

    return ""


def call_telegram_api(token: str, method: str, payload: dict | None = None) -> dict:
    url = f"https://api.telegram.org/bot{token}/{method}"
    headers = {"Content-Type": "application/json; charset=utf-8"}
    data = json.dumps(payload).encode("utf-8") if payload else None

    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8")
        raise RuntimeError(f"HTTP {e.code}: {error_body}") from e
    except Exception as e:
        raise RuntimeError(f"Lỗi kết nối Telegram: {e}") from e


def main():
    parser = argparse.ArgumentParser(description="Cài đặt menu lệnh Telegram Bot")
    parser.add_argument("--token", help="Telegram Bot Token (nếu không truyền sẽ đọc từ .env)")
    args = parser.parse_args()

    token = args.token or load_env_token()
    if not token:
        print("LỖI: Chưa có TELEGRAM_BOT_TOKEN (không tìm thấy trong .env hoặc tham số --token).", file=sys.stderr)
        sys.exit(1)

    print("--- Đang kết nối tới Telegram Bot API ---")
    me = call_telegram_api(token, "getMe")
    if not me.get("ok"):
        print(f"LỖI: Token không hợp lệ. Phản hồi: {me}", file=sys.stderr)
        sys.exit(1)

    bot_info = me.get("result", {})
    print(f"Bot: @{bot_info.get('username')} ({bot_info.get('first_name')}) [ID: {bot_info.get('id')}]")

    print(f"--- Đang cài đặt {len(BOT_COMMANDS)} lệnh menu ---")
    payload = {"commands": BOT_COMMANDS}
    res = call_telegram_api(token, "setMyCommands", payload)
    if not res.get("ok"):
        print(f"LỖI: Không đặt được lệnh. Phản hồi: {res}", file=sys.stderr)
        sys.exit(1)

    print("Cài đặt lệnh thành công!")
    print("--- Kiểm tra danh sách lệnh hiện tại (getMyCommands) ---")
    current_cmds = call_telegram_api(token, "getMyCommands").get("result", [])
    for c in current_cmds:
        print(f"  /{c.get('command')}: {c.get('description')}")


if __name__ == "__main__":
    main()
