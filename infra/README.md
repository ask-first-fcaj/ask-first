# Hướng Dẫn Hạ Tầng AWS SAM (Ask-First Dev Stack)

Tài liệu này hướng dẫn chi tiết cách build, deploy, kiểm tra nghiệm thu, xử lý sự cố (rollback) và dọn dẹp bộ khung hạ tầng Serverless (AWS SAM) của dự án Ask-First trên môi trường `dev` (Task **BE-03**).

---

## 1. Danh Sách Tài Nguyên Được Tạo

Khi triển khai stack `askfirst-dev` (Region `ap-southeast-1`), AWS SAM sẽ tạo các tài nguyên:

| STT | Tài nguyên | Tên logic (SAM) | Tên thực tế trên AWS | Đặc tả chi tiết                                                                                                                 |
|---|---|---|---|---------------------------------------------------------------------------------------------------------------------------------|
| 1 | **Lambda Webhook** | `WebhookFunction` | `askfirst-dev-webhook` | Java 21, Function URL công khai (`AuthType: NONE`), handler `WebhookHandler`: từ chối request thiếu secret, đọc token và secret từ SSM `SecureString` lúc khởi tạo                                                         |
| 2 | **DynamoDB Single-Table** | `AskFirstTable` | `askfirst-dev` | Partition Key (`PK`), Sort Key (`SK`), GSI1 (`GSI1PK`, `GSI1SK`), TTL (`ttl`), PITR bật, Billing: On-Demand (`PAY_PER_REQUEST`) |
| 3 | **SQS Queue chính** | `JobsQueue` | `askfirst-dev-jobs` | Visibility timeout: 720s (6 × 120s worker timeout), SSE bật, Redrive sang DLQ khi lỗi 5 lần                                     |
| 4 | **SQS Dead-Letter Queue** | `JobsDeadLetterQueue` | `askfirst-dev-jobs-dlq` | Message retention: 14 ngày (1.209.600s), SSE bật                                                                                |
| 5 | **S3 Data Bucket** | `DataBucket` | `askfirst-dev-data-<account_id>` | Block Public Access toàn diện, SSE AES256, Lifecycle rule xoá prefix `donated/` sau 30 ngày                                     |
| 6 | **SSM Parameter Store** | `StageParam`, `TableNameParam`, `JobsQueueUrlParam`, `JobsDlqUrlParam`, `DataBucketNameParam` | `/askfirst/dev/...` | Quản lý cấu hình tập trung (`stage`, `table_name`, `jobs_queue_url`, `jobs_dlq_url`, `bucket_name`). Token và secret do bạn tạo thủ công (mục 5), CloudFormation không tạo                             |

> [!IMPORTANT]
> **Quy tắc thiết kế mạng & chi phí (D1):**
> Stack này **KHÔNG sử dụng VPC và KHÔNG sử dụng NAT Gateway** để tránh chi phí cố định ~32 USD/tháng (~800.000 VNĐ). Lambda chạy ở chế độ Serverless mặc định, kết nối trực tiếp đến DynamoDB, SQS, S3 và Internet an toàn qua AWS backbone.

---

## 2. Yêu Cầu Môi Trường (Prerequisites)

Cả hai bạn BE1 và BE2 cần chuẩn bị các công cụ sau trên máy dev:

1. **Java 21 (LTS):**
   ```bash
   java -version
   # Yêu cầu: OpenJDK 21 hoặc Oracle JDK 21
   ```
2. **Maven:** Sử dụng Maven Wrapper có sẵn trong repo (`./mvnw` hoặc `.\mvnw.cmd`).
3. **AWS CLI v2:**
   ```bash
   aws --version
   ```
4. **AWS SAM CLI:**
   ```bash
   sam --version
   # Yêu cầu: SAM CLI version 1.100+
   ```
   *Nếu chưa có SAM CLI trên Windows:* Có thể cài qua winget:
   ```powershell
   winget install --id Amazon.SAM-CLI --exact
   ```
5. **AWS Credentials (Region `ap-southeast-1`):**
   ```bash
   aws configure
   # Nhập AWS Access Key ID, Secret Access Key
   # Default region: ap-southeast-1
   # Default output: json
   ```
   Kiểm tra danh tính đang đăng nhập:
   ```bash
   aws sts get-caller-identity
   ```

