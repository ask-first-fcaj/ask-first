# Rà bảo mật lane B (BE-09a, #77)

> Rà ngày 25/11/2026 trên `main` @ `ac38b01`, chỉ đọc code và cấu hình, không sửa code. Người rà: AI1 (không viết code lane B). Phạm vi theo plan BE-09a và ARCH §9.3: `webhook`, onboarding/đồng ý, rate limit, liên kết người thân, `/nguoithan`, `/lichsu`, `/rutdongy`, `/xoadulieu`, repo `USER#`/`GUARDIAN#`/`WARD_OF#`/`INVITE#`/`RATE#`/`AUDIT#`.
>
> Viết tắt: `W` = `backend/ScamProtection/webhook-handler/src/main/java/com/askfirst/webhookhandler/WebhookHandler.java`, `C` = `backend/ScamProtection/common/src/main/java/com/askfirst/common`. Số dòng theo `ac38b01`.

## 1. Checklist

Kết quả: **5/11 mục đạt, 5 một phần, 1 không đạt.**

| # | Mục | Kết quả | Căn cứ | Ghi chú |
|---|---|---|---|---|
| 1 | IAM của `webhook` | Đạt | `infra/template.yaml` (`WebhookFunction`) | SSM chỉ `/askfirst/${Stage}/telegram/*`, `kms:Decrypt` chỉ qua SSM, SQS chỉ queue `jobs`, S3 chỉ `donated/*`. DynamoDB CRUD cả bảng: chấp nhận vì `/xoadulieu` phải xoá nhiều partition. |
| 2 | Secret webhook | Đạt | `W:891-898`, `W:2725-2748` | `MessageDigest.isEqual` trên bytes UTF-8; sai trả 401, không log body; thiếu secret thì chặn hết. |
| 3 | Bot token, secret qua SSM | Đạt | `aws lambda get-function-configuration` trên `askfirst-dev-webhook` | Env chỉ có `TELEGRAM_BOT_TOKEN_PARAM`, `TELEGRAM_WEBHOOK_SECRET_PARAM` (tên tham số, không phải giá trị). Worker thì khác, xem finding 1 ở `security-lane-a.md`. |
| 4 | Chặn khi chưa đồng ý | Một phần | gate `W:2441-2453`; `W:2408-2409`, `W:2628-2632`, `W:2156-2163` | Nội dung tin chỉ được lưu và enqueue sau khi đồng ý. Nhánh APK chạy trước gate và ghi tên file vào `AUDIT`; `/ketnoi` lưu tên người thân khi người đó chưa đồng ý (LB-04). |
| 5 | `callback_data` không chứa dữ liệu cá nhân | Đạt | `W:1800-2711`, `worker/.../ButtonMapper.java:35-45` | Chỉ có mã hành động, `checkId`, `alertId` và Telegram user id (`tg:<id>`), không có tên, số điện thoại hay nội dung. |
| 6 | Log không chứa nội dung, có test | Một phần | `W:935`, `W:1022`, `W:2384`, `W:2525`, `W:2615`; test `WebhookHandlerTest.java:726-734` | Không log thẳng nội dung, tên hay token. Nhưng nhiều dòng log nguyên `e`/`e.getMessage()` (với `TelegramApiException` là body trả về của Telegram) và log `callback_data` thô. Webhook không có test bắt log; worker thì có (`WorkerRunnerTest.java:108`) (LB-05). |
| 7 | Chỉ nhận chat `private`, bỏ update từ bot | Không đạt | `C/channel/TelegramAdapter.java:30-48`, `:186-208` | Không chỗ nào kiểm `chat.type`; `is_bot` chỉ lọc cho `message`, không lọc `callback_query` (LB-02). |
| 8 | Rate limit theo user | Một phần | `W:2477-2488`, `C/store/RateLimitRepo.java:73-125` | 30 tin/giờ, 100 tin/ngày, chỉ cho đường kiểm tra nội dung. Lệnh, callback, APK không bị đếm; lỗi DynamoDB thì cho qua (LB-07). |
| 9 | Mã mời người thân | Một phần | `W:1166-1171`, `W:2083-2163`, `C/store/InviteRepo.java`, `C/store/GuardianRepo.java:157-199` | Hạn 24 giờ, dùng một lần, không tự kết nối với mình, người cao tuổi phải bấm đồng ý (double opt-in). Nhưng mã 6 số từ `ThreadLocalRandom`, `/ketnoi` không bị rate limit, câu trả lời lộ mã đúng/sai/đã dùng/hết hạn (LB-01). |
| 10 | `/rutdongy`, `/xoadulieu` xoá sạch | Một phần | `W:2225-2394`, `C/store/DynamoStoreService.java:238-312` | `/xoadulieu` xoá partition `USER#<uid>`, `SESSION#`/`BUFFER#`, liên kết `WARD_OF` ở partition người khác và `donated/` trên S3. Còn sót `ALERT#`, `INVITE#`, `RATE#`, `ALERTLOCK#` và dòng audit ở partition người khác (LB-03). `/rutdongy` chỉ đổi trạng thái và gỡ liên kết người thân, giữ lịch sử: đúng ý đồ. |
| 11 | Runbook xoay vòng bot token | Đạt (thêm ở §3) | §3 dưới đây | Trước đây chưa có. |

## 2. Lỗi tìm được

