package com.testframe.plugin.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code Host} gate over a real socket, with no token configured — the shape a DNS-rebinding
 * page meets: the plugin listens on the loopback interface, the browser resolves the attacker's
 * name to 127.0.0.1 and the request arrives with the attacker's {@code Host}. Nothing but a
 * loopback {@code Host} is served, no CORS header is ever sent (so the browser cannot read a
 * response even when one is written), and the tour build stays public.
 * {@code api/ApiFilterTest} covers the same rules unit by unit; {@code api/ApiGateTest} covers the
 * token gate and the tour-asset whitelist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiFilterHostHttpTest {

    @LocalServerPort
    private int port;

    @Test
    void aReboundHostIsRefused() throws IOException {
        for (String host : new String[]{"attacker.example", "attacker.example:" + port,
                "127.0.0.1.attacker.example", "localhost.attacker.example:" + port}) {
            Response response = send("GET", "/api/v1/health", host, null);
            assertEquals(403, response.status(), () -> host + " reached the API: " + response.body());
            assertTrue(response.body().contains("loopback"), response.body());
            assertFalse(response.body().contains("capabilities"), host + " reached the health controller");
        }
    }

    @Test
    void theLoopbackHostIsServed() throws IOException {
        for (String host : new String[]{"127.0.0.1:" + port, "localhost:" + port, "localhost"}) {
            Response response = send("GET", "/api/v1/health", host, null);
            assertEquals(200, response.status(), () -> host + " was refused: " + response.body());
            assertTrue(response.body().contains("capabilities"), response.body());
        }
    }

    @Test
    void aPostFromAReboundHostIsRefusedBeforeItsBodyIsUsed() throws IOException {
        Response response = send("POST", "/api/v1/run", "attacker.example:" + port, null);
        assertEquals(403, response.status(), response.body());
        assertFalse(response.body().contains("\"results\""), response.body());
    }

    @Test
    void noCorsHeaderIsSentToABrowserOrigin() throws IOException {
        Response response = send("GET", "/api/v1/health", "127.0.0.1:" + port, "Origin: http://evil.example\r\n");
        assertEquals(200, response.status(), response.body());
        assertNoCors(response);
    }

    @Test
    void aPreflightGetsNoCorsHeaderEither() throws IOException {
        Response response = send("OPTIONS", "/api/v1/run", "127.0.0.1:" + port,
                "Origin: http://evil.example\r\nAccess-Control-Request-Method: POST\r\n");
        assertNoCors(response);
        assertFalse(response.body().contains("capabilities"), response.body());
    }

    @Test
    void aRefusedRequestCarriesNoCorsHeader() throws IOException {
        Response response = send("GET", "/api/v1/health", "attacker.example:" + port,
                "Origin: http://evil.example\r\n");
        assertEquals(403, response.status(), response.body());
        assertNoCors(response);
    }

    /** The screens are served by the plugin itself, so they load without a token and without the gate. */
    @Test
    void theTourBuildStaysPublic() throws IOException {
        Response index = send("GET", "/tour/index.html", "127.0.0.1:" + port, null);
        assertEquals(200, index.status(), index.body());
        Response entry = send("GET", "/tour", "127.0.0.1:" + port, null);
        assertEquals(200, entry.status(), entry.body());
    }

    // ------------------------------------------------------------------ helpers

    private record Response(int status, String headers, String body) {
    }

    private static void assertNoCors(Response response) {
        assertFalse(response.headers().toLowerCase(Locale.ROOT).contains("access-control-"),
                "a CORS header was sent: " + response.headers());
    }

    /** One HTTP/1.1 request with the {@code Host} header exactly as given. */
    private Response send(String method, String path, String host, String extraHeaders) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(15_000);
            String body = "POST".equals(method) ? "{\"frame\":\"f\",\"environment\":\"local\",\"steps\":[]}" : "";
            StringBuilder request = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("Accept: application/json\r\n");
            if (extraHeaders != null) {
                request.append(extraHeaders);
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
            String[] status = raw.lines().findFirst().orElse("").split(" ");
            int code = status.length > 1 ? Integer.parseInt(status[1]) : -1;
            int split = raw.indexOf("\r\n\r\n");
            return new Response(code, split < 0 ? "" : raw.substring(0, split), split < 0 ? "" : raw.substring(split + 4));
        }
    }
}
