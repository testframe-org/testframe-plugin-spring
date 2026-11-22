package com.testframe.plugin.api;

import com.testframe.plugin.engine.Redactor;
import com.testframe.plugin.engine.RunControl;
import com.testframe.plugin.engine.RunEngine;
import com.testframe.plugin.engine.RunListener;
import com.testframe.plugin.format.Names;
import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.CardResult;
import com.testframe.plugin.model.SourceConnection;
import com.testframe.plugin.model.Step;
import com.testframe.plugin.project.ProblemException;
import com.testframe.plugin.project.ProjectRuns;
import io.github.jsonassert.AssertionResult;
import io.github.jsonassert.JsonataAssertionService;
import io.github.jsonassert.JsonataException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The self-contained run endpoints (docs/runner-protocol.md): {@code /run} (one frame, JSON or
 * NDJSON streaming), {@code /run/card} (one card with the editor's variables) and
 * {@code /assertions} (one jsonassert assertion). Every run is self-contained — the resolved
 * sources and the secrets arrive in the request, the plugin reads no project file and writes
 * nothing.
 */
@RestController
@RequestMapping("/api/v1")
public class RunController {

    public record RunRequest(String frame, String environment, List<Step> steps,
                             Map<String, SourceConnection> sources, List<String> secrets, Long deadlineMs) {
    }

    public record ResultCardInput(String varName, String code) {
    }

    public record CardRequest(String environment, Integer step, Card card, Map<String, Object> vars,
                              String payload, ResultCardInput resultCard, Map<String, SourceConnection> sources,
                              List<String> secrets) {
    }

    public record AssertionsRequest(String expression, Map<String, Object> input) {
    }

    private final RunEngine engine;
    private final JsonataAssertionService assertions;
    private final RunSlots slots;

    public RunController(RunEngine engine, JsonataAssertionService assertions, RunSlots slots) {
        this.engine = engine;
        this.assertions = assertions;
        this.slots = slots;
    }

    @PostMapping("/run")
    public void run(@RequestBody RunRequest body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        List<String> problems = new ArrayList<>();
        if (body == null) {
            problems.add("empty request body");
        } else {
            if (Names.isBlank(body.environment())) {
                problems.add("environment is required");
            }
            if (Names.isBlank(body.frame())) {
                problems.add("frame is required");
            }
            if (body.steps() == null) {
                problems.add("steps is required");
            } else {
                validateCards(body.steps(), problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new ProblemException(400, problems);
        }

        Redactor redactor = new Redactor(body.secrets());
        request.setAttribute(ApiResponses.REDACTOR, redactor);
        RunEngine.RunContext ctx = new RunEngine.RunContext(body.environment(),
                body.sources() == null ? Map.of() : body.sources(), Map.of(), Map.of());
        RunControl control = RunControl.withDeadline(body.deadlineMs());

        slots.acquire();
        try {
            if (wantsNdjson(request)) {
                streamRun(body.frame(), body.steps(), ctx, control, response, redactor);
            } else {
                RunEngine.FrameOutcome outcome = engine.runFrame(body.steps(), ctx, control, RunListener.NONE);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("frame", body.frame());
                result.put("results", outcome.results());
                result.put("vars", outcome.vars());
                result.put("stopped", outcome.stopped());
                result.put("elapsedMs", outcome.elapsedMs());
                ApiResponses.json(response, 200, result, redactor);
            }
        } finally {
            slots.release();
        }
    }

    @PostMapping("/run/card")
    public void runCard(@RequestBody CardRequest body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (body == null) {
            throw new ProblemException(400, "empty request body");
        }
        List<String> problems = new ArrayList<>();
        if (Names.isBlank(body.environment())) {
            problems.add("environment is required");
        }
        if (body.card() == null) {
            problems.add("card is required");
        } else if (Names.isBlank(body.card().varName())) {
            problems.add("card.varName is required");
        }
        if (!problems.isEmpty()) {
            throw new ProblemException(400, problems);
        }

        Redactor redactor = new Redactor(body.secrets());
        request.setAttribute(ApiResponses.REDACTOR, redactor);
        RunEngine.RunContext ctx = new RunEngine.RunContext(body.environment(),
                body.sources() == null ? Map.of() : body.sources(), Map.of(), Map.of());
        RunEngine.ResultCard resultCard = body.resultCard() == null
                ? null : new RunEngine.ResultCard(body.resultCard().varName(), body.resultCard().code());
        RunControl control = RunControl.withDeadline(null);

        slots.acquire();
        try {
            RunEngine.CardOutcome outcome = engine.runCard(body.step() == null ? 1 : body.step(), body.card(),
                    body.vars(), body.payload(), resultCard, ctx, control);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result", outcome.result());
            result.put("vars", outcome.vars());
            ApiResponses.json(response, 200, result, redactor);
        } finally {
            slots.release();
        }
    }

    @PostMapping("/assertions")
    public void assertions(@RequestBody AssertionsRequest body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (body == null || Names.isBlank(body.expression())) {
            throw new ProblemException(400, "expression is required");
        }
        AssertionResult result;
        try {
            result = assertions.evaluate(body.expression(), body.input() == null ? Map.of() : body.input());
        } catch (JsonataException | IllegalArgumentException e) {
            throw new ProblemException(400, "malformed assertion expression: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        ApiResponses.json(response, 200, result, ApiResponses.redactor(request));
    }

    // ----------------------------------------------------------------- helpers

    private void streamRun(String frame, List<Step> steps, RunEngine.RunContext ctx, RunControl control,
                           HttpServletResponse response, Redactor redactor) throws IOException {
        Ndjson ndjson = new Ndjson(response, redactor, control);
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("event", "frame-started");
        started.put("frame", frame);
        started.put("at", Instant.now().toString());
        started.put("cards", cardsOf(steps));
        ndjson.accept(started);

        List<CardResult> results = new ArrayList<>();
        RunEngine.FrameOutcome outcome = engine.runFrame(steps, ctx, control, new RunListener() {
            @Override
            public void cardStarted(int step, Card card, Instant at) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("event", "card-started");
                event.put("step", step);
                event.put("varName", RunEngine.varNameOf(card));
                event.put("at", at.toString());
                ndjson.accept(event);
            }

            @Override
            public void cardFinished(int step, CardResult result) {
                results.add(result);
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("event", "card-finished");
                event.put("step", step);
                event.put("result", result);
                ndjson.accept(event);
            }
        });

        Map<String, Object> finished = new LinkedHashMap<>();
        finished.put("event", "frame-finished");
        finished.put("status", ProjectRuns.statusOf(outcome.stopped()));
        finished.put("stopped", outcome.stopped());
        finished.put("elapsedMs", outcome.elapsedMs());
        finished.put("vars", outcome.vars());
        finished.put("counts", ProjectRuns.counts(results, totalCards(steps)));
        ndjson.accept(finished);
    }

    private void validateCards(List<Step> steps, List<String> problems) {
        Set<String> seen = new HashSet<>();
        for (int s = 0; s < steps.size(); s++) {
            Step step = steps.get(s);
            if (step == null || step.cards() == null) {
                continue;
            }
            for (int c = 0; c < step.cards().size(); c++) {
                Card card = step.cards().get(c);
                String varName = card == null ? null : RunEngine.varNameOf(card);
                if (card == null || Names.isBlank(varName)) {
                    problems.add("steps[" + s + "].cards[" + c + "].varName is required");
                    continue;
                }
                if (!seen.add(varName)) {
                    problems.add("duplicate var-name \"" + varName + "\" within the frame");
                }
            }
        }
    }

    private static List<Map<String, Object>> cardsOf(List<Step> steps) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Step step : steps == null ? List.<Step>of() : steps) {
            for (Card card : step.cards() == null ? List.<Card>of() : step.cards()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("step", step.number());
                entry.put("varName", RunEngine.varNameOf(card));
                entry.put("title", Names.isBlank(card.title()) ? RunEngine.varNameOf(card) : Names.trim(card.title()));
                entry.put("source", card.sourceRef());
                entry.put("subtype", card.subtype());
                if (card.collection() != null && !card.collection().isBlank()) {
                    entry.put("collection", card.collection());
                }
                if (card.endpoint() != null && !card.endpoint().isBlank()) {
                    entry.put("endpoint", card.endpoint());
                }
                if (card.openapiId() != null && !card.openapiId().isBlank()) {
                    entry.put("openapiId", card.openapiId());
                }
                entry.put("code", card.code() == null ? "" : card.code());
                cards.add(entry);
            }
        }
        return cards;
    }

    private static int totalCards(List<Step> steps) {
        int total = 0;
        for (Step step : steps == null ? List.<Step>of() : steps) {
            total += step.cards() == null ? 0 : step.cards().size();
        }
        return total;
    }

    private static boolean wantsNdjson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(ApiResponses.NDJSON);
    }
}