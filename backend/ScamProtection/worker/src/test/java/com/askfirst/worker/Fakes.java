package com.askfirst.worker;

import com.askfirst.common.ai.AiClient;
import com.askfirst.common.ai.AiContract.AnalyzeRequest;
import com.askfirst.common.ai.AiContract.AnalyzeResult;
import com.askfirst.common.ai.AiContract.Button;
import com.askfirst.common.ai.AiContract.Flags;
import com.askfirst.common.ai.AiContract.OutMessage;
import com.askfirst.common.ai.AiContract.RenderGuardianAlertsRequest;
import com.askfirst.common.ai.AiContract.RenderRequest;
import com.askfirst.common.ai.AiContract.ReplyPlan;
import com.askfirst.common.ai.AiContract.Verdict;
import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.job.Job;
import com.askfirst.common.store.AlertLockStore;
import com.askfirst.common.store.AlertRecord;
import com.askfirst.common.store.AlertStore;
import com.askfirst.common.store.BufferStore;
import com.askfirst.common.store.CheckRecord;
import com.askfirst.common.store.CheckStore;
import com.askfirst.common.store.FlagsStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Bản giả trong bộ nhớ cho test worker: cùng ngữ nghĩa với các store DynamoDB (xem Javadoc BufferStore...). */
final class Fakes {

    private Fakes() {
    }

    static final Instant T0 = Instant.parse("2026-11-08T07:00:00Z");

    static final class MutableClock extends Clock {
        Instant now = T0;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ---------- stores ----------

    static final class MemBuffer implements BufferStore {
        private final Map<String, long[]> meta = new HashMap<>();             // chat -> {max, done}
        private final Map<String, Integer> placeholder = new HashMap<>();
        private final Map<String, TreeMap<Long, InboundMessage>> msgs = new HashMap<>();

        @Override
        public synchronized long append(String chatId, InboundMessage message, long ttl) {
            long[] m = meta.computeIfAbsent(chatId, k -> new long[] {0, 0});
            m[0]++;
            msgs.computeIfAbsent(chatId, k -> new TreeMap<>()).put(m[0], message);
            return m[0];
        }

        @Override
        public synchronized void setPlaceholder(String chatId, int messageId) {
            if (meta.containsKey(chatId)) {
                placeholder.put(chatId, messageId);
            }
        }

        @Override
        public synchronized Optional<State> state(String chatId) {
            long[] m = meta.get(chatId);
            return m == null ? Optional.empty() : Optional.of(new State(m[0], m[1], placeholder.get(chatId)));
        }

        @Override
        public synchronized List<InboundMessage> itemsAfter(String chatId, long afterSeq, long upToSeq) {
            TreeMap<Long, InboundMessage> all = msgs.getOrDefault(chatId, new TreeMap<>());
            return new ArrayList<>(all.subMap(afterSeq, false, upToSeq, true).values());
        }

        @Override
        public synchronized boolean finish(String chatId, long seq) {
            TreeMap<Long, InboundMessage> all = msgs.get(chatId);
            if (all != null) {
                all.headMap(seq, true).clear();
            }
            long[] m = meta.get(chatId);
            if (m == null) {
                return true;
            }
            if (m[0] == seq) {
                meta.remove(chatId);
                placeholder.remove(chatId);
                msgs.remove(chatId);
                return true;
            }
            if (m[1] < seq) {
                m[1] = seq;
            }
            placeholder.remove(chatId);
            return false;
        }

        synchronized boolean exists(String chatId) {
            return meta.containsKey(chatId);
        }
    }

    static final class MemFlags implements FlagsStore {
        Flags flags = DEFAULTS;
        int loads;

        @Override
        public Flags load() {
            loads++;
            return flags;
        }
    }

    static final class MemChecks implements CheckStore {
        final Map<String, CheckRecord> records = new ConcurrentHashMap<>();

        @Override
        public boolean exists(String userId, String checkId) {
            return records.containsKey(userId + "/" + checkId);
        }

        @Override
        public void put(CheckRecord record) {
            records.putIfAbsent(record.userId() + "/" + record.checkId(), record);
        }

        @Override
        public void updateFeedback(String userId, String checkId, String feedback) {
            records.computeIfPresent(userId + "/" + checkId, (k, old) -> new CheckRecord(
                    old.checkId(), old.userId(), old.outcome(), old.level(), old.score(),
                    old.group(), old.scenario(), old.path(), old.signalCodes(),
                    old.riskyActions(), old.inputKinds(), old.alerted(), feedback,
                    old.createdAt(), old.ttlEpochSeconds()));
        }

        CheckRecord get(String userId, String checkId) {
            return records.get(userId + "/" + checkId);
        }
    }

