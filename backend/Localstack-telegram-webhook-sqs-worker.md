# feat(backend): bot Telegram chống lừa đảo chạy local — webhook → SQS → worker (LocalStack)

## Mục đích

Dựng khung chạy end-to-end trên máy local, mô phỏng AWS bằng LocalStack, để kiểm chứng luồng
**nhận tin Telegram → xếp hàng → xử lý bất đồng bộ → trả lời người dùng**.

Ở PR này worker **chưa phân tích nội dung**: mọi tin text đều nhận cùng một cảnh báo cố định.
Phần retry/DLQ, gom tin theo người dùng và phân tích bằng Lambda Python nằm ở PR kế tiếp.

## Luồng xử lý

```
Telegram ──HTTPS──▶ ngrok ──▶ API Gateway (LocalStack) ──▶ Lambda `webhook-handler`
                                                              │ 1. kiểm tra secret token
                                                              │ 2. đẩy tin text vào SQS
                                                              ▼ 3. nhắn "Vui lòng chờ..."
                                                     SQS `webhookQueue`
                                                              │
                                                              ▼
                                                     Lambda `worker` ──▶ Telegram (cảnh báo cố định)
```

## Những gì đã setup / cấu hình

### 1. Bot Telegram
- Tạo bằng @BotFather; token nằm trong `.env` (không commit).
- Webhook đăng ký bằng `scripts/set-webhook.sh`:
  - đặt `secret_token`; Lambda kiểm tra header `X-Telegram-Bot-Api-Secret-Token`, sai thì trả `403`;
  - `drop_pending_updates=true` để không xử lý lại tin cũ;
  - thử lại tối đa 3 lần vì kết nối tới `api.telegram.org` đôi lúc bị timeout.

### 2. Cấu trúc code (Java 21, Maven multi-module)

| Module | Vai trò |
|---|---|
| `common` | `TelegramClient` (gọi `sendMessage` bằng `java.net.http`) |
| `webhook-handler` | Lambda nhận webhook từ Telegram |
| `worker` | Lambda xử lý tin lấy từ SQS |

- Lambda viết bằng Java thuần, không khởi động Spring context (handler cần constructor public không tham số).
- `maven-shade-plugin` khai trong `pluginManagement` ở root và chỉ bật ở hai module Lambda để ra fat-jar;
  có `ServicesResourceTransformer` cho AWS SDK.

### 3. Lambda `webhook-handler`
- Xác thực secret token; chỉ xử lý update có `message.text`, loại khác (ảnh, sticker...) trả `200` và bỏ qua.
- Đẩy tin vào SQS rồi nhắn "Vui lòng chờ...".
- Lỗi khi đẩy SQS thì trả `500` để Telegram gửi lại; lỗi ở bước "Vui lòng chờ" không được làm Telegram gửi lại.

### 4. Lambda `worker`
- Nhận record từ SQS, gửi cảnh báo cố định tới đúng `chatId`.
- Gửi lỗi thì ném exception để SQS giao lại message.

### 5. SQS
- Queue `webhookQueue`, visibility timeout 60 giây.
- Trigger SQS → `worker` với `batch-size = 1`.

### 6. API Gateway
- REST API `telegram-webhook-api`, stage `dev`, `POST /webhook`, tích hợp `AWS_PROXY` tới `webhook-handler`.
- URI tích hợp dùng biến `${REGION}` (trước đây gõ cứng `us-east-1` nên API Gateway trả `502` do tìm Lambda sai region).

### 7. LocalStack (`docker-compose.yml`)
- Image `localstack/localstack:latest`, cổng `4566`.
- **Bắt buộc có `LOCALSTACK_AUTH_TOKEN`** (bản mới của LocalStack yêu cầu token), khai trong `.env`.
- `SERVICES=lambda,sqs,apigateway,iam,logs`.
- Mount `/var/run/docker.sock` để LocalStack tự tạo container chạy Lambda (vì vậy `docker ps` có thêm các container `localstack-lambda-*`).
- Volume `./localstack-data` lưu trạng thái.

### 8. Region và credential
- Region **`ap-southeast-1` (Singapore)**.
- Dùng profile AWS CLI `localstack` (khóa giả `test/test`), tách hẳn khỏi profile AWS thật;
  script `unset` các biến `AWS_ACCESS_KEY_ID/SECRET/SESSION_TOKEN` để không dính khóa thật còn trong terminal.
- Lambda gọi ngược lại LocalStack qua `AWS_ENDPOINT_URL=http://localhost.localstack.cloud:4566`.

