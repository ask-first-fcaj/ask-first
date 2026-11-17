package com.askfirst.worker;

import com.askfirst.common.ai.AiClient;
import com.askfirst.common.ai.AiContract.AlertResult;
import com.askfirst.common.ai.AiContract.AnalyzeContext;
import com.askfirst.common.ai.AiContract.AnalyzeRequest;
import com.askfirst.common.ai.AiContract.AnalyzeResult;
import com.askfirst.common.ai.AiContract.Flags;
import com.askfirst.common.ai.AiContract.GuardianAlertContext;
import com.askfirst.common.ai.AiContract.OutMessage;
import com.askfirst.common.ai.AiContract.RenderContext;
import com.askfirst.common.ai.AiContract.RenderGuardianAlertsRequest;
import com.askfirst.common.ai.AiContract.RenderRequest;
import com.askfirst.common.ai.AiContract.ReplyPlan;
import com.askfirst.common.ai.AiContract.Verdict;
import com.askfirst.common.ai.AiException;
import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.job.FollowupJob;
import com.askfirst.common.store.AlertLockStore;
import com.askfirst.common.store.AlertRecord;
import com.askfirst.common.store.AlertStore;
import com.askfirst.common.store.BufferStore;
import com.askfirst.common.store.CheckRecord;
import com.askfirst.common.store.CheckStore;
import com.askfirst.common.store.SessionRecord;
import com.askfirst.common.store.SessionStore;
import com.askfirst.worker.GuardianDirectory.Elder;
import com.askfirst.worker.GuardianDirectory.Guardian;
import com.askfirst.worker.TelegramGateway.Btn;
import com.askfirst.worker.TelegramGateway.SendException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Xử lý một {@link AnalyzeJob}: đọc buffer, gọi ai, báo người thân, trả lời, ghi dấu, dọn buffer.
 *
 * <p>Idempotency khi SQS giao lại: job cũ/đã xong bị bỏ qua; CHECK# là dấu "đã xong"; khóa ALERTLOCK thuộc
 * check_id nên cho chính job này vào lại; alert_id suy ra từ (check, guardian) nên không gửi lại cho người thân
 * đã nhận. Phần còn lại là at-least-once: nếu chết ngay sau khi trả lời mà trước khi ghi CHECK#, lần retry
 * có thể gửi lại tin trả lời (edit placeholder thì vô hại).
 *
 * <p>Log chỉ gồm job_id, seq, mã lỗi: không bao giờ log nội dung tin, verdict, explanation hay id người dùng.
 */
public final class AnalyzeProcessor {

    public enum Result { PROCESSED, DEGRADED, SKIPPED }

    static final long ALERT_LOCK_SECONDS = 30 * 60L;
    static final long RECORD_TTL_SECONDS = 30L * 24 * 3600;
    /** Timeout của ai là 90 giây; chừa biên cho render, gửi Telegram và dọn dẹp. */
    static final double MAX_DEADLINE_S = 85.0;
    static final double RESERVED_S = 25.0;
    static final double MIN_DEADLINE_S = 10.0;

    private final BufferStore buffer;
    private final FlagsCache flags;
    private final CheckStore checks;
    private final AlertStore alerts;
    private final AlertLockStore locks;
    private final GuardianDirectory directory;
    private final AiClient ai;
    private final TelegramGateway telegram;
    private final JobQueue queue;
    private final Clock clock;
    private final Supplier<String> idGenerator;
    private final Consumer<String> log;
    private final SessionStore sessions;

    public AnalyzeProcessor(BufferStore buffer, FlagsCache flags, CheckStore checks, AlertStore alerts,
                            AlertLockStore locks, GuardianDirectory directory, AiClient ai,
                            TelegramGateway telegram, JobQueue queue, Clock clock,
                            Supplier<String> idGenerator, Consumer<String> log) {
        this(buffer, flags, checks, alerts, locks, directory, ai, telegram, queue, clock, idGenerator, log, null);
    }