    static final class MemAlerts implements AlertStore {
        final Map<String, AlertRecord> records = new ConcurrentHashMap<>();

        @Override
        public synchronized AlertRecord reserve(AlertRecord pending) {
            return records.computeIfAbsent(pending.alertId(), k -> pending);
        }

        @Override
        public Optional<AlertRecord> get(String alertId) {
            return Optional.ofNullable(records.get(alertId));
        }

        private synchronized void update(String id, Function<AlertRecord, AlertRecord> f) {
            AlertRecord r = records.get(id);
            if (r != null) {
                records.put(id, f.apply(r));
            }
        }

        private static AlertRecord with(AlertRecord r, String status, Integer messageId, String ackedAt, String remindedAt) {
            return new AlertRecord(r.alertId(), r.elderUid(), r.elderChatId(), r.checkId(), r.guardianUid(),
                    r.guardianChatId(), r.guardianName(), r.shareLevel(), status,
                    messageId != null ? messageId : r.messageId(),
                    ackedAt != null ? ackedAt : r.ackedAt(),
                    remindedAt != null ? remindedAt : r.remindedAt(), r.ttlEpochSeconds());
        }

        @Override
        public void markSent(String alertId, int messageId) {
            update(alertId, r -> AlertRecord.ACKED.equals(r.status()) ? r : with(r, AlertRecord.SENT, messageId, null, null));
        }

        @Override
        public void markFailed(String alertId) {
            update(alertId, r -> AlertRecord.PENDING.equals(r.status()) ? with(r, AlertRecord.FAILED, null, null, null) : r);
        }

        @Override
        public synchronized boolean markAcked(String alertId, Instant at) {
            if (!records.containsKey(alertId)) {
                return false;
            }
            update(alertId, r -> with(r, AlertRecord.ACKED, null, r.ackedAt() != null ? r.ackedAt() : at.toString(), null));
            return true;
        }

        @Override
        public synchronized boolean markReminded(String alertId, Instant at) {
            AlertRecord r = records.get(alertId);
            if (r == null || r.remindedAt() != null) {
                return false;
            }
            records.put(alertId, with(r, r.status(), null, null, at.toString()));
            return true;
        }
    }

    static final class MemLocks implements AlertLockStore {
        private final Map<String, Object[]> locks = new HashMap<>();   // key -> {owner, ttl}

        @Override
        public synchronized boolean acquire(String userId, String scenario, String owner, long now, long ttl) {
            String key = userId + "#" + scenario;
            Object[] held = locks.get(key);
            if (held == null || held[0].equals(owner) || (long) held[1] < now) {
                locks.put(key, new Object[] {owner, ttl});
                return true;
            }
            return false;
        }

        synchronized void hold(String userId, String scenario, String owner, long ttl) {
            locks.put(userId + "#" + scenario, new Object[] {owner, ttl});
        }
    }

    // ---------- ai ----------

    static final class FakeAi implements AiClient {
        Function<AnalyzeRequest, AnalyzeResult> analyzeFn = r -> new AnalyzeResult(verdict("LOW_RISK", false), false);
        Function<RenderRequest, ReplyPlan> renderFn = r -> new ReplyPlan(List.of(
                new OutMessage("<b>Ket luan</b>", List.of(List.of(new Button("report_wrong", "Bao sai"))), false)));
        Function<RenderGuardianAlertsRequest, List<OutMessage>> guardianFn = r -> {
            List<OutMessage> out = new ArrayList<>();
            r.guardians().forEach(g -> out.add(new OutMessage("Canh bao " + g.alertId(),
                    List.of(List.of(new Button("ack", "Toi da lien he"))), false)));
            return out;
        };
        final List<AnalyzeRequest> analyzeRequests = new CopyOnWriteArrayList<>();
        final List<RenderRequest> renderRequests = new CopyOnWriteArrayList<>();
        final List<RenderGuardianAlertsRequest> guardianRequests = new CopyOnWriteArrayList<>();

        @Override
        public AnalyzeResult analyze(AnalyzeRequest request) {
            analyzeRequests.add(request);
            return analyzeFn.apply(request);
        }

        @Override
        public List<OutMessage> renderGuardianAlerts(RenderGuardianAlertsRequest request) {
            guardianRequests.add(request);
            return guardianFn.apply(request);
        }

        @Override
        public ReplyPlan render(RenderRequest request) {
            renderRequests.add(request);
            return renderFn.apply(request);
        }
    }

    // ---------- telegram, queue, directory ----------

    record Sent(String chatId, String text, List<List<TelegramGateway.Btn>> buttons, boolean silent, int messageId) {
    }

    record Edited(String chatId, int messageId, String text, List<List<TelegramGateway.Btn>> buttons) {
    }

