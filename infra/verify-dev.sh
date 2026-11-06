#!/usr/bin/env bash
# ==============================================================================
# Script nghiem thu ha tang BE-03 (askfirst-<stage>) SAU KHI `sam deploy`.
#
# `sam deploy` chi cho biet CloudFormation tao/cap nhat duoc tai nguyen. Script nay kiem tra
# tai nguyen dung voi YEU CAU (schema key, TTL, lifecycle 30 ngay, redrive DLQ, handler, env var,
# xac thuc secret...). Moi muc sai se in [FAIL] va script thoat voi ma loi 1 (dung duoc trong CI).
# Script chi DOC (khong ghi vao S3/SQS/DynamoDB).
#
# Usage: ./verify-dev.sh [region] [stage]
# ==============================================================================

set -euo pipefail

REGION="${1:-ap-southeast-1}"
STAGE="${2:-dev}"
STACK_NAME="askfirst-${STAGE}"
FUNCTION_NAME="askfirst-${STAGE}-webhook"
TABLE_NAME="askfirst-${STAGE}"

FAILED=0
pass() { echo "   [PASS] $*"; }
fail() { echo "   [FAIL] $*"; FAILED=1; }
expect_eq() {  # nhan | thuc_te | mong_doi
  if [ "$2" = "$3" ]; then pass "$1 = $3"; else fail "$1: mong doi '$3', thuc te '$2'"; fi
}
ssm_value() {  # ten tham so -> gia tri (loi AWS se dung script)
  aws ssm get-parameter --name "$1" --with-decryption --region "${REGION}" \
    --query "Parameter.Value" --output text
}

echo "======================================================================"
echo " NGHIEM THU HA TANG BE-03: STACK ${STACK_NAME} (${REGION})"
echo "======================================================================"

# 0. AWS CLI & Identity
echo -e "\n[0/5] Kiem tra ket noi AWS..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "   Account: ${ACCOUNT_ID}"

# 1. Lambda webhook: cau hinh + xac thuc secret qua Function URL
echo -e "\n[1/5] Kiem tra Lambda webhook & Function URL..."
HANDLER=$(aws lambda get-function-configuration --function-name "${FUNCTION_NAME}" \
  --region "${REGION}" --query "Handler" --output text)
expect_eq "Handler" "${HANDLER}" "com.askfirst.webhookhandler.WebhookHandler::handleRequest"

ENV_KEYS=$(aws lambda get-function-configuration --function-name "${FUNCTION_NAME}" \
  --region "${REGION}" --query "keys(Environment.Variables)" --output text | tr '\t' '\n')
for key in DYNAMODB_TABLE_NAME WEBHOOK_QUEUE_URL TELEGRAM_BOT_TOKEN TELEGRAM_WEBHOOK_SECRET POLICY_SHA256; do
  if grep -qx "${key}" <<<"${ENV_KEYS}"; then pass "Env var ${key} co mat"; else fail "Thieu env var ${key}"; fi
done

FUNCTION_URL=$(aws cloudformation describe-stacks --stack-name "${STACK_NAME}" --region "${REGION}" \
  --query "Stacks[0].Outputs[?OutputKey=='WebhookFunctionUrl'].OutputValue" --output text)
echo "   URL: ${FUNCTION_URL}"

# Function URL cong khai (AuthType NONE): request khong co secret PHAI bi tu choi
UNAUTH_STATUS=$(curl -s -o /dev/null -m 30 -w "%{http_code}" -X POST \
  -H "Content-Type: application/json" -d '{}' "${FUNCTION_URL}")
expect_eq "POST khong co secret (fail-closed) -> HTTP" "${UNAUTH_STATUS}" "401"

# Request hop le (secret lay tu SSM, khong in ra man hinh) voi update rong -> IGNORED
WEBHOOK_SECRET=$(ssm_value "/askfirst/${STAGE}/telegram/webhook_secret")
AUTH_RESPONSE=$(curl -s -m 30 -w "\nHTTP_STATUS:%{http_code}" -X POST \
  -H "Content-Type: application/json" \
  -H "X-Telegram-Bot-Api-Secret-Token: ${WEBHOOK_SECRET}" -d '{}' "${FUNCTION_URL}")
AUTH_STATUS=$(echo "${AUTH_RESPONSE}" | tail -n1 | cut -d':' -f2)
AUTH_BODY=$(echo "${AUTH_RESPONSE}" | sed -e '$d')
expect_eq "POST co secret dung -> HTTP" "${AUTH_STATUS}" "200"
expect_eq "POST co secret dung -> body" "${AUTH_BODY}" "IGNORED"

# 2. DynamoDB
echo -e "\n[2/5] Kiem tra DynamoDB ${TABLE_NAME}..."
KEY_SCHEMA=$(aws dynamodb describe-table --table-name "${TABLE_NAME}" --region "${REGION}" \
  --query "Table.KeySchema[*].AttributeName" --output text | tr '\t' ' ')
expect_eq "Key schema (PK SK)" "${KEY_SCHEMA}" "PK SK"

GSI1_KEYS=$(aws dynamodb describe-table --table-name "${TABLE_NAME}" --region "${REGION}" \
  --query "Table.GlobalSecondaryIndexes[?IndexName=='GSI1'].KeySchema[*].AttributeName | [0]" --output text | tr '\t' ' ')
