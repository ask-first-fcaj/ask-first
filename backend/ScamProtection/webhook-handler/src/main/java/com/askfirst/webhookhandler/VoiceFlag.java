package com.askfirst.webhookhandler;

import com.askfirst.common.store.FlagsStore;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Đọc cờ {@code feature_voice} từ CONFIG#global/FLAGS, cache 30 giây (cùng nhịp với worker).
 *
 * <p>Chỉ được hỏi khi có tin voice/audio nên đường chính (văn bản, ảnh) không tốn thêm lần đọc DynamoDB nào.
 * Lỗi đọc cờ thì coi như voice TẮT (an toàn: người dùng nhận câu "chưa hỗ trợ" thay vì voice lọt vào hàng đợi)
 * và không cache lỗi, lần sau thử đọc lại.
 */
final class VoiceFlag implements BooleanSupplier {

    static final long TTL_MILLIS = 30_000L;

    private final FlagsStore store;
    private final LongSupplier clockMillis;

    private boolean cachedValue;
    private long cachedAt = Long.MIN_VALUE;

    VoiceFlag(FlagsStore store) {
        this(store, System::currentTimeMillis);
    }

    VoiceFlag(FlagsStore store, LongSupplier clockMillis) {
        this.store = store;
        this.clockMillis = clockMillis;
    }

    @Override
    public synchronized boolean getAsBoolean() {
        long now = clockMillis.getAsLong();
        if (cachedAt != Long.MIN_VALUE && now - cachedAt < TTL_MILLIS) {
            return cachedValue;
        }
        try {
            cachedValue = store.load().featureVoice();
            cachedAt = now;
            return cachedValue;
        } catch (Exception e) {
            return false;
        }
    }
}
