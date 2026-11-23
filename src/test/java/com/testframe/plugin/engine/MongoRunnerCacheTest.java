package com.testframe.plugin.engine;

import com.testframe.plugin.format.MongoCall;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mongo client cache is bounded. A run request names its own sources, so the set of URIs the
 * plugin sees is caller-controlled: without a bound, every distinct URI would leave a client — a
 * connection pool and its monitor threads — alive until the plugin shuts down.
 *
 * <p>No server is needed: the URIs point at a closed loopback port, so each call fails fast at its
 * server-selection timeout and the cache still grows exactly as it would for a reachable one.
 */
class MongoRunnerCacheTest {

    private static final MongoCall FIND = MongoCall.parse("find", "products", "[{}]");

    /** Nothing listens on the discard port, so server selection gives up at the card timeout. */
    private static String unreachableUri(int n) {
        return "mongodb://127.0.0.1:1/db" + n;
    }

    @Test
    void theSameUriReusesOneClient() {
        MongoRunner runner = new MongoRunner();
        try {
            for (int i = 0; i < 3; i++) {
                assertThrows(RuntimeException.class, () -> runner.run(unreachableUri(0), FIND, 100));
            }
            assertEquals(1, runner.clientCount());
        } finally {
            runner.close();
        }
        assertEquals(0, runner.clientCount(), "close() releases every client");
    }

    @Test
    void theCacheStaysBoundedWhenEveryCallNamesANewUri() {
        MongoRunner runner = new MongoRunner();
        try {
            int calls = MongoRunner.MAX_CLIENTS * 3;
            for (int i = 0; i < calls; i++) {
                String uri = unreachableUri(i);
                assertThrows(RuntimeException.class, () -> runner.run(uri, FIND, 100));
            }
            assertTrue(runner.clientCount() <= MongoRunner.MAX_CLIENTS,
                    "the cache held " + runner.clientCount() + " clients after " + calls + " distinct URIs");
        } finally {
            runner.close();
        }
    }

    @Test
    void aDifferentTimeoutForTheSameUriIsItsOwnClient() {
        MongoRunner runner = new MongoRunner();
        try {
            assertThrows(RuntimeException.class, () -> runner.run(unreachableUri(1), FIND, 100));
            assertThrows(RuntimeException.class, () -> runner.run(unreachableUri(1), FIND, 200));
            assertEquals(2, runner.clientCount(), "the timeouts are client settings, so they cannot share one");
        } finally {
            runner.close();
        }
    }

    @Test
    void aUriThatNamesNoDatabaseIsRefusedBeforeAnythingConnects() {
        assertEquals("products", MongoRunner.databaseOf("mongodb://localhost:27017/products"));
        for (String uri : new String[]{"mongodb://localhost:27017", "mongodb://localhost:27017/",
                "not a uri"}) {
            assertThrows(IllegalArgumentException.class, () -> MongoRunner.databaseOf(uri),
                    () -> uri + " was accepted");
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MongoRunner.databaseOf("mongodb://localhost:27017"));
        assertTrue(e.getMessage().contains("must name a database"), e.getMessage());
    }
}