expect_eq "GSI1 key schema" "${GSI1_KEYS}" "GSI1PK GSI1SK"

TABLE_STATUS=$(aws dynamodb describe-table --table-name "${TABLE_NAME}" --region "${REGION}" \
  --query "Table.TableStatus" --output text)
expect_eq "Table status" "${TABLE_STATUS}" "ACTIVE"

GSI1_STATUS=$(aws dynamodb describe-table --table-name "${TABLE_NAME}" --region "${REGION}" \
  --query "Table.GlobalSecondaryIndexes[?IndexName=='GSI1'].IndexStatus | [0]" --output text)
expect_eq "GSI1 status" "${GSI1_STATUS}" "ACTIVE"

TTL_DESC=$(aws dynamodb describe-time-to-live --table-name "${TABLE_NAME}" --region "${REGION}" \
  --query "TimeToLiveDescription.[TimeToLiveStatus,AttributeName]" --output text | tr '\t' ' ')
expect_eq "TTL (trang thai, attribute)" "${TTL_DESC}" "ENABLED ttl"

PITR_STATUS=$(aws dynamodb describe-continuous-backups --table-name "${TABLE_NAME}" --region "${REGION}" \
  --query "ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus" --output text)
expect_eq "PITR" "${PITR_STATUS}" "ENABLED"

# 3. S3 Data Bucket
echo -e "\n[3/5] Kiem tra S3 Data Bucket..."
BUCKET_NAME=$(ssm_value "/askfirst/${STAGE}/s3/bucket_name")
echo "   Bucket: ${BUCKET_NAME}"

BPA=$(aws s3api get-public-access-block --bucket "${BUCKET_NAME}" --region "${REGION}" \
  --query "PublicAccessBlockConfiguration.[BlockPublicAcls,BlockPublicPolicy,IgnorePublicAcls,RestrictPublicBuckets]" \
  --output text | tr '\t' ' ')
expect_eq "Block Public Access (4 co)" "${BPA}" "True True True True"

# Cam ket voi nguoi dung: du lieu donated/ xoa sau 30 ngay (privacy-policy + ARCH 5.6)
LIFECYCLE=$(aws s3api get-bucket-lifecycle-configuration --bucket "${BUCKET_NAME}" --region "${REGION}" \
  --query "Rules[?Filter.Prefix=='donated/' || Prefix=='donated/'].[Status,Expiration.Days] | [0]" \
  --output text | tr '\t' ' ')
expect_eq "Lifecycle donated/ (trang thai, so ngay)" "${LIFECYCLE}" "Enabled 30"
# Luu y: prefix S3 (models/, kb/, donated/) chi la tien to cua key, khong can tao san va script nay khong ghi vao bucket.

# 4. SQS
echo -e "\n[4/5] Kiem tra SQS jobs & jobs-dlq..."
JOBS_URL=$(ssm_value "/askfirst/${STAGE}/sqs/jobs_queue_url")
DLQ_URL=$(ssm_value "/askfirst/${STAGE}/sqs/jobs_dlq_url")

VISIBILITY=$(aws sqs get-queue-attributes --queue-url "${JOBS_URL}" --region "${REGION}" \
  --attribute-names VisibilityTimeout --query "Attributes.VisibilityTimeout" --output text)
expect_eq "VisibilityTimeout (6x worker 120s)" "${VISIBILITY}" "720"

REDRIVE=$(aws sqs get-queue-attributes --queue-url "${JOBS_URL}" --region "${REGION}" \
  --attribute-names RedrivePolicy --query "Attributes.RedrivePolicy" --output text)
DLQ_ARN=$(aws sqs get-queue-attributes --queue-url "${DLQ_URL}" --region "${REGION}" \
  --attribute-names QueueArn --query "Attributes.QueueArn" --output text)
if grep -qF "${DLQ_ARN}" <<<"${REDRIVE}"; then pass "Redrive tro dung jobs-dlq"; else fail "Redrive KHONG tro toi jobs-dlq (${REDRIVE})"; fi
if grep -Eq '"maxReceiveCount"[[:space:]]*:[[:space:]]*"?5"?' <<<"${REDRIVE}"; then pass "maxReceiveCount = 5"; else fail "maxReceiveCount khac 5 (${REDRIVE})"; fi

# 5. SSM Parameter Store
echo -e "\n[5/5] Kiem tra SSM Parameter Store (/askfirst/${STAGE})..."
for name in config/stage dynamodb/table_name sqs/jobs_queue_url sqs/jobs_dlq_url s3/bucket_name \
            telegram/bot_token telegram/webhook_secret config/policy_sha256; do
  if aws ssm get-parameter --name "/askfirst/${STAGE}/${name}" --region "${REGION}" >/dev/null 2>&1; then
    pass "/askfirst/${STAGE}/${name}"
  else
    fail "Thieu SSM parameter /askfirst/${STAGE}/${name}"
  fi
done

echo -e "\n======================================================================"
if [ "${FAILED}" -eq 0 ]; then
  echo " TAT CA MUC NGHIEM THU BE-03 DAT."
  echo "======================================================================"
else
  echo " CO MUC KHONG DAT - xem cac dong [FAIL] o tren."
  echo "======================================================================"
  exit 1
fi
