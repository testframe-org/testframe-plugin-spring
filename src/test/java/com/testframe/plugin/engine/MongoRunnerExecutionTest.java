package com.testframe.plugin.engine;

import com.testframe.plugin.Json;
import com.testframe.plugin.format.MongoCall;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MongoRunner} against a real {@code mongod}: every subtype the catalog runs live, the
 * document cap, ObjectIds published as hex strings, and the write guards refusing to reach the
 * server at all. The server is started by this test on an ephemeral loopback port with a temporary
 * {@code --dbpath}, and stopped by it; when no {@code mongod} is available the class is skipped
 * (reported as skipped, never as passed).
 */
class MongoRunnerExecutionTest {

    private static final String DATABASE = "testframe";
    private static final long TIMEOUT_MS = 20_000;

    @TempDir
    static Path temp;

    private static Process mongod;
    private static MongoRunner runner;
    private static String uri;

    @BeforeAll
    static void startMongod() throws Exception {
        String binary = mongodBinary();
        Assumptions.assumeTrue(binary != null,
                "no mongod on PATH and $MONGOD is not set: the mongo execution tests are skipped");
        int port = freePort();
        Path dbPath = temp.resolve("dbdata");
        Files.createDirectories(dbPath);
        Path log = temp.resolve("mongod.log");
        mongod = new ProcessBuilder(binary, "--dbpath", dbPath.toString(), "--port", Integer.toString(port),
                "--bind_ip", "127.0.0.1")
                .redirectOutput(log.toFile())
                .redirectErrorStream(true)
                .start();
        uri = "mongodb://127.0.0.1:" + port + "/" + DATABASE;
        awaitAccepting(port, log);
        runner = new MongoRunner();
    }

