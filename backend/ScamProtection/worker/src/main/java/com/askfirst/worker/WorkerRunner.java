package com.askfirst.worker;

import com.askfirst.common.ai.AiException;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.job.AnswerJob;
import com.askfirst.common.job.FollowupJob;
import com.askfirst.common.job.InvalidJobException;
import com.askfirst.common.job.Job;
import com.askfirst.common.job.JobCodec;
import java.util.function.Consumer;

/**
 * Điều phối một message SQS: parse, chuyển cho processor đúng loại. Lỗi được ném lại để handler báo
 * batchItemFailure (SQS giao lại; quá maxReceiveCount thì vào DLQ, không bao giờ bị bỏ mất âm thầm).
 */
public final class WorkerRunner {

    /** Khớp maxReceiveCount của JobsQueue trong template.yaml. */
    public static final int MAX_RECEIVE_COUNT = 5;

    private final AnalyzeProcessor analyze;
    private final FollowupProcessor followup;
    private final Consumer<String> log;

    public WorkerRunner(AnalyzeProcessor analyze, FollowupProcessor followup, Consumer<String> log) {
        this.analyze = analyze;
        this.followup = followup;
        this.log = log;
    }

    public void handle(String body, int receiveCount, long remainingMs) {
        Job job;
        try {
            job = JobCodec.parse(body);
        } catch (InvalidJobException e) {
            log.accept("invalid_job code=" + e.code() + " receive=" + receiveCount);
            throw e;
        }
        try {
            switch (job) {
                case AnalyzeJob a -> analyze.process(a, remainingMs);
                case FollowupJob f -> followup.process(f);
                // Multi-turn đang hoãn khỏi MVP và webhook chưa tạo job này: ghi log để thấy nếu có.
                case AnswerJob a -> log.accept("answer_job_unsupported job=" + a.jobId());
            }
        } catch (RuntimeException e) {
            log.accept("job_failed type=" + job.getClass().getSimpleName() + " job=" + job.jobId()
                    + " error=" + safeCode(e) + " receive=" + receiveCount);
            if (receiveCount >= MAX_RECEIVE_COUNT && job instanceof AnalyzeJob a) {
                analyze.notifyGaveUp(a);
            }
            throw e;
        }
    }

    /** Chỉ lấy tên lớp và mã lỗi ngắn: message của exception bên thứ ba có thể chứa nội dung người dùng. */
    static String safeCode(Throwable e) {
        String name = e.getClass().getSimpleName();
        if (e instanceof AiException ai) {
            return name + ":" + ai.code();
        }
        if (e instanceof RetryableFailure r) {
            return name + ":" + r.code();
        }
        return name;
    }
}
