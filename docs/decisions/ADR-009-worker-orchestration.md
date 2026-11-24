# ADR-009: Worker orchestration, BUFFER# và idempotency

- Ngày: 08/11/2026
- Người viết: BE1
- Gate: không (BE-27, BE-23, BE-24b, BE-29 phần worker)
- Trạng thái: đã cài trong code; **chưa deploy**, chưa đo trên Lambda thật.

## Bối cảnh

Plan §3.2 và ARCH §5.2 mô tả luồng gom tin và worker bằng mã giả, nhưng chưa nói cách giữ đúng khi SQS giao lại một job (at-least-once), khi hai webhook chạy song song, và khi Telegram hoặc `ai` lỗi giữa chừng. Webhook trước đây chỉ đưa nguyên `InboundMessage` vào SQS, không có `seq`, `BUFFER#` hay `placeholder_id`.

## Quyết định

**1. Cấu trúc BUFFER#** (khác ARCH §7 ở chỗ tách item):
- `BUFFER#<chat_id>` / `META`: `max_seq`, `done_seq`, `placeholder_id`, `ttl`.
- `BUFFER#<chat_id>` / `MSG#<seq 10 chữ số>`: `payload` là `InboundMessage` dạng JSON, `ttl`.
- Webhook cấp `seq` bằng `UpdateItem ... ADD max_seq 1` (nguyên tử, trả về giá trị mới), rồi ghi `MSG#<seq>`. Một list trong cùng item không an toàn khi hai webhook chạy đồng thời.
- Chỉ tin `seq == 1` gửi "⏳" và lưu `placeholder_id`. Tin sau đó, sau khi cụm cũ đã đóng, nhận kết quả thành tin mới.
- Kết thúc: xoá các `MSG#` đến `seq` của job trước, rồi xoá `META` có điều kiện `max_seq == seq`; điều kiện sai thì `done_seq = seq` và bỏ `placeholder_id`. Thứ tự này để tin mới đến giữa chừng không bị xoá.

**2. Job SQS** (`common/.../job`): envelope `v`, `type`, `job_id`, `chat_id` (String), `enqueued_at` cộng field riêng của `analyze`, `answer`, `followup` theo §3.4. `JobCodec` là nơi duy nhất đọc/ghi; lỗi contract chỉ mang mã (`malformed_json`, `unsupported_version`, `unknown_type`, `invalid_field:<tên>`), không mang nội dung. `check_id` = `job_id` của job `analyze`.

**3. Idempotency khi SQS giao lại:**
- Job có `seq < max_seq` hoặc `seq <= done_seq`, hoặc buffer đã mất: bỏ qua.
- Item `CHECK#<check_id>` là dấu "đã xong": nếu đã có thì chỉ dọn buffer.
- ALERTLOCK thuộc `check_id` (cho chính job này vào lại, chặn job khác). Điều kiện khoá gồm `ttl < now` vì TTL của DynamoDB xoá trễ.
- `alert_id` = `<check_id>-<6 hex của SHA-256(guardian_uid)>`: retry dùng lại đúng alert, không gửi lại cho người đã `sent`. Không phải ULID thuần (models.py chỉ khai báo `str`).
- Thứ tự tác dụng phụ: alert người thân, `render`, gửi hoặc sửa tin, enqueue `followup`, ghi `CHECK#`, dọn buffer.

**4. Phân loại lỗi:**
- `ai` trả `retryable`, hoặc invoke lỗi: ném lại, SQS giao lại. `fatal` hoặc sai contract: gửi câu suy giảm (BE tự viết), ghi `CHECK#` với `outcome=degraded`, dọn buffer.
- Telegram 400/403/404: vĩnh viễn (người thân: ghi `failed`; người cao tuổi: bỏ qua, không retry). Lỗi khác (429, 5xx, mạng): tạm thời, retry. Sửa tin "⏳" lỗi vĩnh viễn thì gửi tin mới; "message is not modified" coi là đã xong.
- "sent" chỉ khi Telegram trả `message_id`.

**5. DLQ và tin báo bận:** handler trả `SQSBatchResponse` (batchItemFailures). Ở lần giao thứ 5 (`ApproximateReceiveCount >= 5`) mà vẫn lỗi, worker gửi câu "đang bận" rồi trả lỗi để SQS đưa job vào DLQ. Không có consumer DLQ riêng, vì consumer xoá message thì alarm `ApproximateNumberOfMessagesVisible` không bắn.

**6. Log:** chỉ `job_id`, `seq`, mã lỗi, tên lớp exception. Không log nội dung tin, verdict, explanation, id người dùng hay message của exception bên thứ ba.

**7. Không làm (cố ý):** `SESSION#` và job `answer` (multi-turn hoãn, BE-33): `answer` chỉ ghi log. `FakeAiClient` đọc fixture không còn là hướng test: worker dùng bản giả trong bộ nhớ.

## Giới hạn đã biết

- Trả lời cho người cao tuổi là at-least-once: nếu chết ngay sau khi gửi mà trước khi ghi `CHECK#`, lần retry có thể gửi lại (sửa "⏳" thì vô hại).
- Webhook ghi buffer xong mà enqueue SQS lỗi thì trả 500; Telegram gửi lại update và tin có thể vào buffer hai lần.
- Lambda hết giờ hoặc hết bộ nhớ đúng ở lần giao thứ 5 thì không kịp gửi tin báo bận; "⏳" vẫn nằm đó. Alarm DLQ vẫn bắn.
- Tên người cao tuổi trong tin cho người thân là cách gọi chung ("Người thân của bạn"), vì `UserRecord` chưa lưu tên Telegram.
- Cảnh báo người thân chỉ gửi khi người cao tuổi có `purposes.guardian = true`; luồng bật mục đích này (`/nguoithan`) chưa có.
- `WebhookHandler` giữ đường cũ (gửi nguyên `InboundMessage`) khi không có `BufferStore`, chỉ cho test cũ và `LocalBotRunner`. Lambda thật luôn có `BufferStore`.
- Token bot của worker đọc từ biến môi trường `TELEGRAM_BOT_TOKEN` (dev). Chuyển sang SSM khi deploy chính thức.

## Hệ quả

- ARCH §5.2 và §7, plan BE-05b/07/23/24b/27/29 và `huong-dan-backend` được sửa cho khớp.
- `WorkerStoresTest` (cần Docker) là test duy nhất chạy các điều kiện DynamoDB thật.

## Cách đảo ngược

Quay lại một item `BUFFER#` chứa list tin chỉ an toàn nếu có khoá ghi tuần tự theo chat (ví dụ FIFO queue theo `MessageGroupId=chat_id`); khi đó không cần `ADD max_seq`. Đổi cấu trúc cần migrate `DynamoBufferStore` và `WebhookHandler.bufferAndEnqueue`.
