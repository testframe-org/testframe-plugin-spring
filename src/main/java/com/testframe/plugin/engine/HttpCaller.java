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

/**
 * Executes the HTTP calls of openapi request cards and fetches the OpenAPI documents of tour and
 * CLI runs (java.net.http). Connect + read must happen within the card timeout; the response body
 * is cut at {@link Limits#MAX_RESPONSE_BODY_BYTES}. Card calls never throw: no response comes back
 * as status 0 with the reason in {@code error}.
 *
 * <p>Redirects are followed here, not by the client ({@link HttpClient.Redirect#NEVER}), and only
 * to the <b>same host</b>, never downgrading https to http, at most {@link #MAX_REDIRECTS} times.
 * A source's headers can hold credentials from a {@code .private.yaml} layer and an HTTP client
 * resends them on every hop, so a target that answers 3xx towards another host is not followed:
 * the 3xx comes back as the response and the card reports it.
 */
public class HttpCaller implements AutoCloseable {

    /** One exchange: {@code status} 0 means no response arrived ({@code error} says why). Header names are lower-case. */
    public record Response(int status, Map<String, String> headers, String body, boolean truncated, long elapsedMs,
                           String error) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** How many redirects one call follows before the last 3xx is returned as the response. */
    static final int MAX_REDIRECTS = 5;
    /** How long an OpenAPI document fetch may take. */
    static final long SPEC_TIMEOUT_MS = 10_000;

    /** Headers that java.net.http refuses to set (the client manages them). */
    public static final List<String> RESTRICTED = List.of("connection", "content-length", "expect", "host", "upgrade");

    /** One client for the caller's lifetime: the card timeout is a per-request timeout, not a client setting. */
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofMillis(Limits.MAX_CARD_TIMEOUT_MS))
            .build();

    /**
     * Fetches an OpenAPI document: {@code GET} with the source's headers, {@value #SPEC_TIMEOUT_MS}
     * ms, 2xx required, and the body capped at {@link Limits#MAX_RESPONSE_BODY_BYTES} — a document
     * larger than the cap is refused instead of being read into memory and parsed.
     */
    public String fetchDocument(String url, Map<String, String> headers) throws IOException {
        Map<String, String> withAccept = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        withAccept.putIfAbsent("Accept", "application/json");
        Response response = call("GET", url, withAccept, null, SPEC_TIMEOUT_MS);
        if (response.status() == 0) {
            throw new IOException(response.error());
        }
        if (response.truncated()) {
            throw new IOException("the document is larger than " + Limits.MAX_RESPONSE_BODY_BYTES + " bytes");
        }
        if (!response.ok()) {
            throw new IOException("HTTP " + response.status());
        }
        return response.body();
    }

    /** One call, following same-host redirects; never throws. */
    public Response call(String method, String url, Map<String, String> headers, String body, long timeoutMs) {
        long started = System.nanoTime();
        URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException e) {
            return new Response(0, Map.of(), "", false, elapsed(started), reason(e));
        }
        String currentMethod = method;
        String currentBody = body;
        for (int hop = 0; ; hop++) {
            Response response;
            try {
                response = exchange(currentMethod, target, headers, currentBody, timeoutMs, started);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Response(0, Map.of(), "", false, elapsed(started), "interrupted");
            } catch (IOException | IllegalArgumentException e) {
                return new Response(0, Map.of(), "", false, elapsed(started), reason(e));
            }
            URI next = hop < MAX_REDIRECTS ? redirect(target, response) : null;
            if (next == null) {
                return response;
            }
            if (becomesGet(response.status(), currentMethod)) {
                currentMethod = "GET";
                currentBody = null;
            }
            target = next;
        }
    }

    /** Sends one request and reads its body, cut at {@link Limits#MAX_RESPONSE_BODY_BYTES}. */
    private Response exchange(String method, URI target, Map<String, String> headers, String body, long timeoutMs,
                              long started) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(timeoutMs))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach((name, value) -> {
            if (!RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
                builder.header(name, value);
            }
        });
        HttpResponse<InputStream> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
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
    }

    /**
     * Where a 3xx points, when following it is allowed: same host, no https → http downgrade.
     * Null returns the 3xx as the response, which is what keeps a source's headers from reaching
     * another host.
     */
    private static URI redirect(URI from, Response response) {
        int status = response.status();
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            return null;
        }
        String location = response.headers().get("location");
        if (location == null || location.isBlank()) {
            return null;
        }
        URI next;
        try {
            next = from.resolve(location.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (next.getHost() == null || from.getHost() == null || !from.getHost().equalsIgnoreCase(next.getHost())) {
            return null;
        }
        return "https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())
                ? null : next;
    }

    /** 303, and 301/302 on a POST, become a bodyless GET (what a browser does); 307/308 repeat the call. */
    private static boolean becomesGet(int status, String method) {
        return status == 303 || ((status == 301 || status == 302) && "POST".equalsIgnoreCase(method));
    }

    @Override
    public void close() {
        client.close();
    }

    private static String reason(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static long elapsed(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1e6);
    }
}
