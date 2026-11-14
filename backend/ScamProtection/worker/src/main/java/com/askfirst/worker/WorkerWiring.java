package com.askfirst.worker;

import com.askfirst.common.TelegramClient;
import com.askfirst.common.ai.LambdaAiClient;
import com.askfirst.common.store.DynamoAlertLockStore;
import com.askfirst.common.store.DynamoAlertStore;
import com.askfirst.common.store.DynamoBufferStore;
import com.askfirst.common.store.DynamoCheckStore;
import com.askfirst.common.store.DynamoFlagsStore;
import com.askfirst.common.store.GuardianRepo;
import com.askfirst.common.store.UserRepo;
import com.askfirst.common.Ulid;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Dựng toàn bộ worker từ biến môi trường (chạy một lần lúc khởi tạo Lambda).
 * Tên biến khớp webhook: DYNAMODB_TABLE_NAME, WEBHOOK_QUEUE_URL. Thêm AI_FUNCTION_ARN và TELEGRAM_BOT_TOKEN.
 * Token đọc thẳng từ biến môi trường cho giai đoạn dev; chuyển sang SSM khi deploy chính thức.
 */
final class WorkerWiring {

    private WorkerWiring() {
    }

    static WorkerRunner fromEnv() {
        String table = System.getenv("DYNAMODB_TABLE_NAME");
        Consumer<String> log = System.out::println;   // stdout của Lambda đi thẳng vào CloudWatch
        Clock clock = Clock.systemUTC();

        DynamoDbClient dynamo = DynamoDbClient.create();
        DynamoDbEnhancedClient enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build();

        TelegramGateway telegram = new TelegramClientGateway(new TelegramClient(
                System.getenv("TELEGRAM_BOT_TOKEN"), "https://api.telegram.org", Duration.ofSeconds(5), 3));
        GuardianDirectory directory = new RepoGuardianDirectory(
                new UserRepo(enhanced, table), new GuardianRepo(enhanced, table));
        DynamoAlertStore alerts = new DynamoAlertStore(dynamo, table);

        AnalyzeProcessor analyze = new AnalyzeProcessor(
                new DynamoBufferStore(dynamo, table),
                new FlagsCache(new DynamoFlagsStore(dynamo, table), clock),
                new DynamoCheckStore(dynamo, table),
                alerts,
                new DynamoAlertLockStore(dynamo, table),
                directory,
                new LambdaAiClient(AwsLambdaInvoker.fromEnv(), log),
                telegram,
                new SqsJobQueue(SqsClient.create(), System.getenv("WEBHOOK_QUEUE_URL")),
                clock,
                Ulid::next,
                log);
        FollowupProcessor followup = new FollowupProcessor(alerts, directory, telegram, clock, log);
        return new WorkerRunner(analyze, followup, log);
    }
}
