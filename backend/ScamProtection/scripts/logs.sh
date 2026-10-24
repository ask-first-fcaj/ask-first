#!/usr/bin/env bash
# Xem log webhook-handler, trạng thái SQS và log worker trên LocalStack
# Cách dùng: ./scripts/logs.sh [khoảng thời gian]   ví dụ: ./scripts/logs.sh 2h   (mặc định 30m)

# Git Bash không tự đổi /aws/lambda/... thành đường dẫn Windows
export MSYS_NO_PATHCONV=1

# Chỉ dùng khóa giả của LocalStack, tránh dính khóa AWS thật còn trong terminal
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
export AWS_PROFILE=localstack
export AWS_DEFAULT_REGION=ap-southeast-1

awsl() { aws --endpoint-url=http://localhost:4566 "$@"; }

SINCE=${1:-30m}

# In log của một Lambda, báo rõ nếu chưa có log group
show_lambda_logs() {
  local fn=$1
  local group="/aws/lambda/$fn"
  local found
  found=$(awsl logs describe-log-groups --log-group-name-prefix "$group" \
            --query 'logGroups[0].logGroupName' --output text 2>/dev/null)
  if [ -z "$found" ] || [ "$found" = "None" ]; then
    echo "(chưa có log: Lambda '$fn' chưa được gọi lần nào)"
  else
    awsl logs tail "$group" --since "$SINCE" || echo "(không đọc được log)"
  fi
}

echo "===== webhook-handler (${SINCE} gần nhất) ====="
show_lambda_logs webhook-handler

echo
echo "===== SQS webhookQueue ====="
QUEUE_URL=$(awsl sqs get-queue-url --queue-name webhookQueue --query QueueUrl --output text 2>/dev/null)
if [ -z "$QUEUE_URL" ] || [ "$QUEUE_URL" = "None" ]; then
  echo "(không tìm thấy queue 'webhookQueue', đã chạy setup-localstack.sh chưa?)"
else
  echo "Queue: $QUEUE_URL"
  awsl sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names All \
    --query 'Attributes.{cho:ApproximateNumberOfMessages,dangXuLy:ApproximateNumberOfMessagesNotVisible,hoan:ApproximateNumberOfMessagesDelayed}'
  echo "Trigger SQS -> worker:"
  awsl lambda list-event-source-mappings --function-name worker \
    --query 'EventSourceMappings[].{state:State,ketQua:LastProcessingResult}' 2>/dev/null \
    || echo "(chưa có trigger cho worker)"
fi
echo "Hoạt động SQS trong log LocalStack:"
docker logs localstack --since "$SINCE" 2>&1 \
  | grep -iE "sqs\.(SendMessage|ReceiveMessage|DeleteMessage)" \
  | tail -n 20 \
  || echo "(không có hoạt động SQS)"

echo
echo "===== worker (${SINCE} gần nhất) ====="
show_lambda_logs worker