---

## 3. Hướng Dẫn Build & Deploy

### Bước 1: Build mã nguồn Java backend

Trước khi SAM đóng gói, cần biên dịch và cài đặt artifact Maven vào repository local:

**Trên Windows (PowerShell):**
```powershell
cd backend/ScamProtection
.\mvnw.cmd clean install -DskipTests
cd ../../infra
```

**Trên Linux / macOS:**
```bash
cd backend/ScamProtection
./mvnw clean install -DskipTests
cd ../../infra
```

### Bước 2: Build AWS SAM Template

Tại thư mục `infra/`, chạy lệnh:
```bash
sam build
```
SAM sẽ tự động xác định hàm Java 21 từ `backend/ScamProtection/webhook-handler` và chuẩn bị thư mục `.aws-sam/build`.

### Bước 3: Deploy lên AWS

File `infra/samconfig.toml` đã được cấu hình sẵn các tham số chuẩn cho stage `dev` (`stack_name = "askfirst-dev"`, `region = "ap-southeast-1"`).

**Cách 1: Deploy tự động (dùng cấu hình có sẵn):**
```bash
sam deploy
```

**Cách 2: Deploy tương tác lần đầu (Guided):**
Nếu muốn kiểm tra từng tham số hoặc thay đổi cấu hình:
```bash
sam deploy --guided
```
Khi SAM hỏi các câu hỏi:
- **Stack Name:** `askfirst-dev`
- **AWS Region:** `ap-southeast-1`
- **Parameter Stage:** `dev`
- **Confirm changes before deploy:** `N` (hoặc `Y` nếu muốn review changeset)
- **Allow SAM CLI IAM role creation:** `Y`
- **WebhookFunction has no authentication. Is this okay?:** `Y` (Bắt buộc `Y` để cấp quyền Function URL công khai)
- **Save arguments to configuration file:** `Y`

Sau khi hoàn tất, SAM sẽ in ra các giá trị **Outputs** (bao gồm `WebhookFunctionUrl`, `DynamoDBTableName`, `JobsQueueUrl`, `DataBucketName`).

### Bước 4: Lambda `ai` (BE-06, container image)

`AiImageUri` để trống (mặc định) thì stack **không** tạo function `ai`. Image build trên GitHub Actions (`.github/workflows/ai-image.yml`), không build trên máy.

1. **Một lần mỗi tài khoản:** tạo repo ECR `askfirst-ai` và role OIDC cho GitHub Actions, rồi lưu ARN role vào repo variable:
   ```bash
   aws cloudformation deploy --region ap-southeast-1 --stack-name askfirst-github-oidc \
     --template-file infra/github-oidc.yaml --capabilities CAPABILITY_NAMED_IAM
   gh variable set AWS_AI_IMAGE_ROLE_ARN --body "$(aws cloudformation describe-stacks --region ap-southeast-1 \
     --stack-name askfirst-github-oidc --query "Stacks[0].Outputs[?OutputKey=='AiImagePushRoleArn'].OutputValue" --output text)"
   ```
   Tài khoản đã có OIDC provider của GitHub thì thêm `--parameter-overrides CreateOidcProvider=false`.
2. **Model lên S3** (`DataBucket`), theo `ai/MODELS.md`: `ARTIFACT_BUCKET=<DataBucketName> uv run python scripts/upload_artifacts.py <ocr|vietocr|embedder|kb_index> <thư mục>` (chạy trong `ai/`).
3. **Build và đẩy image:** Actions → *AI image* → *Run workflow* trên `main`. Summary in ra URI `…/askfirst-ai:<sha>`.
4. **Deploy:** `sam deploy --parameter-overrides Stage=dev AiImageUri=<URI ở bước 3> ...` (giữ các tham số khác như bước 3). Pipeline thật lỗi thì deploy lại với `AiPipelineImpl=stub`.
5. **Bedrock** ở `ap-southeast-2`: tài khoản phải bật được Qwen3 32B và gpt-oss-120b (BE-05). Guardrail trong `ai/config/models.yaml` là của tài khoản khác: gọi lỗi thì pipeline bỏ qua Guardrail; muốn bật thì chạy `ai/scripts/create_guardrail.py` trên tài khoản này và sửa `guardrail.id`.

