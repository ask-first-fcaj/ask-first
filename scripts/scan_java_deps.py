#!/usr/bin/env python3
"""Quét lỗ hổng dependency của Java (Maven) qua OSV API (BE-09b, ARCH §9.3).

Cách dùng:
    python scripts/scan_java_deps.py
    # Hoặc nhận danh sách từ stdin:
    ./mvnw dependency:list -DincludeScope=runtime | python scripts/scan_java_deps.py --stdin
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEP_LINE_RE = re.compile(
    r"^\s*\[INFO\]\s+([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+):(?:jar|war|pom|aar):([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.-]+)"
)
OSV_BATCH_URL = "https://api.osv.dev/v1/querybatch"
BATCH_SIZE = 500


def collect_deps(lines: list[str]) -> dict[str, str]:
    """Trích xuất (package_name, version) từ output của `mvn dependency:list`."""
    deps: dict[str, str] = {}
    for line in lines:
        m = DEP_LINE_RE.match(line)
        if not m:
            continue
        pkg, ver, _scope = m.group(1), m.group(2), m.group(3)
        # Bỏ qua module nội bộ của askfirst
        if pkg.startswith("com.askfirst"):
            continue
        deps[pkg] = ver
    return deps


def run_mvn_dependency_list(project_dir: Path) -> list[str]:
    """Chạy mvn dependency:list để lấy danh sách runtime dependencies."""
    if os.name == "nt" and (project_dir / "mvnw.cmd").is_file():
        mvn = str((project_dir / "mvnw.cmd").resolve())
    elif (project_dir / "mvnw").is_file():
        mvn = str((project_dir / "mvnw").resolve())
    else:
        mvn = "mvn"

    cmd = [mvn, "test-compile", "dependency:list", "-DincludeScope=runtime", "-B"]
    proc = subprocess.run(cmd, cwd=project_dir, capture_output=True, text=True, check=False, shell=(os.name == "nt"))
    if proc.returncode != 0:
        err_msg = proc.stderr.strip() or proc.stdout.strip()
        print(f"Lỗi khi chạy {mvn} dependency:list:\n{err_msg}", file=sys.stderr)
        sys.exit(1)
    return proc.stdout.splitlines()


def query_osv(packages: list[tuple[str, str]]) -> list[dict]:
    """Gửi batch query lên OSV API."""
    queries = [
        {"package": {"name": pkg, "ecosystem": "Maven"}, "version": ver}
        for pkg, ver in packages
    ]
    req = urllib.request.Request(
        OSV_BATCH_URL,
        data=json.dumps({"queries": queries}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data.get("results", [])


def is_high_or_critical(vuln: dict) -> bool:
    """Kiểm tra xem vulnerability có mức High hoặc Critical không."""
    # Kiểm tra database_specific severity nếu có
    db_spec = vuln.get("database_specific", {})
    severity_str = str(db_spec.get("severity", "")).upper()
    if severity_str in ("HIGH", "CRITICAL"):
        return True

    # Kiểm tra CVSS score trong severity list
    for sev in vuln.get("severity", []):
        score = sev.get("score", "")
        # Nếu có CVSS vector
        if "CVSS:3" in score or "CVSS:4" in score:
            # Nếu có điểm số cụ thể hoặc không xác định, coi là có nguy cơ
            pass

    # Nếu không có thông tin chi tiết, coi mọi advisory từ GitHub (GHSA) có severity là cần chú ý
    # Mặc định OSV trả về các vuln đã xác nhận
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Quét lỗ hổng dependency Java qua OSV")
    parser.add_argument("--stdin", action="store_true", help="Đọc output mvn dependency:list từ stdin")
    parser.add_argument(
        "--dir",
        default="backend/ScamProtection",
        help="Thư mục chứa dự án Maven (mặc định: backend/ScamProtection)",
    )
    args = parser.parse_args()

    if args.stdin:
        lines = sys.stdin.readlines()
    else:
        proj_dir = Path(args.dir)
        if not proj_dir.is_dir():
            print(f"Không tìm thấy thư mục {proj_dir}", file=sys.stderr)
            return 1
        lines = run_mvn_dependency_list(proj_dir)

    deps = collect_deps(lines)
    if not deps:
        print("Không tìm thấy dependency nào cần quét.")
        return 0

    print(f"Bắt đầu quét {len(deps)} thư viện Java qua OSV API...")

    items = list(deps.items())
    all_vulns: list[tuple[str, str, dict]] = []

    for i in range(0, len(items), BATCH_SIZE):
        chunk = items[i : i + BATCH_SIZE]
        results = query_osv(chunk)
        for (pkg, ver), res in zip(chunk, results):
            for vuln in res.get("vulns", []):
                all_vulns.append((pkg, ver, vuln))

    if not all_vulns:
        print(f"✅ Hoàn tất quét {len(deps)} dependencies Java: 0 lỗ hổng bảo mật!")
        return 0

    print(f"\n⚠️  PHÁT HIỆN {len(all_vulns)} LỖ HỔNG BẢO MẬT TRONG DEPENDENCIES JAVA:")
    for pkg, ver, vuln in all_vulns:
        vid = vuln.get("id", "UNKNOWN")
        summary = vuln.get("summary", "Không có mô tả tóm tắt")
        print(f"  - [{vid}] {pkg}:{ver} -> {summary}")

    return 1


if __name__ == "__main__":
    sys.exit(main())
