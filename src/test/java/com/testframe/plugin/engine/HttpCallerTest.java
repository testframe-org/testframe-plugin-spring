package com.testframe.plugin.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HTTP caller follows a redirect only to the same host, and an OpenAPI document is refused
 * once it is larger than the response cap. Both rules protect the same thing: a source's headers
 * can hold credentials from a {@code .private.yaml} layer and an HTTP client resends them on every
 * hop, so a target that redirects elsewhere must not be followed with them
 * (agents/specs/runner-protocol.md, "Security"). The test target binds an ephemeral loopback port
 * and is stopped by this test.
 */
class HttpCallerTest {

    private static final String SECRET_HEADER = "x-api-key";
    private static final String SECRET = "Sup3rS3cretApiKey";
    private static final long TIMEOUT_MS = 10_000;

    private HttpCaller caller;
    private HttpServer server;
    private ExecutorService threads;
    private String base;

    private final AtomicInteger leakHits = new AtomicInteger();
    private final AtomicInteger loopHits = new AtomicInteger();
    private final AtomicReference<String> methodAtTarget = new AtomicReference<>();
    private final AtomicReference<String> keyAtTarget = new AtomicReference<>();
    private final AtomicReference<String> bodyAtTarget = new AtomicReference<>();
    private final AtomicReference<String> hostAtTarget = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        caller = new HttpCaller();
        threads = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/final", exchange -> {
            methodAtTarget.set(exchange.getRequestMethod());
            keyAtTarget.set(exchange.getRequestHeaders().getFirst(SECRET_HEADER));
            bodyAtTarget.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "final");
        });
        server.createContext("/same-host", exchange -> redirect(exchange, "/final"));
        // another NAME for the same machine, and a name that does not resolve: both are another host
        server.createContext("/cross-host-name", exchange ->
                redirect(exchange, "http://localhost:" + server.getAddress().getPort() + "/leak"));
        server.createContext("/cross-host", exchange -> redirect(exchange, "http://other-host.invalid/leak"));
        server.createContext("/leak", exchange -> {
            leakHits.incrementAndGet();
            respond(exchange, 200, "leaked");
        });
        server.createContext("/loop", exchange -> {
            loopHits.incrementAndGet();
            redirect(exchange, "/loop");
        });
        server.createContext("/see-other", exchange -> redirect(exchange, 303, "/final"));
        server.createContext("/huge", exchange -> huge(exchange));
        server.createContext("/missing", exchange -> respond(exchange, 404, "nope"));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        threads.shutdownNow();
        caller.close();
    }

    // --------------------------------------------------------------------- redirects

    @Test
    void aSameHostRedirectIsFollowedWithTheSourcesHeaders() {
        HttpCaller.Response response = caller.call("GET", base + "/same-host", headers(), null, TIMEOUT_MS);
        assertEquals(200, response.status(), response.error());
        assertEquals("final", response.body());
        assertEquals(SECRET, keyAtTarget.get(), "the source header travels to the same host");
    }

    @Test
    void aRedirectToAnotherHostIsNotFollowed() {
        HttpCaller.Response response = caller.call("GET", base + "/cross-host", headers(), null, TIMEOUT_MS);
        assertEquals(302, response.status(), "the 3xx comes back instead of being followed: " + response.body());
        assertEquals("http://other-host.invalid/leak", response.headers().get("location"));
        assertEquals(0, leakHits.get(), "the other host was called");
    }

    /** Deny by default: another NAME for the machine the plugin runs on is still another host. */
    @Test
    void aRedirectToAnotherNameOfTheSameMachineIsNotFollowed() {
        HttpCaller.Response response = caller.call("GET", base + "/cross-host-name", headers(), null, TIMEOUT_MS);
        assertEquals(302, response.status(), "the 3xx comes back instead of being followed: " + response.body());
        assertEquals(0, leakHits.get(), "the source header reached the redirected host");
    }

    @Test
    void redirectsStopAfterTheLimit() {
        HttpCaller.Response response = caller.call("GET", base + "/loop", headers(), null, TIMEOUT_MS);
        assertEquals(302, response.status(), "the last 3xx is the response");
        assertEquals(HttpCaller.MAX_REDIRECTS + 1, loopHits.get(), "one request plus the followed redirects");
    }

    @Test
    void aSeeOtherTurnsTheCallIntoABodylessGet() {
        HttpCaller.Response response = caller.call("POST", base + "/see-other", headers(), "{\"a\":1}", TIMEOUT_MS);
        assertEquals(200, response.status(), response.error());
        assertEquals("GET", methodAtTarget.get());
        assertEquals("", bodyAtTarget.get(), "the body is not replayed");
    }

    // ------------------------------------------------------------------- the spec cap

    @Test
    void aDocumentOverTheResponseCapIsRefusedNotRead() {
        IOException e = assertThrows(IOException.class, () -> caller.fetchDocument(base + "/huge", Map.of()));
        assertTrue(e.getMessage().contains("larger than " + Limits.MAX_RESPONSE_BODY_BYTES + " bytes"),
                e.getMessage());
    }

    @Test
    void aSmallDocumentIsFetchedAsIs() throws IOException {
        assertEquals("final", caller.fetchDocument(base + "/final", Map.of()));
    }

    @Test
    void aNon2xxDocumentIsAnError() {
        IOException e = assertThrows(IOException.class, () -> caller.fetchDocument(base + "/missing", Map.of()));
        assertEquals("HTTP 404", e.getMessage());
    }

    @Test
    void aDocumentBehindACrossHostRedirectIsNotFetched() {
        assertThrows(IOException.class, () -> caller.fetchDocument(base + "/cross-host", Map.of()));
        assertEquals(0, leakHits.get(), "the spec fetch followed a redirect to another host");
    }

    // ------------------------------------------------------------------------ failures

    @Test
    void noResponseComesBackAsStatusZeroWithTheReason() {
        HttpCaller.Response refused = caller.call("GET", "http://127.0.0.1:1/nothing", headers(), null, 2000);
        assertEquals(0, refused.status());
        assertTrue(refused.error().contains("ConnectException"), refused.error());

        HttpCaller.Response invalid = caller.call("GET", "http://exa mple.invalid/", headers(), null, 2000);
        assertEquals(0, invalid.status());
        assertTrue(invalid.error().contains("IllegalArgumentException"), invalid.error());
    }

    @Test
    void aBodyOverTheCapIsTruncatedAndMarked() throws IOException {
        server.createContext("/truncating", exchange -> huge(exchange));
        HttpCaller.Response response = caller.call("GET", base + "/truncating", headers(), null, TIMEOUT_MS);
        assertEquals(200, response.status(), response.error());
        assertTrue(response.truncated());
        assertEquals(Limits.MAX_RESPONSE_BODY_BYTES, response.body().length());
    }

    /** {@code Host} and {@code Content-Length} are the client's: a card cannot spoof them. */
    @Test
    void restrictedHeadersAreDroppedNotSent() {
        server.createContext("/host", exchange -> {
            hostAtTarget.set(exchange.getRequestHeaders().getFirst("Host"));
            respond(exchange, 200, "ok");
        });
        Map<String, String> headers = new LinkedHashMap<>(headers());
        headers.put("Host", "evil.example");
        headers.put("Content-Length", "999999");
        headers.put("Upgrade", "h2c");
        HttpCaller.Response response = caller.call("GET", base + "/host", headers, null, TIMEOUT_MS);
        assertEquals(200, response.status(), response.error());
        assertEquals("127.0.0.1:" + server.getAddress().getPort(), hostAtTarget.get(),
                "the request went out under a spoofed Host");
    }

    // ------------------------------------------------------------------------ helpers

    private static Map<String, String> headers() {
        return Map.of(SECRET_HEADER, SECRET);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        redirect(exchange, 302, location);
    }

    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    /** Streams one byte over the response cap, without holding it in memory. */
    private static void huge(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, 0);
        byte[] chunk = new byte[64 * 1024];
        Arrays.fill(chunk, (byte) 'a');
        long total = Limits.MAX_RESPONSE_BODY_BYTES + 1L;
        try (OutputStream out = exchange.getResponseBody()) {
            while (total > 0) {
                int n = (int) Math.min(chunk.length, total);
                out.write(chunk, 0, n);
                total -= n;
            }
        }
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    @Test
    void theCallerIsReusableAcrossCalls() {
        List<Integer> statuses = List.of(
                caller.call("GET", base + "/final", headers(), null, TIMEOUT_MS).status(),
                caller.call("GET", base + "/final", headers(), null, TIMEOUT_MS).status());
        assertEquals(List.of(200, 200), statuses);
    }
}
