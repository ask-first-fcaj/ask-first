# AskFirst Backend (Scam Protection Bot)

Hệ thống backend xử lý tin nhắn Telegram, phát hiện dấu hiệu lừa đảo và bảo vệ người dùng, đặc biệt là người cao tuổi.

---

## 1. Cấu trúc các module

```
backend/ScamProtection/
├── common/             # TelegramClient, TelegramAdapter, Data Model, DynamoDB Store
├── webhook-handler/    # Lambda nhận webhook từ Telegram (Fast path & Slow path)
│   └── local/          # LocalBotRunner (chế độ Long Polling chạy thử nghiệm trên máy local)
└── worker/             # Lambda xử lý tin phân tích lừa đảo từ SQS
```

---

## 2. Chạy test DynamoDB local (BE-14)

Để kiểm chứng tương thích với DynamoDB Enhanced Client và Single-table Design trên cả Windows, macOS và Linux:

### Cách 1: Chạy qua LocalStack (Khuyên dùng)
1. Khởi động LocalStack bằng Docker Compose:
   ```bash
   cd backend/ScamProtection
   docker compose up -d
   ```
2. Chạy test:
   ```bash
   # Linux/macOS
   ./mvnw test -pl common -Dtest=DynamoSpikeTest
   # Windows PowerShell
   .\mvnw.cmd test -pl common -Dtest=DynamoSpikeTest
   ```

### Cách 2: Chạy qua Docker standalone `amazon/dynamodb-local`
```bash
docker run -d -p 8000:8000 amazon/dynamodb-local
AWS_ENDPOINT_URL=http://localhost:8000 ./mvnw test -pl common -Dtest=DynamoSpikeTest
```

### Cách 3: Testcontainers trong CI
Khi môi trường CI có Docker daemon hoạt động, JUnit test sẽ tự động tương tác với container DynamoDB Local hoặc LocalStack để kiểm tra:
- Tạo bảng `askfirst-main` với Partition Key `pk` (String) và Sort Key `sk` (String).
- Lệnh ghi có điều kiện `attribute_not_exists(pk)` để loại bỏ tin nhắn trùng lặp (Dedup / Idempotency cho BE-22).
- Thuộc tính TTL tự động dọn dẹp bản ghi sau 24 giờ.

---

## 3. Chạy Bot chế độ Local (Long Polling - BE-20)

Chế độ Long Polling cho phép dev lập trình và test bot ngay trên máy cá nhân mà không cần cài đặt hoặc duy trì tunnel ngrok:

1. Đảm bảo file `backend/ScamProtection/.env` đã có `TELEGRAM_BOT_TOKEN`.
2. Chạy lệnh:
   ```bash
   # Tại thư mục backend/ScamProtection
   ./mvnw exec:java@local-bot
   # Trên Windows PowerShell
   .\mvnw.cmd exec:java@local-bot
   ```
3. Mở Telegram, nhắn `/start` hoặc `/huongdan` vào bot để thấy log hiển thị ngay lập tức trên màn hình console.

---

## 4. Các script cấu hình (Python)

Tất cả các script đều chạy được trên Windows, macOS và Linux bằng thư viện chuẩn của Python:

- **Cài đặt Menu lệnh bot (`scripts/set_commands.py`):**
  ```bash
  python scripts/set_commands.py
  ```
  Cài đặt các lệnh: `/batdau`, `/huongdan`, `/caidat`, `/nguoithan`, `/lichsu`, `/bilua`.

- **Cấu hình Webhook lên AWS Lambda (`scripts/set_webhook.py`):**
  ```bash
  python scripts/set_webhook.py --url https://<api-gateway-id>.execute-api.ap-southeast-1.amazonaws.com/dev/webhook
  ```
  Hoặc gỡ webhook để chuyển sang test local:
  ```bash
  python scripts/set_webhook.py --delete
  ```

