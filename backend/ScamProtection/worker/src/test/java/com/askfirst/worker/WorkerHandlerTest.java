package com.askfirst.worker;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.job.JobCodec;
import com.askfirst.worker.Fakes.Env;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerHandlerTest {

    private static final String JOB = "01JOB00000000000000000000A";

    @Test
    @DisplayName("Worker handles valid SQS job successfully and reports no failures")
    void testWorkerHandlesJobSuccessfully() {
        Env env = new Env();
        WorkerHandler handler = new WorkerHandler(env.runner());

        long seq = env.seed("100", 1);
        String body = JobCodec.toJson(AnalyzeJob.create(JOB, "100", "tg:100", seq, Fakes.T0));

        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId("msg-1");
        message.setBody(body);
        message.setAttributes(Map.of("ApproximateReceiveCount", "1"));

        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(message));

        SQSBatchResponse response = handler.handleRequest(event, null);

        assertThat(response.getBatchItemFailures()).isEmpty();
        assertThat(env.telegram.sentTo("100")).hasSize(1);
    }

    @Test
    @DisplayName("Worker records batch item failure when job processing fails")
    void testWorkerRecordsBatchItemFailureOnError() {
        Env env = new Env();
        WorkerHandler handler = new WorkerHandler(env.runner());

        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId("msg-err-1");
        message.setBody("invalid-json");
        message.setAttributes(Map.of("ApproximateReceiveCount", "1"));

        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(message));

        SQSBatchResponse response = handler.handleRequest(event, null);

        assertThat(response.getBatchItemFailures()).hasSize(1);
        assertThat(response.getBatchItemFailures().get(0).getItemIdentifier()).isEqualTo("msg-err-1");
    }

    @Test
    @DisplayName("Worker returns empty batch item failures on null or empty event")
    void testWorkerHandlesNullOrEmptyEvent() {
        Env env = new Env();
        WorkerHandler handler = new WorkerHandler(env.runner());

        assertThat(handler.handleRequest(null, null).getBatchItemFailures()).isEmpty();

        SQSEvent emptyEvent = new SQSEvent();
        emptyEvent.setRecords(null);
        assertThat(handler.handleRequest(emptyEvent, null).getBatchItemFailures()).isEmpty();
    }
}
