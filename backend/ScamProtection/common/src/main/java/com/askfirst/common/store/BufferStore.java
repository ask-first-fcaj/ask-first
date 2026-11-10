package com.askfirst.common.store;

import com.askfirst.common.channel.model.InboundMessage;
import java.util.List;
import java.util.Optional;

/**
 * BUFFER#: nơi webhook gom các tin của một chat trong vài giây để worker xử lý cả cụm (debounce).
 *
 * <p>Cấu trúc (chọn để cấp {@code seq} nguyên tử khi nhiều webhook chạy đồng thời):
 * <pre>
 *   PK=BUFFER#&lt;chat_id&gt; SK=META           max_seq, done_seq, placeholder_id, ttl
 *   PK=BUFFER#&lt;chat_id&gt; SK=MSG#&lt;seq 10 số&gt;  payload (JSON InboundMessage), ttl
 * </pre>
 * {@code max_seq} tăng bằng {@code ADD} (nguyên tử). Tin lưu thành item riêng nên không tranh ghi nhau.
 * Nội dung tin chỉ nằm ở đây tối đa 10 phút (TTL) và bị xóa ngay khi worker xử lý xong.
 */
public interface BufferStore {

    /** Trạng thái META của một chat. {@code placeholderId} null nếu chưa có tin "⏳" nào được lưu. */
    record State(long maxSeq, long doneSeq, Integer placeholderId) {
    }

    /**
     * Cấp seq mới và lưu tin. seq tăng dần theo từng cụm, bắt đầu từ 1 khi buffer rỗng.
     *
     * @return seq vừa cấp
     */
    long append(String chatId, InboundMessage message, long ttlEpochSeconds);

    /** Lưu message_id của tin "⏳" để worker edit. Bỏ qua nếu buffer đã biến mất. */
    void setPlaceholder(String chatId, int messageId);

    Optional<State> state(String chatId);

    /** Các tin có {@code afterSeq < seq <= upToSeq}, theo thứ tự seq tăng. Bỏ qua seq bị thiếu. */
    List<InboundMessage> itemsAfter(String chatId, long afterSeq, long upToSeq);

    /**
     * Kết thúc xử lý đến {@code seq}: xóa các tin &lt;= seq; nếu không có tin mới hơn thì xóa luôn META
     * (cụm sau bắt đầu lại từ seq 1), ngược lại đặt {@code done_seq = seq} và xóa placeholder.
     * An toàn khi gọi lại (retry).
     *
     * @return true nếu buffer đã được xóa hẳn
     */
    boolean finish(String chatId, long seq);
}
