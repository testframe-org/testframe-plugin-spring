package com.testframe.plugin.api;

import com.testframe.plugin.engine.Limits;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request gate, unit by unit (agents/specs/runner-protocol.md, "Errors" and "Security"): 401
 * without the configured bearer token, 403 for a {@code Host} that is not the plugin's own loopback
 * name when no token gates the API (DNS rebinding), 413 over the request limit — by declared length
 * and while a chunked body is read — 503 while shutting down, and no CORS header on anything.
 * {@code api/ApiGateTest} covers the same gate end to end over a raw socket, where the path
 * spellings that Spring routes but a prefix check would miss are exercised.
 */
class ApiFilterTest {

    private static final String TOKEN = "gate-token-0123456789";
    private static final String LOOPBACK_HOST = "127.0.0.1:18080";

    // ----------------------------------------------------------------- Host (rebinding)

    @Test
    void withoutATokenOnlyTheLoopbackHostIsServed() throws Exception {
        ApiFilter filter = new ApiFilter("", "127.0.0.1");
        for (String host : List.of("localhost:18080", "127.0.0.1:18080", "127.0.0.1", "[::1]:18080",
                "LOCALHOST:18080", "LocalHost")) {
            assertEquals(200, send(filter, get("/api/v1/health", host)).getStatus(), () -> host + " was refused");
        }
    }

    /**
     * A page that makes its own domain resolve to 127.0.0.1 reaches the loopback port with a
     * {@code Host} the plugin does not own; without a token that header is the only defence.
     */
    @Test
    void withoutATokenAForeignHostIsRefused() throws Exception {
        ApiFilter filter = new ApiFilter("", "127.0.0.1");
        for (String host : List.of("attacker.example", "attacker.example:18080", "127.0.0.1.attacker.example",
                "localhost.attacker.example", "192.168.1.10:18080", "0.0.0.0:18080", "[::1].attacker.example")) {
            MockHttpServletResponse response = send(filter, get("/api/v1/health", host));
            assertEquals(403, response.getStatus(), () -> host + " was served");
            assertTrue(response.getContentAsString().contains("loopback"), response.getContentAsString());
        }
    }

    @Test
    void aMissingOrEmptyHostHeaderIsRefused() throws Exception {
        ApiFilter filter = new ApiFilter("", "127.0.0.1");
        assertEquals(403, send(filter, get("/api/v1/health", null)).getStatus(), "no Host header");
        assertEquals(403, send(filter, get("/api/v1/health", "  ")).getStatus(), "a blank Host header");
        assertEquals(403, send(filter, get("/api/v1/health", ":18080")).getStatus(), "an empty host with a port");
    }

    @Test
    void aConfiguredLoopbackAddressJoinsTheAllowlistButANonLoopbackOneDoesNot() throws Exception {
        assertEquals(200, send(new ApiFilter("", "127.0.0.5"), get("/api/v1/health", "127.0.0.5:18080")).getStatus());
        assertEquals(403, send(new ApiFilter("", "192.168.1.10"), get("/api/v1/health", "192.168.1.10:18080"))
                .getStatus(), "a reachable address still needs the token to be served");
    }

    @Test
    void withATokenTheHostIsNotCheckedBecauseTheTokenGatesTheRequest() throws Exception {
        ApiFilter filter = new ApiFilter(TOKEN, "0.0.0.0");
        assertEquals(401, send(filter, get("/api/v1/health", "plugin.example.com")).getStatus());

        MockHttpServletRequest authorized = get("/api/v1/health", "plugin.example.com");
        authorized.addHeader("Authorization", "Bearer " + TOKEN);
        assertEquals(200, send(filter, authorized).getStatus());
    }

    // ----------------------------------------------------------------------------- 401

    @Test
    void anythingButTheConfiguredBearerTokenIs401() throws Exception {
        ApiFilter filter = new ApiFilter(TOKEN, "127.0.0.1");
        for (String header : List.of("", "Basic " + TOKEN, "Bearer", "Bearer ", "bearer " + TOKEN,
                "Bearer " + TOKEN + "x", "Bearer " + TOKEN.substring(1), TOKEN)) {
            MockHttpServletRequest request = get("/api/v1/health", LOOPBACK_HOST);
            if (!header.isEmpty()) {
                request.addHeader("Authorization", header);
            }
            MockHttpServletResponse response = send(filter, request);
            assertEquals(401, response.getStatus(), () -> "\"" + header + "\" was accepted");
            assertTrue(response.getContentAsString().contains("\"status\":401"), response.getContentAsString());
        }
    }

    @Test
    void theExactTokenIsAcceptedWhateverTheAddress() throws Exception {
        ApiFilter filter = new ApiFilter(TOKEN, "127.0.0.1");
        MockHttpServletRequest request = get("/api/v1/health", LOOPBACK_HOST);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        assertEquals(200, send(filter, request).getStatus());
    }

    // ----------------------------------------------------------------------------- 413

