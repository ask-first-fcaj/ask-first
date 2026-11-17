package com.askfirst.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.askfirst.common.job.FollowupJob;
import com.askfirst.common.store.AlertRecord;
import com.askfirst.worker.Fakes.Env;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FollowupProcessorTest {

    private static final String CHECK = "01JOB00000000000000000000A";
    private final Env env = new Env();

    private String alert(String guardianUid, String guardianChat, String status) {
        String id = AnalyzeProcessor.alertId(CHECK, guardianUid);
        env.alerts.reserve(new AlertRecord(id, "tg:100", "100", CHECK, guardianUid, guardianChat, "Lan", 2,
                status, 5, null, null, 0));
        return id;
    }

    private FollowupJob job(String... alertIds) {
        return FollowupJob.create("01FU00000000000000000000A", "100", "tg:100", CHECK, List.of(alertIds), Fakes.T0);
    }

    @Test
    void noAck_remindsElderAndGuardiansOnce_guardianReminderIsSilentWithAckButton() {
        String a1 = alert("tg:201", "201", AlertRecord.SENT);
        String a2 = alert("tg:202", "202", AlertRecord.SENT);

        env.followup.process(job(a1, a2));

        assertEquals(1, env.telegram.sentTo("100").size());
        assertFalse(env.telegram.sentTo("100").get(0).silent());
        assertEquals(1, env.telegram.sentTo("201").size());
        assertTrue(env.telegram.sentTo("201").get(0).silent());
        assertEquals("ack:" + a1, env.telegram.sentTo("201").get(0).buttons().get(0).get(0).callbackData());

        env.followup.process(job(a1, a2));   // giao lại: không nhắc lần hai

        assertEquals(1, env.telegram.sentTo("100").size());
        assertEquals(1, env.telegram.sentTo("201").size());
    }

    @Test
    void anyAck_meansNoReminder() {
        String a1 = alert("tg:201", "201", AlertRecord.SENT);
        String a2 = alert("tg:202", "202", AlertRecord.SENT);
        env.alerts.markAcked(a2, Instant.parse("2026-11-08T07:05:00Z"));

        env.followup.process(job(a1, a2));

        assertTrue(env.telegram.sent.isEmpty());
    }

    @Test
    void failedAlerts_areNotReminded() {
        String sent = alert("tg:201", "201", AlertRecord.SENT);
        String failed = alert("tg:202", "202", AlertRecord.FAILED);

        env.followup.process(job(sent, failed));

        assertTrue(env.telegram.sentTo("202").isEmpty());
        assertEquals(1, env.telegram.sentTo("201").size());
    }

    @Test
    void unknownAlerts_doNothing() {
        env.followup.process(job("DOES-NOT-EXIST"));

        assertTrue(env.telegram.sent.isEmpty());
    }

    @Test
    void sendFailure_doesNotThrow() {
        String a1 = alert("tg:201", "201", AlertRecord.SENT);
        env.telegram.sendFailure.put("100", Fakes.transientError());
        env.telegram.sendFailure.put("201", Fakes.permanent());

        env.followup.process(job(a1));   // không ném: thà thiếu một lời nhắc còn hơn nhắc trùng
    }
}
