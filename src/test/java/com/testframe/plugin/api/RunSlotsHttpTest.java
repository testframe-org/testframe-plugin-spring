package com.testframe.plugin.api;

import com.testframe.plugin.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Past {@code testframe.max-concurrent-runs} the plugin answers 429 instead of queueing runs it
 * cannot time (agents/specs/runner-protocol.md, "Errors"). {@code api/RunSlotsTest} covers the slot
 * accounting itself; this proves the 429 reaches the wire with the one error body of the API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "testframe.max-concurrent-runs=1")
class RunSlotsHttpTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final String RUN = """
            {"frame":"empty","environment":"local","steps":[{"number":1,"cards":[]}],"sources":{},"secrets":[]}
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private RunSlots slots;

    @Test
    void aRunBeyondTheLastSlotAnswers429() throws IOException, InterruptedException {
        slots.acquire();
        try {
            HttpResponse<String> refused = post(RUN);
            assertEquals(429, refused.statusCode(), refused.body());
            JsonNode body = Json.MAPPER.readTree(refused.body());
            assertEquals(429, body.path("status").asInt());
            assertTrue(body.path("message").asString().contains("too many runs in progress"), refused.body());
        } finally {
            slots.release();
        }

        assertEquals(200, post(RUN).statusCode(), "the slot is free again");
    }

    @Test
    void aRefusedRunAnswersWithTheSameErrorBodyAsAnyOther() throws IOException, InterruptedException {
        slots.acquire();
        try {
            JsonNode body = Json.MAPPER.readTree(post(RUN).body());
            assertEquals("too many runs in progress (testframe.max-concurrent-runs)", body.path("message").asString());
            assertEquals(1, body.path("problems").size(), body.toString());
        } finally {
            slots.release();
        }
    }

    private HttpResponse<String> post(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/run"))
                .header("Content-Type", "application/json")
                .header("Accept", ApiResponses.JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
