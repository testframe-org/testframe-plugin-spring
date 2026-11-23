package com.testframe.plugin.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin refuses to start bound to an address other machines can reach unless a token gates
 * every request (agents/specs/runner-protocol.md, "Security"): without it, anything on the network
 * could use the plugin as a proxy, since it executes the HTTP calls and database operations it is
 * asked to.
 */
class BindGuardTest {

    @Test
    void aLoopbackAddressNeedsNoToken() {
        assertDoesNotThrow(() -> BindGuard.check("127.0.0.1", null));
        assertDoesNotThrow(() -> BindGuard.check("127.0.0.1", ""));
        assertDoesNotThrow(() -> BindGuard.check("127.0.0.1", "   "));
        assertDoesNotThrow(() -> BindGuard.check("127.0.0.2", null), "the whole 127.0.0.0/8 range is loopback");
        assertDoesNotThrow(() -> BindGuard.check("::1", null));
        assertDoesNotThrow(() -> BindGuard.check("localhost", null));
    }

    @Test
    void aNonLoopbackAddressWithoutATokenIsRefused() {
        for (String address : new String[]{"0.0.0.0", "192.168.1.10", "10.0.0.1", "8.8.8.8"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> BindGuard.check(address, null),
                    () -> address + " was accepted without a token");
            assertTrue(e.getMessage().contains(address), e.getMessage());
            assertTrue(e.getMessage().contains("testframe.token"), e.getMessage());
            assertThrows(IllegalStateException.class, () -> BindGuard.check(address, "  "),
                    () -> address + " was accepted with a blank token");
        }
    }

    @Test
    void anyAddressIsAllowedOnceATokenGatesTheApi() {
        assertDoesNotThrow(() -> BindGuard.check("0.0.0.0", "s3cret-token"));
        assertDoesNotThrow(() -> BindGuard.check("192.168.1.10", "s3cret-token"));
    }

    @Test
    void anUnsetAddressIsRefusedBecauseTheDefaultIsNotProvable() {
        for (String address : new String[]{null, "", "   "}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> BindGuard.check(address, null));
            assertEquals("server.address is not set: the plugin binds to 127.0.0.1 unless testframe.token is set",
                    e.getMessage());
        }
    }

    @Test
    void theSpringBeanAppliesTheSameRule() {
        assertDoesNotThrow(() -> new BindGuard("127.0.0.1", ""));
        assertThrows(IllegalStateException.class, () -> new BindGuard("0.0.0.0", ""));
        assertDoesNotThrow(() -> new BindGuard("0.0.0.0", "s3cret-token"));
    }
}
