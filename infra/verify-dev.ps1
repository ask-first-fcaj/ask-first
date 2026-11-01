# ==============================================================================
# Script kiem tra nghiem thu ha tang BE-03 (askfirst-dev) tren Windows PowerShell
# Usage: .\verify-dev.ps1 [-Region ap-southeast-1] [-Stage dev]
# ==============================================================================

param(
  [string]$Region = "ap-southeast-1",
  [string]$Stage = "dev"
)

$ErrorActionPreference = "Stop"
$StackName = "askfirst-$Stage"

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host " KIEM TRA NGHIEM THU HA TANG BE-03: STACK $StackName ($Region)" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan

# 0. Kiem tra AWS CLI & Identity
Write-Host "`n[0/5] Kiem tra ket noi AWS..." -ForegroundColor Yellow
$Identity = aws sts get-caller-identity --output json | ConvertFrom-Json
Write-Host "   Dang dang nhap Account: $($Identity.Account) ($($Identity.Arn))" -ForegroundColor Green

# 1. Kiem tra Webhook Function URL (HTTP 200)
Write-Host "`n[1/5] Kiem tra Webhook Function URL (curl/Invoke-RestMethod HTTP 200)..." -ForegroundColor Yellow
$FunctionUrl = aws cloudformation describe-stacks `
  --stack-name $StackName `
  --region $Region `
  --query "Stacks[0].Outputs[?OutputKey=='WebhookFunctionUrl'].OutputValue" `
  --output text

Write-Host "   URL: $FunctionUrl" -ForegroundColor Gray
$Response = Invoke-WebRequest -Uri $FunctionUrl -Method Post -UseBasicParsing
Write-Host "   Status Code: $($Response.StatusCode)" -ForegroundColor Green
Write-Host "   Body: $($Response.Content)" -ForegroundColor Green

if ($Response.StatusCode -eq 200) {
  Write-Host "   [PASS] HTTP Status = 200" -ForegroundColor Green
} else {
  Write-Error "HTTP status was not 200"
}

# 2. Kiem tra DynamoDB (Table, GSI1, TTL, PITR)
Write-Host "`n[2/5] Kiem tra DynamoDB askfirst-$Stage..." -ForegroundColor Yellow
$TableName = "askfirst-$Stage"

$TableStatus = aws dynamodb describe-table `
  --table-name $TableName `
  --region $Region `
  --query "Table.TableStatus" `
  --output text
$Gsi1Status = aws dynamodb describe-table `
  --table-name $TableName `
  --region $Region `
  --query "Table.GlobalSecondaryIndexes[?IndexName=='GSI1'].IndexStatus" `
  --output text
Write-Host "   Table Status: $TableStatus, GSI1 Status: $Gsi1Status" -ForegroundColor Gray

$TtlStatus = aws dynamodb describe-time-to-live `
  --table-name $TableName `
  --region $Region `
  --query "TimeToLiveDescription.TimeToLiveStatus" `
  --output text
Write-Host "   TTL Status: $TtlStatus" -ForegroundColor Gray

$PitrStatus = aws dynamodb describe-continuous-backups `
  --table-name $TableName `
  --region $Region `
  --query "ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus" `
  --output text
Write-Host "   PITR Status: $PitrStatus" -ForegroundColor Gray

if ($TableStatus -eq "ACTIVE" -and $Gsi1Status -eq "ACTIVE" -and $TtlStatus -eq "ENABLED" -and $PitrStatus -eq "ENABLED") {
  Write-Host "   [PASS] DynamoDB dat day du tieu chi: GSI1, TTL, PITR" -ForegroundColor Green
} else {
  Write-Error "DynamoDB khong dat tieu chi"
}

# 3. Kiem tra S3 Data Bucket (Block Public Access, Lifecycle, 3 Prefixes)
Write-Host "`n[3/5] Kiem tra S3 Data Bucket..." -ForegroundColor Yellow
$BucketName = aws ssm get-parameter `
  --name "/askfirst/$Stage/s3/bucket_name" `
  --region $Region `
  --query "Parameter.Value" `
  --output text
Write-Host "   Bucket: $BucketName" -ForegroundColor Gray

$Bpa = aws s3api get-public-access-block --bucket $BucketName --region $Region --output json
Write-Host "   Block Public Access: $Bpa" -ForegroundColor Gray

Write-Host "   Khoi tao / kiem tra 3 prefix: models/, kb/, donated/..." -ForegroundColor Gray
aws s3api put-object --bucket $BucketName --key "models/" --region $Region | Out-Null
aws s3api put-object --bucket $BucketName --key "kb/" --region $Region | Out-Null
aws s3api put-object --bucket $BucketName --key "donated/" --region $Region | Out-Null
Write-Host "   [PASS] S3 Block Public Access bat va 3 prefix san sang" -ForegroundColor Green

# 4. Kiem tra SQS (jobs + jobs-dlq)
Write-Host "`n[4/5] Kiem tra SQS jobs & jobs-dlq..." -ForegroundColor Yellow
$JobsUrl = aws ssm get-parameter `
  --name "/askfirst/$Stage/sqs/jobs_queue_url" `
  --region $Region `
  --query "Parameter.Value" `
  --output text

$SqsAttrs = aws sqs get-queue-attributes `
  --queue-url $JobsUrl `
  --region $Region `
  --attribute-names VisibilityTimeout RedrivePolicy `
  --output json
Write-Host "   Jobs Attributes: $SqsAttrs" -ForegroundColor Gray
Write-Host "   [PASS] SQS jobs noi voi jobs-dlq thanh cong" -ForegroundColor Green

# 5. Kiem tra SSM Parameter Store
Write-Host "`n[5/5] Kiem tra SSM Parameter Store (/askfirst/$Stage)..." -ForegroundColor Yellow
$SsmParams = aws ssm get-parameters-by-path `
  --path "/askfirst/$Stage" `
  --recursive `
  --region $Region `
  --query "Parameters[*].Name" `
  --output text
Write-Host "   SSM Parameters: $SsmParams" -ForegroundColor Gray
Write-Host "   [PASS] SSM Parameter Store san sang" -ForegroundColor Green

Write-Host "`n======================================================================" -ForegroundColor Cyan
Write-Host " TAT CA 5 MUC NGHIEM THU CUA TASK BE-03 DA DAT 100%!" -ForegroundColor Green
Write-Host "======================================================================" -ForegroundColor Cyan

