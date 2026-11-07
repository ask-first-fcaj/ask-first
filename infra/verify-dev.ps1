# ==============================================================================
# Script nghiem thu ha tang BE-03 (askfirst-<stage>) SAU KHI `sam deploy`, chay tren Windows PowerShell.
#
# `sam deploy` chi cho biet CloudFormation tao/cap nhat duoc tai nguyen. Script nay kiem tra tai nguyen
# dung voi YEU CAU (schema key, TTL, lifecycle 30 ngay, redrive DLQ, handler, env var, xac thuc secret...).
# Moi muc sai se in [FAIL] va script thoat voi ma loi 1. Script chi DOC (khong ghi vao S3/SQS/DynamoDB).
#
# Usage: .\verify-dev.ps1 [-Region ap-southeast-1] [-Stage dev]
# ==============================================================================

param(
  [string]$Region = "ap-southeast-1",
  [string]$Stage = "dev"
)

$ErrorActionPreference = "Stop"
$StackName = "askfirst-$Stage"
$FunctionName = "askfirst-$Stage-webhook"
$TableName = "askfirst-$Stage"
$script:Failed = $false

# Goi aws CLI va KIEM TRA $LASTEXITCODE: lenh loi (AccessDenied, khong tim thay...) se dung script,
# khong con tinh trang lenh loi nhung van in [PASS].
function Invoke-Aws {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AwsArgs)
  $out = & aws @AwsArgs
  if ($LASTEXITCODE -ne 0) {
    throw "aws $($AwsArgs -join ' ') that bai (exit=$LASTEXITCODE)"
  }
  if ($null -eq $out) { return "" }
  return (($out | ForEach-Object { "$_" }) -join "`n").Trim()
}

function Get-SsmValue([string]$Name) {
  return Invoke-Aws ssm get-parameter --name $Name --with-decryption --region $Region `
    --query "Parameter.Value" --output text
}

function Write-Pass([string]$Message) { Write-Host "   [PASS] $Message" -ForegroundColor Green }
function Write-Fail([string]$Message) { Write-Host "   [FAIL] $Message" -ForegroundColor Red; $script:Failed = $true }

function Expect-Equal([string]$Label, [string]$Actual, [string]$Expected) {
  if ($Actual -eq $Expected) { Write-Pass "$Label = $Expected" }
  else { Write-Fail "${Label}: mong doi '$Expected', thuc te '$Actual'" }
}

Write-Host "======================================================================" -ForegroundColor Cyan
Write-Host " NGHIEM THU HA TANG BE-03: STACK $StackName ($Region)" -ForegroundColor Cyan
Write-Host "======================================================================" -ForegroundColor Cyan

# 0. AWS CLI & Identity
Write-Host "`n[0/5] Kiem tra ket noi AWS..." -ForegroundColor Yellow
$AccountId = Invoke-Aws sts get-caller-identity --query Account --output text
Write-Host "   Account: $AccountId" -ForegroundColor Gray

# 1. Lambda webhook: cau hinh + xac thuc secret qua Function URL
Write-Host "`n[1/5] Kiem tra Lambda webhook & Function URL..." -ForegroundColor Yellow
$Handler = Invoke-Aws lambda get-function-configuration --function-name $FunctionName --region $Region `
  --query "Handler" --output text
Expect-Equal "Handler" $Handler "com.askfirst.webhookhandler.WebhookHandler::handleRequest"

$EnvKeys = (Invoke-Aws lambda get-function-configuration --function-name $FunctionName --region $Region `
  --query "keys(Environment.Variables)" --output text) -split "\s+"
foreach ($Key in @("DYNAMODB_TABLE_NAME", "WEBHOOK_QUEUE_URL", "TELEGRAM_BOT_TOKEN_PARAM", "TELEGRAM_WEBHOOK_SECRET_PARAM", "POLICY_SHA256")) {
  if ($EnvKeys -contains $Key) { Write-Pass "Env var $Key co mat" } else { Write-Fail "Thieu env var $Key" }
}

