package com.askfirst.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.askfirst.common.ai.AiContract.AlertResult;
import com.askfirst.common.ai.AiContract.AnalyzeResult;
import com.askfirst.common.ai.AiException;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.job.FollowupJob;
import com.askfirst.common.store.AlertRecord;
import com.askfirst.common.store.AlertStore;
import com.askfirst.common.store.CheckRecord;
import com.askfirst.common.store.CheckStore;
import com.askfirst.common.store.SessionRecord;
import com.askfirst.common.store.SessionStore;
import com.askfirst.worker.AnalyzeProcessor.Result;
import com.askfirst.worker.Fakes.Env;
import com.askfirst.worker.TelegramGateway.Btn;
import com.askfirst.worker.TelegramGateway.SendException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** Test hồi quy cho các lỗi logic của AnalyzeProcessor phát hiện khi review PR BE-30. */
class AnalyzeProcessorFixesTest {

    private static final String CHAT = "100";
    private static final String UID = "tg:100";
    private static final String JOB = "01JOB00000000000000000000B";

    private final Env env = new Env();

    private long seedWithPlaceholder() {
        long seq = env.seed(CHAT, 1);
        env.buffer.setPlaceholder(CHAT, 77);
        return seq;
    }

    private AnalyzeJob job(long seq) {
        return Fakes.analyzeJob(JOB, CHAT, seq);
    }

    private void dangerous() {
        env.ai.analyzeFn = r -> new AnalyzeResult(Fakes.verdict("DANGEROUS", true), false);
    }

    private AnalyzeProcessor processor(AlertStore alerts, TelegramGateway telegram, JobQueue queue,
                                       SessionStore sessions) {
        return new AnalyzeProcessor(env.buffer, new FlagsCache(env.flagsStore, env.clock), env.checks, alerts,
                env.locks, env.directory, env.ai, telegram, queue, env.clock, () -> "01FOLLOWUP0000000000000001",
                env.logs::add, sessions);
    }

    // ---------- #6 notifyGaveUp ----------

    @Test
    void notifyGaveUp_doesNotOverwriteResultThatWasAlreadyDelivered() {
        long seq = seedWithPlaceholder();
        env.analyze.process(job(seq), 120_000);
        String verdictText = env.telegram.edited.get(0).text();

        env.analyze.notifyGaveUp(job(seq));

        assertEquals(1, env.telegram.edited.size());                       // không có tin "bận" đè lên kết luận
        assertEquals(verdictText, env.telegram.edited.get(0).text());
        assertTrue(env.logs.stream().anyMatch(l -> l.contains("give_up_skip=already_done")));
    }

    @Test
    void notifyGaveUp_closesTheBufferCluster() {
        long seq = seedWithPlaceholder();

        env.analyze.notifyGaveUp(job(seq));

        assertEquals(WorkerMessages.busy("Bác"), env.telegram.edited.get(0).text());
        assertFalse(env.buffer.exists(CHAT));    // tin gửi lại sau đó không bị gộp với job đã bỏ cuộc
    }

    @Test
    void notifyGaveUp_marksJobHandled_soDlqHandlerDoesNotNotifyAgain() {
        long seq = seedWithPlaceholder();

        env.analyze.notifyGaveUp(job(seq));

        assertTrue(env.checks.exists(UID, JOB));
        assertEquals("gave_up", env.checks.get(UID, JOB).outcome());
    }

    @Test
    void notifyGaveUp_whenNewerMessageArrived_leavesSharedPlaceholderAlone() {
        long newest = env.seed(CHAT, 2);                 // job seq=1 đã cũ: job seq=2 sẽ trả lời cả cụm
        env.buffer.setPlaceholder(CHAT, 77);

        env.analyze.notifyGaveUp(job(1));

        assertEquals(2, newest);
        assertTrue(env.telegram.edited.isEmpty());       // không biến "⏳" của cụm đang chạy thành "bận"
        assertTrue(env.telegram.sent.isEmpty());
        assertEquals(77, env.buffer.state(CHAT).get().placeholderId());
        assertEquals("superseded", env.checks.get(UID, JOB).outcome());
        assertTrue(env.logs.stream().anyMatch(l -> l.contains("give_up_skip=superseded")));
    }

