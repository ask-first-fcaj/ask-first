package com.askfirst.worker;

import com.askfirst.common.ai.AiContract.Flags;
import com.askfirst.common.store.FlagsStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Cache FLAGS 30 giây trong một execution environment. Đọc lỗi thì dùng bản cũ (hoặc mặc định). */
final class FlagsCache {

    static final Duration TTL = Duration.ofSeconds(30);

    private final FlagsStore store;
    private final Clock clock;
    private Flags cached;
    private Instant loadedAt = Instant.MIN;

    FlagsCache(FlagsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    synchronized Flags get() {
        Instant now = clock.instant();
        if (cached == null || Duration.between(loadedAt, now).compareTo(TTL) >= 0) {
            try {
                cached = store.load();
            } catch (RuntimeException e) {
                if (cached == null) {
                    cached = FlagsStore.DEFAULTS;
                }
            }
            loadedAt = now;
        }
        return cached;
    }
}
