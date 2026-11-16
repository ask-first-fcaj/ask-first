package com.askfirst.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.askfirst.common.TelegramClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerHandlerTest {

    static class FakeTelegramClient extends TelegramClient {
        final List<Long> sentChats = new ArrayList<>();
        final List<String> sentTexts = new ArrayList<>();

        public FakeTelegramClient() {
            super("fake-token");
        }

        @Override
        public int sendText(long chatId, String text) {
            sentChats.add(chatId);
            sentTexts.add(text);
            return 1;
        }

        @Override
        public int sendText(long chatId, String text, Map<String, Object> replyMarkup) {
            return sendText(chatId, text);
        }
    }

    @Test
    @DisplayName("Worker sends polite warning matching SH-08 persona for chat_id")
    void testWorkerHandlesInboundMessagePayload() {
        FakeTelegramClient fakeTelegram = new FakeTelegramClient();
        WorkerHandler handler = new WorkerHandler(fakeTelegram);

        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setBody("""
                {
                  "message_id": "msg-123",
                  "chat_id": 999888,
                  "user_id": "tg:111",
                  "kind": "text",
                  "text": "Alo ck chuyen tien cho em"
                }
                """);

        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(message));

        handler.handleRequest(event, null);

        assertThat(fakeTelegram.sentChats).containsExactly(999888L);
        assertThat(fakeTelegram.sentTexts.get(0)).isEqualTo(WorkerHandler.DEFAULT_WARNING_TEXT);
        assertThat(fakeTelegram.sentTexts.get(0)).contains("Bác tuyệt đối không làm theo");
        assertThat(fakeTelegram.sentTexts.get(0)).contains("không chuyển tiền");
        assertThat(fakeTelegram.sentTexts.get(0)).contains("không cung cấp mã OTP");
    }

    @Test
    @DisplayName("Worker handles legacy chatId format")
    void testWorkerHandlesLegacyChatIdPayload() {
        FakeTelegramClient fakeTelegram = new FakeTelegramClient();
        WorkerHandler handler = new WorkerHandler(fakeTelegram);

        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setBody("""
                {
                  "chatId": 123456,
                  "text": "Link la"
                }
                """);

        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(message));

        handler.handleRequest(event, null);

        assertThat(fakeTelegram.sentChats).containsExactly(123456L);
        assertThat(fakeTelegram.sentTexts.get(0)).isEqualTo(WorkerHandler.DEFAULT_WARNING_TEXT);
    }
}
