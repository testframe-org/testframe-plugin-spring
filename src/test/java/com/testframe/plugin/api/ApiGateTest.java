package com.testframe.plugin.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request gate (`ApiFilter`) is deny-by-default: with a token configured, nothing but a
 * plain {@code GET}/{@code HEAD} of a tour asset is served without it. Spring decodes
 * percent-escapes and drops {@code ;params} before routing, so a gate keyed on the raw
 * {@code /api/} prefix was bypassed by {@code /%61pi/v1/run} or {@code /api;x=1/v1/run}. Requests
 * are written on a raw socket so no client normalises the path first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "testframe.token=" + ApiGateTest.TOKEN)
class ApiGateTest {

    static final String TOKEN = "gate-token-0123456789";

    @LocalServerPort
    private int port;

    /** Spellings that Spring routes to a controller: every one must be refused without the token. */
    @ParameterizedTest
    @ValueSource(strings = {
            "GET /api/v1/health",
            "GET /%61pi/v1/health",
            "GET /%61%70%69/v1/health",
            "GET /api;x=1/v1/health",
            "GET /api/v1;x=1/health",
            "GET /api/v1/health;x=1",
            "GET /API/v1/health",
            "GET /%2561pi/v1/health",
            "GET //api/v1/health",
            "GET /api//v1/health",
            "GET /api/v1/./health",
            "GET /tour/../api/v1/health",
            "GET /tour/..;/api/v1/health",
            "GET /tour;x=1/../api/v1/health",
            "GET /tour/%2e%2e/api/v1/health",
            "GET /tour/%2e%2e;/api/v1/health",
            "GET /tour/..%2fapi/v1/health",
            "GET /tour/..%5capi/v1/health",
            "POST /api/v1/run",
            "POST /%61pi/v1/run",
            "POST /api;x=1/v1/run",
            "POST /api/v1/run/card",
            "POST /%61pi/v1/assertions",
            "POST /api;jsessionid=1/v1/assertions",
            "GET /%61pi/v1/tour/projects",
            "POST /tour/index.html",
    })
    void nothingButATourAssetIsServedWithoutTheToken(String line) throws IOException {
        String[] parts = line.split(" ", 2);
        Response response = send(parts[0], parts[1], null);
        assertTrue(Set.of(400, 401).contains(response.status()),
                () -> line + " reached past the gate: " + response.status() + " " + response.body());
        assertFalse(response.body().contains("capabilities"), () -> line + " reached the health controller");
        assertFalse(response.body().contains("\"results\""), () -> line + " reached the run controller");
    }

    @Test
    void theTokenOpensTheApi() throws IOException {
        Response response = send("GET", "/api/v1/health", TOKEN);
        assertEquals(200, response.status(), response.body());
        assertTrue(response.body().contains("capabilities"), response.body());
    }

    @Test
    void aWrongTokenIsRefused() throws IOException {
        assertEquals(401, send("GET", "/%61pi/v1/health", TOKEN + "x").status());
        assertEquals(401, send("GET", "/api/v1/health", "").status());
    }

    @Test
    void anUnknownPathIsNotFoundNotAnInternalError() throws IOException {
        Response response = send("GET", "/API/v1/health", TOKEN);
        assertEquals(404, response.status(), response.body());
        assertTrue(response.body().contains("\"status\":404"), response.body());
    }

    @Test
    void theTourAssetsStayPublic() throws IOException {
        Response response = send("GET", "/tour/index.html", null);
        assertEquals(200, response.status(), response.body());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/tour", "/tour/", "/tour?project=product-test", "/tour/?project=product-test"})
    void theTourEntryServesTheIndexPageWithoutTheToken(String path) throws IOException {
        Response response = send("GET", path, null);
        assertEquals(200, response.status(), path + " " + response.body());
        assertTrue(response.body().contains("<div id=\"root\">") || response.body().contains("<script"), response.body());
    }

    // ------------------------------------------------------------------ helpers

    private record Response(int status, String body) {
    }

    /** One HTTP/1.1 request with the path exactly as given (no client-side normalisation). */
    private Response send(String method, String path, String token) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(10_000);
            String body = "POST".equals(method) ? "{}" : "";
            StringBuilder request = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1:").append(port).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("Accept: application/json\r\n");
            if (token != null) {
                request.append("Authorization: Bearer ").append(token).append("\r\n");
            }
            if (!body.isEmpty()) {
                request.append("Content-Type: application/json\r\n");
            }
            request.append("Content-Length: ").append(body.length()).append("\r\n\r\n").append(body);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String statusLine = raw.lines().findFirst().orElse("");
            String[] status = statusLine.split(" ");
            int code = status.length > 1 ? Integer.parseInt(status[1]) : -1;
            int split = raw.indexOf("\r\n\r\n");
            return new Response(code, split < 0 ? "" : raw.substring(split + 4));
        }
    }
}
