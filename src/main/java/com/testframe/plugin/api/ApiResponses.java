package com.testframe.plugin.api;

import com.testframe.plugin.Json;
import com.testframe.plugin.engine.Redactor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes JSON bodies straight to the servlet response (no content negotiation, so an error of
 * an NDJSON request is still a plain JSON body). Every body passes the request's redactor.
 */
public final class ApiResponses {

    /** Request attribute holding the {@link Redactor} of the request's {@code secrets}. */
    public static final String REDACTOR = ApiResponses.class.getName() + ".redactor";
    public static final String JSON = "application/json;charset=UTF-8";
    public static final String NDJSON = "application/x-ndjson";

    private ApiResponses() {
    }

    public static Redactor redactor(HttpServletRequest request) {
        Object r = request.getAttribute(REDACTOR);
        return r instanceof Redactor redactor ? redactor : Redactor.structural();
    }

    public static void json(HttpServletResponse response, int status, Object body, Redactor redactor) throws IOException {
        response.setStatus(status);
        response.setContentType(JSON);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Json.MAPPER.writeValue(response.getOutputStream(), redactor.tree(body, Json.MAPPER));
    }

    /** The error body {@code {status, message, problems?}} (agents/specs/runner-protocol.md, "Errors"). */
    public static void error(HttpServletResponse response, int status, String message, List<String> problems,
                             Redactor redactor) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", message);
        if (problems != null && !problems.isEmpty()) {
            body.put("problems", problems);
        }
        json(response, status, body, redactor);
    }
}