Function: 3008 MB, timeout 90 s, `/tmp` 1 GB, x86_64, alias `live` (worker gọi `askfirst-<stage>-ai:live`).

---

## 4. Kiểm Tra Nghiệm Thu ("Xong Khi")

> [!TIP]
> **Script kiểm tra tự động 1 lệnh:**
> Repo đã chuẩn bị sẵn 2 script kiểm tra toàn diện 5 tiêu chí nghiệm thu bên dưới:
> - **Trên Windows (PowerShell):** `.\infra\verify-dev.ps1`
> - **Trên Linux / macOS (Bash):** `./infra/verify-dev.sh`

Chạy các lệnh thủ công hoặc script tự động để xác nhận 100% tài nguyên đã hoạt động đúng:

### 1. Kiểm tra Webhook Function URL (xác thực secret)

Function URL là công khai (`AuthType: NONE`), nên Lambda **phải** từ chối request không có secret và chấp nhận request có secret đúng. Lấy URL từ output `WebhookFunctionUrl` của SAM:

```bash
FUNCTION_URL="<WebhookFunctionUrl từ output>"
SECRET=$(aws ssm get-parameter --name "/askfirst/dev/telegram/webhook_secret" \
  --with-decryption --region ap-southeast-1 --query Parameter.Value --output text)

# Không có secret -> phải trả 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST "$FUNCTION_URL" \
  -H "Content-Type: application/json" -d '{}'

# Có secret đúng, update rỗng -> phải trả 200 với body IGNORED
curl -s -w "\n%{http_code}\n" -X POST "$FUNCTION_URL" \
  -H "Content-Type: application/json" \
  -H "X-Telegram-Bot-Api-Secret-Token: $SECRET" -d '{}'
```

**Kết quả mong đợi:** lệnh đầu in `401`; lệnh sau in `IGNORED` rồi `200`.
Hoặc chạy `verify-dev.sh` / `verify-dev.ps1`, script sẽ kiểm tra cả hai trường hợp.

### 2. Kiểm tra DynamoDB `askfirst-dev` (GSI1, TTL, PITR)

```bash
# Kiểm tra thuộc tính bảng, khóa PK/SK và GSI1
aws dynamodb describe-table --table-name askfirst-dev --region ap-southeast-1 \
  --query "Table.[TableName,BillingModeSummary.BillingMode,KeySchema,GlobalSecondaryIndexes[0].IndexName,TableStatus]"

# Kiểm tra trạng thái TTL (AttributeName: ttl, Enabled)
aws dynamodb describe-time-to-live --table-name askfirst-dev --region ap-southeast-1

# Kiểm tra sao lưu liên tục PITR (PointInTimeRecoveryStatus: ENABLED)
aws dynamodb describe-continuous-backups --table-name askfirst-dev --region ap-southeast-1
```

### 3. Kiểm tra S3 Bucket

```bash
BUCKET_NAME=$(aws ssm get-parameter --name "/askfirst/dev/s3/bucket_name" \
  --region ap-southeast-1 --query Parameter.Value --output text)

# Block Public Access: cả 4 cờ phải là true
aws s3api get-public-access-block --bucket "$BUCKET_NAME" --region ap-southeast-1

# Lifecycle: prefix donated/ hết hạn sau 30 ngày
aws s3api get-bucket-lifecycle-configuration --bucket "$BUCKET_NAME" --region ap-southeast-1
```

Các prefix `models/`, `kb/`, `donated/` chỉ là tiền tố của key, không cần tạo trước. Script verify không ghi gì vào bucket.

### 4. Kiểm tra SQS `jobs` & `jobs-dlq`

```bash
# Kiểm tra VisibilityTimeout = 720 và cấu hình RedrivePolicy
aws sqs get-queue-attributes \
  --queue-url $(aws ssm get-parameter --name "/askfirst/dev/sqs/jobs_queue_url" --query "Parameter.Value" --output text) \
  --attribute-names VisibilityTimeout RedrivePolicy
```

### 5. Kiểm tra SSM Parameter Store

