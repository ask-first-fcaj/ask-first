package com.askfirst.worker;

import com.askfirst.common.ai.LambdaInvokeException;
import com.askfirst.common.ai.LambdaInvoker;
import java.time.Duration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Gọi Lambda {@code ai} đồng bộ (RequestResponse) bằng ARN có alias, ví dụ {@code ...:function:askfirst-dev-ai:live}.
 * Read timeout 95 giây (lớn hơn timeout 90 giây của ai), KHÔNG retry trong SDK: retry đi qua SQS.
 * Exception chỉ mang mã lỗi, không chain cause của SDK.
 */
public final class AwsLambdaInvoker implements LambdaInvoker {

    public static final Duration READ_TIMEOUT = Duration.ofSeconds(95);

    private final LambdaClient client;
    private final String functionArn;

    public AwsLambdaInvoker(LambdaClient client, String functionArn) {
        if (functionArn == null || functionArn.isBlank()) {
            throw new IllegalStateException("Thiếu AI_FUNCTION_ARN");
        }
        this.client = client;
        this.functionArn = functionArn;
    }

    public static AwsLambdaInvoker fromEnv() {
        return new AwsLambdaInvoker(buildClient(), System.getenv("AI_FUNCTION_ARN"));
    }

    static LambdaClient buildClient() {
        return LambdaClient.builder()
                // socketTimeout mặc định của Apache client là 30 giây, ngắn hơn thời gian chạy của ai
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(READ_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(READ_TIMEOUT.plusSeconds(5))
                        .retryStrategy(AwsRetryStrategy.doNotRetry())
                        .build())
                .build();
    }

    @Override
    public String invoke(String jsonPayload) {
        try {
            InvokeResponse response = client.invoke(InvokeRequest.builder()
                    .functionName(functionArn)
                    .invocationType(InvocationType.REQUEST_RESPONSE)
                    .payload(SdkBytes.fromUtf8String(jsonPayload))
                    .build());
            if (response.functionError() != null) {
                throw new LambdaInvokeException("function_error");
            }
            return response.payload().asUtf8String();
        } catch (LambdaInvokeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LambdaInvokeException("invoke_failed");
        }
    }
}