$FunctionUrl = Invoke-Aws cloudformation describe-stacks --stack-name $StackName --region $Region `
  --query "Stacks[0].Outputs[?OutputKey=='WebhookFunctionUrl'].OutputValue" --output text
Write-Host "   URL: $FunctionUrl" -ForegroundColor Gray

# Function URL cong khai (AuthType NONE): request khong co secret PHAI bi tu choi.
# Dung curl.exe (co san tren Windows 10+) vi Invoke-WebRequest nem loi khi gap HTTP 4xx tren PowerShell 5.1.
$UnauthStatus = (& curl.exe -s -o NUL -m 30 -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "{}" $FunctionUrl)
Expect-Equal "POST khong co secret (fail-closed) -> HTTP" "$UnauthStatus" "401"

# Request hop le (secret lay tu SSM, khong in ra man hinh) voi update rong -> IGNORED
$WebhookSecret = Get-SsmValue "/askfirst/$Stage/telegram/webhook_secret"
$BodyFile = [System.IO.Path]::GetTempFileName()
try {
  $AuthStatus = (& curl.exe -s -o $BodyFile -m 30 -w "%{http_code}" -X POST `
    -H "Content-Type: application/json" -H "X-Telegram-Bot-Api-Secret-Token: $WebhookSecret" -d "{}" $FunctionUrl)
  $AuthBody = (Get-Content -Raw $BodyFile)
  if ($null -eq $AuthBody) { $AuthBody = "" }
} finally {
  Remove-Item $BodyFile -ErrorAction SilentlyContinue
}
Expect-Equal "POST co secret dung -> HTTP" "$AuthStatus" "200"
Expect-Equal "POST co secret dung -> body" ($AuthBody.Trim()) "IGNORED"

# 2. DynamoDB
Write-Host "`n[2/5] Kiem tra DynamoDB $TableName..." -ForegroundColor Yellow
$KeySchema = (Invoke-Aws dynamodb describe-table --table-name $TableName --region $Region `
  --query "Table.KeySchema[*].AttributeName" --output text) -replace "\s+", " "
Expect-Equal "Key schema (PK SK)" $KeySchema "PK SK"

$Gsi1Keys = (Invoke-Aws dynamodb describe-table --table-name $TableName --region $Region `
  --query "Table.GlobalSecondaryIndexes[?IndexName=='GSI1'].KeySchema[*].AttributeName | [0]" --output text) -replace "\s+", " "
Expect-Equal "GSI1 key schema" $Gsi1Keys "GSI1PK GSI1SK"

$TableStatus = Invoke-Aws dynamodb describe-table --table-name $TableName --region $Region `
  --query "Table.TableStatus" --output text
Expect-Equal "Table status" $TableStatus "ACTIVE"

$Gsi1Status = Invoke-Aws dynamodb describe-table --table-name $TableName --region $Region `
  --query "Table.GlobalSecondaryIndexes[?IndexName=='GSI1'].IndexStatus | [0]" --output text
Expect-Equal "GSI1 status" $Gsi1Status "ACTIVE"

