package com.askfirst.worker;

import java.util.List;

/** Cổng gửi Telegram cho worker: chat id là String như contract, tách khỏi TelegramClient để test bằng fake. */
public interface TelegramGateway {

    record Btn(String text, String callbackData) {
    }

    /** @return message_id Telegram trả về (chỉ coi là "đã gửi" khi có message_id) */
    int send(String chatId, String htmlText, List<List<Btn>> buttons, boolean silent);

    void edit(String chatId, int messageId, String htmlText, List<List<Btn>> buttons);

    /**
     * Gửi/edit thất bại. {@code permanent}: gửi lại cũng vô ích (bị chặn, tin đã xóa);
     * {@code notModified}: edit với nội dung y hệt (retry sau khi đã edit xong). Không mang body của Telegram.
     */
    final class SendException extends RuntimeException {
        private final boolean permanent;
        private final boolean notModified;
        private final int status;

        public SendException(int status, boolean permanent, boolean notModified) {
            super("telegram_" + status, null, false, false);
            this.status = status;
            this.permanent = permanent;
            this.notModified = notModified;
        }

        public boolean permanent() {
            return permanent;
        }

        public boolean notModified() {
            return notModified;
        }

        public int status() {
            return status;
        }
    }
}
