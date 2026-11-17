# ADR-008: Cold start Java và SnapStart cho worker

- Ngày: 08/11/2026
- Người viết: BE1 (pair BE2)
- Gate: không (BE-07)
- Trạng thái: **chưa có số đo, chưa quyết định.** Mọi ô số liệu bên dưới để trống cho tới khi đo trên Lambda thật. Plan cũ dự kiến số 007 cho ADR này, nhưng 007 đã được dùng cho DynamoDB Enhanced Client.

## Bối cảnh

BE-07 yêu cầu "bật SnapStart cho webhook/worker (published version + alias)" và "đo cold start Java có/không SnapStart, ghi ADR". Worker cộng dồn cold start với Lambda `ai` (BE-06), nên tổng thời gian từ tin nhắn tới kết quả bị ảnh hưởng.

Trong `infra/template.yaml`:
- Worker luôn có `AutoPublishAlias: live`; event source SQS tự gắn vào alias.
- SnapStart điều khiển bằng tham số `WorkerSnapStart` (`None` mặc định, hoặc `PublishedVersions`). Mặc định None để có số đo gốc.
- **Webhook chưa bật SnapStart.** Có `AutoPublishAlias` thì SAM gắn Function URL vào alias, URL đổi và phải chạy lại `scripts/set_webhook.py`. Cần quyết định riêng trước khi làm.

## Phương án và số đo

Đo cùng cấu hình (512 MB, x86_64, Java 21), mỗi nhánh ≥ 20 cold start.

| Cấu hình | Init Duration (p50 / p95) | Restore Duration (p50 / p95) | Tổng thời gian tới khi handler chạy | Ghi chú |
|---|---|---|---|---|
| Worker, SnapStart None | chưa đo | không áp dụng | chưa đo | |
| Worker, SnapStart PublishedVersions | không áp dụng | chưa đo | chưa đo | |
| Webhook (hiện tại, không SnapStart) | chưa đo | không áp dụng | chưa đo | |

## Cách đo

1. Deploy với `WorkerSnapStart=None`. Ép cold start bằng cách đổi `MemorySize` qua lại giữa 512 và 513 (đừng dùng `update-function-configuration --environment`: lệnh đó thay toàn bộ biến môi trường và xoá token). Mỗi lần đổi, gửi một tin vào bot dev.
2. Lấy số từ CloudWatch Logs Insights trên log group của worker:

```
filter @type = "REPORT"
| stats count() as n,
        avg(@initDuration) as init_avg_ms,
        pct(@initDuration, 50) as init_p50_ms,
        pct(@initDuration, 95) as init_p95_ms
```

3. Deploy lại với `WorkerSnapStart=PublishedVersions` và đo `Restore Duration`:

```
filter @type = "REPORT"
| parse @message /Restore Duration: (?<restore>[\d.]+) ms/
| stats count() as n, pct(restore, 50) as restore_p50_ms, pct(restore, 95) as restore_p95_ms
```

4. Đo cả cold start cộng dồn worker + `ai` (BE-06) bằng thời gian từ lúc gửi tin tới lúc tin "⏳" được sửa, ở lần đầu sau khi đổi cấu hình cả hai function.

## Điểm cần kiểm trước khi bật SnapStart cho worker

- `Ulid` dùng `SecureRandom`: runtime Java trên Lambda cần reseed sau restore. Kiểm bằng cách tạo 2 ULID trên 2 môi trường được restore từ cùng snapshot và so phần ngẫu nhiên.
- `WorkerWiring.fromEnv()` chạy lúc init: tạo client AWS và `TelegramClient`, không mở kết nối mạng thật. Token đọc từ biến môi trường nên nằm trong snapshot; khi chuyển sang SSM phải đọc lười sau restore (như plan BE-22).
- `FlagsCache` 30 giây so với `Clock.instant()` lúc gọi nên không bị snapshot làm sai.

## Quyết định

Chưa. Sẽ ghi sau khi có số đo ở bảng trên. Nếu SnapStart giảm rõ rệt thời gian tới khi handler chạy và không có vấn đề ở mục kiểm tra, đổi mặc định `WorkerSnapStart` thành `PublishedVersions`.

## Hệ quả

- BE-07 chỉ coi là xong khi bảng số đo có số và có quyết định.
- BE-06 cần dùng output `WorkerRoleArn` để đặt resource policy của Lambda `ai`.

## Cách đảo ngược

Đặt lại `WorkerSnapStart=None` và deploy: version mới được publish không có snapshot, alias `live` trỏ vào version đó.
