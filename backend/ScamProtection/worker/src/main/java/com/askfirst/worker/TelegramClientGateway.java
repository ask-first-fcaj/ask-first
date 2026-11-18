package com.askfirst.worker;

import com.askfirst.common.TelegramApiException;
import com.askfirst.common.TelegramClient;
import com.askfirst.common.TelegramRateLimitException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@link TelegramGateway} trên TelegramClient. chat id String được đổi sang long ở đúng một chỗ này. */
public final class TelegramClientGateway implements TelegramGateway {

    private final TelegramClient client;

    public TelegramClientGateway(TelegramClient client) {
        this.client = client;
    }

    @Override
    public int send(String chatId, String htmlText, List<List<Btn>> buttons, boolean silent) {
        try {
            return client.sendText(Long.parseLong(chatId), htmlText, markup(buttons), silent);
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public void edit(String chatId, int messageId, String htmlText, List<List<Btn>> buttons) {
        try {
            client.editText(Long.parseLong(chatId), messageId, htmlText, markup(buttons));
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    private static Map<String, Object> markup(List<List<Btn>> buttons) {
        if (buttons == null || buttons.isEmpty()) {
            return null;
        }
        List<List<TelegramClient.InlineButton>> rows = new ArrayList<>();
        for (List<Btn> row : buttons) {
            List<TelegramClient.InlineButton> out = new ArrayList<>();
            for (Btn b : row) {
                out.add(new TelegramClient.InlineButton(b.text(), b.callbackData()));
            }
            rows.add(out);
        }
        return TelegramClient.inlineKeyboard(rows);
    }

    private static RuntimeException translate(RuntimeException e) {
        if (e instanceof TelegramApiException api) {
            return new SendException(api.status(), api.isPermanent(), api.isMessageNotModified());
        }
        if (e instanceof TelegramRateLimitException) {
            return new SendException(429, false, false);
        }
        if (e instanceof NumberFormatException) {
            return new SendException(400, true, false);   // chat_id không phải số: không bao giờ gửi được
        }
        return new SendException(0, false, false);         // mạng/IO/lỗi khác: coi là tạm thời
    }
}
