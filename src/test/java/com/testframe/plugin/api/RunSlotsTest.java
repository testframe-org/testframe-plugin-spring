package com.testframe.plugin.api;

import com.testframe.plugin.project.ProblemException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run occupies one slot for its whole duration; past {@code testframe.max-concurrent-runs} the
 * plugin answers 429 instead of queueing runs it cannot time (agents/specs/runner-protocol.md,
 * "Errors"). {@code api/RunSlotsHttpTest} proves the 429 reaches the wire.
 */
class RunSlotsTest {

    @Test
    void theRunAfterTheLastSlotIs429() {
        RunSlots slots = new RunSlots(2);
        slots.acquire();
        slots.acquire();
        ProblemException e = assertThrows(ProblemException.class, slots::acquire);
        assertEquals(429, e.status());
        assertTrue(e.getMessage().contains("too many runs in progress"), e.getMessage());
        assertTrue(e.getMessage().contains("testframe.max-concurrent-runs"), e.getMessage());
    }

    @Test
    void aReleasedSlotIsAvailableAgain() {
        RunSlots slots = new RunSlots(1);
        slots.acquire();
        assertEquals(429, assertThrows(ProblemException.class, slots::acquire).status());
        slots.release();
        assertDoesNotThrow(slots::acquire);
    }

    @Test
    void aRejectedRunOccupiesNoSlot() {
        RunSlots slots = new RunSlots(1);
        slots.acquire();
        assertThrows(ProblemException.class, slots::acquire);
        slots.release();
        assertDoesNotThrow(slots::acquire, "the refused acquire did not consume the only slot");
    }

    @Test
    void aConfigurationBelowOneStillAllowsOneRun() {
        for (int max : new int[]{0, -1}) {
            RunSlots slots = new RunSlots(max);
            assertDoesNotThrow(slots::acquire);
            assertEquals(429, assertThrows(ProblemException.class, slots::acquire).status());
        }
    }
}