    @AfterAll
    static void stopMongod() {
        if (runner != null) {
            runner.close();
        }
        if (mongod != null) {
            mongod.destroy();
            try {
                if (!mongod.waitFor(15, TimeUnit.SECONDS)) {
                    mongod.destroyForcibly();
                    mongod.waitFor(15, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mongod.destroyForcibly();
            }
        }
    }

    // ------------------------------------------------------------------------- reads

    @Test
    void findAppliesFilterProjectionSortAndLimit() throws Exception {
        seed("find-options", """
                [[{"nome": "A", "preco": 10, "ativo": true},
                  {"nome": "B", "preco": 30, "ativo": false},
                  {"nome": "C", "preco": 20, "ativo": true}]]
                """);

        JsonNode found = run("find", "find-options",
                "[{\"ativo\": true}, {\"nome\": 1, \"_id\": 0}, {\"sort\": {\"preco\": -1}, \"limit\": 1}]");

        assertEquals("find", found.path("operation").asString());
        assertEquals("find-options", found.path("collection").asString());
        assertEquals(1, found.path("count").asInt(), "the limit is applied");
        assertEquals("C", found.path("result").path(0).path("nome").asString());
        assertTrue(found.path("result").path(0).path("preco").isMissingNode(), "the projection is applied");
    }

    @Test
    void findWithoutALimitStopsAtTheDocumentCap() throws Exception {
        int over = Limits.MAX_MONGO_DOCUMENTS + 5;
        StringBuilder documents = new StringBuilder("[[");
        for (int i = 0; i < over; i++) {
            documents.append(i == 0 ? "" : ",").append("{\"n\":").append(i).append('}');
        }
        seed("find-cap", documents.append("]]").toString());

        JsonNode found = run("find", "find-cap", "[{}]");

        assertEquals(Limits.MAX_MONGO_DOCUMENTS, found.path("count").asInt(),
                "more documents than the cap came back");
        assertEquals(Limits.MAX_MONGO_DOCUMENTS, found.path("result").size());
    }

    @Test
    void findOnAnUnknownCollectionIsEmpty() throws Exception {
        JsonNode found = run("find", "never-written", "[{}]");
        assertEquals(0, found.path("count").asInt());
        assertTrue(found.path("result").isArray());
    }

    @Test
    void aggregationRunsItsPipeline() throws Exception {
        seed("aggregation", """
                [[{"nome": "A", "preco": 10, "ativo": true},
                  {"nome": "C", "preco": 20, "ativo": true},
                  {"nome": "B", "preco": 30, "ativo": false}]]
                """);

        JsonNode found = run("aggregation", "aggregation", """
                [[{"$match": {"ativo": true}}, {"$group": {"_id": null, "total": {"$sum": "$preco"}}}] ]
                """);

        assertEquals("aggregation", found.path("operation").asString());
        assertEquals(1, found.path("count").asInt());
        assertEquals(30, found.path("result").path(0).path("total").asInt());
    }

    // ------------------------------------------------------------------------- writes

    @Test
    void insertOnePublishesTheIdAsAHexString() throws Exception {
        JsonNode inserted = run("insertOne", "insert-one", "[{\"nome\": \"A\"}]");

        assertEquals(1, inserted.path("count").asInt());
        String id = inserted.path("result").path("insertedId").asString();
        assertEquals(24, id.length(), "an ObjectId comes out as its hex string, not as an object: " + id);
        assertTrue(id.matches("[0-9a-f]{24}"), id);

        JsonNode found = run("find", "insert-one", "[{\"_id\": {\"$oid\": \"" + id + "\"}}]");
        assertEquals("A", found.path("result").path(0).path("nome").asString());
        assertEquals(id, found.path("result").path(0).path("_id").asString(), "and reads back the same way");
    }

    @Test
    void insertManyPublishesEveryId() throws Exception {
        JsonNode inserted = run("insertMany", "insert-many", "[[{\"n\": 1}, {\"n\": 2}, {\"n\": 3}]]");

        assertEquals(3, inserted.path("count").asInt(), "the inserted count is what the card publishes");
        assertEquals(3, inserted.path("result").path("insertedIds").size());
        assertEquals(3, inserted.path("result").path("insertedCount").asInt());
    }

    @Test
    void updateOneReportsItsCounts() throws Exception {
        seed("update-one", "[[{\"nome\": \"A\", \"preco\": 10}, {\"nome\": \"B\", \"preco\": 20}]]");

        JsonNode updated = run("updateOne", "update-one", "[{\"nome\": \"A\"}, {\"$set\": {\"preco\": 11}}]");

        assertEquals(1, updated.path("result").path("matchedCount").asInt());
        assertEquals(1, updated.path("result").path("modifiedCount").asInt());
        assertTrue(updated.path("result").path("upsertedId").isNull());
        assertEquals(11, run("find", "update-one", "[{\"nome\": \"A\"}]").path("result").path(0)
                .path("preco").asInt());
    }

    @Test
    void updateWithAPipelineIsAccepted() throws Exception {
        seed("update-pipeline", "[[{\"nome\": \"A\", \"preco\": 10}]]");

        JsonNode updated = run("updateMany", "update-pipeline",
                "[{\"preco\": {\"$gte\": 10}}, [{\"$set\": {\"preco\": {\"$multiply\": [\"$preco\", 2]}}}]]");

        assertEquals(1, updated.path("result").path("modifiedCount").asInt());
        assertEquals(20, run("find", "update-pipeline", "[{}]").path("result").path(0).path("preco").asInt());
    }

    @Test
    void deletesReportTheirCounts() throws Exception {
        seed("deletes", "[[{\"n\": 1}, {\"n\": 2}, {\"n\": 3, \"keep\": true}]]");

        assertEquals(1, run("deleteOne", "deletes", "[{\"n\": 1}]").path("result").path("deletedCount").asInt());
        assertEquals(1, run("deleteMany", "deletes", "[{\"keep\": {\"$exists\": false}}]")
                .path("result").path("deletedCount").asInt());
        assertEquals(1, run("find", "deletes", "[{}]").path("count").asInt());
    }

    // ------------------------------------------------------------------------- guards

    /** The guards are enforced while the call is parsed, so a dangerous call never reaches a server. */
    @Test
    void aWriteWithoutAFilterNeverReachesTheServer() {
        assertThrows(IllegalArgumentException.class, () -> run("deleteMany", "guarded", "[{}]"));
        assertThrows(IllegalArgumentException.class, () -> run("updateOne", "guarded", "[{}, {\"$set\": {\"a\": 1}}]"));
        assertThrows(IllegalArgumentException.class,
                () -> run("aggregation", "guarded", "[[{\"$out\": \"elsewhere\"}]]"));
    }

    /** Decision P5 ({@code agents/decisions-taken.md}): mongo filters refuse server-side JavaScript. */
    @Test
    void serverSideJavascriptNeverReachesTheServer() {
        for (String filter : new String[]{"[{\"$where\": \"sleep(5000)\"}]",
                "[{\"$expr\": {\"$function\": {\"body\": \"function() { return true }\", \"args\": [], \"lang\": \"js\"}}}]"}) {
            assertThrows(IllegalArgumentException.class, () -> run("find", "guarded", filter), filter);
        }
    }

    @Test
    void aCollectionInTheSystemNamespaceIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run("find", "system.users", "[{}]"));
        assertTrue(e.getMessage().contains("system collections cannot be used"), e.getMessage());
    }

    // ------------------------------------------------------------------------ helpers

    private static JsonNode run(String subtype, String collection, String args) {
        try {
            return Json.MAPPER.readTree(runner.run(uri, MongoCall.parse(subtype, collection, args), TIMEOUT_MS));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void seed(String collection, String insertManyArgs) {
        JsonNode inserted = run("insertMany", collection, insertManyArgs);
        assertTrue(inserted.path("count").asInt() > 0, "the fixture was not written: " + inserted);
    }

    private static String mongodBinary() {
        String configured = System.getenv("MONGOD");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, "mongod");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitAccepting(int port, Path log) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException refused) {
                if (!mongod.isAlive()) {
                    throw new IllegalStateException("mongod exited with " + mongod.exitValue() + ": "
                            + Files.readString(log, StandardCharsets.UTF_8), refused);
                }
                TimeUnit.MILLISECONDS.sleep(200);
            }
        }
        throw new IllegalStateException("mongod did not accept connections on port " + port + ": "
                + Files.readString(log, StandardCharsets.UTF_8));
    }
}