### 9. Biến môi trường của Lambda

| Lambda | Biến |
|---|---|
| `webhook-handler` | `WEBHOOK_QUEUE_URL`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_WEBHOOK_SECRET`, `AWS_ENDPOINT_URL` |
| `worker` | `TELEGRAM_BOT_TOKEN` |

Cấu hình chung: runtime `java21`, timeout 30 giây, bộ nhớ 1024 MB.

### 10. Script trong `scripts/`

| Script | Việc làm |
|---|---|
| `setup-localstack.sh` | Build Maven, tạo queue, deploy 2 Lambda, đợi `function-active-v2`, tạo trigger SQS, dựng API Gateway. Chạy lại nhiều lần được (xóa function và API cũ trước khi tạo lại). |
| `set-webhook.sh` | Lấy token từ `.env`, URL từ ngrok (`127.0.0.1:4040`), `API_ID` từ LocalStack rồi gọi `setWebhook`; có retry. |
| `logs.sh` | Xem log từng Lambda, trạng thái SQS và trigger. Dùng `MSYS_NO_PATHCONV=1` để Git Bash không làm hỏng đường dẫn `/aws/lambda/...`. |

> Sau **mỗi lần** chạy `setup-localstack.sh` phải chạy lại `set-webhook.sh`, vì `API_ID` đổi.

### 11. ngrok
- `ngrok http 4566` để Telegram gọi được vào LocalStack (webhook bắt buộc là HTTPS công khai).
- Gói Free: URL đổi mỗi lần chạy lại ngrok. Có thể xem request tại `http://127.0.0.1:4040`.

### 12. Bảo mật
- Token và secret chỉ nằm trong `.env`, đã nằm trong `.gitignore`; không ghi token vào log.
- Webhook xác thực bằng `secret_token`, nên không ai gọi giả được dù biết URL ngrok.
- `.env` phải lưu dạng LF (CRLF làm token và secret bị dính ký tự `\r`).

## Cách chạy

```bash
# 1. Điền .env: LOCALSTACK_AUTH_TOKEN, TELEGRAM_BOT_TOKEN, TELEGRAM_WEBHOOK_SECRET
docker compose up -d
ngrok http 4566                      # cửa sổ riêng, để chạy suốt
./scripts/setup-localstack.sh
./scripts/set-webhook.sh
# 2. Nhắn một tin text cho bot, rồi xem log:
./scripts/logs.sh
```

Yêu cầu: Docker Desktop, JDK 21, AWS CLI v2, ngrok (đã `config add-authtoken`), Git Bash hoặc WSL.
Cần profile `localstack`: `aws configure --profile localstack` (key `test`/`test`, region `ap-southeast-1`).

## Đã kiểm thử thủ công
- Nhắn tin text cho bot → nhận cảnh báo cố định.
- `logs.sh`: SQS `SendMessage` trả `200`, worker được trigger, queue trở về 0 sau khi xử lý.
- ngrok inspector thấy request từ Telegram được xử lý thành công.

## Lưu ý và hạn chế
- Kết nối tới `api.telegram.org` từ Việt Nam đôi lúc bị timeout (Telegram bị chặn chập chờn ở một số nhà mạng),
  ảnh hưởng cả `set-webhook.sh` lẫn việc Lambda gửi tin. Tin "Vui lòng chờ" vì vậy có thể thỉnh thoảng không tới nơi;
  cảnh báo từ worker thì được SQS giao lại khi lỗi.
- Lần gọi đầu sau khi deploy mất khoảng 10–20 giây (Java cold start trong LocalStack).
- Worker trả cảnh báo cho **mọi** tin text, kể cả `/start`.
- Chưa có test tự động.

## Ngoài phạm vi (PR kế tiếp)
- SQS retry tối đa 5 lần rồi chuyển sang DLQ.
- Worker gom tin theo từng người dùng trong cửa sổ 3 giây.
- Tách bước lọc nội dung, dựng prompt và gửi JSON sang Lambda Python để phân tích.

## Checklist trước khi merge
- [ ] `./mvnw clean package` build thành công
- [ ] `.env` không nằm trong commit (`git ls-files | grep -E '\.env$'` không có kết quả)
- [ ] Chạy lại `setup-localstack.sh` và `set-webhook.sh` từ môi trường sạch, nhắn bot nhận được cảnh báo
- [ ] Đã xóa các file sinh sẵn không dùng (`src/` ở thư mục gốc, `*ApplicationTests.java`, `TelegramUpdate.java`)
