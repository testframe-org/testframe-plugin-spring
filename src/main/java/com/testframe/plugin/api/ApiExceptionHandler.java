package com.testframe.plugin.api;

import com.testframe.plugin.engine.Limits;
import com.testframe.plugin.project.ProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * Maps request-level failures to the error body {@code {status, message, problems?}}
 * (agents/specs/runner-protocol.md, "Errors"), redacted. Card-level failures are never HTTP errors: they
 * come back as the card's {@code status: "error"} result.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiFilter.RequestTooLarge.class)
    public void tooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(response, 413, "request body larger than " + Limits.MAX_REQUEST_BYTES + " bytes", null,
                ApiResponses.redactor(request));
    }

    @ExceptionHandler(ProblemException.class)
    public void problem(ProblemException e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(response, e.status(), String.join("; ", e.problems()),
                e.problems().isEmpty() ? null : e.problems(), ApiResponses.redactor(request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public void media(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(response, 415, "the request body must be JSON", null, ApiResponses.redactor(request));
    }

    /** No route and no static resource: 404, without echoing the path. */
    @ExceptionHandler(NoResourceFoundException.class)
    public void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(response, 404, "not found", null, ApiResponses.redactor(request));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void method(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ApiResponses.error(response, 405, "method not allowed", null, ApiResponses.redactor(request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void unreadable(HttpMessageNotReadableException e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (causeOf(e, ApiFilter.RequestTooLarge.class)) {
            ApiResponses.error(response, 413, "request body larger than " + Limits.MAX_REQUEST_BYTES + " bytes", null,
                    ApiResponses.redactor(request));
            return;
        }
        ApiResponses.error(response, 400, "malformed JSON body", null, ApiResponses.redactor(request));
    }

    @ExceptionHandler(Exception.class)
    public void any(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        ApiResponses.error(response, 500, ApiResponses.redactor(request).apply(reason), null,
                ApiResponses.redactor(request));
    }

    private static boolean causeOf(Throwable thrown, Class<?> type) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}