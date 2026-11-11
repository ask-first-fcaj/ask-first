# ADR-007: DynamoDB Enhanced Client và Spike DynamoDB Local

- **Trạng thái:** Bản nháp kỹ thuật. Spike đã có code và test; **số đo chưa có** (xem mục 6).
- **Người thực hiện:** BE2 (pair BE1).
- **Task:** BE-14. Mở khoá BE-22, BE-24, BE-24b.

---

## 1. Bối cảnh

Hệ thống AskFirst thiết kế theo kiến trúc Single-Table Design trên DynamoDB (ARCH §7) để quản lý các thực thể:
- `DEDUP#<update_id>` (chống trùng lặp tin nhắn, TTL 24 giờ)
- `USER#<user_id>` (thông tin người dùng và đồng ý chính sách)
- `CHECK#<check_id>` (lịch sử kiểm tra lừa đảo)
- `BUFFER#<chat_id>` (gom tin nhắn)
- `CONFIG#global/FLAGS` (cờ tính năng)

Mục tiêu spike BE-14:
1. Xác nhận **AWS SDK for Java v2 DynamoDB Enhanced Client** ánh xạ bean và schema đúng.
2. Kiểm chứng ghi có điều kiện (`attribute_not_exists(PK)`) cho cơ chế dedup ở webhook (BE-22).
3. Chạy test DynamoDB trên Windows, macOS và Linux mà không cần AWS thật.

---

## 2. Sơ đồ kiến trúc lưu trữ và dedup

```mermaid
flowchart TD
    Update["Telegram Update\nupdate_id"] --> Webhook["WebhookHandler (Java 21)"]
    Webhook --> DedupCheck{"Ghi DEDUP#update_id\nattribute_not_exists(PK)"}

    subgraph DynamoDB ["Bảng askfirst-<stage> (LocalStack / AWS)"]
        TableStore[("PK: PK (String)\nSK: SK (String)\nGSI1: GSI1PK / GSI1SK\nTTL: ttl (epoch sec)")]
    end

    DedupCheck -->|Thành công (chưa có)| StoreRecord["Ghi bản ghi DEDUP\nTTL = now + 24h"]
    StoreRecord --> TableStore
    StoreRecord --> FastSlow["Phân luồng: Fast Path hoặc enqueue SQS"]

    DedupCheck -->|ConditionalCheckFailedException| Ignore["Bỏ qua tin trùng\nTrả HTTP 200 ngay"]
```

---

## 3. Kết quả đánh giá

| Tiêu chí | Kết quả | Trạng thái |
|---|---|---|
| **Mapping Java Bean** | `TableSchema.fromBean(DedupRecord.class)` cho khóa `PK` và `SK` (hoa), đúng ARCH §7 | Test `DynamoSpikeTest.testDedupRecordTableSchema` **đang nằm chung class với test Testcontainers** nên cũng bị skip ngoài Docker. Cần tách ra để chạy được trên mọi OS |
| **Ghi có điều kiện (dedup)** | `attribute_not_exists(PK)` trả `false` ở lần ghi thứ hai | Có test Testcontainers, chạy thật trên Ubuntu CI |
| **Hỗ trợ TTL** | Trường `ttl` (Long, epoch seconds), bật bằng `createTableIfNotExists` | Có test bật TTL; chưa đo thời gian xóa thực tế |
| **Tương thích hệ điều hành** | Test integration dùng `amazon/dynamodb-local` qua Testcontainers | Chỉ chạy khi có Docker. Windows/macOS runner trên CI **không** chạy test này |

> Ghi chú: phiên bản trước của ADR này ghi "đã kiểm chứng trên Windows, macOS, Ubuntu". Điều đó **không đúng**: test cũ bắt exception rồi cho pass khi không kết nối được DynamoDB, nên không chứng minh được gì. Đã loại bỏ các khối catch-all; mục này chỉ được coi là đạt khi CI Ubuntu xanh và báo cáo surefire không có test nào bị skip.

---

## 4. Quyết định kỹ thuật

