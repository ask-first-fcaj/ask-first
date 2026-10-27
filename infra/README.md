# Hướng Dẫn Hạ Tầng AWS SAM (Ask-First Dev Stack)

Tài liệu này hướng dẫn chi tiết cách build, deploy, kiểm tra nghiệm thu, xử lý sự cố (rollback) và dọn dẹp bộ khung hạ tầng Serverless (AWS SAM) của dự án Ask-First trên môi trường `dev` (Task **BE-03**).

---

## 1. Danh Sách Tài Nguyên Được Tạo

Khi triển khai stack `askfirst-dev` (Region `ap-southeast-1`), AWS SAM sẽ tạo các tài nguyên:

| STT | Tài nguyên | Tên logic (SAM) | Tên thực tế trên AWS | Đặc tả chi tiết |
|---|---|---|---|---|
| 1 | **Lambda Webhook** | `WebhookFunction` | `askfirst-dev-webhook` | Java 21, Function URL công khai (`AuthType: NONE`), handler hello world |
| 2 | **DynamoDB Single-Table** | `AskFirstTable` | `askfirst-dev` | Partition Key (`PK`), Sort Key (`SK`), GSI1 (`GSI1PK`, `GSI1SK`), TTL (`ttl`), PITR bật, Billing: On-Demand (`PAY_PER_REQUEST`) |
| 3 | **SQS Queue chính** | `JobsQueue` | `askfirst-dev-jobs` | Visibility timeout: 720s (6 × 120s worker timeout), SSE bật, Redrive sang DLQ khi lỗi 5 lần |
| 4 | **SQS Dead-Letter Queue** | `JobsDeadLetterQueue` | `askfirst-dev-jobs-dlq` | Message retention: 14 ngày (1.209.600s), SSE bật |
| 5 | **S3 Data Bucket** | `DataBucket` | `askfirst-dev-data-<account_id>` | Block Public Access toàn diện, SSE AES256, Lifecycle rule xoá prefix `donated/` sau 90 ngày |
| 6 | **SSM Parameter Store** | `StageParam`, `TableNameParam`, `JobsQueueUrlParam`, `JobsDlqUrlParam`, `DataBucketNameParam` | `/askfirst/dev/...` | Quản lý cấu hình tập trung (`stage`, `table_name`, `jobs_queue_url`, `jobs_dlq_url`, `bucket_name`) |

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

---

## 4. Kiểm Tra Nghiệm Thu ("Xong Khi")

Chạy các lệnh sau để xác nhận 100% tài nguyên đã hoạt động đúng:

### 1. Kiểm tra Function URL của Webhook (HTTP 200)

Lấy Function URL từ output của SAM và gọi bằng `curl`:
```bash
curl -X POST https://<id-duoc-cap>.lambda-url.ap-southeast-1.on.aws/
```
**Kết quả mong đợi:**
- HTTP Status: `200 OK`
- Body trả về:
  ```json
  {"message": "Hello from AskFirst Webhook!"}
  ```

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

### 3. Kiểm tra S3 Bucket & Khởi tạo 3 prefix

```bash
# Lấy tên bucket từ SSM hoặc SAM output
$BUCKET_NAME = $(aws ssm get-parameter --name "/askfirst/dev/s3/bucket_name" --query "Parameter.Value" --output text)

# Kiểm tra trạng thái Block Public Access
aws s3api get-public-access-block --bucket $BUCKET_NAME

# Kiểm tra Lifecycle Rule (ExpireDonatedSamplesAfter90Days)
aws s3api get-bucket-lifecycle-configuration --bucket $BUCKET_NAME

# Khởi tạo 3 prefix chuẩn: models/, kb/, donated/
aws s3api put-object --bucket $BUCKET_NAME --key models/
aws s3api put-object --bucket $BUCKET_NAME --key kb/
aws s3api put-object --bucket $BUCKET_NAME --key donated/

# Liệt kê kiểm tra các prefix đã có
aws s3 ls s3://$BUCKET_NAME/
```

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

## 5. Hướng Dẫn Lưu Secret & Token (Bảo Mật)

Tuyệt đối **KHÔNG commit token hoặc secret vào Git/template SAM**. Khi cần lưu Telegram Bot Token và Webhook Secret cho bot dev, thực hiện lưu trực tiếp vào SSM Parameter Store dưới dạng `SecureString`:

```bash
# 1. Lưu Telegram Bot Token
aws ssm put-parameter \
  --name "/askfirst/dev/telegram/bot_token" \
  --value "YOUR_TELEGRAM_BOT_TOKEN_HERE" \
  --type SecureString \
  --overwrite

# 2. Lưu Telegram Webhook Secret Token
aws ssm put-parameter \
  --name "/askfirst/dev/telegram/secret_token" \
  --value "YOUR_TELEGRAM_SECRET_TOKEN_HERE" \
  --type SecureString \
  --overwrite
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

Sau khi hoàn thành đợt kiểm thử hoặc khi không sử dụng môi trường dev, hãy xoá stack để không phát sinh chi phí hoặc rác hạ tầng:

```bash
# Bước 1: Dọn sạch object trong S3 bucket (nếu bucket có chứa file, CloudFormation sẽ không xoá được bucket)
$BUCKET_NAME = $(aws ssm get-parameter --name "/askfirst/dev/s3/bucket_name" --query "Parameter.Value" --output text)
aws s3 rm s3://$BUCKET_NAME --recursive

# Bước 2: Xoá sạch toàn bộ stack AWS SAM
sam delete --stack-name askfirst-dev
```
Xác nhận `y` khi SAM hỏi xác nhận xoá stack và S3 bucket.

> [!NOTE]
> Luôn theo dõi cảnh báo **AWS Budgets** (ngưỡng 10$, 25$, 50$) đã thiết lập ở task BE-01 để đảm bảo tài khoản dev luôn nằm trong hạn mức an toàn.
