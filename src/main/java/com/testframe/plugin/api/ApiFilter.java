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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Request gate of {@code /api/v1/**}: 503 while shutting down, 401 without the configured
 * {@code Authorization: Bearer {token}}, 413 for bodies over {@link Limits#MAX_REQUEST_BYTES}
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

    private final byte[] token;
    private volatile boolean closing;

    public ApiFilter(@Value("${testframe.token:}") String token) {
        this.token = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        closing = true;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (closing) {
            ApiResponses.error(response, 503, "the plugin is shutting down", null, Redactor.structural());
            return;
        }
        if (token != null && !authorized(request.getHeader("Authorization"))) {
            ApiResponses.error(response, 401, "missing or invalid Authorization: Bearer token", null, Redactor.structural());
            return;
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