| Mã | Mức | Lỗi | Vị trí | Cách sửa gợi ý |
|---|---|---|---|---|
| LB-01 | Medium | Mã mời đoán được: 6 số (900.000 khả năng), `ThreadLocalRandom`, `/ketnoi` không rate limit, câu trả lời cho biết mã đúng hay sai. Đoán trúng thì người lạ dùng mất mã của con cháu thật và gửi yêu cầu kết nối với tên tự đặt; nếu người cao tuổi bấm đồng ý thì người lạ nhận cảnh báo. Double opt-in làm giảm tác hại. | `W:1166-1171`, `W:1006-1011`, `W:2097-2154` | Mã ≥ 8 ký tự base32 từ `SecureRandom`; rate limit riêng cho `/ketnoi` (vd 5 lần/giờ); một câu trả lời chung cho mọi lỗi; ghi rõ tên người gửi chưa được xác minh. |
| LB-02 | Medium | Không lọc chat nhóm. Thêm bot vào nhóm thì `/lichsu`, `/nguoithan`, kết luận và link mời hiện cho cả nhóm. Chưa kiểm cài đặt BotFather (`setjoingroups`). | `C/channel/TelegramAdapter.java:35-48`, `:190-200` | Adapter bỏ update khi `chat.type != "private"` (cả `message` lẫn `callback_query`); tắt `setjoingroups` trong BotFather. |
| LB-03 | Medium | `/xoadulieu` còn sót dữ liệu: `ALERT#` (uid, chat id, tên người thân; TTL 30 ngày), `INVITE#` (TTL 24 giờ), `RATE#`, `ALERTLOCK#`, dòng audit ở partition người khác có uid. Dấu `DATA_DELETED` giữ `PK=USER#tg:<id>` và SHA-256 không salt của id số, dò ngược được. Mã mời đang chờ vẫn dùng được sau `/rutdongy`, `/xoadulieu`. | `C/store/DynamoStoreService.java:238-312`, `C/store/AuditRepo.java:130-135`, `C/store/GuardianRepo.java:140` | Xoá `ALERT#`/`INVITE#` theo uid (thêm GSI hoặc quét theo `elder_uid`); bỏ uid khỏi audit của người khác; băm tombstone bằng HMAC có secret; `listWardsOf` ném lỗi thay vì nuốt để purge báo `PURGE_FAILED`. |
| LB-04 | Low | Nhánh APK và `/ketnoi` xử lý dữ liệu trước khi kiểm đồng ý: ghi tên file APK vào `AUDIT`, lưu `first_name`/`@username` của người thân khi người đó chưa đồng ý. | `W:2408-2409`, `W:2628-2632`, `W:2156-2163` | Bỏ tên file khỏi audit; `/ketnoi` yêu cầu người thân đã đồng ý. |
| LB-05 | Low | Log nguyên exception (body trả về của Telegram), uid, chat id, `callback_data`; webhook không có test bắt log. | `W:1022`, `W:2384`, `W:2525`, `W:2615`; `WebhookHandlerTest.java:726-734` | Chỉ log `e.getClass().getSimpleName()` và `status()` như worker; thêm test logger bắt chuỗi giống `WorkerRunnerTest:108`. |
| LB-06 | Low | `createInvite` và `GuardianRepo.save` ghi đè không điều kiện (trùng mã thì đè lời mời của người khác; liên kết `active` có thể bị đè thành `pending`). `/nguoithan` hiện `pending` thành "✅ Đang hoạt động". | `C/store/InviteRepo.java:32-36`, `C/store/GuardianRepo.java:41-47`, `W:1878-1883` | `attribute_not_exists(PK)` khi tạo mã, trùng thì sinh mã khác; thêm `case "pending"`. |
| LB-07 | Low | Rate limit cho qua khi DynamoDB lỗi (có chủ đích, có comment) và không đếm lệnh, callback. | `C/store/RateLimitRepo.java:120-125`, `W:2477-2488` | Với `/ketnoi` thì chặn khi lỗi; đếm lệnh và callback ở bucket riêng, ngưỡng lỏng hơn. |

Ngoài phạm vi bảo mật: trên `main`, webhook chưa xử lý callback `ack:` của worker (`W:1151`), nên nút "Tôi đã liên hệ" chưa ghi nhận. PR #170 (BE-29) đã thêm phần này.

Chưa kiểm: cài đặt BotFather (`setjoingroups`, privacy mode); Lambda `ai` và worker (đã rà ở `security-lane-a.md`).

## 3. Runbook xoay vòng bot token

Làm khi token lộ (log, chat, commit) hoặc định kỳ. Các bước 1–2 do người giữ tài khoản BotFather làm.

1. BotFather: `/revoke` → chọn bot → nhận token mới. Token cũ chết ngay, bot ngừng trả lời cho tới bước 4.
2. Ghi token mới vào SSM (không dán vào chat, commit hay `.env` dùng chung):
   ```bash
   aws ssm put-parameter --name "/askfirst/$STAGE/telegram/bot_token" --type SecureString --overwrite --value "<token mới>"
   ```
3. Worker và worker-dlq hiện nhận token qua tham số CloudFormation `TelegramBotToken` (finding 1 ở `security-lane-a.md`), nên phải deploy lại: `sam deploy --parameter-overrides Stage=$STAGE TelegramBotToken=<token mới>`. Webhook đọc token từ SSM và cache trong RAM, nên ép tạo môi trường mới:
   ```bash
   aws lambda update-function-configuration --function-name "askfirst-$STAGE-webhook" --description "rotate $(date +%F)"
   ```
4. Đăng ký lại webhook với token mới: sửa `TELEGRAM_BOT_TOKEN` trong `.env` trên máy mình (không truyền `--token` để token không vào lịch sử shell), rồi chạy `python scripts/set_webhook.py --url <Function URL của webhook>`. Muốn đổi luôn secret thì ghi SSM `/askfirst/$STAGE/telegram/webhook_secret` và `TELEGRAM_WEBHOOK_SECRET` trong `.env` trước bước này.
5. Kiểm: `getWebhookInfo` không có `last_error_message`; gửi `/start` cho bot thấy trả lời; log worker không có lỗi 401 từ Telegram.
