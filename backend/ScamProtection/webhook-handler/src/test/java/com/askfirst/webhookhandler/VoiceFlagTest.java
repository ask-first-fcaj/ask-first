package com.askfirst.webhookhandler;

import static org.assertj.core.api.Assertions.assertThat;

import com.askfirst.common.ai.AiContract.Flags;
import com.askfirst.common.store.FlagsStore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VoiceFlagTest {

    @Test
    @DisplayName("cache 30 giây rồi đọc lại")
    void cachesForThirtySeconds() {
        AtomicInteger loads = new AtomicInteger();
        AtomicLong now = new AtomicLong(1_000);
        VoiceFlag flag = new VoiceFlag(() -> {
            loads.incrementAndGet();
            return new Flags(true, true, true, true);
        }, now::get);

        assertThat(flag.getAsBoolean()).isTrue();
        now.addAndGet(29_000);
        assertThat(flag.getAsBoolean()).isTrue();
        assertThat(loads.get()).isEqualTo(1);

        now.addAndGet(2_000);
        flag.getAsBoolean();
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("lỗi đọc cờ thì coi như voice tắt và lần sau thử lại")
    void failureMeansDisabledAndIsNotCached() {
        AtomicInteger loads = new AtomicInteger();
        FlagsStore flaky = () -> {
            if (loads.incrementAndGet() == 1) {
                throw new IllegalStateException("DynamoDB lỗi");
            }
            return new Flags(true, true, true, true);
        };
        VoiceFlag flag = new VoiceFlag(flaky, () -> 5_000L);

        assertThat(flag.getAsBoolean()).isFalse();
        assertThat(flag.getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("mặc định của FlagsStore: voice tắt")
    void defaultsAreVoiceOff() {
        assertThat(new VoiceFlag(() -> FlagsStore.DEFAULTS).getAsBoolean()).isFalse();
    }
}
