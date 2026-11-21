#!/usr/bin/env bash
# Kịch bản so sánh code CŨ và code MỚI của worker / worker-dlq trên LocalStack (cùng quy ước với setup-localstack.sh).
#
# Usage: ./scripts/scenario.sh <check|prep|A|B|C|reset> [nhãn]
#   nhãn (vd: cu, moi): ghi toàn bộ kết quả vào scenario-results/<nhãn>-<lệnh>.txt để đối chiếu hai lần chạy.
#
# Quy trình:
#   1. Checkout code CŨ  -> ./scripts/setup-localstack.sh -> ./scripts/set-webhook.sh
#      ./scripts/scenario.sh check ; prep ; A cu ; B cu ; C cu ; reset
#   2. Checkout code MỚI -> làm lại y hệt, với nhãn "moi"
#   Webhook luôn là bản có bufferAndEnqueue (đúng contract) ở cả hai lần; chỉ worker và worker-dlq đổi.
#
#   A  job sai contract            (không cần Telegram)
#   B  lỗi 1: báo "bận" hai lần    (cần nhắn bot thật 1 tin)
#   C  lỗi 2: DLQ sửa nhầm "⏳"    (cần nhắn bot thật 2 tin liên tiếp)
#   D  gọi thẳng worker-dlq, đọc log từ response (khi log CloudWatch của LocalStack trống)
set -euo pipefail
cd "$(dirname "$0")/.."

