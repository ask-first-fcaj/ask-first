package com.askfirst.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UlidTest {

    @Test
    void hasCrockfordAlphabetAnd26Chars() {
        String id = Ulid.next();

        assertEquals(26, id.length());
        assertTrue(id.matches("[0-9A-HJKMNP-TV-Z]{26}"));
    }

    @Test
    void sortsByCreationTime() {
        assertTrue(Ulid.next(1_000L).compareTo(Ulid.next(1_001L)) < 0);
        assertTrue(Ulid.next(1_700_000_000_000L).compareTo(Ulid.next(1_800_000_000_000L)) < 0);
    }

    @Test
    void sameMillisecondStillProducesDistinctIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(Ulid.next(1_700_000_000_000L));
        }
        assertEquals(1000, ids.size());
    }

    @Test
    void timePartIsDeterministic() {
        assertEquals(Ulid.next(1_700_000_000_000L).substring(0, 10), Ulid.next(1_700_000_000_000L).substring(0, 10));
        assertFalse(Ulid.next(1L).substring(0, 10).equals(Ulid.next(2L).substring(0, 10)));
    }

    @Test
    void rejectsOutOfRangeTime() {
        assertThrows(IllegalArgumentException.class, () -> Ulid.next(-1));
        assertThrows(IllegalArgumentException.class, () -> Ulid.next(1L << 48));
    }
}
