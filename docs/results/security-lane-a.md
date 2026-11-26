# Rà bảo mật lane A + `ai` (BE-09b #78)

> Người rà: AI1. Ngày: 2026-11-25. So code trên `main` (ac38b01) và cấu hình đang chạy trên `dev` (account team, `ap-southeast-1`) với ARCH §9.3. Chỉ đọc cấu hình, kh đổi gì trên AWS.

## Kết luận

- ARCH §9.3 cho `worker` + `ai`: 5/9 mục đạt, 1 kh áp dụng (SnapStart), 1 đạt một phần, 2 kh đạt. Có 3 lỗi (mục **Lỗi tìm được**), nặng nhất là bot token nằm plaintext trong biến môi trường của `worker`.
- Quét dependency: `ai/` sạch; Java có 2 package Jackson dính 7 lỗ hổng High, đã nâng lên 2.21.7 trong cùng PR, quét lại còn 0.
- Test "log không chứa nội dung" pass ở cả hai phía.

## Checklist ARCH §9.3, phần lane A

| Mục | Kết quả | Căn cứ |
|---|---|---|
| Bot token đặt trong SSM SecureString | **Không đạt** ở `worker` | `ai` đọc qua `BOT_TOKEN_PARAM` (SSM). `worker` và `worker-dlq` nhận token qua tham số CloudFormation `TelegramBotToken` thành env `TELEGRAM_BOT_TOKEN` (`infra/template.yaml:164`, `:194`; `WorkerWiring.java:40`). Xem lỗi 1. |
| SnapStart kh chụp secret vào snapshot | Kh áp dụng | `worker`, `ai` trên `dev` đều `SnapStart.ApplyOn = None`. |
| URL tải file (có token) kh bị log hay trả về `worker` | Đạt | `ai/src/askfirst/media/telegram.py`: lỗi tải kh chứa URL hay token. |
| `ai` kh có Function URL | Đạt | `get-function-url-config` trả `ResourceNotFoundException`. |
| Chỉ role của `worker` invoke được `ai` | Một phần | Role `worker` chỉ được invoke `askfirst-<stage>-ai:live` (`infra/template.yaml:155`). `ai` kh có resource policy. Xem lỗi 3. |
| `ai` chỉ ghi được `CACHE#`/`URL#` | Template đạt, `dev` **không đạt** | Template có `dynamodb:LeadingKeys` `CACHE#*`, `URL#*`. Bản `ai` trên `dev` deploy tay, role `askfirst-ai2test-ai-role` được `PutItem` cả bảng `askfirst-dev` kh có điều kiện. Xem lỗi 2. |
| Log kh có prompt, nội dung, file, token | Đạt | Test `ai/tests/unit/test_handler.py::test_log_has_no_content_or_url` và `AnalyzeProcessorTest.logs_neverContainMessageContentOrUserIds` (worker) pass 25/11. Quét log CloudWatch 30 ngày ở `docs/results/lambda-p95.md` mục Log. |
| Model local có checksum SHA-256, kh nạp pickle | Đạt | `ai/config/artifacts.yaml` ghi sha256, `ai/src/askfirst/artifacts/s3.py` kiểm khi tải, sai thì xoá file và báo lỗi. Kh có `pickle`/`torch.load` trong `ai/src`. |
| Chống SSRF khi mở link rút gọn | Đạt | `ai/src/askfirst/pipeline/url_net.py` chỉ cho IP `is_global`, chặn cả dải mà `is_global` bỏ sót, kiểm lại sau mỗi redirect (tối đa 3). Test trong `ai/tests/unit/test_url_net.py` (IP nội bộ, metadata `169.254.169.254`, redirect vào IP nội bộ). |

## Lỗi tìm được

1. **Bot token plaintext trong env của `worker` và `worker-dlq` (cao).** Ai có quyền `lambda:GetFunctionConfiguration` đều đọc được token. Token này đã lộ một lần (25/11), team đã hẹn rotate. Sửa: đọc token từ SSM SecureString như `webhook`, `ai` (env chứa tên tham số, kh chứa giá trị), rồi bỏ tham số `TelegramBotToken` khỏi template.
2. **`ai` trên `dev` ghi được mọi item của bảng `askfirst-dev` (trung bình).** Do function deploy tay ngoài stack với role tạm. Sửa: deploy `ai` qua stack (`AiImageUri`) để dùng role có `LeadingKeys`, hoặc thêm điều kiện đó vào role đang dùng.
3. **`ai` kh có resource policy (thấp).** Hiện chỉ dựa vào IAM trong account. Sửa nếu cần: `AWS::Lambda::Permission` cho role của `worker` trên alias `live`.

## Quét dependency

| Phần | Cách quét | Kết quả |
|---|---|---|
| `ai/` (Python) | `uv export --frozen --format requirements-txt --no-emit-project` rồi `uvx pip-audit -r <file> --disable-pip` | 818 dòng requirements, kh có lỗ hổng đã biết. Đã thêm bước này vào CI (job `ai`, chỉ Ubuntu). |
| `backend/` (Java) | `mvn dependency:list -DincludeScope=runtime`, tra từng package qua API `https://api.osv.dev/v1/querybatch` | Trước: 64 package, `jackson-databind` 2.17.0 (12 advisory, 5 High) và `jackson-core` 2.21.5 (2 High). Sau khi import `jackson-bom` 2.21.7: 63 package, 0 lỗ hổng. `mvn test` pass. |

- Chưa chạy OWASP Dependency-Check (`mvn dependency-check:check`) vì phải tải cơ sở dữ liệu NVD, nặng cho máy dev. OSV gom advisory của GitHub nên đủ cho mức High/Critical, nma chưa so với NVD.
- Đã thêm bước quét dependency Java vào CI (job `backend`, chỉ Ubuntu) bằng `scripts/scan_java_deps.py` tra cứu OSV API tự động.