    @Test
    void aDeclaredBodyOverTheLimitIs413BeforeItIsRead() throws Exception {
        ApiFilter filter = new ApiFilter("", "127.0.0.1");
        MockHttpServletRequest request = post("/api/v1/run", LOOPBACK_HOST);
        request.setContent(new byte[(int) Limits.MAX_REQUEST_BYTES + 1]);

        MockHttpServletResponse response = send(filter, request);

        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("larger than " + Limits.MAX_REQUEST_BYTES + " bytes"),
                response.getContentAsString());
    }

    @Test
    void aBodyAtTheLimitIsLetThrough() throws Exception {
        ApiFilter filter = new ApiFilter("", "127.0.0.1");
        MockHttpServletRequest request = post("/api/v1/run", LOOPBACK_HOST);
        request.setContent(new byte[(int) Limits.MAX_REQUEST_BYTES]);

        assertEquals(200, send(filter, request).getStatus());
    }

    /** A chunked body declares no length, so the only way to hold the limit is to count while reading. */
    @Test
    void anUndeclaredBodyOverTheLimitIsRefusedWhileItIsRead() throws Exception {
        ApiFilter filter = new ApiFilter("", "127.0.0.1");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/run") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.addHeader("Host", LOOPBACK_HOST);
        request.setContentType("application/json");
        request.setContent(new byte[(int) Limits.MAX_REQUEST_BYTES + 1]);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        FilterChain readsTheBody = (req, res) -> {
            try {
                req.getInputStream().readAllBytes();
            } catch (Throwable t) {
                thrown.set(t);
            }
        };
        filter.doFilter(request, new MockHttpServletResponse(), readsTheBody);

        assertInstanceOf(ApiFilter.RequestTooLarge.class, thrown.get(),
                "reading an oversized body must fail: " + thrown.get());
        assertTrue(thrown.get().getMessage().contains(String.valueOf(Limits.MAX_REQUEST_BYTES)),
                thrown.get().getMessage());
    }

    @Test
    void anUndeclaredBodyUnderTheLimitIsReadWhole() throws Exception {
        ApiFilter filter = new ApiFilter("", "127.0.0.1");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/run") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.addHeader("Host", LOOPBACK_HOST);
        request.setContentType("application/json");
        request.setContent("{\"frame\":\"f\"}".getBytes(StandardCharsets.UTF_8));

        AtomicReference<String> read = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                read.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));

        assertEquals("{\"frame\":\"f\"}", read.get());
    }

    // ----------------------------------------------------------------------------- 503

    @Test
    void everyGatedRequestIs503WhileShuttingDown() throws Exception {
        ApiFilter filter = new ApiFilter(TOKEN, "127.0.0.1");
        MockHttpServletRequest request = get("/api/v1/health", LOOPBACK_HOST);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        assertEquals(200, send(filter, request).getStatus(), "before the shutdown it serves");

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            filter.onApplicationEvent(new ContextClosedEvent(context));
        }

        MockHttpServletRequest after = get("/api/v1/health", LOOPBACK_HOST);
        after.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = send(filter, after);
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("shutting down"), response.getContentAsString());
    }

    // -------------------------------------------------------------------------- no CORS

    @Test
    void noCorsHeaderIsEverSent() throws Exception {
        ApiFilter filter = new ApiFilter("", "127.0.0.1");
        MockHttpServletRequest request = get("/api/v1/health", LOOPBACK_HOST);
        request.addHeader("Origin", "http://evil.example");
        request.addHeader("Access-Control-Request-Method", "POST");

        MockHttpServletResponse response = send(filter, request);

        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(response.getHeaderNames().stream().noneMatch(name -> name.startsWith("Access-Control-")),
                response.getHeaderNames().toString());
    }

    @Test
    void aRefusedRequestCarriesNoCorsHeaderEither() throws Exception {
        ApiFilter filter = new ApiFilter("", "127.0.0.1");
        MockHttpServletRequest request = get("/api/v1/health", "attacker.example");
        request.addHeader("Origin", "http://evil.example");

        MockHttpServletResponse response = send(filter, request);

        assertEquals(403, response.getStatus());
        assertTrue(response.getHeaderNames().stream().noneMatch(name -> name.startsWith("Access-Control-")),
                response.getHeaderNames().toString());
    }

    // -------------------------------------------------------------------- tour assets

    @Test
    void aTourAssetIsNotGatedAtAll() throws Exception {
        ApiFilter filter = new ApiFilter(TOKEN, "127.0.0.1");
        MockHttpServletResponse response = send(filter, get("/tour/index.html", LOOPBACK_HOST));
        assertEquals(200, response.getStatus(), "the tour build is public so the screens can load");
    }

    // ------------------------------------------------------------------------ helpers

    private static MockHttpServletRequest get(String path, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (host != null) {
            request.addHeader("Host", host);
        }
        request.addHeader("Accept", "application/json");
        return request;
    }

    private static MockHttpServletRequest post(String path, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Host", host);
        request.setContentType("application/json");
        return request;
    }

    private static MockHttpServletResponse send(ApiFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