export MSYS_NO_PATHCONV=1
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN   # tránh dính khóa AWS thật còn trong terminal
export AWS_PROFILE=localstack AWS_DEFAULT_REGION=ap-southeast-1
awsl() { aws --endpoint-url=http://localhost:4566 "$@"; }

get_env() { [ -f .env ] && grep -E "^$1=" .env | head -n1 | cut -d= -f2- | tr -d '\r' || true; }
TABLE=$(get_env DYNAMODB_TABLE_NAME); TABLE=${TABLE:-askfirst-dev}
QUEUE=webhookQueue        # hàng đợi job: webhook gửi vào, worker đọc
DLQ=webhookDLQ
VIS=20                    # giây. Khi ai tắt, mỗi lần xử lý chỉ mất vài giây nên 20 s là đủ; reset sẽ trả về 720
CMD="${1:-}"
LABEL="${2:-}"

if [ -n "$LABEL" ]; then
  mkdir -p scenario-results
  exec > >(tee "scenario-results/$LABEL-$CMD.txt") 2>&1
fi

hr() { printf '\n===== %s =====\n' "$*"; }
now_iso() { date -u +%Y-%m-%dT%H:%M:%SZ; }
qurl() { awsl sqs get-queue-url --queue-name "$1" --query QueueUrl --output text 2>/dev/null || true; }

need_queues() {
  QUEUE_URL=$(qurl "$QUEUE"); DLQ_URL=$(qurl "$DLQ")
  if [ -z "$QUEUE_URL" ] || [ "$QUEUE_URL" = "None" ] || [ -z "$DLQ_URL" ] || [ "$DLQ_URL" = "None" ]; then
    echo "Thiếu queue $QUEUE hoặc $DLQ. Chạy ./scripts/setup-localstack.sh (bản mới) trước."; exit 1
  fi
}

# Tự lấy chat id thật từ BUFFER#<chat>/META (webhook ghi ngay khi nhận tin): không phải nhập tay.
# Chat riêng thì chat id = user id Telegram, và user_id trong job là "tg:<id>".
detect_chat() {
  if [ -z "${CHAT:-}" ]; then
    local pk t=0
    while [ "$t" -lt 30 ]; do
      pk=$(awsl dynamodb scan --table-name "$TABLE" \
             --filter-expression "SK = :m AND begins_with(PK, :p)" \
             --expression-attribute-values '{":m":{"S":"META"},":p":{"S":"BUFFER#"}}' \
             --query 'Items[0].PK.S' --output text 2>/dev/null || true)
      if [ -n "$pk" ] && [ "$pk" != "None" ]; then CHAT=${pk#BUFFER#}; break; fi
      sleep 3; t=$((t + 3))
    done
    [ -n "${CHAT:-}" ] || { echo "Không thấy BUFFER# nào trong $TABLE: webhook chưa nhận/ghi tin (hoặc đang chạy bản webhook cũ). Có thể đặt CHAT=<id> rồi chạy lại."; exit 1; }
  fi
  USER_ID="${USER_ID:-tg:$CHAT}"
  echo "Chat id: $CHAT  user_id: $USER_ID"
}

# "chờ đang xử lý trễ" của một queue: tổng số message còn lại (đang chờ + đang xử lý + hoãn)
total() {
  local r; r=$(awsl sqs get-queue-attributes --queue-url "$1" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible ApproximateNumberOfMessagesDelayed \
    --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible,ApproximateNumberOfMessagesDelayed]' \
    --output text)
  set -- $r; echo $(( $1 + $2 + $3 ))
}

queue_counts() {
  echo "$QUEUE : còn $(total "$QUEUE_URL") message"
  echo "$DLQ : còn $(total "$DLQ_URL") message"
}

# Chờ tới khi cả hai queue trống (tối thiểu $1 giây, tối đa $2 giây), in tiến độ mỗi 10 giây
wait_drain() {
  local min=$1 max=$2 t=0
  while [ "$t" -lt "$max" ]; do
    sleep 10; t=$((t + 10))
    printf '  t=%3ss  %s=%s  %s=%s\n' "$t" "$QUEUE" "$(total "$QUEUE_URL")" "$DLQ" "$(total "$DLQ_URL")"
    if [ "$t" -ge "$min" ] && [ "$(total "$QUEUE_URL")" -eq 0 ] && [ "$(total "$DLQ_URL")" -eq 0 ]; then break; fi
  done
  sleep 5
}

logs() {  # $1 = tên Lambda, $2 = regex
  awsl logs tail "/aws/lambda/$1" --since 15m --format short 2>/dev/null | grep -E "$2" \
    || echo "(không có dòng khớp '$2' trong log $1)"
}

logs_wait() {  # $1 = tên Lambda, $2 = regex, $3 = số giây chờ tối đa (log LocalStack có thể trễ)
  local t=0 out max="${3:-45}"
  while [ "$t" -lt "$max" ]; do
    out=$(awslogs "$1" | grep -E "$2" || true)
    if [ -n "$out" ]; then echo "$out"; return; fi
    sleep 5; t=$((t + 5))
  done
  echo "(không có dòng khớp '$2' trong log $1 sau $max giây)"
}
awslogs() { awsl logs tail "/aws/lambda/$1" --since "${LOGSINCE:-15m}" --format short 2>/dev/null || true; }

dlq_diag() {  # in khi không thấy log worker-dlq: để phân biệt "handler không chạy" với "log LocalStack không có"
  hr "Chẩn đoán worker-dlq"
  echo "Log group khớp dlq:"
  awsl logs describe-log-groups --query 'logGroups[].logGroupName' --output text 2>/dev/null | tr '\t' '\n' | grep -i dlq || echo "  (không có log group nào: Lambda chưa từng được gọi)"
  echo "Trigger worker-dlq (state, kết quả lần xử lý gần nhất):"
  awsl lambda list-event-source-mappings --function-name worker-dlq \
    --query 'EventSourceMappings[].[State,LastProcessingResult]' --output text 2>/dev/null || true
  queue_counts
}

meta() {
  local r
  r=$(awsl dynamodb get-item --table-name "$TABLE" --consistent-read \
        --key "{\"PK\":{\"S\":\"BUFFER#$CHAT\"},\"SK\":{\"S\":\"META\"}}" \
        --query 'Item.[max_seq.N,done_seq.N,placeholder_id.N]' --output text 2>/dev/null || true)
  if [ -z "$r" ] || [ "$r" = "None" ]; then echo "META: (không còn)"
  else echo "META (max_seq, done_seq, placeholder_id): $r"; fi
}

check_rows() {  # PK = USER#<uid>, SK = CHECK#<check_id>
  local r
  r=$(awsl dynamodb scan --table-name "$TABLE" \
        --filter-expression "begins_with(SK, :p)" --expression-attribute-values '{":p":{"S":"CHECK#"}}' \
        --query 'Items[].[SK.S,outcome.S]' --output text 2>/dev/null || true)
  if [ -z "$r" ]; then echo "CHECK#: (không có bản ghi nào)"; else echo "CHECK# (SK, outcome):"; echo "$r"; fi
}

case "$CMD" in
  check)
    hr "Hàm Lambda"
    for fn in webhook-handler worker worker-dlq; do
      r=$(awsl lambda get-function-configuration --function-name "$fn" \
            --query '[Timeout,Handler]' --output text 2>/dev/null || true)
      [ -n "$r" ] && echo "$fn: timeout/handler = $r" || echo "$fn: THIẾU (chạy lại setup-localstack.sh)"
    done
    keys=$(awsl lambda get-function-configuration --function-name worker \
             --query 'Environment.Variables|keys(@)' --output text 2>/dev/null || true)
    for k in DYNAMODB_TABLE_NAME WEBHOOK_QUEUE_URL AI_FUNCTION_ARN TELEGRAM_BOT_TOKEN AWS_ENDPOINT_URL; do
      case " $keys " in *"$k"*) echo "worker env $k: có" ;; *) echo "worker env $k: THIẾU" ;; esac
    done
    r=$(awsl lambda get-function-configuration --function-name ai --query FunctionName --output text 2>/dev/null || true)
    [ -n "$r" ] && echo "ai: có (prep sẽ xóa để ép lỗi AI)" || echo "ai: chưa deploy (đúng ý: AI luôn lỗi, kịch bản B/C chạy được)"

    hr "Trigger SQS"
    for fn in worker worker-dlq; do
      echo "$fn:"; awsl lambda list-event-source-mappings --function-name "$fn" \
        --query 'EventSourceMappings[].{state:State,batch:BatchSize,responseTypes:FunctionResponseTypes}' --output text \
        || echo "  (chưa có trigger)"
    done
    echo "(worker phải có ReportBatchItemFailures, nếu không SQS không bao giờ giao lại job lỗi)"

    hr "Queue"
    need_queues
    awsl sqs get-queue-attributes --queue-url "$QUEUE_URL" --attribute-names RedrivePolicy VisibilityTimeout \
      --query Attributes --output text
    echo "(RedrivePolicy phải có maxReceiveCount 5 và trỏ tới $DLQ)"
    queue_counts
    ;;

  prep)
    hr "Chuẩn bị: rút ngắn VisibilityTimeout, xóa queue cũ, tắt Lambda ai"
    need_queues
    awsl sqs set-queue-attributes --queue-url "$QUEUE_URL" --attributes VisibilityTimeout=$VIS
    awsl sqs purge-queue --queue-url "$QUEUE_URL" || true
    awsl sqs purge-queue --queue-url "$DLQ_URL" || true
    if awsl lambda get-function-configuration --function-name ai >/dev/null 2>&1; then
      awsl lambda delete-function --function-name ai && echo "Đã xóa Lambda ai (deploy lại ai sau khi test xong)"
    else
      echo "Lambda ai chưa có: AI sẵn ở trạng thái lỗi"
    fi
    echo "Xong. Đã đặt VisibilityTimeout=$VIS giây."
    ;;

  A)
    hr "A. Job sai contract (giống webhook cũ gửi nguyên InboundMessage)"
    need_queues
    awsl sqs send-message --queue-url "$QUEUE_URL" \
      --message-body "{\"chat_id\":\"${CHAT:-1}\",\"text\":\"webhook-cu-gui-nguyen-inbound\"}" >/dev/null
    echo "Đã gửi. Chờ queue trống (cũ: ~$((5 * VIS)) giây vì retry 5 lần; mới: vài giây) ..."
    wait_drain 10 $((5 * VIS + 40))
    hr "Log worker"; logs worker 'invalid_job'
    hr "Queue"; queue_counts
    ;;

  B)
    hr "B. Lỗi 1: báo 'bận' hai lần"
    need_queues
    cat <<EOT
