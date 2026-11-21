package com.askfirst.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.job.AnalyzeJob;
import com.askfirst.common.job.FollowupJob;
import com.askfirst.common.job.JobCodec;
import com.askfirst.common.store.CheckRecord;
import com.askfirst.worker.Fakes.FakeDirectory;
import com.askfirst.worker.Fakes.MemBuffer;
import com.askfirst.worker.Fakes.MemChecks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DlqHandler là lưới an toàn sau AnalyzeProcessor.notifyGaveUp: chỉ báo khi chưa ai báo cho job này, và chỉ sửa tin "⏳"
 * khi tin đó thuộc cụm hiện tại. Telegram được giả bằng HttpServer cục bộ để kiểm tra đúng request gửi đi.
 */
class DlqHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHAT = "100";
    private static final String UID = "tg:100";
    private static final String JOB = "01JOB00000000000000000000C";

    private HttpServer server;
    private final List<String> sentBodies = new CopyOnWriteArrayList<>();
    private final List<String> editedBodies = new CopyOnWriteArrayList<>();
    private volatile boolean editFails;

    private final MemBuffer buffer = new MemBuffer();
    private final MemChecks checks = new MemChecks();
    private final FakeDirectory directory = new FakeDirectory();
    private DlqHandler handler;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bottok/sendMessage", ex -> {
            sentBodies.add(readBody(ex));
            respond(ex, 200, "{\"ok\":true,\"result\":{\"message_id\":5}}");
        });
        server.createContext("/bottok/editMessageText", ex -> {
            editedBodies.add(readBody(ex));
            if (editFails) {
                respond(ex, 400, "{\"ok\":false,\"error_code\":400,\"description\":\"message to edit not found\"}");
            } else {
                respond(ex, 200, "{\"ok\":true,\"result\":{\"message_id\":77}}");
            }
        });
        server.start();
        TelegramClient client = new TelegramClient("tok", "http://localhost:" + server.getAddress().getPort());
        handler = new DlqHandler(client, buffer, checks, directory);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static JsonNode analyzeJob(long seq) throws IOException {
        return MAPPER.readTree(JobCodec.toJson(AnalyzeJob.create(JOB, CHAT, UID, seq, Fakes.T0)));
    }

    private long seedWithPlaceholder(int messages) {
        long seq = 0;
        for (int i = 1; i <= messages; i++) {
            seq = buffer.append(CHAT, Fakes.message(CHAT, i, "noi dung " + i), 0);
        }
        buffer.setPlaceholder(CHAT, 77);
        return seq;
    }

    private static String textOf(String requestBody) throws IOException {
        return MAPPER.readTree(requestBody).path("text").asText();
    }

    // ---------- lỗi 1: báo "bận" hai lần ----------

    @Test
    void jobAlreadyMarkedByWorker_sendsNothing() throws Exception {
        long seq = seedWithPlaceholder(1);
        checks.put(new CheckRecord(JOB, UID, "gave_up", "UNSURE", 0, "OTHER", "OTHER", "none",
                List.of(), List.of(), List.of(), false, Fakes.T0.toString(), 0L));

        String outcome = handler.notifyBusy(100L, analyzeJob(seq));

        assertEquals("already_handled", outcome);
        assertTrue(sentBodies.isEmpty());
        assertTrue(editedBodies.isEmpty());
        assertTrue(buffer.exists(CHAT));     // không đụng vào cụm tin
    }

    @Test
    void jobWithRealResultAlreadyDelivered_isNotOverwrittenByBusyMessage() throws Exception {
        long seq = seedWithPlaceholder(1);
        checks.put(new CheckRecord(JOB, UID, "verdict", "DANGEROUS", 92, "N1", "N1.4", "fast",
                List.of(), List.of(), List.of(), true, Fakes.T0.toString(), 0L));

        assertEquals("already_handled", handler.notifyBusy(100L, analyzeJob(seq)));

        assertTrue(sentBodies.isEmpty());
        assertTrue(editedBodies.isEmpty());
    }

    // ---------- lỗi 2: sửa nhầm "⏳" của job khác ----------

    @Test
    void staleJob_whileNewerMessagePending_doesNotTouchPlaceholder() throws Exception {
        seedWithPlaceholder(2);                           // cụm có seq 1,2; job seq=1 đã cũ

        String outcome = handler.notifyBusy(100L, analyzeJob(1));

        assertEquals("superseded", outcome);
        assertTrue(editedBodies.isEmpty());
        assertTrue(sentBodies.isEmpty());
        assertEquals(77, buffer.state(CHAT).get().placeholderId());   // "⏳" của job mới nhất còn nguyên
        assertEquals(2, buffer.state(CHAT).get().maxSeq());
    }

    @Test
    void jobAlreadyCoveredByDoneSeq_isSkipped() throws Exception {
        seedWithPlaceholder(2);
        buffer.finish(CHAT, 1);                           // done_seq=1, cụm vẫn còn tin seq=2

        assertEquals("superseded", handler.notifyBusy(100L, analyzeJob(1)));

        assertTrue(editedBodies.isEmpty());
        assertTrue(sentBodies.isEmpty());
    }

    @Test
    void currentJob_editsOwnPlaceholder_withElderAddressForm_andClosesBuffer() throws Exception {
        directory.elder = new GuardianDirectory.Elder("Cô", true);
        long seq = seedWithPlaceholder(1);

        String outcome = handler.notifyBusy(100L, analyzeJob(seq));

        assertEquals("edited", outcome);
        assertEquals(1, editedBodies.size());
        assertEquals(77, MAPPER.readTree(editedBodies.get(0)).path("message_id").asInt());
        assertEquals(WorkerMessages.busy("Cô"), textOf(editedBodies.get(0)));   // xưng hô theo người dùng, không cứng "bác/cô"
        assertTrue(sentBodies.isEmpty());
        assertFalse(buffer.exists(CHAT));
    }

    // ---------- các nhánh còn lại ----------

    @Test
    void noBufferLeft_sendsNewBusyMessage() throws Exception {
        String outcome = handler.notifyBusy(100L, analyzeJob(1));    // META đã hết hạn: người dùng vẫn đang chờ

        assertEquals("sent", outcome);
        assertEquals(1, sentBodies.size());
        assertEquals(WorkerMessages.busy("Bác"), textOf(sentBodies.get(0)));
        assertTrue(editedBodies.isEmpty());
    }

    @Test
    void editFails_fallsBackToNewMessage_andStillClosesBuffer() throws Exception {
        editFails = true;
        long seq = seedWithPlaceholder(1);

        String outcome = handler.notifyBusy(100L, analyzeJob(seq));

        assertEquals("sent", outcome);
        assertEquals(1, sentBodies.size());
        assertFalse(buffer.exists(CHAT));
    }

    @Test
    void handleRequest_ignoresFollowupJobs_withoutTouchingChat() throws Exception {
        seedWithPlaceholder(1);
        String followup = JobCodec.toJson(FollowupJob.create("01FU00000000000000000000A", CHAT, UID, JOB,
                List.of("alert-1"), Fakes.T0));

        handler.handleRequest(event(followup), context());

        assertTrue(sentBodies.isEmpty());
        assertTrue(editedBodies.isEmpty());
        assertEquals(77, buffer.state(CHAT).get().placeholderId());
    }

    @Test
    void handleRequest_analyzeJob_notifiesOnce() throws Exception {
        long seq = seedWithPlaceholder(1);
        String body = JobCodec.toJson(AnalyzeJob.create(JOB, CHAT, UID, seq, Fakes.T0));

        handler.handleRequest(event(body), context());

        assertEquals(1, editedBodies.size());
        assertTrue(sentBodies.isEmpty());
    }

    // ---------- helpers ----------

    private static SQSEvent event(String body) {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId("sqs-1");
        message.setBody(body);
        message.setAttributes(Map.of("ApproximateReceiveCount", "1"));
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(message));
        return event;
    }

    /** Context tối giản: chỉ cần getLogger(), các phương thức khác không được DlqHandler dùng. */
    private static Context context() {
        LambdaLogger logger = (LambdaLogger) Proxy.newProxyInstance(DlqHandlerTest.class.getClassLoader(),
                new Class<?>[] {LambdaLogger.class}, (proxy, method, args) -> null);
        return (Context) Proxy.newProxyInstance(DlqHandlerTest.class.getClassLoader(),
                new Class<?>[] {Context.class},
                (proxy, method, args) -> "getLogger".equals(method.getName()) ? logger : null);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
