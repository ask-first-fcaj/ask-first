#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source <(tr -d '\r' < .env); set +a

REGION=ap-southeast-1
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN   # tránh dính khóa AWS thật còn trong terminal
export AWS_PROFILE=localstack AWS_DEFAULT_REGION=$REGION
awsl() { aws --endpoint-url=http://localhost:4566 "$@"; }

INTERNAL=http://localhost.localstack.cloud:4566   # địa chỉ LocalStack nhìn từ trong container Lambda
ROLE=arn:aws:iam::000000000000:role/lambda-role
VERSION=0.0.1-SNAPSHOT

./mvnw -q clean package -DskipTests

QUEUE_URL=$(awsl sqs create-queue --queue-name webhookQueue \
  --attributes VisibilityTimeout=60 --query QueueUrl --output text)
QUEUE_ARN=$(awsl sqs get-queue-attributes --queue-url "$QUEUE_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

deploy() {  # tên | handler | jar | biến môi trường
  awsl lambda delete-function --function-name "$1" >/dev/null 2>&1 || true
  awsl lambda create-function --function-name "$1" --runtime java21 \
    --handler "$2" --zip-file "fileb://$3" --role "$ROLE" \
    --timeout 30 --memory-size 1024 --environment "$4" >/dev/null
  awsl lambda wait function-active-v2 --function-name "$1"
}

deploy webhook-handler "com.askfirst.webhookhandler.WebhookHandler::handleRequest" \
  "webhook-handler/target/webhook-handler-$VERSION.jar" \
  "Variables={WEBHOOK_QUEUE_URL=$QUEUE_URL,TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN,TELEGRAM_WEBHOOK_SECRET=$TELEGRAM_WEBHOOK_SECRET,AWS_ENDPOINT_URL=$INTERNAL}"

deploy worker "com.askfirst.worker.WorkerHandler::handleRequest" \
  "worker/target/worker-$VERSION.jar" \
  "Variables={TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN}"

awsl lambda create-event-source-mapping --function-name worker \
  --event-source-arn "$QUEUE_ARN" --batch-size 1 >/dev/null

# API Gateway (xóa bản cũ cùng tên để chạy lại được)
for id in $(awsl apigateway get-rest-apis --query "items[?name=='telegram-webhook-api'].id" --output text); do
  awsl apigateway delete-rest-api --rest-api-id "$id"
done
API_ID=$(awsl apigateway create-rest-api --name telegram-webhook-api --query id --output text)
ROOT_ID=$(awsl apigateway get-resources --rest-api-id "$API_ID" --query 'items[0].id' --output text)
RES_ID=$(awsl apigateway create-resource --rest-api-id "$API_ID" --parent-id "$ROOT_ID" \
  --path-part webhook --query id --output text)
awsl apigateway put-method --rest-api-id "$API_ID" --resource-id "$RES_ID" \
  --http-method POST --authorization-type NONE >/dev/null
awsl apigateway put-integration --rest-api-id "$API_ID" --resource-id "$RES_ID" \
  --http-method POST --type AWS_PROXY --integration-http-method POST \
  --uri "arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:000000000000:function:webhook-handler/invocations" >/dev/null
awsl apigateway create-deployment --rest-api-id "$API_ID" --stage-name dev >/dev/null

echo "Path webhook: /restapis/$API_ID/dev/_user_request_/webhook"
echo "Xong. Chạy tiếp: ./scripts/set-webhook.sh"