package com.testframe.plugin.engine;

import com.testframe.plugin.Json;
import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.CardResult;
import com.testframe.plugin.model.Operation;
import com.testframe.plugin.model.SourceConnection;
import com.testframe.plugin.model.Step;
import io.github.jsonassert.JsonataAssertionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which host a request card may send the source's headers to. An operation's {@code path} comes
 * from an OpenAPI document fetched over the network, so it is untrusted, and a source's headers can
 * hold credentials from a {@code .private.yaml} layer: an operation that points at another host is
 * refused before anything is sent (agents/specs/runner-protocol.md, "Security"). A card's own
 * absolute endpoint keeps the documented behaviour — sent as is, with no source headers.
 */
class RunEngineRequestTargetTest {

    private static final String SECRET = "Sup3rS3cretApiKey";
    private static final String API_BASE = "http://product-api.local:18081";

    private MongoRunner mongo;
    private RecordingCaller http;
    private RunEngine engine;

    @BeforeEach
    void start() {
        mongo = new MongoRunner();
        http = new RecordingCaller();
        engine = new RunEngine(mongo, http, new JsonataAssertionService(), Json.MAPPER);
    }

    @AfterEach
    void stop() {
        http.close();
        mongo.close();
    }

    private static Card operationCard(String openapiId) {
        return new Card("getLeak", "Get leak", "openapi", "product-api", "restapi", null, null, openapiId,
                2000, "");
    }

    private static RunEngine.RunContext context(String apiBase, String operationPath) {
        return new RunEngine.RunContext("local",
                Map.of("openapi/product-api", SourceConnection.openapi(apiBase,
                        Map.of("authorization", "Bearer " + SECRET),
                        List.of(new Operation("leak", "GET", operationPath, "leak")))),
                Map.of(), Map.of());
    }

    private CardResult run(RunEngine.RunContext ctx, Card card) {
        RunEngine.FrameOutcome outcome = engine.runFrame(List.of(new Step(1, List.of(card))), ctx,
                RunControl.withDeadline(null), RunListener.NONE);
        return outcome.results().getFirst();
    }

    @Test
    void anOperationThatPointsAtAnotherHostIsRefusedBeforeAnythingIsSent() {
        CardResult result = run(context(API_BASE, "http://attacker.example/collect"), operationCard("leak"));

        assertEquals(CardResult.ERROR, result.status());
        assertTrue(result.output().contains("points to another host"), result.output());
        assertTrue(result.output().contains("http://attacker.example/collect"), result.output());
        assertEquals(List.of(), http.urls, "the request was sent to the other host");
    }

    /** Deny by default: another NAME for the same machine is still another host. */
    @Test
    void anOperationOnAnotherNameOfTheSameMachineIsRefused() {
        CardResult result = run(context(API_BASE, "http://localhost:18081/collect"), operationCard("leak"));

        assertEquals(CardResult.ERROR, result.status());
        assertEquals(List.of(), http.urls);
    }

    @Test
    void anOperationOnTheSourcesOwnHostIsSentWithItsHeaders() {
        CardResult result = run(context(API_BASE, "http://product-api.local:18081/api/products"), operationCard("leak"));

        assertEquals(CardResult.SUCCESS, result.status(), result.output());
        assertEquals(List.of("http://product-api.local:18081/api/products"), http.urls);
        assertEquals(Map.of("authorization", "Bearer " + SECRET), http.headers.getFirst());
    }

    @Test
    void aRelativeOperationPathIsJoinedToTheApiBase() {
        CardResult result = run(context(API_BASE, "/api/products"), operationCard("leak"));

        assertEquals(CardResult.SUCCESS, result.status(), result.output());
        assertEquals(List.of(API_BASE + "/api/products"), http.urls);
    }

    /**
     * The documented escape hatch: a card may name an absolute endpoint of its own, which is sent
     * as is — and precisely because it bypasses the source, none of the source's headers go with it.
     */
    @Test
    void aCardsOwnAbsoluteEndpointCarriesNoSourceHeaders() {
        for (String sourceName : new String[]{null, "product-api"}) {
            http.urls.clear();
            http.headers.clear();
            Card card = new Card("getOwn", "Get own", "openapi", sourceName, "restapi", null,
                    "GET http://anywhere.example/x", null, 2000, "");
            CardResult result = run(context(API_BASE, "/unused"), card);

            assertEquals(CardResult.SUCCESS, result.status(), result.output());
            assertEquals(List.of("http://anywhere.example/x"), http.urls, "sourceName=" + sourceName);
            assertEquals(List.of(Map.of()), http.headers,
                    "the source's credentials went with the card's own URL (sourceName=" + sourceName + ")");
        }
    }

    @Test
    void theSameHostRuleIsCaseInsensitiveAndRefusesAnythingUnusable() {
        assertTrue(RunEngine.sameHost("http://api.local:18081", "http://API.LOCAL:18081/x"));
        assertTrue(RunEngine.sameHost("https://api.local", "https://api.local:8443/x"), "the port is not the boundary");
        assertFalse(RunEngine.sameHost("http://api.local", "http://evil.example/x"));
        assertFalse(RunEngine.sameHost("http://api.local", "http://localhost/x"));
        assertFalse(RunEngine.sameHost("http://api.local", "http://api.local.evil.example/x"), "a suffix is not a match");
        assertFalse(RunEngine.sameHost("http://api.local", "mailto:x@y"), "no host at all");
        assertFalse(RunEngine.sameHost("http://api.local", "not a url"));
        assertFalse(RunEngine.sameHost("not a url", "http://api.local/x"));
        assertFalse(RunEngine.sameHost(null, "http://api.local/x"));
        assertFalse(RunEngine.sameHost("http://api.local", null));
        assertFalse(RunEngine.sameHost("", ""), "unusable is not the same host");
    }

    /** Records what would have been sent and answers 200 without touching the network. */
    private static final class RecordingCaller extends HttpCaller {
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();

        @Override
        public Response call(String method, String url, Map<String, String> requestHeaders, String body,
                             long timeoutMs) {
            urls.add(url);
            headers.add(Map.copyOf(requestHeaders));
            return new Response(200, Map.of(), "{}", false, 1, null);
        }
    }
}
