package com.askfirst.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Lambda worker nối với SQS jobs (batch size 1, bật ReportBatchItemFailures trong template.yaml).
 * Message lỗi được báo qua batchItemFailures để SQS giao lại; không còn ném RuntimeException cả lô.
 */
public class WorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final WorkerRunner runner;

    public WorkerHandler() {
        this(WorkerWiring.fromEnv());
    }

    WorkerHandler(WorkerRunner runner) {
        this.runner = runner;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                runner.handle(message.getBody(), receiveCount(message), context.getRemainingTimeInMillis());
            } catch (Exception e) {
                // Lý do đã được WorkerRunner log (chỉ mã lỗi). Không log thêm e ở đây vì message có thể chứa nội dung.
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        return new SQSBatchResponse(failures);
    }

    private static int receiveCount(SQSEvent.SQSMessage message) {
        try {
            return Integer.parseInt(message.getAttributes().get("ApproximateReceiveCount"));
        } catch (RuntimeException e) {
            return 1;
        }
    }
}