    public AnalyzeProcessor(BufferStore buffer, FlagsCache flags, CheckStore checks, AlertStore alerts,
                            AlertLockStore locks, GuardianDirectory directory, AiClient ai,
                            TelegramGateway telegram, JobQueue queue, Clock clock,
                            Supplier<String> idGenerator, Consumer<String> log,
                            SessionStore sessions) {
        this.buffer = buffer;
        this.flags = flags;
        this.checks = checks;
        this.alerts = alerts;
        this.locks = locks;
        this.directory = directory;
        this.ai = ai;
        this.telegram = telegram;
        this.queue = queue;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.log = log;
        this.sessions = sessions;
    }

    private record Out(String html, List<List<Btn>> buttons, boolean silent) {
    }

    private record AlertOutcome(List<AlertResult> results, List<String> deliveredIds) {
        static AlertOutcome none() {
            return new AlertOutcome(List.of(), List.of());
        }
    }

    public Result process(AnalyzeJob job, long remainingMs) {
        String chat = job.chatId();
        String uid = job.userId();
        String checkId = job.jobId();

        Optional<BufferStore.State> state = buffer.state(chat);
        if (state.isEmpty()) {
            log.accept("analyze job=" + checkId + " skip=no_buffer");
            return Result.SKIPPED;
        }
        BufferStore.State s = state.get();
        if (job.seq() < s.maxSeq() || job.seq() <= s.doneSeq()) {
            log.accept("analyze job=" + checkId + " skip=stale seq=" + job.seq());
            return Result.SKIPPED;
        }
        if (checks.exists(uid, checkId)) {
            buffer.finish(chat, job.seq());
            log.accept("analyze job=" + checkId + " skip=already_done");
            return Result.SKIPPED;
        }
        List<InboundMessage> items = buffer.itemsAfter(chat, s.doneSeq(), job.seq());
        if (items.isEmpty()) {
            buffer.finish(chat, job.seq());
            log.accept("analyze job=" + checkId + " skip=empty_buffer");
            return Result.SKIPPED;
        }

        Elder elder = directory.elder(uid).orElse(new Elder("Bác", false));
        Flags currentFlags = flags.get();
        double deadline = Math.max(MIN_DEADLINE_S, Math.min(MAX_DEADLINE_S, remainingMs / 1000.0 - RESERVED_S));

        AnalyzeResult result;
        try {
            result = ai.analyze(AnalyzeRequest.of(items, null,
                    new AnalyzeContext(checkId, uid, currentFlags, deadline)));
        } catch (AiException e) {
            if (e.isRetryable()) {
                throw e;
            }
            return degrade(job, s, elder, items, e.code(), AlertOutcome.none());
        }
        Verdict verdict = result.verdict();

        List<Guardian> active = directory.activeGuardians(uid);
        AlertOutcome alertOutcome = verdict.shouldAlert()
                ? alertGuardians(job, verdict, elder, active)
                : recoverDeliveredAlerts(checkId, active);

        ReplyPlan plan;
        try {
            plan = ai.render(RenderRequest.of(verdict, new RenderContext(
                    checkId,
                    WorkerMessages.contractAddressForm(elder.addressForm()),
                    !active.isEmpty(),
                    alertOutcome.results(),
                    !currentFlags.llmEnabled())));
        } catch (AiException e) {
            if (e.isRetryable()) {
                throw e;
            }
            return degrade(job, s, elder, items, e.code(), alertOutcome);
        }

        List<Out> messages = new ArrayList<>();
        for (OutMessage m : plan.messages()) {
            messages.add(new Out(m.textHtml(), ButtonMapper.map(m.buttons(), checkId, null), m.silent()));
        }
        // Ghi session TRƯỚC khi trả lời và ghi CHECK#: lỗi ở đây được SQS retry an toàn. Nếu để sau CHECK#, lần retry
        // thấy "đã xong" nên bỏ qua và session mất vĩnh viễn (nút đóng góp báo "phiên hết hạn").
        if (sessions != null && verdict.maskedText() != null && !verdict.maskedText().isBlank()) {
            sessions.put(new SessionRecord(
                    chat,
                    checkId,
                    uid,
                    verdict.maskedText(),
                    verdict.followupQuestion(),
                    1,
                    clock.instant().getEpochSecond() + 30 * 60L));
        }
        deliver(chat, s.placeholderId(), messages);

        enqueueFollowup(job, alertOutcome.deliveredIds());
        checks.put(new CheckRecord(checkId, uid, verdict.outcome(), verdict.level(), verdict.score(),
                verdict.group(), verdict.scenario(),
                verdict.path(),
                verdict.signals().stream().map(sig -> sig.code()).toList(),
                verdict.riskyActions(),
                items.stream().map(InboundMessage::kind).distinct().toList(),
                !alertOutcome.deliveredIds().isEmpty(),
                clock.instant().toString(),
                clock.instant().getEpochSecond() + RECORD_TTL_SECONDS));
        buffer.finish(chat, job.seq());
        log.accept("analyze job=" + checkId + " done level=" + verdict.level() + " path=" + verdict.path()
                + " alerts=" + alertOutcome.deliveredIds().size());
        return Result.PROCESSED;
    }

