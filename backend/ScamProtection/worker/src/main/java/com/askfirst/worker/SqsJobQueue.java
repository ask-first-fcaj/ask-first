package com.askfirst.worker;

import com.askfirst.common.job.Job;
import com.askfirst.common.job.JobCodec;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public final class SqsJobQueue implements JobQueue {

    private final SqsClient sqs;
    private final String queueUrl;

    public SqsJobQueue(SqsClient sqs, String queueUrl) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalStateException("Thiếu WEBHOOK_QUEUE_URL");
        }
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public void enqueue(Job job, int delaySeconds) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(JobCodec.toJson(job))
                .delaySeconds(delaySeconds)
                .build());
    }
}