    @Test
    void notifyGaveUp_markFailure_doesNotPreventClosingTheBuffer() {
        CheckStore failingPut = new CheckStore() {
            @Override
            public boolean exists(String userId, String checkId) {
                return env.checks.exists(userId, checkId);
            }

            @Override
            public void put(CheckRecord record) {
                throw new IllegalStateException("dynamo down");
            }

            @Override
            public void updateFeedback(String userId, String checkId, String feedback) {
                env.checks.updateFeedback(userId, checkId, feedback);
            }
        };
        AnalyzeProcessor p = new AnalyzeProcessor(env.buffer, new FlagsCache(env.flagsStore, env.clock), failingPut,
                env.alerts, env.locks, env.directory, env.ai, env.telegram, env.queue, env.clock,
                () -> "01FOLLOWUP0000000000000001", env.logs::add);
        long seq = seedWithPlaceholder();

        p.notifyGaveUp(job(seq));

        assertEquals(WorkerMessages.busy("Bác"), env.telegram.edited.get(0).text());
        assertFalse(env.buffer.exists(CHAT));
        assertTrue(env.logs.stream().anyMatch(l -> l.contains("give_up_mark_failed")));
    }

    // ---------- #7 session phải được ghi trước CHECK# ----------

    @Test
    void sessionWriteFailure_isRetriedSafely_andSessionIsNotLost() {
        MemSessions sessions = new MemSessions();
        sessions.failPut = true;
        AnalyzeProcessor p = processor(env.alerts, env.telegram, env.queue, sessions);
        long seq = seedWithPlaceholder();

        assertThrows(IllegalStateException.class, () -> p.process(job(seq), 120_000));
        assertFalse(env.checks.exists(UID, JOB));        // chưa ghi CHECK# nên retry không bị coi là "đã xong"
        assertTrue(env.telegram.edited.isEmpty());       // chưa trả lời

        sessions.failPut = false;
        assertEquals(Result.PROCESSED, p.process(job(seq), 120_000));
        assertTrue(sessions.get(CHAT).isPresent());
        assertEquals(JOB, sessions.get(CHAT).get().checkId());
        assertEquals(UID, sessions.get(CHAT).get().userId());
    }

    // ---------- #8 render lỗi sau khi đã báo người thân ----------

    @Test
    void renderFatalAfterGuardiansAlerted_stillRecordsAlertedAndSchedulesFollowup() {
        long seq = seedWithPlaceholder();
        dangerous();
        env.ai.renderFn = r -> {
            throw AiException.fatal("contract");
        };

        assertEquals(Result.DEGRADED, env.analyze.process(job(seq), 120_000));

        assertTrue(env.checks.get(UID, JOB).alerted());
        assertEquals(1, env.queue.jobs.size());
        assertEquals(2, ((FollowupJob) env.queue.jobs.get(0).job()).alertIds().size());
    }

    @Test
    void analyzeFatal_withoutAlerts_schedulesNoFollowup() {
        long seq = seedWithPlaceholder();
        env.ai.analyzeFn = r -> {
            throw AiException.fatal("bad_request");
        };

        assertEquals(Result.DEGRADED, env.analyze.process(job(seq), 120_000));

        assertFalse(env.checks.get(UID, JOB).alerted());
        assertTrue(env.queue.jobs.isEmpty());
    }

    // ---------- #12 markSent lỗi không được làm gửi lặp cảnh báo ----------

    @Test
    void markSentFailure_doesNotFailJob_norResendAlerts() {
        AlertStore flaky = new DelegatingAlerts(env.alerts) {
            @Override
            public void markSent(String alertId, int messageId) {
                throw new IllegalStateException("dynamo");
            }
        };
        AnalyzeProcessor p = processor(flaky, env.telegram, env.queue, null);
        long seq = seedWithPlaceholder();
        dangerous();

        assertEquals(Result.PROCESSED, p.process(job(seq), 120_000));

        assertEquals(1, env.telegram.sentTo("201").size());
        assertEquals(1, env.telegram.sentTo("202").size());
        List<AlertResult> results = env.ai.renderRequests.get(0).ctx().alerts();
        assertTrue(results.stream().allMatch(a -> "sent".equals(a.status())));   // tin đã tới nên vẫn là "sent"
        assertEquals(2, ((FollowupJob) env.queue.jobs.get(0).job()).alertIds().size());
    }

    // ---------- #13 retry ra verdict khác ----------

