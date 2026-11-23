package com.testframe.plugin.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.testframe.plugin.Json;
import com.testframe.plugin.cli.Cli;
import com.testframe.plugin.engine.Redactor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The redaction acceptance test: a secret declared in a {@code sources.{env}.private.yaml} layer
 * and echoed back by an HTTP target appears in nothing that leaves the plugin — no response body,
 * no NDJSON stream event, no log line and no headless report (agents/specs/runner-protocol.md, "Security";
 * agents/specs/test-tour.md, "Redaction"). The project on disk, the target and the plugin are real; the
 * target binds an ephemeral port and this test shuts it down and removes the project it wrote.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.level.com.testframe.plugin=DEBUG")
class RedactionBoundaryTest {

    private static final String PROJECT = "echo-project";
    private static final String FRAME = "echo-secret";
    private static final String ENV = "local";
    /** The secret of the private layer; long enough to be substring-matched by the redactor. */
    private static final String SECRET = "Sup3rS3cretApiKey";
    private static final String NDJSON = "application/x-ndjson";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ExecutorService TARGET_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer TARGET = startTarget();
    private static final String TARGET_BASE = "http://127.0.0.1:" + TARGET.getAddress().getPort();
    private static final Path PROJECTS = writeProject();
    private static ListAppender<ILoggingEvent> logs;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        root().addAppender(logs);
    }

    /** Runs last: detaches the capture, asserts nothing was logged, then frees the target and the project. */
    @AfterAll
    static void stopTarget() {
        root().detachAppender(logs);
        List<String> leaked = logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(SECRET))
                .toList();
        try {
            assertTrue(leaked.isEmpty(), () -> "the secret reached a log line: " + leaked);
        } finally {
            TARGET.stop(0);
            TARGET_THREADS.shutdownNow();
            delete(PROJECTS);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("testframe.projects-dir", () -> PROJECTS.toString());
    }

    // ------------------------------------------------------------------ channels

    @Test
    void aTourRunStreamsNoSecretInAnyEvent() throws Exception {
        String stream = post("/api/v1/tour/projects/%s/frames/%s/run".formatted(PROJECT, FRAME),
                "{\"runId\":\"tour-1\",\"environment\":\"%s\"}".formatted(ENV), NDJSON);
        assertMasked(stream);
        List<JsonNode> events = events(stream);
        assertEquals("passed", event(events, "frame-finished").path("status").asString(),
                () -> "the run reached the target: " + stream);

        JsonNode result = event(events, "card-finished").path("result");
        assertEquals("success", result.path("status").asString());
        JsonNode body = result.path("detail").path("response").path("body");
        assertEquals(Redactor.MASK, body.path("apiKey").asString(), "the echoed secret is masked");
        assertEquals("mongodb://***@db:27017/products", body.path("dsn").asString(), "URI user-info is masked");
        assertEquals(Redactor.MASK, result.path("detail").path("request").path("headers").path("x-api-key").asString());
        // the private layer's header is already masked in the frame-started source list
        assertEquals(Redactor.MASK, event(events, "frame-started")
                .path("sources").path("openapi/echo-api").path("headers").path("x-api-key").asString());
    }

    @Test
    void aRunResponseBodyCarriesNoSecret() throws Exception {
        String body = post("/api/v1/run", runRequest(), ApiResponses.JSON);
        assertMasked(body);
        JsonNode json = Json.MAPPER.readTree(body);
        JsonNode result = json.path("results").path(0);
        assertEquals("success", result.path("status").asString(), () -> "the run reached the target: " + body);
        assertEquals(Redactor.MASK, result.path("detail").path("response").path("body").path("apiKey").asString());
        assertEquals(Redactor.MASK, result.path("detail").path("request").path("headers").path("x-api-key").asString());
        assertEquals(Redactor.MASK, json.path("vars").path("getEcho").path("apiKey").asString());
    }

    @Test
    void aStreamedRunCarriesNoSecret() throws Exception {
        String stream = post("/api/v1/run", runRequest(), NDJSON);
        assertMasked(stream);
        JsonNode finished = event(events(stream), "frame-finished");
        assertTrue(finished.path("stopped").isNull(), () -> "the run reached the target: " + stream);
        assertEquals(Redactor.MASK, finished.path("vars").path("getEcho").path("apiKey").asString());
    }

    @Test
    void theHeadlessReportsCarryNoSecret(@TempDir Path reports) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Path json = reports.resolve("report.json");
        Path xml = reports.resolve("junit.xml");
        int exit = Cli.main(new String[]{"run", PROJECTS.resolve(PROJECT).toString(), "--env", ENV,
                        "--out", json.toString(), "--out", xml.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, exit, () -> "the run passed: " + err.toString(StandardCharsets.UTF_8));

        assertNoSecret(out.toString(StandardCharsets.UTF_8));
        assertNoSecret(err.toString(StandardCharsets.UTF_8));
        assertNoSecret(Files.readString(xml));

        String report = Files.readString(json);
        assertMasked(report);
        JsonNode frame = Json.MAPPER.readTree(report).path("frames").path(0);
        assertEquals("passed", frame.path("status").asString(), report);
        assertEquals(Redactor.MASK, frame.path("results").path(0).path("detail")
                .path("response").path("body").path("apiKey").asString());
        assertEquals(Redactor.MASK, frame.path("sources").path("openapi/echo-api")
                .path("headers").path("x-api-key").asString());
    }

    // -------------------------------------------------------------------- target

    /** A local HTTP target: it serves the source's OpenAPI spec and echoes the private header back. */
    private static HttpServer startTarget() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(TARGET_THREADS);
            server.createContext("/spec.json", RedactionBoundaryTest::serveSpec);
            server.createContext("/echo", RedactionBoundaryTest::serveEcho);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start the echo target", e);
        }
    }

    private static void serveSpec(HttpExchange exchange) throws IOException {
        respond(exchange, """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "echo", "version": "1" },
                  "servers": [{ "url": "%s" }],
                  "paths": { "/echo": { "get": { "operationId": "echo", "summary": "Echo the api key" } } }
                }""".formatted(TARGET_BASE));
    }

    /** Echoes the private header back, plus a URI whose user-info holds the same value. */
    private static void serveEcho(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestHeaders().getFirst("x-api-key");
        respond(exchange, """
                {
                  "apiKey": "%s",
                  "dsn": "mongodb://app:%s@db:27017/products"
                }""".formatted(key, key));
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ------------------------------------------------------------------- project

    /** The project the tour API and the CLI read; the secret lives only in the private layer. */
    private static Path writeProject() {
        try {
            Path root = Files.createTempDirectory("testframe-redaction-projects");
            Path testframe = root.resolve(PROJECT).resolve("testframe");
            Path step = testframe.resolve("frames").resolve(FRAME).resolve("step-1");
            Files.createDirectories(step);
            Files.writeString(testframe.resolve("sources.yaml"), """
                    environments: [%s]
                    openapi:
                      echo-api:
                        base-url: %s/spec.json
                    """.formatted(ENV, TARGET_BASE));
            Files.writeString(testframe.resolve("sources." + ENV + ".private.yaml"), """
                    openapi:
                      echo-api:
                        headers:
                          x-api-key: %s
                    """.formatted(SECRET));
            Files.writeString(step.resolve("flow.md"), """
                    -------------------
                    -------------------
                    get-echo
                    """);
            Files.writeString(step.resolve("get-echo.md"), """
                    -------------------
                    title: Get echo
                    source: openapi/echo-api
                    subtype: restapi
                    endpoint: GET /echo
                    openapi-id: echo
                    -------------------
                    """);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the test project", e);
        }
    }

    private static void delete(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot remove " + root, e);
        }
    }

    // ------------------------------------------------------------------- helpers

    /** The self-contained run request of the same card (agents/specs/runner-protocol.md, "Sources"). */
    private static String runRequest() {
        return """
                {
                  "frame": "%s",
                  "environment": "%s",
                  "steps": [{ "number": 1, "cards": [{
                      "varName": "getEcho", "title": "Get echo", "sourceType": "openapi", "sourceName": "echo-api",
                      "subtype": "restapi", "endpoint": "GET /echo", "openapiId": "echo", "code": "" }] }],
                  "sources": { "openapi/echo-api": {
                      "apiBase": "%s",
                      "headers": { "x-api-key": "%s" },
                      "operations": [{ "operationId": "echo", "method": "GET", "path": "/echo" }] } },
                  "secrets": ["%s"]
                }""".formatted(FRAME, ENV, TARGET_BASE, SECRET, SECRET);
    }

    private String post(String path, String body, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static List<JsonNode> events(String stream) {
        return stream.lines().filter(line -> !line.isBlank()).map(Json.MAPPER::readTree).toList();
    }

    private static JsonNode event(List<JsonNode> events, String name) {
        return events.stream().filter(e -> name.equals(e.path("event").asString())).findFirst().orElseThrow(
                () -> new AssertionError("no " + name + " event in " + events));
    }

    private static void assertNoSecret(String text) {
        assertFalse(text.contains(SECRET), () -> "the secret left the plugin: " + text);
    }

    /** No secret, and proof that the value the target echoed came back masked (not merely absent). */
    private static void assertMasked(String text) {
        assertNoSecret(text);
        assertTrue(text.contains(Redactor.MASK), () -> "nothing was masked: " + text);
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
