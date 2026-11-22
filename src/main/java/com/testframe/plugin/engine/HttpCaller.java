package com.testframe.plugin.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes the HTTP calls of openapi request cards (java.net.http). Connect + read must happen
 * within the card timeout; the response body is cut at {@link Limits#MAX_RESPONSE_BODY_BYTES}.
 * Failures never throw: no response comes back as status 0 with the reason in {@code error}.
 */
public class HttpCaller {

    /** One exchange: {@code status} 0 means no response arrived ({@code error} says why). Header names are lower-case. */
    public record Response(int status, Map<String, String> headers, String body, boolean truncated, long elapsedMs,
                           String error) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final Map<Long, HttpClient> clients = new ConcurrentHashMap<>();

    public Response call(String method, String url, Map<String, String> headers, String body, long timeoutMs) {
        long started = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach((name, value) -> {
                if (!RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
                    builder.header(name, value);
                }
            });
            HttpResponse<InputStream> res = client(timeoutMs).send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            boolean truncated;
            try (InputStream in = res.body()) {
                bytes = in.readNBytes(Limits.MAX_RESPONSE_BODY_BYTES + 1);
                truncated = bytes.length > Limits.MAX_RESPONSE_BODY_BYTES;
            }
            String text = new String(bytes, 0, Math.min(bytes.length, Limits.MAX_RESPONSE_BODY_BYTES), StandardCharsets.UTF_8);
            Map<String, String> resHeaders = new LinkedHashMap<>();
            res.headers().map().forEach((name, values) -> {
                if (!name.startsWith(":")) {
                    resHeaders.put(name.toLowerCase(Locale.ROOT), String.join(", ", values));
                }
            });
            return new Response(res.statusCode(), resHeaders, text, truncated, elapsed(started), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, Map.of(), "", false, elapsed(started), "interrupted");
        } catch (IOException | IllegalArgumentException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            return new Response(0, Map.of(), "", false, elapsed(started), reason);
        }
    }

    private HttpClient client(long timeoutMs) {
        return clients.computeIfAbsent(timeoutMs, t -> HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(t))
                .build());
    }

    /** Headers that java.net.http refuses to set (the client manages them). */
    public static final List<String> RESTRICTED = List.of("connection", "content-length", "expect", "host", "upgrade");

    private static long elapsed(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1e6);
    }
}