```bash
aws ssm get-parameters-by-path --path "/askfirst/dev" --recursive
```

---

## 5. Lưu Token & Secret (Bảo Mật)

Tuyệt đối **không** commit token hoặc secret vào Git hay template SAM. Lambda đọc hai giá trị bí mật lúc khởi tạo từ SSM `SecureString`, nên giá trị không nằm trong cấu hình Lambda.

| Tham số SSM | Kiểu | Lambda nhận qua biến | Ghi chú |
|---|---|---|---|
| `/askfirst/dev/telegram/bot_token` | `SecureString` | `TELEGRAM_BOT_TOKEN_PARAM` | Lấy từ BotFather |
| `/askfirst/dev/telegram/webhook_secret` | `SecureString` | `TELEGRAM_WEBHOOK_SECRET_PARAM` | Chỉ dùng `A-Za-z0-9_-`, tối đa 256 ký tự |
| `/askfirst/dev/config/policy_sha256` | `String` | `POLICY_SHA256` (CloudFormation resolve lúc deploy) | Không phải bí mật |

**Tạo token và secret bằng PowerShell.** Nhập giá trị qua prompt để không lưu vào lịch sử lệnh:

```powershell
$token = Read-Host "Telegram bot token"
aws ssm put-parameter --region ap-southeast-1 --name "/askfirst/dev/telegram/bot_token" `
  --value $token --type SecureString --overwrite

$secret = Read-Host "Webhook secret"
aws ssm put-parameter --region ap-southeast-1 --name "/askfirst/dev/telegram/webhook_secret" `
  --value $secret --type SecureString --overwrite

Remove-Variable token, secret
```

Tạo webhook secret ngẫu nhiên (Git Bash): `openssl rand -hex 32`.

**Tạo policy hash** (chạy từ thư mục gốc repo). Hash này phải khớp với `docs/privacy-policy.md` hiện hành. Khi đổi chính sách, phải cập nhật lại hash, và người dùng sẽ được yêu cầu đồng ý lại:

```powershell
$sha = (Get-FileHash -Algorithm SHA256 docs/privacy-policy.md).Hash.ToLower()
aws ssm put-parameter --region ap-southeast-1 --name "/askfirst/dev/config/policy_sha256" `
  --value $sha --type String --overwrite
```

Sau khi tạo, kiểm tra kiểu của từng tham số:

```powershell
aws ssm get-parameters-by-path --region ap-southeast-1 --path "/askfirst/dev" --recursive `
  --query "Parameters[*].[Name,Type]" --output table
```

---

## 6. Xử Lý Sự Cố & Rollback

### Xem log khi triển khai gặp lỗi:
Nếu CloudFormation báo trạng thái `ROLLBACK_IN_PROGRESS` hoặc `ROLLBACK_COMPLETE`:
1. Kiểm tra sự kiện lỗi trực tiếp trên terminal:
   ```bash
   aws cloudformation describe-stack-events \
     --stack-name askfirst-dev \
     --query "StackEvents[?ResourceStatus=='CREATE_FAILED'].[LogicalResourceId,ResourceStatusReason]" \
     --output table
   ```
2. Xem log thực thi của Lambda Webhook:
   ```bash
   sam logs -n WebhookFunction --stack-name askfirst-dev --tail
   ```

### Xử lý các lỗi thường gặp:

1. **Lỗi `S3 Bucket already exists`:**
   - Nguyên nhân: Tên bucket S3 có tính duy nhất trên toàn cầu (Global namespace).
   - Khắc phục: Template sử dụng `askfirst-${Stage}-data-${AWS::AccountId}`. Đảm bảo bạn đang deploy vào đúng tài khoản AWS cá nhân.
2. **Lỗi thiếu quyền IAM khi deploy:**
   - Đảm bảo tài khoản IAM hoặc Role của bạn có đủ quyền tạo các tài nguyên: CloudFormation, Lambda, DynamoDB, SQS, S3, SSM, IAM Role (`iam:CreateRole`, `iam:AttachRolePolicy`, `iam:PassRole`).