$TtlDesc = (Invoke-Aws dynamodb describe-time-to-live --table-name $TableName --region $Region `
  --query "TimeToLiveDescription.[TimeToLiveStatus,AttributeName]" --output text) -replace "\s+", " "
Expect-Equal "TTL (trang thai, attribute)" $TtlDesc "ENABLED ttl"

$PitrStatus = Invoke-Aws dynamodb describe-continuous-backups --table-name $TableName --region $Region `
  --query "ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus" --output text
Expect-Equal "PITR" $PitrStatus "ENABLED"

# 3. S3 Data Bucket
Write-Host "`n[3/5] Kiem tra S3 Data Bucket..." -ForegroundColor Yellow
$BucketName = Get-SsmValue "/askfirst/$Stage/s3/bucket_name"
Write-Host "   Bucket: $BucketName" -ForegroundColor Gray

$Bpa = (Invoke-Aws s3api get-public-access-block --bucket $BucketName --region $Region `
  --query "PublicAccessBlockConfiguration.[BlockPublicAcls,BlockPublicPolicy,IgnorePublicAcls,RestrictPublicBuckets]" `
  --output text) -replace "\s+", " "
Expect-Equal "Block Public Access (4 co)" $Bpa "True True True True"

# Cam ket voi nguoi dung: du lieu donated/ xoa sau 30 ngay (privacy-policy + ARCH 5.6)
$Lifecycle = (Invoke-Aws s3api get-bucket-lifecycle-configuration --bucket $BucketName --region $Region `
  --query "Rules[?Filter.Prefix=='donated/' || Prefix=='donated/'].[Status,Expiration.Days] | [0]" `
  --output text) -replace "\s+", " "
Expect-Equal "Lifecycle donated/ (trang thai, so ngay)" $Lifecycle "Enabled 30"
# Luu y: prefix S3 (models/, kb/, donated/) chi la tien to cua key, khong can tao san va script nay khong ghi vao bucket.

# 4. SQS
Write-Host "`n[4/5] Kiem tra SQS jobs & jobs-dlq..." -ForegroundColor Yellow
$JobsUrl = Get-SsmValue "/askfirst/$Stage/sqs/jobs_queue_url"
$DlqUrl = Get-SsmValue "/askfirst/$Stage/sqs/jobs_dlq_url"

$Visibility = Invoke-Aws sqs get-queue-attributes --queue-url $JobsUrl --region $Region `
  --attribute-names VisibilityTimeout --query "Attributes.VisibilityTimeout" --output text
Expect-Equal "VisibilityTimeout (6x worker 120s)" $Visibility "720"

$Redrive = Invoke-Aws sqs get-queue-attributes --queue-url $JobsUrl --region $Region `
  --attribute-names RedrivePolicy --query "Attributes.RedrivePolicy" --output text
$DlqArn = Invoke-Aws sqs get-queue-attributes --queue-url $DlqUrl --region $Region `
  --attribute-names QueueArn --query "Attributes.QueueArn" --output text
if ($Redrive.Contains($DlqArn)) { Write-Pass "Redrive tro dung jobs-dlq" } else { Write-Fail "Redrive KHONG tro toi jobs-dlq ($Redrive)" }
if ($Redrive -match '"maxReceiveCount"\s*:\s*"?5"?') { Write-Pass "maxReceiveCount = 5" } else { Write-Fail "maxReceiveCount khac 5 ($Redrive)" }

# 5. SSM Parameter Store
Write-Host "`n[5/5] Kiem tra SSM Parameter Store (/askfirst/$Stage)..." -ForegroundColor Yellow
foreach ($Name in @("config/stage", "dynamodb/table_name", "sqs/jobs_queue_url", "sqs/jobs_dlq_url", "s3/bucket_name",
                    "telegram/bot_token", "telegram/webhook_secret", "config/policy_sha256")) {
  $ErrorActionPreference = "Continue"   # PowerShell 5.1: stderr cua lenh native khong duoc bien thanh loi dung script
  & aws ssm get-parameter --name "/askfirst/$Stage/$Name" --region $Region *> $null
  $ErrorActionPreference = "Stop"
  if ($LASTEXITCODE -eq 0) { Write-Pass "/askfirst/$Stage/$Name" } else { Write-Fail "Thieu SSM parameter /askfirst/$Stage/$Name" }
}

Write-Host "`n======================================================================" -ForegroundColor Cyan
if (-not $script:Failed) {
  Write-Host " TAT CA MUC NGHIEM THU BE-03 DAT." -ForegroundColor Green
  Write-Host "======================================================================" -ForegroundColor Cyan
} else {
  Write-Host " CO MUC KHONG DAT - xem cac dong [FAIL] o tren." -ForegroundColor Red
  Write-Host "======================================================================" -ForegroundColor Cyan
  exit 1
}
