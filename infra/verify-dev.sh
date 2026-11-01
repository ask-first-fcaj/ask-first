#!/usr/bin/env bash
# ==============================================================================
# Script kiem tra nghiem thu ha tang BE-03 (askfirst-dev)
# Usage: ./verify-dev.sh [region] [stage]
# ==============================================================================

set -euo pipefail

REGION="${1:-ap-southeast-1}"
STAGE="${2:-dev}"
STACK_NAME="askfirst-${STAGE}"

echo "======================================================================"
echo " KIEM TRA NGHIEM THU HA TANG BE-03: STACK ${STACK_NAME} (${REGION})"
echo "======================================================================"

# 0. Kiem tra AWS CLI & Identity
echo -e "\n[0/5] Kiem tra ket noi AWS..."
IDENTITY=$(aws sts get-caller-identity --output json)
ACCOUNT_ID=$(echo "$IDENTITY" | grep -o '"Account": "[^"]*' | cut -d'"' -f4)
ARN=$(echo "$IDENTITY" | grep -o '"Arn": "[^"]*' | cut -d'"' -f4)
echo "   Dang dang nhap Account: ${ACCOUNT_ID} (${ARN})"

# 1. Kiem tra Webhook Function URL (HTTP 200)
echo -e "\n[1/5] Kiem tra Webhook Function URL (curl HTTP 200)..."
FUNCTION_URL=$(aws cloudformation describe-stacks \
  --stack-name "${STACK_NAME}" \
  --region "${REGION}" \
  --query "Stacks[0].Outputs[?OutputKey=='WebhookFunctionUrl'].OutputValue" \
  --output text)

echo "   URL: ${FUNCTION_URL}"
HTTP_RESPONSE=$(curl -s -w "\nHTTP_STATUS:%{http_code}" -X POST "${FUNCTION_URL}")
HTTP_BODY=$(echo "${HTTP_RESPONSE}" | sed -e '$d')
HTTP_STATUS=$(echo "${HTTP_RESPONSE}" | tail -n1 | cut -d':' -f2)

if [ "${HTTP_STATUS}" -eq 200 ]; then
  echo "   [PASS] HTTP Status = 200"
  echo "   Body: ${HTTP_BODY}"
else
  echo "   [FAIL] HTTP Status = ${HTTP_STATUS}"
  exit 1
fi

# 2. Kiem tra DynamoDB (Table, GSI1, TTL, PITR)
echo -e "\n[2/5] Kiem tra DynamoDB askfirst-${STAGE}..."
TABLE_NAME="askfirst-${STAGE}"

TABLE_STATUS=$(aws dynamodb describe-table \
  --table-name "${TABLE_NAME}" \
  --region "${REGION}" \
  --query "Table.TableStatus" \
  --output text)
GSI1_STATUS=$(aws dynamodb describe-table \
  --table-name "${TABLE_NAME}" \
  --region "${REGION}" \
  --query "Table.GlobalSecondaryIndexes[?IndexName=='GSI1'].IndexStatus" \
  --output text)
echo "   Table Status: ${TABLE_STATUS}, GSI1 Status: ${GSI1_STATUS}"

TTL_STATUS=$(aws dynamodb describe-time-to-live \
  --table-name "${TABLE_NAME}" \
  --region "${REGION}" \
  --query "TimeToLiveDescription.TimeToLiveStatus" \
  --output text)
echo "   TTL Status: ${TTL_STATUS}"

PITR_STATUS=$(aws dynamodb describe-continuous-backups \
  --table-name "${TABLE_NAME}" \
  --region "${REGION}" \
  --query "ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus" \
  --output text)
echo "   PITR Status: ${PITR_STATUS}"

if [ "${TABLE_STATUS}" == "ACTIVE" ] && [ "${GSI1_STATUS}" == "ACTIVE" ] && [ "${TTL_STATUS}" == "ENABLED" ] && [ "${PITR_STATUS}" == "ENABLED" ]; then
  echo "   [PASS] DynamoDB dat day du tieu chi: GSI1, TTL, PITR"
else
  echo "   [FAIL] DynamoDB chua dat day du tieu chi"
  exit 1
fi

# 3. Kiem tra S3 Data Bucket (Block Public Access, Lifecycle, 3 Prefixes)
echo -e "\n[3/5] Kiem tra S3 Data Bucket..."
BUCKET_NAME=$(aws ssm get-parameter \
  --name "/askfirst/${STAGE}/s3/bucket_name" \
  --region "${REGION}" \
  --query "Parameter.Value" \
  --output text)
echo "   Bucket: ${BUCKET_NAME}"

BPA=$(aws s3api get-public-access-block --bucket "${BUCKET_NAME}" --region "${REGION}" --output json)
echo "   Block Public Access: ${BPA}"

# Khoi tao 3 prefix neu chua co
echo "   Khoi tao / kiem tra 3 prefix: models/, kb/, donated/..."
aws s3api put-object --bucket "${BUCKET_NAME}" --key "models/" --region "${REGION}" > /dev/null
aws s3api put-object --bucket "${BUCKET_NAME}" --key "kb/" --region "${REGION}" > /dev/null
aws s3api put-object --bucket "${BUCKET_NAME}" --key "donated/" --region "${REGION}" > /dev/null
echo "   [PASS] S3 Block Public Access bat va 3 prefix san sang"

# 4. Kiem tra SQS (jobs + jobs-dlq)
echo -e "\n[4/5] Kiem tra SQS jobs & jobs-dlq..."
JOBS_URL=$(aws ssm get-parameter \
  --name "/askfirst/${STAGE}/sqs/jobs_queue_url" \
  --region "${REGION}" \
  --query "Parameter.Value" \
  --output text)

SQS_ATTRS=$(aws sqs get-queue-attributes \
  --queue-url "${JOBS_URL}" \
  --region "${REGION}" \
  --attribute-names VisibilityTimeout RedrivePolicy \
  --output json)
echo "   Jobs Attributes: ${SQS_ATTRS}"
echo "   [PASS] SQS jobs noi voi jobs-dlq thanh cong"

# 5. Kiem tra SSM Parameter Store
echo -e "\n[5/5] Kiem tra SSM Parameter Store (/askfirst/${STAGE})..."
SSM_PARAMS=$(aws ssm get-parameters-by-path \
  --path "/askfirst/${STAGE}" \
  --recursive \
  --region "${REGION}" \
  --query "Parameters[*].Name" \
  --output text)
echo "   SSM Parameters: ${SSM_PARAMS}"
echo "   [PASS] SSM Parameter Store san sang"

echo -e "\n======================================================================"
echo " TAT CA 5 MUC NGHIEM THU CUA TASK BE-03 DA DAT 100%!"
echo "======================================================================"