3. **Kẹt trạng thái `ROLLBACK_COMPLETE`:**
   - Nếu stack bị lỗi ngay trong lần deploy đầu tiên và rơi vào `ROLLBACK_COMPLETE`, CloudFormation không cho phép cập nhật đè lên stack này.
   - Khắc phục: Chạy `sam delete --stack-name askfirst-dev` để xoá sạch stack hỏng, sau đó chạy lại `sam deploy`.

---

## 7. Dọn Dẹp Tài Nguyên (Clean Up)

Làm xong workshop hoặc không dùng stack nữa thì dọn theo đúng thứ tự dưới đây để không bị tính tiền tiếp. Lệnh viết cho stage `dev`, Region `ap-southeast-1`, chạy ở gốc repo bằng Bash (Windows dùng Git Bash hoặc WSL). Đổi `STAGE` nếu bạn deploy stage khác.

```bash
STAGE=dev
export AWS_REGION=ap-southeast-1
```

> [!CAUTION]
> **Các bước 3, 5, 6 không hoàn tác được.** Bước 3 xoá mọi file trong bucket (model, ảnh người dùng hiến tặng). Bước 5 xoá bảng DynamoDB `askfirst-$STAGE` cùng toàn bộ dữ liệu người dùng, bản backup PITR của bảng cũng mất theo. Bước 6 xoá token bot và log. Cần giữ gì thì sao lưu trước.

### Bước 1: Gỡ webhook Telegram

Để Telegram ngừng gửi tin vào Function URL trước khi xoá function:

```bash
python scripts/set_webhook.py --delete   # đọc TELEGRAM_BOT_TOKEN từ biến môi trường hoặc .env
```

Kiểm tra: script in ra kết quả `deleteWebhook` thành công. Gọi `getWebhookInfo` của bot sẽ thấy `url` rỗng.

### Bước 2: Tắt provisioned concurrency (nếu đã bật)

Template mặc định **không** bật provisioned concurrency. Nếu bạn tự bật thì tắt:

```bash
for f in webhook worker ai; do
  aws lambda list-provisioned-concurrency-configs --function-name askfirst-$STAGE-$f \
    --query 'ProvisionedConcurrencyConfigs[].FunctionArn' --output text 2>/dev/null
done
# Có dòng nào in ra thì xoá theo alias đó, ví dụ:
aws lambda delete-provisioned-concurrency-config --function-name askfirst-$STAGE-ai --qualifier live
```

Kiểm tra: chạy lại vòng `for` ở trên không in ra gì.

### Bước 3: Dọn S3 bucket

CloudFormation không xoá được bucket còn object. Bucket của stack không bật versioning, nhưng nếu bạn tự bật thì phải xoá cả các version cũ:

```bash
BUCKET=$(aws ssm get-parameter --name "/askfirst/$STAGE/s3/bucket_name" --query Parameter.Value --output text)
aws s3 rm "s3://$BUCKET" --recursive
# Chỉ khi đã bật versioning: xoá mọi version và delete marker
aws s3api delete-objects --bucket "$BUCKET" --delete "$(aws s3api list-object-versions --bucket "$BUCKET" \
  --query '{Objects: [Versions, DeleteMarkers][][].{Key: Key, VersionId: VersionId}}' --output json)"
```

Kiểm tra: `aws s3api list-object-versions --bucket "$BUCKET" --max-items 1` không còn `Versions` hay `DeleteMarkers`. Bucket sẽ bị xoá ở bước 5.

### Bước 4: Xoá image trong ECR

Repo `askfirst-ai` thuộc stack `askfirst-github-oidc` (mục 3, bước 4) và đã đặt `EmptyOnDelete: true`, nên xoá stack là xoá luôn image:

```bash
aws cloudformation delete-stack --stack-name askfirst-github-oidc
aws cloudformation wait stack-delete-complete --stack-name askfirst-github-oidc
```

Kiểm tra: `aws ecr describe-repositories --repository-names askfirst-ai` báo `RepositoryNotFoundException`. Nếu tài khoản dùng chung OIDC provider với stack khác thì xem kỹ trước khi xoá stack này.

### Bước 5: Xoá stack SAM

```bash
sam delete --stack-name askfirst-$STAGE
```

Gõ `y` khi SAM hỏi xác nhận xoá stack và artifact.