Việc cần làm (thủ công):
  - Mở bot test trên Telegram (đã bấm "Tôi đồng ý"), nhắn 1 tin. Bot phải trả "⏳".
  - Quay lại đây và bấm Enter. Script chờ worker thử 5 lần (~$((5 * VIS)) giây) rồi DLQ chạy.
EOT
    read -r -p "Bấm Enter SAU KHI đã nhắn tin ..."
    detect_chat
    wait_drain 30 $((5 * VIS + 90))
    hr "Log worker"; logs worker 'job_failed|give_up|receive='
    hr "Log worker-dlq"; LOGSINCE=2m logs_wait worker-dlq 'DLQ job' 45   # cửa sổ ngắn để không dính log của lần chạy trước
    [ -z "$(LOGSINCE=2m awslogs worker-dlq)" ] && dlq_diag
    hr "Dữ liệu"; meta; check_rows; queue_counts
    echo
    echo ">>> Đếm trên Telegram: có bao nhiêu tin 'bận'? (cũ: 2 tin, mới: 1 tin sửa từ ⏳)"
    ;;

  C)
    hr "C. Lỗi 2: job cũ trong DLQ sửa nhầm '⏳' của job khác"
    need_queues
    cat <<EOT
Việc cần làm (thủ công):
  - (đã bấm "Tôi đồng ý") Nhắn 2 tin LIÊN TIẾP cho bot test trong vòng 2 giây (cụm có seq=1 và seq=2, chỉ một '⏳').
  - Bấm Enter NGAY (trong 30 giây, khi worker còn đang retry seq=2).