    /** SQS đã giao đủ số lần mà vẫn lỗi: báo "bận" để người cao tuổi không phải chờ mãi ở tin "⏳". Best-effort. */
    public void notifyGaveUp(AnalyzeJob job) {
        try {
            // Đã có CHECK# nghĩa là người cao tuổi đã nhận kết quả thật (job chỉ lỗi ở bước dọn dẹp): không được đè
            // kết luận cảnh báo lừa đảo bằng tin "đang bận".
            if (checks.exists(job.userId(), job.jobId())) {
                buffer.finish(job.chatId(), job.seq());
                log.accept("analyze job=" + job.jobId() + " give_up_skip=already_done");
                return;
            }
            Integer placeholder = buffer.state(job.chatId()).map(BufferStore.State::placeholderId).orElse(null);
            Elder elder = directory.elder(job.userId()).orElse(new Elder("Bác", false));
            Out msg = new Out(WorkerMessages.busy(elder.addressForm()), List.of(), false);
            deliver(job.chatId(), placeholder, List.of(msg));
            // Đóng cụm tin để tin gửi lại sau đó không bị gộp với nội dung của job đã bỏ cuộc
            buffer.finish(job.chatId(), job.seq());
        } catch (RuntimeException e) {
            log.accept("analyze job=" + job.jobId() + " give_up_notice_failed");
        }
    }

    // ---------- ai lỗi chắc chắn lặp lại ----------

    private Result degrade(AnalyzeJob job, BufferStore.State s, Elder elder, List<InboundMessage> items, String code,
                           AlertOutcome alerted) {
        deliver(job.chatId(), s.placeholderId(),
                List.of(new Out(WorkerMessages.degraded(elder.addressForm()), List.of(), false)));
        // Người thân có thể đã nhận cảnh báo trước khi render lỗi: vẫn phải nhắc lại và ghi alerted=true
        enqueueFollowup(job, alerted.deliveredIds());
        checks.put(new CheckRecord(job.jobId(), job.userId(), "degraded", "UNSURE", 0, "OTHER", "OTHER",
                "none", List.of(), List.of(),
                items.stream().map(InboundMessage::kind).distinct().toList(),
                !alerted.deliveredIds().isEmpty(), clock.instant().toString(),
                clock.instant().getEpochSecond() + RECORD_TTL_SECONDS));
        buffer.finish(job.chatId(), job.seq());
        log.accept("analyze job=" + job.jobId() + " degraded code=" + code);
        return Result.DEGRADED;
    }

    /**
     * Tạo job nhắc lại sau 15 phút. Nhắc lại chỉ là phụ so với việc người cao tuổi đã nhận kết quả: lỗi SQS ở đây
     * không được làm cả job lỗi (SQS giao lại sẽ gửi lại kết quả và đè tin ở lần giao thứ 5), nên thử 2 lần rồi ghi log.
     */
    private void enqueueFollowup(AnalyzeJob job, List<String> deliveredAlertIds) {
        if (deliveredAlertIds.isEmpty()) {
            return;
        }
        FollowupJob followup = FollowupJob.create(idGenerator.get(), job.chatId(), job.userId(), job.jobId(),
                deliveredAlertIds, clock.instant());
        for (int attempt = 1; ; attempt++) {
            try {
                queue.enqueue(followup, FollowupJob.DELAY_SECONDS);
                return;
            } catch (RuntimeException e) {
                if (attempt >= 2) {
                    log.accept("analyze job=" + job.jobId() + " followup_enqueue_failed");
                    return;
                }
            }
        }
    }

