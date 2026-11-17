package com.askfirst.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.askfirst.common.ai.AiException;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.job.AnswerJob;
import com.askfirst.common.job.FollowupJob;
import com.askfirst.common.job.InvalidJobException;
import com.askfirst.common.job.JobCodec;
import com.askfirst.common.store.AlertRecord;
import com.askfirst.worker.Fakes.Env;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerRunnerTest {

    private static final String JOB = "01JOB00000000000000000000A";
    private final Env env = new Env();

    private String analyzeBody(long seq) {
        return JobCodec.toJson(AnalyzeJob.create(JOB, "100", "tg:100", seq, Fakes.T0));
    }

    @Test
    void invalidBody_isRejected_andNothingRuns() {
        WorkerRunner runner = env.runner();

        assertThrows(InvalidJobException.class, () -> runner.handle("{\"chatId\":100}", 1, 120_000));

        assertTrue(env.ai.analyzeRequests.isEmpty());
        assertTrue(env.logs.get(0).startsWith("invalid_job code=invalid_field"));
    }

    @Test
    void analyzeJob_isRoutedToProcessor() {
        long seq = env.seed("100", 1);

        env.runner().handle(analyzeBody(seq), 1, 120_000);

        assertEquals(1, env.ai.analyzeRequests.size());
        assertEquals(1, env.telegram.sentTo("100").size());
    }

    @Test
    void followupJob_isRoutedToProcessor() {
        String id = AnalyzeProcessor.alertId(JOB, "tg:201");
        env.alerts.reserve(new AlertRecord(id, "tg:100", "100", JOB, "tg:201", "201", "Lan", 2,
                AlertRecord.SENT, 5, null, null, 0));
        String body = JobCodec.toJson(FollowupJob.create("01FU00000000000000000000A", "100", "tg:100", JOB,
                List.of(id), Fakes.T0));

        env.runner().handle(body, 1, 120_000);

        assertEquals(1, env.telegram.sentTo("201").size());
    }

    @Test
    void answerJob_isLoggedAsUnsupported_notFailed() {
        String body = JobCodec.toJson(new AnswerJob(1, JOB, "100", "tg:100", JOB, "yes", "7", Fakes.T0));

        env.runner().handle(body, 1, 120_000);

        assertTrue(env.logs.stream().anyMatch(l -> l.startsWith("answer_job_unsupported")));
    }

    @Test
    void failure_isRethrown_withoutGiveUpNoticeBeforeLastAttempt() {
        long seq = env.seed("100", 1);
        env.buffer.setPlaceholder("100", 77);
        env.ai.analyzeFn = r -> {
            throw AiException.retryable("invoke");
        };

        assertThrows(AiException.class, () -> env.runner().handle(analyzeBody(seq), 4, 120_000));

        assertTrue(env.telegram.edited.isEmpty());
        assertTrue(env.logs.stream().anyMatch(l -> l.contains("error=AiException:invoke") && l.contains("receive=4")));
    }

    @Test
    void failureOnLastAttempt_notifiesUser_thenRethrows() {
        long seq = env.seed("100", 1);
        env.buffer.setPlaceholder("100", 77);
        env.ai.analyzeFn = r -> {
            throw AiException.retryable("invoke");
        };

        assertThrows(AiException.class,
                () -> env.runner().handle(analyzeBody(seq), WorkerRunner.MAX_RECEIVE_COUNT, 120_000));

        assertEquals(1, env.telegram.edited.size());
        assertEquals(WorkerMessages.busy("Bác"), env.telegram.edited.get(0).text());
    }

    @Test
    void failureLogs_neverContainExceptionMessageText() {
        long seq = env.seed("100", 1);
        env.ai.analyzeFn = r -> {
            throw new IllegalStateException("NOI-DUNG-NHAY-CAM-123456");
        };

        assertThrows(IllegalStateException.class, () -> env.runner().handle(analyzeBody(seq), 1, 120_000));

        assertFalse(String.join("\n", env.logs).contains("NOI-DUNG-NHAY-CAM-123456"));
        assertTrue(env.logs.stream().anyMatch(l -> l.contains("error=IllegalStateException")));
    }
}