Lệnh xoá Lambda `webhook`, `worker`, `worker-dlq`, `ai`, bảng DynamoDB, hai hàng đợi SQS, bucket, alarm, các SSM parameter do stack tạo và artifact deploy của stack trong bucket `aws-sam-cli-managed-default`.

Kiểm tra: `aws cloudformation describe-stacks --stack-name askfirst-$STAGE` báo `does not exist`.

### Bước 6: Xoá thứ stack không quản lý

```bash
# Log group do Lambda tự tạo
for g in $(aws logs describe-log-groups --log-group-name-prefix "/aws/lambda/askfirst-$STAGE-" \
           --query 'logGroups[].logGroupName' --output text); do
  aws logs delete-log-group --log-group-name "$g"
done
# SSM parameter tạo tay ở mục 5 (token bot, webhook secret, policy_sha256...)
for n in $(aws ssm get-parameters-by-path --path "/askfirst/$STAGE" --recursive \
           --query 'Parameters[].Name' --output text); do
  aws ssm delete-parameter --name "$n"
done
```

- **Tài nguyên tạo tay ngoài stack**, ví dụ Lambda `ai` deploy tay khi chưa truyền `AiImageUri`, hay repo ECR khác `askfirst-ai`: `sam delete` không xoá. Tìm bằng các lệnh ở mục kiểm tra cuối rồi xoá tay (`aws lambda delete-function --function-name <tên>`, `aws ecr delete-repository --repository-name <tên> --force`).
- **AWS Budgets** (BE-01) không tính tiền nên có thể giữ. Muốn xoá: `aws budgets describe-budgets --account-id <account_id>` để lấy tên, rồi `aws budgets delete-budget --account-id <account_id> --budget-name <tên>`.
- **Bucket `aws-sam-cli-managed-default`** do `resolve_s3 = true` tạo, dùng chung cho mọi stack SAM trong Region. Không còn stack SAM nào thì làm rỗng bucket đó rồi xoá stack `aws-sam-cli-managed-default`.
- **Guardrail Bedrock** (`ap-southeast-2`): chỉ khi bạn đã tạo bằng `ai/scripts/create_guardrail.py` thì xoá bằng `aws bedrock delete-guardrail --region ap-southeast-2 --guardrail-identifier <id>`.

### Kiểm tra cuối: không còn tài nguyên `askfirst-$STAGE`

```bash
aws cloudformation describe-stacks --stack-name askfirst-$STAGE 2>&1 | grep -q 'does not exist' && echo "stack: đã xoá"
aws lambda list-functions --query "Functions[?starts_with(FunctionName, 'askfirst-$STAGE')].FunctionName" --output text
aws dynamodb list-tables --query "TableNames[?starts_with(@, 'askfirst-$STAGE')]" --output text
aws sqs list-queues --queue-name-prefix "askfirst-$STAGE" --query QueueUrls --output text
aws s3 ls | grep "askfirst-$STAGE"
aws logs describe-log-groups --log-group-name-prefix "/aws/lambda/askfirst-$STAGE-" --query 'logGroups[].logGroupName' --output text
aws ssm get-parameters-by-path --path "/askfirst/$STAGE" --recursive --query 'Parameters[].Name' --output text
```

Sáu lệnh sau dòng đầu đều phải in ra rỗng (hoặc `None`).

> [!NOTE]
> Trong lúc còn dùng stack, theo dõi cảnh báo **AWS Budgets** (ngưỡng 10$, 25$, 50$) đã thiết lập ở task BE-01.

---

## 8. Rủi Ro Cần Lưu Ý & Ghi Chú Kỹ Thuật (Risk Notes)

### 1. Chi phí AWS & Ngưỡng AWS Budgets (BE-01):
- Giữ chi phí môi trường `dev` nghiêm ngặt trong ngưỡng AWS Budgets (10 USD cảnh báo sớm, 25 USD cảnh báo vừa, 50 USD ngưỡng khẩn cấp) đã thiết lập ở task BE-01.
- Không bật NAT Gateway hay VPC cho Lambda webhook ở giai đoạn dev để tránh chi phí cố định ~32 USD/tháng (Quy tắc thiết kế mạng & chi phí D1).
- Luôn dọn dẹp (xoá) tài nguyên thử nghiệm hoặc chạy `sam delete` khi kết thúc đợt test kéo dài.