    /**
     * Retry sau khi lần chạy trước ĐÃ báo người thân, nhưng lần phân tích này (LLM không tất định) ra shouldAlert=false:
     * vẫn phải tính các alert đã gửi để câu trả lời và followup khớp với việc người thân đã nhận cảnh báo.
     */
    private AlertOutcome recoverDeliveredAlerts(String checkId, List<Guardian> active) {
        List<AlertResult> results = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        for (Guardian g : active) {
            Optional<AlertRecord> rec = alerts.get(alertId(checkId, g.uid()));
            if (rec.isPresent() && rec.get().delivered()) {
                results.add(new AlertResult(guardianName(rec.get()), "sent"));
                delivered.add(rec.get().alertId());
            }
        }
        return delivered.isEmpty() ? AlertOutcome.none() : new AlertOutcome(results, delivered);
    }

    private static String guardianName(AlertRecord r) {
        return r.guardianName() == null || r.guardianName().isBlank()
                ? WorkerMessages.GUARDIAN_FALLBACK_NAME : r.guardianName();
    }

    // ---------- báo người thân ----------

    private AlertOutcome alertGuardians(AnalyzeJob job, Verdict verdict, Elder elder, List<Guardian> active) {
        String checkId = job.jobId();
        List<Guardian> targets = !elder.guardianConsent() ? List.of()
                : active.stream().filter(g -> g.chatId() != null && !g.chatId().isBlank()).toList();
        if (targets.isEmpty()) {
            return AlertOutcome.none();
        }
        long now = clock.instant().getEpochSecond();
        if (!locks.acquire(job.userId(), verdict.scenario(), checkId, now, now + ALERT_LOCK_SECONDS)) {
            log.accept("analyze job=" + checkId + " alert=locked");
            return AlertOutcome.none();
        }

        List<AlertRecord> records = new ArrayList<>();
        for (Guardian g : targets) {
            records.add(alerts.reserve(new AlertRecord(alertId(checkId, g.uid()), job.userId(), job.chatId(),
                    checkId, g.uid(), g.chatId(), g.name(), g.shareLevel(), AlertRecord.PENDING,
                    null, null, null, now + RECORD_TTL_SECONDS)));
        }

        List<AlertRecord> toSend = records.stream().filter(r -> AlertRecord.PENDING.equals(r.status())).toList();
        boolean[] transientFailure = {false};
        Set<String> deliveredLocally = ConcurrentHashMap.newKeySet();
        if (!toSend.isEmpty()) {
            List<GuardianAlertContext> contexts = new ArrayList<>();
            for (AlertRecord r : toSend) {
                contexts.add(new GuardianAlertContext(r.alertId(), WorkerMessages.ELDER_NAME_FALLBACK,
                        r.shareLevel(), clock.instant()));
            }
            List<OutMessage> outs = null;
            try {
                outs = ai.renderGuardianAlerts(RenderGuardianAlertsRequest.of(verdict, contexts));
            } catch (AiException e) {
                if (e.isRetryable()) {
                    throw e;
                }
                log.accept("analyze job=" + checkId + " alert_render_fatal code=" + e.code());
                toSend.forEach(r -> alerts.markFailed(r.alertId()));
            }
            if (outs != null) {
                sendAll(toSend, outs, checkId, transientFailure, deliveredLocally);
            }
        }
        if (transientFailure[0]) {
            throw new RetryableFailure("alert_send");
        }

        List<AlertResult> results = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        for (AlertRecord r : records) {
            AlertRecord current = alerts.get(r.alertId()).orElse(r);
            // Telegram đã trả message_id mà markSent lỗi: tin đã tới người thân nên vẫn tính là đã gửi
            boolean ok = current.delivered() || deliveredLocally.contains(current.alertId());
            results.add(new AlertResult(guardianName(current), ok ? "sent" : "failed"));
            if (ok) {
                delivered.add(current.alertId());
            }
        }
        return new AlertOutcome(results, delivered);
    }

