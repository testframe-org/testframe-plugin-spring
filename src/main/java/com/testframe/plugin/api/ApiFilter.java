package com.testframe.plugin.api;

import com.testframe.plugin.engine.Limits;
import com.testframe.plugin.engine.Redactor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Request gate of every request but the tour assets (see {@link #shouldNotFilter}): 503 while shutting down, 401 without the configured
 * {@code Authorization: Bearer {token}}, 403 for a {@code Host} that is not the plugin's own
 * loopback name when no token is configured, 413 for bodies over {@link Limits#MAX_REQUEST_BYTES}
 * (declared length, or counted while reading). The plugin sends no CORS headers.
 */
@Component
public class ApiFilter extends OncePerRequestFilter implements ApplicationListener<ContextClosedEvent> {

    /** Thrown while reading a body that exceeds the limit (mapped to 413). */
    public static class RequestTooLarge extends IOException {
        public RequestTooLarge() {
            super("request body larger than " + Limits.MAX_REQUEST_BYTES + " bytes");
        }
    }

    /**
     * The {@code Host} names a request may carry when no token is configured. Loopback binding
     * alone does not keep a browser out: a page that makes its own domain resolve to 127.0.0.1
     * (DNS rebinding) sends requests that look same-origin to it and reach this port. The
     * {@code Host} header is the only thing that tells them apart, so it must name the loopback
     * interface literally — no name is resolved to decide, since a name that resolves to the
     * loopback is exactly what rebinding uses.
     */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final byte[] token;
    private final Set<String> allowedHosts;
    private volatile boolean closing;

    public ApiFilter(@Value("${testframe.token:}") String token, @Value("${server.address:}") String address) {
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
        this.allowedHosts = allowedHosts(address);
    }

    /** The loopback literals, plus {@code server.address} when it is itself a loopback literal. */
    static Set<String> allowedHosts(String address) {
        Set<String> hosts = new LinkedHashSet<>(LOOPBACK_HOSTS);
        if (isLoopbackLiteral(address)) {
            hosts.add(address.trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(hosts);
    }

    /**
     * Whether {@code address} is an IP literal of the loopback range. A hostname is refused without
     * being resolved: only a literal is guaranteed not to reach the resolver.
     */
    private static boolean isLoopbackLiteral(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String value = address.trim();
        if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") && !value.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(value).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * The host of a {@code Host} header, lower-case and without its port; null when the header does
     * not name one. Only the two shapes a valid header has are accepted ({@code host[:port]} and
     * {@code [ipv6][:port]}), so a look-alike such as {@code [::1].attacker.example} is not a
     * loopback host.
     */
    static String hostOf(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.startsWith("[")) { // an IPv6 literal: [::1] or [::1]:18080
            int end = value.indexOf(']');
            String rest = end < 0 ? null : value.substring(end + 1);
            if (rest == null || !(rest.isEmpty() || rest.matches(":[0-9]+"))) {
                return null;
            }
            return value.substring(0, end + 1).toLowerCase(Locale.ROOT);
        }
        int colon = value.indexOf(':');
        if (colon >= 0 && !value.substring(colon + 1).matches("[0-9]+")) {
            return null;
        }
        String host = colon < 0 ? value : value.substring(0, colon);
        return host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        closing = true;
    }

    /**
     * Deny by default: every request is gated except a plain {@code GET}/{@code HEAD} of a tour
     * asset. The exemption is decided on the raw request URI with a strict whitelist (letters,
     * digits, {@code .}, {@code _}, {@code -} per segment, no dot-segments, no percent-escapes, no
     * {@code ;params}, no empty segments), so decoding cannot change what it names: Spring routes on
     * the decoded path without {@code ;params}, and a prefix check on the raw URI let
     * {@code /%61pi/v1/run} or {@code /api;x=1/v1/run} reach a controller ungated.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isTourAsset(request.getMethod(), request.getRequestURI());
    }

    private static final java.util.regex.Pattern TOUR_ASSET =
            java.util.regex.Pattern.compile("/tour(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*/?");

    static boolean isTourAsset(String method, String rawUri) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        return rawUri != null && TOUR_ASSET.matcher(rawUri).matches() && !rawUri.contains("..");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (closing) {
            ApiResponses.error(response, 503, "the plugin is shutting down", null, Redactor.structural());
            return;
        }
        if (token != null) {
            if (!authorized(request.getHeader("Authorization"))) {
                ApiResponses.error(response, 401, "missing or invalid Authorization: Bearer token", null,
                        Redactor.structural());
                return;
            }
        } else {
            String host = hostOf(request.getHeader("Host"));
            if (host == null || !allowedHosts.contains(host)) {
                // the refused Host is not echoed back: it is caller-controlled text
                ApiResponses.error(response, 403,
                        "the plugin only answers requests to its own loopback address", null, Redactor.structural());
                return;
            }
        }
        if (request.getContentLengthLong() > Limits.MAX_REQUEST_BYTES) {
            ApiResponses.error(response, 413, "request body larger than " + Limits.MAX_REQUEST_BYTES + " bytes", null,
                    Redactor.structural());
            return;
        }
        chain.doFilter(new Limited(request), response);
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(token, header.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Counts the body while it is read. */
    private static final class Limited extends HttpServletRequestWrapper {
        private ServletInputStream stream;

        Limited(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream in = super.getInputStream();
                stream = new ServletInputStream() {
                    private long count;

                    @Override
                    public int read() throws IOException {
                        int b = in.read();
                        if (b >= 0 && ++count > Limits.MAX_REQUEST_BYTES) {
                            throw new RequestTooLarge();
                        }
                        return b;
                    }

                    @Override
                    public int read(byte[] buf, int off, int len) throws IOException {
                        int n = in.read(buf, off, len);
                        if (n > 0 && (count += n) > Limits.MAX_REQUEST_BYTES) {
                            throw new RequestTooLarge();
                        }
                        return n;
                    }

                    @Override
                    public boolean isFinished() {
                        return in.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return in.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        in.setReadListener(listener);
                    }
                };
            }
            return stream;
        }
    }
}
