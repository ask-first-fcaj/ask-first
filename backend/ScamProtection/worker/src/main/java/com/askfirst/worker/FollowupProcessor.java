package com.askfirst.worker;

import com.askfirst.common.job.FollowupJob;
import com.askfirst.common.store.AlertRecord;
import com.askfirst.common.store.AlertStore;
import com.askfirst.worker.GuardianDirectory.Elder;
import com.askfirst.worker.TelegramGateway.Btn;
import com.askfirst.worker.TelegramGateway.SendException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Xử lý {@link FollowupJob} (trễ 15 phút): không gọi ai. Nếu chưa người thân nào bấm xác nhận thì nhắc người
 * cao tuổi và nhắc (im lặng) từng người thân, tối đa MỘT lần (khóa bằng reminded_at của alert đầu tiên).
 * Gửi lỗi sau khi đã đánh dấu thì không retry: thà thiếu một lời nhắc còn hơn nhắc trùng.
 */
public final class FollowupProcessor {

    private final AlertStore alerts;
    private final GuardianDirectory directory;
    private final TelegramGateway telegram;
    private final Clock clock;
    private final Consumer<String> log;

    public FollowupProcessor(AlertStore alerts, GuardianDirectory directory, TelegramGateway telegram,
                             Clock clock, Consumer<String> log) {
        this.alerts = alerts;
        this.directory = directory;
        this.telegram = telegram;
        this.clock = clock;
        this.log = log;
    }

    public void process(FollowupJob job) {
        List<AlertRecord> records = job.alertIds().stream()
                .map(alerts::get)
                .flatMap(Optional::stream)
                .toList();
        if (records.isEmpty()) {
            log.accept("followup job=" + job.jobId() + " skip=no_alert");
            return;
        }
        if (records.stream().anyMatch(r -> AlertRecord.ACKED.equals(r.status()))) {
            log.accept("followup job=" + job.jobId() + " skip=acked");
            return;
        }
        if (!alerts.markReminded(records.get(0).alertId(), clock.instant())) {
            log.accept("followup job=" + job.jobId() + " skip=already_reminded");
            return;
        }

        Elder elder = directory.elder(job.elderUid()).orElse(new Elder("Bác", false));
        trySend(job.chatId(), WorkerMessages.followupElder(elder.addressForm()), List.of(), false, job);
        for (AlertRecord r : records) {
            if (AlertRecord.SENT.equals(r.status())) {
                List<List<Btn>> ack = List.of(List.of(new Btn(WorkerMessages.ACK_BUTTON, "ack:" + r.alertId())));
                trySend(r.guardianChatId(), WorkerMessages.followupGuardian(WorkerMessages.ELDER_NAME_FALLBACK),
                        ack, true, job);
            }
        }
        log.accept("followup job=" + job.jobId() + " reminded alerts=" + records.size());
    }

    private void trySend(String chat, String html, List<List<Btn>> buttons, boolean silent, FollowupJob job) {
        try {
            telegram.send(chat, html, buttons, silent);
        } catch (SendException e) {
            log.accept("followup job=" + job.jobId() + " send_failed status=" + e.status());
        }
    }
}