    @Test
    void retryWithBenignVerdict_stillAccountsForAlertsDeliveredEarlier() {
        long seq = seedWithPlaceholder();
        dangerous();
        env.telegram.sendFailure.put("202", Fakes.transientError());
        assertThrows(RetryableFailure.class, () -> env.analyze.process(job(seq), 120_000));

        env.telegram.sendFailure.clear();
        env.ai.analyzeFn = r -> new AnalyzeResult(Fakes.verdict("LOW_RISK", false), false);   // LLM đổi ý
        assertEquals(Result.PROCESSED, env.analyze.process(job(seq), 120_000));

        List<AlertResult> results = env.ai.renderRequests.get(0).ctx().alerts();
        assertEquals(1, results.size());                  // người thân 201 đã nhận từ lần chạy trước
        assertEquals("sent", results.get(0).status());
        assertTrue(env.checks.get(UID, JOB).alerted());
        assertEquals(1, ((FollowupJob) env.queue.jobs.get(0).job()).alertIds().size());
        assertEquals(1, env.telegram.sentTo("201").size());   // và không bị gửi lại
    }

    // ---------- followup SQS lỗi không làm job lỗi ----------

    @Test
    void followupEnqueueFailure_doesNotFailJob_orResendResult() {
        JobQueue failing = (j, delay) -> {
            throw new IllegalStateException("sqs");
        };
        AnalyzeProcessor p = processor(env.alerts, env.telegram, failing, null);
        long seq = seedWithPlaceholder();
        dangerous();

        assertEquals(Result.PROCESSED, p.process(job(seq), 120_000));

        assertTrue(env.checks.exists(UID, JOB));
        assertEquals(1, env.telegram.edited.size());
        assertTrue(env.logs.stream().anyMatch(l -> l.contains("followup_enqueue_failed")));
    }

    // ---------- #15 Telegram từ chối HTML ----------

    @Test
    void elderMessageRejectedAs400_isResentAsPlainText() {
        TelegramGateway rejectsHtml = new TelegramGateway() {
            @Override
            public int send(String chatId, String htmlText, List<List<Btn>> buttons, boolean silent) {
                if (htmlText.contains("<")) {
                    throw new SendException(400, true, false);   // "can't parse entities"
                }
                return env.telegram.send(chatId, htmlText, buttons, silent);
            }

            @Override
            public void edit(String chatId, int messageId, String htmlText, List<List<Btn>> buttons) {
                env.telegram.edit(chatId, messageId, htmlText, buttons);
            }
        };
        AnalyzeProcessor p = processor(env.alerts, rejectsHtml, env.queue, null);
        long seq = env.seed(CHAT, 1);   // không có placeholder: kết quả đi bằng send()

        assertEquals(Result.PROCESSED, p.process(job(seq), 120_000));

        assertEquals(1, env.telegram.sentTo(CHAT).size());
        assertEquals("Ket luan", env.telegram.sentTo(CHAT).get(0).text());
        assertTrue(env.logs.contains("elder_send_plain_fallback"));
    }

    @Test
    void stripTags_removesTagsButKeepsEntities() {
        assertEquals("a &amp; b &lt;c&gt;", AnalyzeProcessor.stripTags("<b>a &amp; b</b> &lt;c&gt;"));
        assertEquals("", AnalyzeProcessor.stripTags(null));
    }

    // ---------- bản giả ----------

    static final class MemSessions implements SessionStore {
        final Map<String, SessionRecord> store = new ConcurrentHashMap<>();
        volatile boolean failPut;

        @Override
        public Optional<SessionRecord> get(String chatId) {
            return Optional.ofNullable(store.get(chatId));
        }

        @Override
        public void put(SessionRecord record) {
            if (failPut) {
                throw new IllegalStateException("dynamo");
            }
            store.put(record.chatId(), record);
        }

        @Override
        public void delete(String chatId) {
            store.remove(chatId);
        }
    }

    static class DelegatingAlerts implements AlertStore {
        private final AlertStore delegate;

        DelegatingAlerts(AlertStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public AlertRecord reserve(AlertRecord pending) {
            return delegate.reserve(pending);
        }

        @Override
        public Optional<AlertRecord> get(String alertId) {
            return delegate.get(alertId);
        }

        @Override
        public void markSent(String alertId, int messageId) {
            delegate.markSent(alertId, messageId);
        }

        @Override
        public void markFailed(String alertId) {
            delegate.markFailed(alertId);
        }

        @Override
        public boolean markAcked(String alertId, Instant at) {
            return delegate.markAcked(alertId, at);
        }

        @Override
        public boolean markReminded(String alertId, Instant at) {
            return delegate.markReminded(alertId, at);
        }
    }
}
