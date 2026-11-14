package com.askfirst.common.store;

import com.askfirst.common.channel.model.InboundMessage;
import java.util.List;
import java.util.Optional;

/**
 * BUFFER#: nơi webhook gom các tin của một chat trong vài giây để worker xử lý cả cụm (debounce).
 */
public interface BufferStore {

    record State(long maxSeq, long doneSeq, Integer placeholderId) {
    }

    long append(String chatId, InboundMessage message, long ttlEpochSeconds);

    void setPlaceholder(String chatId, int messageId);

    Optional<State> state(String chatId);

    List<InboundMessage> itemsAfter(String chatId, long afterSeq, long upToSeq);

    boolean finish(String chatId, long seq);

    void delete(String chatId);
}