EOT
    read -r -p "Bấm Enter SAU KHI đã nhắn 2 tin ..."
    detect_chat
    hr "META trước khi bơm job cũ"; meta
    awsl sqs send-message --queue-url "$DLQ_URL" --message-body \
      "{\"v\":1,\"type\":\"analyze\",\"job_id\":\"01SCENARIOC00000000000000A\",\"chat_id\":\"$CHAT\",\"user_id\":\"$USER_ID\",\"seq\":1,\"enqueued_at\":\"$(now_iso)\"}" >/dev/null
    echo "Đã bơm job seq=1 vào $DLQ. Chờ worker-dlq chạy ..."
    hr "Log worker-dlq (kết quả xử lý job seq=1)"
    LOGSINCE=1m logs_wait worker-dlq 'DLQ job xử lý xong|Lỗi tạm thời|Bỏ job' 45
    [ -z "$(LOGSINCE=1m awslogs worker-dlq)" ] && dlq_diag
    hr "META sau khi DLQ xử lý"; meta
    echo
    echo ">>> Nhìn Telegram: '⏳' của cụm có bị đổi thành 'bận' ngay lúc này không? (cũ: có, mới: không)"
    ;;

  D)
    # Gọi trực tiếp worker-dlq với một SQS event giả và đọc log từ chính response (--log-type Tail),
    # không phụ thuộc CloudWatch Logs của LocalStack. Dùng SEQ=<n> để chọn seq của job giả (mặc định 1).
    hr "D. Gọi trực tiếp worker-dlq (đọc log từ response)"
    need_queues
    echo "Cần có BUFFER# đang tồn tại: nhắn bot 1-2 tin rồi chạy NGAY (trong ~80 giây, khi worker còn đang retry)."
    detect_chat
    meta
    SEQ="${SEQ:-1}"
    JOB="01DIRECT${SEQ}00000000000000000"; JOB=${JOB:0:26}
    BODY="{\\\"v\\\":1,\\\"type\\\":\\\"analyze\\\",\\\"job_id\\\":\\\"$JOB\\\",\\\"chat_id\\\":\\\"$CHAT\\\",\\\"user_id\\\":\\\"$USER_ID\\\",\\\"seq\\\":$SEQ,\\\"enqueued_at\\\":\\\"$(now_iso)\\\"}"
    printf '{"Records":[{"messageId":"direct-1","body":"%s","attributes":{"ApproximateReceiveCount":"1"}}]}' "$BODY" > .scenario-payload.json
    out=$(awsl lambda invoke --function-name worker-dlq --cli-binary-format raw-in-base64-out \
            --payload file://.scenario-payload.json --log-type Tail \
            --query '[StatusCode,FunctionError]' --output text .scenario-response.json 2>&1 || true)
    echo "StatusCode/FunctionError: $out"
    awsl lambda invoke --function-name worker-dlq --cli-binary-format raw-in-base64-out \
      --payload file://.scenario-payload.json --log-type Tail --query LogResult --output text \
      .scenario-response.json 2>/dev/null | base64 -d 2>/dev/null | grep -E "DLQ job|Lỗi|Bỏ job|Exception|ERROR" || echo "(không có log trong response)"
    rm -f .scenario-payload.json .scenario-response.json
    hr "META sau"; meta
    echo ">>> Kết quả mong đợi: seq nhỏ hơn max_seq -> 'superseded'; seq = max_seq -> 'edited' (chỉ khi chưa có CHECK# cho job này)."
    ;;

  reset)
    hr "Trả cấu hình"
    need_queues
    awsl sqs set-queue-attributes --queue-url "$QUEUE_URL" --attributes VisibilityTimeout=720
    echo "Đã trả VisibilityTimeout=720. Deploy lại Lambda ai nếu bạn đã xóa nó ở bước prep."
    ;;

  *)
    echo "Usage: $0 {check|prep|A|B|C|D|reset} [nhãn]"; exit 1 ;;
esac