### 2. IAM Permissions & Vướng mắc phân quyền lần đầu deploy:
Trong lần triển khai đầu tiên, người dùng IAM hoặc Role deploy thường bị từ chối quyền (`AccessDenied`) tại các khâu CloudFormation tạo IAM Role hoặc gắn Policy. Để deploy thành công, IAM Principal cần có các nhóm quyền tối thiểu sau:
- **CloudFormation:** `cloudformation:*` trên stack `askfirst-dev`.
- **Lambda:** `lambda:CreateFunction`, `lambda:DeleteFunction`, `lambda:UpdateFunction*`, `lambda:AddPermission`, `lambda:CreateFunctionUrlConfig`, `lambda:GetFunctionUrlConfig`.
- **IAM:** `iam:CreateRole`, `iam:DeleteRole`, `iam:GetRole`, `iam:AttachRolePolicy`, `iam:DetachRolePolicy`, `iam:PutRolePolicy`, `iam:DeleteRolePolicy`, `iam:PassRole` (PassRole vào role thực thi của Lambda `WebhookFunctionRole`).
- **DynamoDB:** `dynamodb:CreateTable`, `dynamodb:DeleteTable`, `dynamodb:DescribeTable`, `dynamodb:UpdateTimeToLive`, `dynamodb:UpdateContinuousBackups`.
- **SQS:** `sqs:CreateQueue`, `sqs:DeleteQueue`, `sqs:GetQueueAttributes`, `sqs:SetQueueAttributes`.
- **S3:** `s3:CreateBucket`, `s3:DeleteBucket`, `s3:PutBucketPublicAccessBlock`, `s3:PutEncryptionConfiguration`, `s3:PutLifecycleConfiguration`, `s3:PutObject`, `s3:DeleteObject`.
- **SSM:** `ssm:PutParameter`, `ssm:DeleteParameter`, `ssm:GetParameter*`, và `kms:Decrypt` (để verify đọc được SecureString với `--with-decryption`).

### 3. Trạng thái kiểm nghiệm SnapStart (Java 21):
- `not verified: hành vi SAM + Java 21 SnapStart trên cả 3 OS dev (Windows, macOS, Linux)`
  - *Giải thích:* Theo implementation plan, SnapStart cho Java 21 trên Lambda (Singapore `ap-southeast-1`) được lên kế hoạch đo lường và khảo sát chuyên sâu tại spike **BE-14** (W1) và hoàn thiện đường ống tại **BE-07** (W2–W3). Bản hello-world Webhook hiện tại tập trung thông suốt luồng build/deploy/Function URL công khai không bật SnapStart để giữ độ đơn giản tối đa và đảm bảo tương thích đa hệ điều hành dev.

### 4. Bedrock ở account khác (Lambda `ai`)
Account team chạy Lambda nhưng Bedrock (model + guardrail trong `ai/config/models.yaml`) nằm ở account khác. Lambda `ai` có env `BEDROCK_ROLE_ARN` thì assume role đó trước khi gọi Bedrock (`ai/src/askfirst/llm/bedrock.py`, `runtime_client`); không có env thì gọi Bedrock trong chính account đang chạy.

```bash
# Account có Bedrock: role cho role của Lambda ai assume
aws iam create-role --role-name askfirst-bedrock-from-team --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::<TEAM_ACCOUNT>:role/<AI_LAMBDA_ROLE>"},"Action":"sts:AssumeRole"}]}'
aws iam put-role-policy --role-name askfirst-bedrock-from-team --policy-name bedrock --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["bedrock:InvokeModel","bedrock:ApplyGuardrail"],"Resource":"*"}]}'
# Account team: cho role của Lambda ai assume role trên
aws iam put-role-policy --role-name <AI_LAMBDA_ROLE> --policy-name assume-bedrock --policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole","Resource":"arn:aws:iam::<BEDROCK_ACCOUNT>:role/askfirst-bedrock-from-team"}]}'
```
Rồi đặt env `BEDROCK_ROLE_ARN=arn:aws:iam::<BEDROCK_ACCOUNT>:role/askfirst-bedrock-from-team` cho function `ai`.
