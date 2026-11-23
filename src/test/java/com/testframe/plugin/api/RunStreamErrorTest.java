package com.testframe.plugin.api;

import com.testframe.plugin.Json;
import com.testframe.plugin.engine.HttpCaller;
import com.testframe.plugin.engine.MongoRunner;
import com.testframe.plugin.engine.Redactor;
import com.testframe.plugin.engine.RunControl;
import com.testframe.plugin.engine.RunEngine;
import com.testframe.plugin.engine.RunListener;
import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.SourceConnection;
import com.testframe.plugin.model.Step;
import com.testframe.plugin.project.ProblemException;
import io.github.jsonassert.JsonataAssertionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A streaming {@code /run} has already committed a 200 by the time it fails, so the failure cannot
 * become an HTTP error: it must end the stream with an {@code error} event and a
 * {@code frame-finished} whose status is {@code interrupted} (agents/specs/test-tour.md, "Run
 * events"; agents/specs/runner-protocol.md, "Streaming variant"). Before the first line, a request
 * error is still a plain non-2xx JSON response. The failure message is redacted like everything
 * else that leaves the plugin.
 */
class RunStreamErrorTest {

    private static final String SECRET = "Sup3rS3cretApiKey";

    private ExplodingEngine engine;
    private RunController controller;

    @BeforeEach
    void start() {
        engine = new ExplodingEngine(new MongoRunner(), new HttpCaller());
        controller = new RunController(engine, new JsonataAssertionService(), new RunSlots(4));
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    private static RunController.RunRequest request() {
        Card card = new Card("getProducts", "Get products", "openapi", "product-api", "restapi", null,
                "GET /products", "getProducts", 2000, "");
        return new RunController.RunRequest("active-products", "local",
                List.of(new Step(1, List.of(card))),
                Map.of("openapi/product-api", SourceConnection.openapi("http://127.0.0.1:1", Map.of(), List.of())),
                List.of(SECRET), null);
    }

    private static MockHttpServletRequest ndjsonRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/run");
        request.addHeader("Accept", ApiResponses.NDJSON);
        return request;
    }

    @Test
    void aFailureAfterTheStreamStartedEndsItWithAnErrorEvent() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.run(request(), ndjsonRequest(), response);

        assertEquals(200, response.getStatus(), "the stream was already committed");
        assertTrue(response.getContentType().startsWith(ApiResponses.NDJSON), response.getContentType());
        String stream = response.getContentAsString();
        assertFalse(stream.contains(SECRET), "the failure message left the plugin unredacted: " + stream);

        List<JsonNode> events = events(stream);
        assertEquals(List.of("frame-started", "card-started", "error", "frame-finished"),
                events.stream().map(e -> e.path("event").asString()).toList(), stream);

        JsonNode error = events.get(2);
        assertEquals("the driver blew up holding " + Redactor.MASK, error.path("message").asString());

        JsonNode finished = events.get(3);
        assertEquals("interrupted", finished.path("status").asString());
        assertEquals("cancelled", finished.path("stopped").asString());
        assertEquals(1, finished.path("counts").path("notRun").asInt(), "the card that blew up never finished");
    }

    /** The cards that did finish are still counted, so the stream stays a usable record. */
    @Test
    void theEventsWrittenBeforeTheFailureAreKept() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.run(request(), ndjsonRequest(), response);

        List<JsonNode> events = events(response.getContentAsString());
        assertEquals("active-products", events.getFirst().path("frame").asString());
        assertEquals(1, events.getFirst().path("cards").size());
        assertEquals("getProducts", events.get(1).path("varName").asString());
    }

    @Test
    void aFailureBeforeTheStreamStartedIsStillAnHttpError() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/run");
        request.addHeader("Accept", ApiResponses.JSON);
        MockHttpServletResponse response = new MockHttpServletResponse();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> controller.run(request(), request, response));

        assertTrue(e.getMessage().contains(SECRET), "the JSON path reports the raw failure to the handler, "
                + "which redacts it: " + e.getMessage());
        assertEquals(0, response.getContentAsByteArray().length, "nothing was written to the response");
    }

    @Test
    void anInvalidRequestIsRefusedBeforeTheStreamStarts() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RunController.RunRequest body = new RunController.RunRequest("active-products", null, List.of(), Map.of(),
                List.of(), null);

        ProblemException e = assertThrows(ProblemException.class, () -> controller.run(body, ndjsonRequest(), response));

        assertEquals(400, e.status());
        assertEquals(List.of("environment is required"), e.problems());
        assertEquals(0, response.getContentAsByteArray().length, "no event was streamed");
    }

    // -------------------------------------------------------------------------- the seam

    /** Emits one event, then fails the way a driver would: with a message that holds a credential. */
    private static final class ExplodingEngine extends RunEngine {
        private final MongoRunner mongo;
        private final HttpCaller http;

        ExplodingEngine(MongoRunner mongo, HttpCaller http) {
            super(mongo, http, new JsonataAssertionService(), Json.MAPPER);
            this.mongo = mongo;
            this.http = http;
        }

        @Override
        public FrameOutcome runFrame(List<Step> steps, RunContext ctx, RunControl control, RunListener listener) {
            listener.cardStarted(1, steps.getFirst().cards().getFirst(), Instant.now());
            throw new IllegalStateException("the driver blew up holding " + SECRET);
        }

        void close() {
            http.close();
            mongo.close();
        }
    }

    private static List<JsonNode> events(String stream) {
        return stream.lines().filter(line -> !line.isBlank()).map(Json.MAPPER::readTree).toList();
    }
}
