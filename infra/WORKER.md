# Worker Lambda: deploy và kiểm tra (BE-07)

Bổ sung cho `infra/README.md`. Mọi lệnh dưới đây chưa được chạy trên tài khoản thật vào thời điểm viết (08/11/2026).

## Tham số template

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `Stage` | `dev` | |
| `TelegramBotToken` | rỗng, `NoEcho` | Token bot cho worker (dev: biến môi trường; deploy chính thức chuyển sang SSM). Rỗng thì worker lỗi ngay lúc khởi tạo với thông báo rõ ràng |
| `WorkerSnapStart` | `None` | `None` hoặc `PublishedVersions`; xem `docs/decisions/ADR-008-cold-start-snapstart.md` |
| `AlarmTopicArn` | rỗng | SNS topic nhận alarm DLQ (BE-13). Rỗng thì alarm vẫn đổi trạng thái nhưng không gửi thông báo |

`--parameter-overrides` trên dòng lệnh thay cho giá trị trong `samconfig.toml`, nên phải ghi lại cả `Stage`. Không ghi token vào `samconfig.toml`:

```
sam build
sam deploy --parameter-overrides Stage=dev TelegramBotToken=<token> WorkerSnapStart=None
```

Lambda `ai` (BE-06) phải có function tên `askfirst-<stage>-ai` và alias `live`, vì template mặc định gọi `...:function:askfirst-<stage>-ai:live`. Tên khác thì sửa hai chỗ trong `WorkerFunction` (`Resource` của policy và `AI_FUNCTION_ARN`). Output `WorkerRoleArn` dùng để đặt resource policy của `ai` chỉ cho role worker.

## Biến môi trường của worker

| Biến | Giá trị |
|---|---|
| `DYNAMODB_TABLE_NAME` | tên bảng (cùng tên với webhook) |
| `WEBHOOK_QUEUE_URL` | URL hàng đợi jobs (cùng tên với webhook); dùng để tạo job `followup` |
| `AI_FUNCTION_ARN` | ARN alias `live` của Lambda `ai` |
| `TELEGRAM_BOT_TOKEN` | token bot |

## Kiểm tra: job lỗi 5 lần vào DLQ

> Bước kiểm tra DLQ bên dưới cần worker thật của BE-27 đã được deploy (tin "đang bận" do worker gửi). Trước đó chỉ kiểm tra được phần hạ tầng: event source, quyền, alarm.
, alarm bắn, người dùng nhận tin báo bận

Tiền đề: đã deploy, bot dev đã đồng ý chính sách, Lambda `ai` đã chạy được.

1. Rút ngắn visibility để không phải chờ 5 × 12 phút (sẽ lệch với template, nhớ trả lại ở bước 6):

```
aws sqs set-queue-attributes --queue-url <JobsQueueUrl> --attributes VisibilityTimeout=20
```

2. Làm `ai` lỗi tạm thời mà không đụng cấu hình worker (mỗi lần gọi bị throttle, worker coi là `retryable`):

```
aws lambda put-function-concurrency --function-name askfirst-dev-ai --reserved-concurrent-executions 0
```

3. Gửi một tin text vào bot dev. Kỳ vọng: nhận tin "⏳" ngay.
4. Sau khoảng 2 đến 3 phút (5 lần giao, mỗi lần cách khoảng 20 giây): tin "⏳" được sửa thành câu "đang bận", DLQ có 1 message:

```
aws sqs get-queue-attributes --queue-url <JobsDlqUrl> --attribute-names ApproximateNumberOfMessages
```

5. Alarm `askfirst-dev-jobs-dlq-not-empty` chuyển sang `ALARM` (số liệu SQS trễ vài phút). Có `AlarmTopicArn` thì kiểm hộp thư.
6. Dọn:

```
aws lambda delete-function-concurrency --function-name askfirst-dev-ai
aws sqs purge-queue --queue-url <JobsDlqUrl>
sam deploy ...   # trả VisibilityTimeout về 720
```

Ghi kết quả (đạt hay không, thời gian thực tế) vào issue BE-07.