    /** Gửi song song cho các người thân. "sent" chỉ khi Telegram trả message_id. */
    private void sendAll(List<AlertRecord> toSend, List<OutMessage> outs, String checkId, boolean[] transientFailure,
                         Set<String> deliveredLocally) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < toSend.size(); i++) {
                AlertRecord r = toSend.get(i);
                OutMessage out = outs.get(i);
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        int messageId = telegram.send(r.guardianChatId(), out.textHtml(),
                                ButtonMapper.map(out.buttons(), checkId, r.alertId()), out.silent());
                        deliveredLocally.add(r.alertId());
                        markSentWithRetry(r.alertId(), messageId, checkId);
                    } catch (SendException e) {
                        if (e.permanent()) {
                            alerts.markFailed(r.alertId());
                            log.accept("analyze job=" + checkId + " alert_send_permanent status=" + e.status());
                        } else {
                            synchronized (transientFailure) {
                                transientFailure[0] = true;
                            }
                            log.accept("analyze job=" + checkId + " alert_send_transient status=" + e.status());
                        }
                    }
                }, pool));
            }
            futures.forEach(CompletableFuture::join);
        }
    }

    /**
     * Tin đã tới người thân rồi, nên lỗi ghi trạng thái không được ném ra: nếu ném, job bị giao lại, alert vẫn
     * "pending" và người thân nhận cảnh báo lần thứ hai. Thử lại một lần, vẫn lỗi thì chỉ ghi log.
     */
    private void markSentWithRetry(String alertId, int messageId, String checkId) {
        for (int attempt = 1; ; attempt++) {
            try {
                alerts.markSent(alertId, messageId);
                return;
            } catch (RuntimeException e) {
                if (attempt >= 2) {
                    log.accept("analyze job=" + checkId + " alert_mark_sent_failed");
                    return;
                }
            }
        }
    }

    /** alert_id xác định theo (check, guardian): retry dùng lại đúng alert, không gửi trùng. */
    static String alertId(String checkId, String guardianUid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(guardianUid.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return checkId + "-" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 không khả dụng");
        }
    }

    // ---------- trả lời người cao tuổi ----------

    private void deliver(String chat, Integer placeholderId, List<Out> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Out m = messages.get(i);
            if (i == 0 && placeholderId != null) {
                editOrSend(chat, placeholderId, m);
            } else {
                sendToElder(chat, m);
            }
        }
    }

    private void editOrSend(String chat, int placeholderId, Out m) {
        try {
            telegram.edit(chat, placeholderId, m.html(), m.buttons());
        } catch (SendException e) {
            if (e.notModified()) {
                return;
            }
            if (!e.permanent()) {
                throw new RetryableFailure("telegram_edit");
            }
            sendToElder(chat, m);   // tin "⏳" đã bị xóa hoặc quá cũ để sửa: gửi thành tin mới
        }
    }

    private void sendToElder(String chat, Out m) {
        try {
            telegram.send(chat, m.html(), m.buttons(), m.silent());
        } catch (SendException e) {
            if (!e.permanent()) {
                throw new RetryableFailure("telegram_send");
            }
            if (e.status() == 400) {
                // 400 thường là Telegram không parse được HTML của chính tin này (không phải bị chặn bot). Nếu bỏ qua
                // thì người cao tuổi không nhận được cảnh báo nào, nên thử lại một lần với bản đã bỏ thẻ HTML.
                sendPlainToElder(chat, m);
                return;
            }
            log.accept("elder_send_permanent status=" + e.status());   // bị chặn bot: retry vô ích
        }
    }

    private void sendPlainToElder(String chat, Out m) {
        try {
            telegram.send(chat, stripTags(m.html()), m.buttons(), m.silent());
            log.accept("elder_send_plain_fallback");
        } catch (SendException e) {
            if (!e.permanent()) {
                throw new RetryableFailure("telegram_send");
            }
            log.accept("elder_send_permanent status=" + e.status());
        }
    }

    /** Bỏ thẻ HTML, giữ nguyên entity (&lt; &amp;...) vì gửi vẫn dùng parse_mode=HTML nên entity vẫn được giải mã. */
    static String stripTags(String html) {
        return html == null ? "" : html.replaceAll("<[^>]*>", "");
    }
}
