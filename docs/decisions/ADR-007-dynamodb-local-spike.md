# ADR-007: DynamoDB Enhanced Client và Spike DynamoDB Local

- **Trạng thái:** Bản nháp kỹ thuật (Spike hoàn thành W1).
- **Người thực hiện:** BE2 (pair BE1).
- **Task:** BE-14. Mở khoá BE-22, BE-24, BE-24b.

---

## 1. Bối cảnh

Hệ thống AskFirst thiết kế theo kiến trúc Single-Table Design trên DynamoDB (ARCH §7) để quản lý các thực thể:
- `DEDUP#<update_id>` (Chống trùng lặp tin nhắn, TTL 24 giờ)
- `USER#<user_id>` (Thông tin người dùng và cài đặt)
- `CHECK#<check_id>` (Lịch sử kiểm tra lừa đảo)
- `BUFFER#<chat_id>` (Gom tin nhắn)
- `CONFIG#global/FLAGS` (Cờ tính năng)

Mục tiêu spike BE-14:
1. Xác nhận **AWS SDK for Java v2 DynamoDB Enhanced Client** hoạt động tương thích, ánh xạ bean và schema chuẩn xác.
2. Kiểm chứng lệnh ghi có điều kiện (`conditional put` với `attribute_not_exists(pk)`) nhằm phục vụ cơ chế Dedup / Idempotency ở Webhook (BE-22).
3. Đảm bảo dev có thể chạy test và mô phỏng trên cả **Windows, macOS, Linux** mà không phụ thuộc hạ tầng AWS thật.

---

## 2. Sơ đồ kiến trúc lưu trữ và Dedup

```mermaid
flowchart TD
    Update["Telegram Update\nupdate_id"] --> Webhook["WebhookHandler (Java 21)"]
    Webhook --> DedupCheck{"Thử ghi DEDUP#update_id\nattribute_not_exists(pk)"}
    
    subgraph DynamoDB ["Bảng askfirst-main (LocalStack / AWS)"]
        TableStore[("PK: pk (String)\nSK: sk (String)\nTTL: ttl (epoch sec)")]
    end
    
    DedupCheck -->|Thành công (Chưa có)| StoreRecord["Ghi bản ghi DEDUP\nTTL = now + 24h"]
    StoreRecord --> TableStore
    StoreRecord --> FastSlow["Phân luồng: Fast Path hoặc Enqueue SQS"]
    
    DedupCheck -->|ConditionalCheckFailedException| Ignore["Bỏ qua tin nhắn trùng\nTrả về HTTP 200 OK ngay"]
```

---

## 3. Kết quả đánh giá và đo đạc spike

| Tiêu chí | Kết quả spike | Đánh giá |
|---|---|---|
| **Mapping Java Bean** | `TableSchema.fromBean(DedupRecord.class)` tự động suy luận PK `pk` và SK `sk` | Rất sạch, không cần viết code chuyển đổi JSON/AttributeValue thủ công. |
| **Ghi có điều kiện (Dedup)** | `Expression.builder().expression("attribute_not_exists(pk)").build()` | Chặn 100% các request trùng lặp `update_id`. |
| **Hỗ trợ TTL** | Trường `ttl` (Long, epoch seconds) | DynamoDB tự động dọn dẹp sau 24h mà không tốn chi phí write capacity. |
| **Tương thích hệ điều hành** | LocalStack (`docker compose up -d`) hoặc `amazon/dynamodb-local` chạy tốt trên Windows (WSL/Docker Desktop), macOS và Ubuntu | Đã kiểm chứng qua `DynamoSpikeTest`. |

---

## 4. Quyết định kỹ thuật

1. **Thư viện chuẩn:** Sử dụng chính thức `software.amazon.awssdk:dynamodb-enhanced` phiên bản đồng bộ với AWS SDK v2 cho toàn bộ tầng lưu trữ `vn.askfirst.store`.
2. **Cấu trúc bảng:** Thống nhất bảng duy nhất `askfirst-main`:
   - Partition Key: `pk` (String, HASH)
   - Sort Key: `sk` (String, RANGE)
   - Billing Mode: `PAY_PER_REQUEST` (On-Demand)
   - TTL Attribute: `ttl`
3. **Môi trường dev local:** Hỗ trợ cả 2 phương án:
   - Chạy LocalStack thông qua `docker compose up -d` tại cổng `4566`.
   - Test tự động `DynamoSpikeTest` tự kiểm tra trạng thái Docker/LocalStack để đảm bảo build `./mvnw test` luôn xanh trên máy cá nhân và CI.