1. **Thư viện:** `software.amazon.awssdk:dynamodb-enhanced` cùng phiên bản với `dynamodb` (AWS SDK v2) cho tầng lưu trữ `com.askfirst.common.store`.
2. **Cấu trúc bảng:** một bảng cho mỗi stage, tên `askfirst-<stage>` (ví dụ `askfirst-dev`), do `infra/template.yaml` tạo:
   - Partition key: `PK` (String, HASH); sort key: `SK` (String, RANGE)
   - GSI1: `GSI1PK` (HASH), `GSI1SK` (RANGE), projection ALL
   - Billing: `PAY_PER_REQUEST`; TTL attribute: `ttl`
   - Khóa phải viết hoa: DynamoDB phân biệt hoa thường, dùng `pk` sẽ bị `ValidationException` khi chạy với bảng SAM.
3. **Môi trường dev:**
   - Chạy thử thủ công: LocalStack qua `docker compose up -d` (cổng `4566`) hoặc `scripts/setup-localstack.sh`.
   - Test tự động: Testcontainers với `amazon/dynamodb-local`. Test tự bỏ qua khi không có Docker. CI Ubuntu có job kiểm tra rằng test DynamoDB không bị skip, nên không có chuyện "xanh giả".

---

## 5. Quyết định bổ sung (từ review PR)

| Chủ đề | Quyết định | Lý do |
|---|---|---|
| Rate limit khi DynamoDB lỗi | **Fail-open**: cho người dùng đi tiếp, ghi log WARN | Chặn nhầm người cao tuổi đang cần kiểm tra tin lừa đảo còn nguy hiểm hơn việc tạm thời không đếm được |
| Cập nhật user, guardian | Khóa lạc quan theo `updated_at` (`OptimisticUpdates`), retry tối đa 5 lần | Hai request song song không được đè mất thay đổi của nhau |
| Audit trail | Ghi có điều kiện `attribute_not_exists(PK)`; trùng SK thì thêm hậu tố ngẫu nhiên | Audit là bằng chứng đồng ý (NĐ 356), không được ghi đè |
| Dedup `update_id` | Nhả khóa dedup chỉ khi chưa đưa job vào SQS (trả 5xx để Telegram gửi lại). Các lỗi khác trả 200 | Tránh xử lý đôi (ghi audit, đếm rate limit, enqueue hai lần) khi Telegram retry |
| Secret webhook | Fail-closed: thiếu `TELEGRAM_WEBHOOK_SECRET` thì từ chối mọi request. Chỉ `LocalBotRunner` được bỏ qua | Function URL công khai (`AuthType: NONE`), secret là lớp xác thực duy nhất |
| Timeout Telegram | Mỗi request 4 giây, tổng ngân sách 8 giây cho một lời gọi, không ngủ quá 2 giây khi bị 429 | Lambda timeout 30 giây |
| Đồng ý / từ chối | Đồng ý chỉ bật `analyze`; từ chối thu hồi toàn bộ mục đích | Không tự động bật lại quyền người dùng đã chọn tắt |
| Lưu secret | **Đang chờ quyết định** (xem mục 7) | Template hiện truyền secret vào biến môi trường Lambda |

---

## 6. Số đo (BE-14 yêu cầu)

Chưa có số đo. Không ghi con số nào cho đến khi có kết quả đo thật. Cách đo:

| Chỉ số | Cách đo | Kết quả |
|---|---|---|
| Độ trễ `tryAcquireDedup` p50/p95 | Chạy 1000 lần ghi trên DynamoDB Local và trên dev AWS, ghi lại thời gian | [chưa đo] |
| Độ trễ `checkAndIncrement` p50/p95 | Như trên, với hai `UpdateItem` mỗi lần gọi | [chưa đo] |
| Số lần `ConditionalCheckFailedException` đúng khi gửi trùng | Gửi cùng `update_id` 10 lần, kỳ vọng 1 lần thành công và 9 lần từ chối | [chưa đo] |
| Thời gian TTL xóa bản ghi hết hạn | Ghi bản ghi có `ttl` = hiện tại + 60 giây, theo dõi khi nào bị xóa | [chưa đo, DynamoDB không đảm bảo thời điểm chính xác] |

---

## 7. Việc còn mở

- Quyết định cách Lambda đọc `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET` (`SecureString` đọc lúc chạy hay biến môi trường `String`). Ảnh hưởng đến `infra/template.yaml`, `WebhookHandler`, `infra/README.md`.
- Số đo mục 6.
