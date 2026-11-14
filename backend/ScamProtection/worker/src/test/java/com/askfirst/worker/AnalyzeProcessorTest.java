package com.askfirst.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.askfirst.common.ai.AiContract.AnalyzeResult;
import com.askfirst.common.ai.AiContract.Flags;
import com.askfirst.common.ai.AiException;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.job.FollowupJob;
import com.askfirst.common.store.CheckRecord;
import com.askfirst.worker.AnalyzeProcessor.Result;
import com.askfirst.worker.Fakes.Env;
import com.askfirst.worker.TelegramGateway.SendException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyzeProcessorTest {

    private static final String CHAT = "100";
    private static final String UID = "tg:100";
    private static final String JOB = "01JOB00000000000000000000A";

    private final Env env = new Env();

    private long seedWithPlaceholder(int messages) {
        long seq = env.seed(CHAT, messages);
        env.buffer.setPlaceholder(CHAT, 77);
        return seq;
    }

    private void dangerous() {
        env.ai.analyzeFn = r -> new AnalyzeResult(Fakes.verdict("DANGEROUS", true), false);
    }

    @Test
    void lowRisk_editsPlaceholder_recordsCheck_clearsBuffer_noFollowup() {
        long seq = seedWithPlaceholder(1);

        Result result = env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals(Result.PROCESSED, result);
        assertEquals(1, env.telegram.edited.size());
        assertEquals(77, env.telegram.edited.get(0).messageId());
        assertEquals("<b>Ket luan</b>", env.telegram.edited.get(0).text());
        assertTrue(env.telegram.sent.isEmpty());   // không gửi thêm tin mới
        assertEquals("fb:wrong:" + JOB, env.telegram.edited.get(0).buttons().get(0).get(0).callbackData());
        CheckRecord check = env.checks.get(UID, JOB);
        assertNotNull(check);
        assertEquals("LOW_RISK", check.level());
        assertFalse(check.alerted());
        assertFalse(env.buffer.exists(CHAT));
        assertTrue(env.queue.jobs.isEmpty());
    }

    @Test
    void staleJob_isSkipped_withoutCallingAi() {
        env.seed(CHAT, 2);

        Result result = env.analyze.process(Fakes.analyzeJob(JOB, CHAT, 1), 120_000);

        assertEquals(Result.SKIPPED, result);
        assertTrue(env.ai.analyzeRequests.isEmpty());
        assertTrue(env.buffer.exists(CHAT));
    }

    @Test
    void missingBuffer_isSkipped() {
        assertEquals(Result.SKIPPED, env.analyze.process(Fakes.analyzeJob(JOB, CHAT, 1), 120_000));
        assertTrue(env.ai.analyzeRequests.isEmpty());
    }

    @Test
    void latestJob_sendsWholeClusterToAi() {
        long seq = seedWithPlaceholder(3);

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals(1, env.ai.analyzeRequests.size());
        assertEquals(3, env.ai.analyzeRequests.get(0).items().size());
        assertEquals(JOB, env.ai.analyzeRequests.get(0).ctx().checkId());
    }

    @Test
    void messagesArrivingDuringProcessing_stayForNextJob() {
        long seq = seedWithPlaceholder(2);
        env.ai.analyzeFn = r -> {
            env.buffer.append(CHAT, Fakes.message(CHAT, 9, "tin moi"), 0);   // đến giữa lúc đang xử lý
            return new AnalyzeResult(Fakes.verdict("LOW_RISK", false), false);
        };

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertTrue(env.buffer.exists(CHAT));
        assertEquals(2L, env.buffer.state(CHAT).get().doneSeq());
        assertEquals(1, env.buffer.itemsAfter(CHAT, 2, 3).size());
        assertNull(env.buffer.state(CHAT).get().placeholderId());
    }

    @Test
    void completedCheck_isNotRepeated_onRedelivery() {
        long seq = seedWithPlaceholder(1);
        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);
        // giả lập: tin mới tạo lại buffer cùng lúc job cũ được giao lại
        env.seed(CHAT, 1);
        env.checks.put(new CheckRecord(JOB + "X", UID, "verdict", "LOW_RISK", 0, "OTHER", "OTHER", "fast",
                List.of(), List.of(), List.of(), false, "t", 1));

        Result again = env.analyze.process(Fakes.analyzeJob(JOB, CHAT, 1), 120_000);

        assertEquals(Result.SKIPPED, again);
        assertEquals(1, env.ai.analyzeRequests.size());
        assertEquals(1, env.telegram.edited.size());
    }

    @Test
    void withoutPlaceholder_resultIsSentAsNewMessage() {
        long seq = env.seed(CHAT, 1);

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertTrue(env.telegram.edited.isEmpty());
        assertEquals(1, env.telegram.sentTo(CHAT).size());
    }

    // ---------- báo người thân ----------

    @Test
    void dangerous_alertsAllGuardians_andSchedulesFollowup() {
        long seq = seedWithPlaceholder(1);
        dangerous();

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals(1, env.telegram.sentTo("201").size());
        assertEquals(1, env.telegram.sentTo("202").size());
        String ackData = env.telegram.sentTo("201").get(0).buttons().get(0).get(0).callbackData();
        assertTrue(ackData.startsWith("ack:" + JOB + "-"));
        assertTrue(ackData.getBytes().length <= 64);
        assertEquals(2, env.ai.renderRequests.get(0).ctx().alerts().size());
        assertEquals("sent", env.ai.renderRequests.get(0).ctx().alerts().get(0).status());
        assertTrue(env.ai.renderRequests.get(0).ctx().hasGuardian());

        assertEquals(1, env.queue.jobs.size());
        assertEquals(900, env.queue.jobs.get(0).delaySeconds());
        FollowupJob followup = (FollowupJob) env.queue.jobs.get(0).job();
        assertEquals(2, followup.alertIds().size());
        assertEquals(CHAT, followup.chatId());
        assertEquals(JOB, followup.checkId());
        assertTrue(env.checks.get(UID, JOB).alerted());
    }

    @Test
    void guardianBlockedBot_isMarkedFailed_othersStillSent() {
        long seq = seedWithPlaceholder(1);
        dangerous();
        env.telegram.sendFailure.put("201", Fakes.permanent());

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        List<com.askfirst.common.ai.AiContract.AlertResult> alerts = env.ai.renderRequests.get(0).ctx().alerts();
        assertEquals("failed", alerts.get(0).status());
        assertEquals("sent", alerts.get(1).status());
        assertEquals(1, ((FollowupJob) env.queue.jobs.get(0).job()).alertIds().size());
    }

    @Test
    void transientAlertFailure_retriesWithoutDuplicatingDeliveredAlerts() {
        long seq = seedWithPlaceholder(1);
        dangerous();
        env.telegram.sendFailure.put("202", Fakes.transientError());

        assertThrows(RetryableFailure.class, () -> env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000));
        assertTrue(env.buffer.exists(CHAT));                 // chưa dọn: retry vẫn thấy tin
        assertTrue(env.telegram.edited.isEmpty());           // chưa trả lời khi alert chưa xong
        assertEquals(1, env.telegram.sentTo("201").size());

        env.telegram.sendFailure.clear();
        Result result = env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals(Result.PROCESSED, result);
        assertEquals(1, env.telegram.sentTo("201").size());  // không gửi lại cho người đã nhận
        assertEquals(1, env.telegram.sentTo("202").size());
        assertEquals(2, ((FollowupJob) env.queue.jobs.get(0).job()).alertIds().size());
    }

    @Test
    void alertLockedByAnotherCheck_sendsNoDuplicateAlerts() {
        long seq = seedWithPlaceholder(1);
        dangerous();
        long now = Fakes.T0.getEpochSecond();
        env.locks.hold(UID, "N1.4", "OTHER-CHECK", now + 600);

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertTrue(env.telegram.sentTo("201").isEmpty());
        assertTrue(env.ai.renderRequests.get(0).ctx().alerts().isEmpty());
        assertTrue(env.queue.jobs.isEmpty());
    }

    @Test
    void expiredLock_doesNotBlockNewAlert() {
        long seq = seedWithPlaceholder(1);
        dangerous();
        long now = Fakes.T0.getEpochSecond();
        env.locks.hold(UID, "N1.4", "OTHER-CHECK", now - 1);   // hết hạn nhưng TTL của DynamoDB chưa xóa

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals(1, env.telegram.sentTo("201").size());
    }

    @Test
    void elderWithoutGuardianConsent_sendsNoAlerts_butStillKnowsHasGuardian() {
        long seq = seedWithPlaceholder(1);
        dangerous();
        env.directory.elder = new GuardianDirectory.Elder("Bác", false);

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertTrue(env.telegram.sentTo("201").isEmpty());
        assertTrue(env.ai.renderRequests.get(0).ctx().alerts().isEmpty());
        assertTrue(env.ai.renderRequests.get(0).ctx().hasGuardian());
    }

    @Test
    void noGuardians_hasGuardianFalse() {
        long seq = seedWithPlaceholder(1);
        dangerous();
        env.directory.guardians = List.of();

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertFalse(env.ai.renderRequests.get(0).ctx().hasGuardian());
    }

    @Test
    void lowRisk_neverTouchesGuardians() {
        long seq = seedWithPlaceholder(1);

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertTrue(env.ai.guardianRequests.isEmpty());
        assertTrue(env.telegram.sentTo("201").isEmpty());
    }

    // ---------- lỗi ai ----------

    @Test
    void aiFatal_sendsDegradedReply_recordsCheck_clearsBuffer() {
        long seq = seedWithPlaceholder(1);
        env.ai.analyzeFn = r -> {
            throw AiException.fatal("bad_request");
        };

        Result result = env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals(Result.DEGRADED, result);
        assertEquals(WorkerMessages.degraded("Bác"), env.telegram.edited.get(0).text());
        assertEquals("degraded", env.checks.get(UID, JOB).outcome());
        assertFalse(env.buffer.exists(CHAT));
    }

    @Test
    void aiRetryable_isRethrown_andKeepsBufferUntouched() {
        long seq = seedWithPlaceholder(1);
        env.ai.analyzeFn = r -> {
            throw AiException.retryable("invoke");
        };

        assertThrows(AiException.class, () -> env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000));

        assertTrue(env.buffer.exists(CHAT));
        assertTrue(env.telegram.edited.isEmpty() && env.telegram.sent.isEmpty());
        assertNull(env.checks.get(UID, JOB));
    }

    @Test
    void renderFatal_alsoDegrades() {
        long seq = seedWithPlaceholder(1);
        env.ai.renderFn = r -> {
            throw AiException.fatal("contract");
        };

        assertEquals(Result.DEGRADED, env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000));
    }

    // ---------- gửi cho người cao tuổi ----------

    @Test
    void editPermanentFailure_fallsBackToNewMessage() {
        long seq = seedWithPlaceholder(1);
        env.telegram.editFailure = new SendException(400, true, false);

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals(1, env.telegram.sentTo(CHAT).size());
    }

    @Test
    void editNotModified_isTreatedAsDone() {
        long seq = seedWithPlaceholder(1);
        env.telegram.editFailure = new SendException(400, true, true);

        Result result = env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals(Result.PROCESSED, result);
        assertTrue(env.telegram.sentTo(CHAT).isEmpty());
    }

    @Test
    void editTransientFailure_retries() {
        long seq = seedWithPlaceholder(1);
        env.telegram.editFailure = Fakes.transientError();

        assertThrows(RetryableFailure.class, () -> env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000));
        assertTrue(env.buffer.exists(CHAT));
    }

    @Test
    void elderBlockedBot_doesNotFailJob() {
        long seq = env.seed(CHAT, 1);
        env.telegram.sendFailure.put(CHAT, Fakes.permanent());

        Result result = env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals(Result.PROCESSED, result);
        assertFalse(env.buffer.exists(CHAT));
    }

    // ---------- tham số truyền cho ai ----------

    @Test
    void maintenanceFlag_isPassedToRender_andFlagsToAnalyze() {
        long seq = seedWithPlaceholder(1);
        env.flagsStore.flags = new Flags(false, true, false, true);

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertTrue(env.ai.renderRequests.get(0).ctx().maintenance());
        assertFalse(env.ai.analyzeRequests.get(0).ctx().flags().llmEnabled());
    }

    @Test
    void addressForm_isMappedToContractValue() {
        long seq = seedWithPlaceholder(1);
        env.directory.elder = new GuardianDirectory.Elder("Chú", false);

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);

        assertEquals("uncle", env.ai.renderRequests.get(0).ctx().addressForm());
    }

    @Test
    void deadline_leavesRoomForRenderAndTelegram() {
        long seq = seedWithPlaceholder(1);
        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, seq), 120_000);
        assertEquals(85.0, env.ai.analyzeRequests.get(0).ctx().deadlineS(), 0.001);

        env.seed("101", 1);
        env.analyze.process(Fakes.analyzeJob("01JOB00000000000000000000B", "101", 1), 60_000);
        assertEquals(35.0, env.ai.analyzeRequests.get(1).ctx().deadlineS(), 0.001);

        env.seed("102", 1);
        env.analyze.process(Fakes.analyzeJob("01JOB00000000000000000000C", "102", 1), 20_000);
        assertEquals(10.0, env.ai.analyzeRequests.get(2).ctx().deadlineS(), 0.001);
    }

    @Test
    void logs_neverContainMessageContentOrUserIds() {
        env.buffer.append(CHAT, Fakes.message(CHAT, 1, "MA-OTP-123456"), 0);
        env.buffer.setPlaceholder(CHAT, 77);
        dangerous();

        env.analyze.process(Fakes.analyzeJob(JOB, CHAT, 1), 120_000);

        String all = String.join("\n", env.logs);
        assertFalse(all.contains("MA-OTP-123456"));
        assertFalse(all.contains("tg:100"));
        assertFalse(all.contains("giai thich"));
    }

    @Test
    void alertId_isStablePerCheckAndGuardian_andShortEnoughForCallback() {
        String a = AnalyzeProcessor.alertId(JOB, "tg:201");

        assertEquals(a, AnalyzeProcessor.alertId(JOB, "tg:201"));
        assertFalse(a.equals(AnalyzeProcessor.alertId(JOB, "tg:202")));
        assertTrue(("ack:" + a).length() <= 64);
    }

    @Test
    void notifyGaveUp_editsPlaceholderWithBusyMessage() {
        seedWithPlaceholder(1);

        env.analyze.notifyGaveUp(Fakes.analyzeJob(JOB, CHAT, 1));

        assertEquals(WorkerMessages.busy("Bác"), env.telegram.edited.get(0).text());
    }

    @Test
    void notifyGaveUp_neverThrows() {
        seedWithPlaceholder(1);
        env.telegram.editFailure = Fakes.transientError();

        env.analyze.notifyGaveUp(Fakes.analyzeJob(JOB, CHAT, 1));   // không ném: kết thúc bình thường là đạt
        assertTrue(env.telegram.edited.isEmpty());
    }
}