    static final class FakeTelegram implements TelegramGateway {
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final List<Edited> edited = new CopyOnWriteArrayList<>();
        /** chat -> lỗi ném khi gửi (null = gửi được). Có thể đổi giữa các lần gọi để mô phỏng retry. */
        final Map<String, SendException> sendFailure = new ConcurrentHashMap<>();
        SendException editFailure;
        private final AtomicInteger ids = new AtomicInteger(1000);

        @Override
        public int send(String chatId, String htmlText, List<List<Btn>> buttons, boolean silent) {
            SendException failure = sendFailure.get(chatId);
            if (failure != null) {
                throw failure;
            }
            int id = ids.incrementAndGet();
            sent.add(new Sent(chatId, htmlText, buttons, silent, id));
            return id;
        }

        @Override
        public void edit(String chatId, int messageId, String htmlText, List<List<Btn>> buttons) {
            if (editFailure != null) {
                throw editFailure;
            }
            edited.add(new Edited(chatId, messageId, htmlText, buttons));
        }

        List<Sent> sentTo(String chatId) {
            return sent.stream().filter(s -> s.chatId().equals(chatId)).toList();
        }
    }

    static TelegramGateway.SendException permanent() {
        return new TelegramGateway.SendException(403, true, false);
    }

    static TelegramGateway.SendException transientError() {
        return new TelegramGateway.SendException(500, false, false);
    }

    record Enqueued(Job job, int delaySeconds) {
    }

    static final class FakeQueue implements JobQueue {
        final List<Enqueued> jobs = new CopyOnWriteArrayList<>();

        @Override
        public void enqueue(Job job, int delaySeconds) {
            jobs.add(new Enqueued(job, delaySeconds));
        }
    }

    static final class FakeDirectory implements GuardianDirectory {
        Elder elder = new Elder("Bác", true);
        List<Guardian> guardians = List.of(
                new Guardian("tg:201", "201", "Lan", 2),
                new Guardian("tg:202", "202", "Minh", 3));

        @Override
        public Optional<Elder> elder(String userId) {
            return Optional.ofNullable(elder);
        }

        @Override
        public List<Guardian> activeGuardians(String elderUid) {
            return guardians;
        }
    }

    // ---------- dữ liệu mẫu ----------

    static Verdict verdict(String level, boolean shouldAlert) {
        return new Verdict("verdict", level, shouldAlert ? 92 : 5, "N1", shouldAlert ? "N1.4" : "OTHER",
                List.of(), shouldAlert ? List.of("share_otp") : List.of(), shouldAlert,
                "giai thich", List.of(), null, "masked", "fast", List.of("m1"), Map.of(), Map.of(), 0.0);
    }

    static InboundMessage message(String chatId, int n, String text) {
        return new InboundMessage("telegram", "u" + n, chatId, "tg:" + chatId, "m" + n, "text", text,
                List.of(), null, null, null, T0);
    }

    static AnalyzeJob analyzeJob(String jobId, String chatId, long seq) {
        return AnalyzeJob.create(jobId, chatId, "tg:" + chatId, seq, T0);
    }

    /** Toàn bộ worker bằng bản giả, dựng sẵn. */
    static final class Env {
        final MutableClock clock = new MutableClock();
        final MemBuffer buffer = new MemBuffer();
        final MemFlags flagsStore = new MemFlags();
        final MemChecks checks = new MemChecks();
        final MemAlerts alerts = new MemAlerts();
        final MemLocks locks = new MemLocks();
        final FakeAi ai = new FakeAi();
        final FakeTelegram telegram = new FakeTelegram();
        final FakeQueue queue = new FakeQueue();
        final FakeDirectory directory = new FakeDirectory();
        final List<String> logs = new CopyOnWriteArrayList<>();
        final AtomicInteger followupIds = new AtomicInteger();
        final AnalyzeProcessor analyze;
        final FollowupProcessor followup;

        Env() {
            analyze = new AnalyzeProcessor(buffer, new FlagsCache(flagsStore, clock), checks, alerts, locks, directory,
                    ai, telegram, queue, clock, () -> String.format("01FOLLOWUP%016d", followupIds.incrementAndGet()),
                    logs::add);
            followup = new FollowupProcessor(alerts, directory, telegram, clock, logs::add);
        }

        /** Thêm n tin vào buffer của chat; trả về seq cuối. */
        long seed(String chatId, int n) {
            long seq = 0;
            for (int i = 1; i <= n; i++) {
                seq = buffer.append(chatId, message(chatId, i, "noi dung " + i), 0);
            }
            return seq;
        }

        WorkerRunner runner() {
            return new WorkerRunner(analyze, followup, logs::add);
        }
    }
}
