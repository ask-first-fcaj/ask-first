"""`KVCache` trên bảng DynamoDB single-table `askfirst` (AI-03b, ARCH §7).

Item: `PK = <prefix><key>`, `SK` cố định theo loại (`CACHE#…/VERDICT`, `URL#…/INTEL`), giá trị lưu dạng **chuỗi JSON**
trong thuộc tính `value` (tránh việc boto3 đổi float thành Decimal), hết hạn qua thuộc tính TTL `ttl` (epoch giây).
⚠️ Tên thuộc tính TTL phải khớp `TimeToLiveSpecification` của bảng trong `infra/template.yaml` (BE-03).

DynamoDB xoá item hết hạn trễ tới vài chục giờ, nên `get` tự bỏ qua item đã quá `ttl`.
Chỉ lưu dữ liệu không có nội dung người dùng (vd. `Verdict.for_cache()`); lớp này không kiểm điều đó.
"""

from __future__ import annotations

import json
import time
from collections.abc import Callable
from typing import Any


class DynamoKV:
    def __init__(self, table: Any, prefix: str, sk: str, ttl_attr: str = "ttl", now: Callable[[], float] = time.time) -> None:
        """`table` = `boto3.resource("dynamodb").Table(TABLE_NAME)`; test truyền bảng giả."""
        self.table, self.prefix, self.sk, self.ttl_attr, self.now = table, prefix, sk, ttl_attr, now

    def _key(self, key: str) -> dict[str, str]:
        return {"PK": f"{self.prefix}{key}", "SK": self.sk}

    def get(self, key: str) -> dict | None:
        item = self.table.get_item(Key=self._key(key)).get("Item")
        if not item or int(item.get(self.ttl_attr, 0)) <= self.now():
            return None
        return json.loads(item["value"])

    def put(self, key: str, value: dict, ttl_s: int) -> None:
        self.table.put_item(Item=self._key(key) | {"value": json.dumps(value, ensure_ascii=False), self.ttl_attr: int(self.now()) + ttl_s})
