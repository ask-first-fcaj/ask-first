package com.askfirst.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.askfirst.common.ai.AiContract.Flags;
import com.askfirst.common.store.FlagsStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FlagsCacheTest {

    private final Fakes.MutableClock clock = new Fakes.MutableClock();
    private final Fakes.MemFlags store = new Fakes.MemFlags();
    private final FlagsCache cache = new FlagsCache(store, clock);

    @Test
    void cachesFor30Seconds_thenReloads() {
        cache.get();
        clock.now = clock.now.plus(Duration.ofSeconds(29));
        cache.get();
        assertEquals(1, store.loads);

        store.flags = new Flags(false, true, false, true);
        clock.now = clock.now.plus(Duration.ofSeconds(2));

        assertFalse(cache.get().llmEnabled());
        assertEquals(2, store.loads);
    }

    @Test
    void readFailure_keepsPreviousValue_orDefaults() {
        FlagsCache failing = new FlagsCache(() -> {
            throw new IllegalStateException("dynamo down");
        }, clock);

        assertEquals(FlagsStore.DEFAULTS, failing.get());
        assertTrue(failing.get().llmEnabled());
    }
}
