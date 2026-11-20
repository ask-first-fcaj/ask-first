"""Tạo Bedrock Guardrail cho AI-08 (ARCH §6.10) rồi in id/version để ghi vào config/models.yaml. Chạy từ ai/:

    AWS_PROFILE=<profile> uv run python scripts/create_guardrail.py            # tạo (hoặc dùng lại theo tên) + version 1
    AWS_PROFILE=<profile> uv run python scripts/create_guardrail.py --dry-run  # chỉ in request

Cấu hình (ARCH §6.10 bảng đe doạ):
- Input: **chỉ** filter PROMPT_ATTACK, mức HIGH. Không bật content filter hận thù/bạo lực/xúc phạm ở input vì tin lừa đảo
  vốn chứa đe doạ; chặn nhầm là mất cảnh báo. Pipeline không chặn tin, chỉ đọc kết quả phát hiện.
- Output: denied topics "tư vấn đầu tư" và "hướng dẫn chuyển tiền" (chưa nối vào pipeline: lời giải thích đã qua post_check).
- Tier STANDARD (tiếng Việt "Optimized and supported", research-concept [A4]); STANDARD bắt buộc cross-Region,
  dùng guardrail profile ``--profile-id`` (mặc định ``apac.guardrail.v1:0`` cho ap-southeast-2).
Word filter và contextual grounding không hỗ trợ tiếng Việt nên không bật.

Guardrail không tốn phí cố định; mỗi lần ApplyGuardrail tính theo text unit (bảng giá Bedrock).
"""
from __future__ import annotations

import argparse
import json
import sys

import boto3

NAME = "askfirst-guard"
BLOCKED = "Bot không xử lý được nội dung này."


def guardrail_request(profile_id: str) -> dict:
    return {
        "name": NAME,
        "description": "Ask First AI-08: input chỉ phát hiện prompt attack; output chặn tư vấn đầu tư và hướng dẫn chuyển tiền.",
        "contentPolicyConfig": {
            "filtersConfig": [{"type": "PROMPT_ATTACK", "inputStrength": "HIGH", "outputStrength": "NONE"}],
            "tierConfig": {"tierName": "STANDARD"},
        },
        "topicPolicyConfig": {
            "topicsConfig": [
                {"name": "tu-van-dau-tu", "type": "DENY", "inputEnabled": False, "outputEnabled": True,
                 "definition": "Lời khuyên mua, bán hoặc bỏ tiền vào cổ phiếu, tiền ảo, vàng, ngoại hối hay một kênh đầu tư cụ thể.",
                 "examples": ["Bác nên mua cổ phiếu này ngay vì sắp tăng giá.", "Nạp tiền vào sàn này để lãi 30% mỗi tháng."]},
                {"name": "huong-dan-chuyen-tien", "type": "DENY", "inputEnabled": False, "outputEnabled": True,
                 "definition": "Chỉ dẫn từng bước để chuyển khoản, nạp tiền hoặc gửi tiền cho một người hay tài khoản.",
                 "examples": ["Bác mở app ngân hàng, chọn chuyển khoản rồi nhập số tài khoản này.",
                              "Bác ra cây ATM nạp tiền vào số tài khoản trong tin."]},
            ],
            "tierConfig": {"tierName": "STANDARD"},
        },
        "crossRegionConfig": {"guardrailProfileIdentifier": profile_id},
        "blockedInputMessaging": BLOCKED,
        "blockedOutputsMessaging": BLOCKED,
    }


def find_by_name(client, name: str) -> str | None:
    for page in client.get_paginator("list_guardrails").paginate():
        for g in page.get("guardrails", []):
            if g.get("name") == name:
                return g["id"]
    return None


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--region", default="ap-southeast-2")
    ap.add_argument("--profile-id", default="apac.guardrail.v1:0", help="guardrail profile cho cross-Region (tier STANDARD)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)

    req = guardrail_request(args.profile_id)
    if args.dry_run:
        print(json.dumps(req, ensure_ascii=False, indent=1))
        return 0
    client = boto3.client("bedrock", region_name=args.region)
    gid = find_by_name(client, NAME)
    if gid:
        print(f"đã có guardrail {NAME} ({gid}), cập nhật DRAFT", file=sys.stderr)
        client.update_guardrail(guardrailIdentifier=gid, **req)
    else:
        gid = client.create_guardrail(**req)["guardrailId"]
    version = client.create_guardrail_version(guardrailIdentifier=gid, description="AI-08")["version"]
    print(f'guardrail: {{id: "{gid}", version: "{version}", timeout_s: 1}}')
    return 0


if __name__ == "__main__":
    sys.exit(main())
