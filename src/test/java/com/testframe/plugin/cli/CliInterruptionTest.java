package com.testframe.plugin.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.testframe.plugin.Json;
import com.testframe.plugin.PluginApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI interrupted by SIGINT/SIGTERM (agents/specs/test-tour.md, "Headless CLI"): the card in progress
 * is abandoned, the frames after it do not run (they are reported {@code not-run}), the reports
 * are still written with what ran, and the exit code is 3. The in-process tests trigger the same
 * {@link Cli.Interruption} the shutdown hook uses; the subprocess tests send the real signals.
 * The target binds an ephemeral port; every test stops it and kills its subprocess.
 */
class CliInterruptionTest {

    private static final String ENV = "local";
    private static final String SLOW = "a-slow";
    private static final String NEXT = "b-next";

    @TempDir
    Path root;

    private HttpServer target;
    private ExecutorService threads;
    private final CountDownLatch slowArrived = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger nextCalls = new AtomicInteger();
    private Path project;

    @BeforeEach
    void start() throws IOException {
        threads = Executors.newVirtualThreadPerTaskExecutor();
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.setExecutor(threads);
        target.createContext("/spec.json", this::serveSpec);
        target.createContext("/slow", exchange -> {
            slowArrived.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, "{\"slow\":true}");
        });
        target.createContext("/next", exchange -> {
            nextCalls.incrementAndGet();
            respond(exchange, "{\"next\":true}");
        });
        target.start();
        project = writeProject();
    }

    @AfterEach
    void stop() {
        release.countDown();
        target.stop(0);
        threads.shutdownNow();
    }

    // ----------------------------------------------------------------- in-process

    @Test
    void anInterruptionStopsTheRunWritesTheReportsAndExitsWith3() throws Exception {
        Path json = root.resolve("report.json");
        Path xml = root.resolve("junit.xml");
        AtomicReference<Cli.Interruption> interruption = new AtomicReference<>();
        Cli cli = new Cli();
        cli.installShutdownHook = false;
        cli.onInterruption = interruption::set;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        ExecutorService main = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> exit = main.submit(() -> cli.run(args(json, xml), print(out), print(err)));
            assertTrue(slowArrived.await(30, TimeUnit.SECONDS), "the slow card reached the target");
            interruption.get().interrupt();
            assertEquals(3, exit.get(30, TimeUnit.SECONDS), () -> err.toString(StandardCharsets.UTF_8));
        } finally {
            main.shutdownNow();
        }

        assertEquals(0, nextCalls.get(), "the frame after the interrupted one never ran");
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertTrue(stdout.contains("frame " + SLOW + ": interrupted"), stdout);
        assertTrue(stdout.contains("frame " + NEXT + ": not-run"), stdout);

        JsonNode report = Json.MAPPER.readTree(Files.readString(json));
        assertEquals("interrupted", report.path("result").asString());
        assertEquals(2, report.path("frames").size(), "every selected frame is in the report");
        JsonNode slow = report.path("frames").path(0);
        assertEquals(SLOW, slow.path("frame").asString());
        assertEquals("interrupted", slow.path("status").asString());
        JsonNode next = report.path("frames").path(1);
        assertEquals(NEXT, next.path("frame").asString());
        assertEquals("not-run", next.path("status").asString());
        assertEquals(1, next.path("counts").path("notRun").asInt());
        assertEquals(0, next.path("results").size());

        String junit = Files.readString(xml);
        assertTrue(junit.contains("<testsuite name=\"" + NEXT + "\" tests=\"1\" failures=\"0\" skipped=\"1\""), junit);
        assertTrue(junit.contains("name=\"getNext\" time=\"0.000\"><skipped/>"), junit);
    }

    @Test
    void anInterruptionBeforeTheFirstFrameRunsNothing() throws Exception {
        Path json = root.resolve("report.json");
        Cli cli = new Cli();
        cli.installShutdownHook = false;
        cli.onInterruption = Cli.Interruption::interrupt;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = cli.run(args(json, null), print(out), print(err));

        assertEquals(3, exit, () -> err.toString(StandardCharsets.UTF_8));
        assertFalse(Thread.currentThread().isInterrupted(), "the CLI leaves no pending interrupt behind");
        assertEquals(1, slowArrived.getCount(), "no card ran");
        assertEquals(0, nextCalls.get(), "no card ran");
        JsonNode report = Json.MAPPER.readTree(Files.readString(json));
        assertEquals("interrupted", report.path("result").asString());
        assertEquals("not-run", report.path("frames").path(0).path("status").asString());
        assertEquals("not-run", report.path("frames").path(1).path("status").asString());
    }

    // ----------------------------------------------------------------- real signals

    @Test
    void sigtermExitsWith3AndWritesTheReports() throws Exception {
        signalled(Process::destroy); // SIGTERM on Unix
    }

    @Test
    void sigintExitsWith3AndWritesTheReports() throws Exception {
        signalled(process -> {
            try {
                Process kill = new ProcessBuilder("kill", "-INT", Long.toString(process.pid())).inheritIO().start();
                assertEquals(0, kill.waitFor());
            } catch (IOException | InterruptedException e) {
                throw new AssertionError("cannot send SIGINT", e);
            }
        });
    }

    private void signalled(java.util.function.Consumer<Process> signal) throws Exception {
        Path json = root.resolve("report.json");
        Path xml = root.resolve("junit.xml");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new java.util.ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"),
                PluginApplication.class.getName()));
        command.addAll(List.of(args(json, xml)));
        Path log = root.resolve("cli.log");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            assertTrue(slowArrived.await(60, TimeUnit.SECONDS), () -> "the slow card reached the target: " + read(log));
            signal.accept(process);
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the CLI exits after the signal");
            assertEquals(3, process.exitValue(), () -> read(log));
        } finally {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        assertEquals(0, nextCalls.get(), "the frame after the interrupted one never ran");
        JsonNode report = Json.MAPPER.readTree(Files.readString(json));
        assertEquals("interrupted", report.path("result").asString());
        assertEquals("interrupted", report.path("frames").path(0).path("status").asString());
        assertEquals("not-run", report.path("frames").path(1).path("status").asString());
        assertTrue(Files.readString(xml).contains("<testsuite name=\"" + NEXT + "\""));
    }

    // -------------------------------------------------------------------- project

    private String[] args(Path json, Path xml) {
        List<String> args = new java.util.ArrayList<>(List.of("run", project.toString(), "--env", ENV,
                "--frames", SLOW + "," + NEXT, "--out", json.toString()));
        if (xml != null) {
            args.addAll(List.of("--out", xml.toString()));
        }
        return args.toArray(String[]::new);
    }

    private Path writeProject() throws IOException {
        Path dir = root.resolve("cli-project");
        Path testframe = dir.resolve("testframe");
        Files.createDirectories(testframe);
        Files.writeString(testframe.resolve("sources.yaml"), """
                environments: [%s]
                openapi:
                  echo-api:
                    base-url: http://127.0.0.1:%d/spec.json
                """.formatted(ENV, target.getAddress().getPort()));
        card(testframe, SLOW, "get-slow", "Get slow", "GET /slow", "slow");
        card(testframe, NEXT, "get-next", "Get next", "GET /next", "next");
        return dir;
    }

    private static void card(Path testframe, String frame, String file, String title, String endpoint, String id)
            throws IOException {
        Path step = testframe.resolve("frames").resolve(frame).resolve("step-1");
        Files.createDirectories(step);
        Files.writeString(step.resolve("flow.md"), """
                -------------------
                -------------------
                %s
                """.formatted(file));
        Files.writeString(step.resolve(file + ".md"), """
                -------------------
                title: %s
                source: openapi/echo-api
                subtype: restapi
                endpoint: %s
                openapi-id: %s
                timeout: 60000
                -------------------
                """.formatted(title, endpoint, id));
    }

    private void serveSpec(HttpExchange exchange) throws IOException {
        respond(exchange, """
                {
                  "openapi": "3.0.1",
                  "info": { "title": "echo", "version": "1" },
                  "servers": [{ "url": "http://127.0.0.1:%d" }],
                  "paths": {
                    "/slow": { "get": { "operationId": "slow" } },
                    "/next": { "get": { "operationId": "next" } }
                  }
                }""".formatted(target.getAddress().getPort()));
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static PrintStream print(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private static String read(Path log) {
        try {
            return Files.exists(log) ? Files.readString(log) : "";
        } catch (IOException e) {
            return e.toString();
        }
    }
}
