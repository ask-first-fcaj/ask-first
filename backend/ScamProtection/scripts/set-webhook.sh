#!/usr/bin/env bash
# Đăng ký webhook Telegram: token từ .env, URL từ ngrok, API_ID từ LocalStack
set -euo pipefail
cd "$(dirname "$0")/.."

# Đọc từng biến từ .env (tự bỏ ký tự \r nếu file bị CRLF)
get_env() { grep -E "^$1=" .env | head -n1 | cut -d= -f2- | tr -d '\r' || true; }
TELEGRAM_BOT_TOKEN=$(get_env TELEGRAM_BOT_TOKEN)
TELEGRAM_WEBHOOK_SECRET=$(get_env TELEGRAM_WEBHOOK_SECRET)
if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ -z "$TELEGRAM_WEBHOOK_SECRET" ]; then
  echo "Thiếu TELEGRAM_BOT_TOKEN hoặc TELEGRAM_WEBHOOK_SECRET trong .env"; exit 1
fi

# URL công khai từ ngrok (ngrok phải đang chạy)
NGROK_URL=$(curl -s http://127.0.0.1:4040/api/tunnels \
  | grep -oE '"public_url" *: *"https://[^"]+"' | head -n1 | cut -d'"' -f4 || true)
if [ -z "$NGROK_URL" ]; then
  echo "Không lấy được URL ngrok. Đã chạy 'ngrok http 4566' chưa?"; exit 1
fi

# API_ID hiện tại trong LocalStack
export MSYS_NO_PATHCONV=1
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
export AWS_PROFILE=localstack AWS_DEFAULT_REGION=ap-southeast-1
API_ID=$(aws --endpoint-url=http://localhost:4566 apigateway get-rest-apis \
  --query "items[?name=='telegram-webhook-api'].id | [0]" --output text 2>/dev/null || true)
if [ -z "$API_ID" ] || [ "$API_ID" = "None" ]; then
  echo "Không tìm thấy API Gateway. Đã chạy setup-localstack.sh chưa?"; exit 1
fi

WEBHOOK_URL="$NGROK_URL/restapis/$API_ID/dev/_user_request_/webhook"
echo "Đăng ký webhook: $WEBHOOK_URL"

call_telegram() {  # $1 = tên method, phần còn lại là tham số curl
  local method=$1; shift
  curl -sS --ssl-no-revoke --connect-timeout 10 -m 30 -X POST \
    "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/${method}" "$@"
}

OK=0
for i in 1 2 3; do
  echo "Lần thử $i/3..."
  set +e
  RESP=$(call_telegram setWebhook \
    -d "url=${WEBHOOK_URL}" -d "secret_token=${TELEGRAM_WEBHOOK_SECRET}" \
    -d "drop_pending_updates=true" 2>&1)
  CODE=$?
  set -e
  if [ "$CODE" -eq 0 ]; then OK=1; break; fi
  echo "  curl lỗi (exit=$CODE): $RESP"
  sleep 3
done

if [ "$OK" -ne 1 ]; then
  echo "Không kết nối được api.telegram.org (xem hướng dẫn kiểm tra mạng)."; exit 1
fi

echo "setWebhook: $RESP"
echo "--- getWebhookInfo ---"
call_telegram getWebhookInfo
echo