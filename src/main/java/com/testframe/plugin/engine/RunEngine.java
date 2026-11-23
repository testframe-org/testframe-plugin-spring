package com.testframe.plugin.engine;

import com.testframe.plugin.format.Catalog;
import com.testframe.plugin.format.CodeRules;
import com.testframe.plugin.format.JsonassertCode;
import com.testframe.plugin.format.MongoCall;
import com.testframe.plugin.format.Names;
import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.CardResult;
import com.testframe.plugin.model.Operation;
import com.testframe.plugin.model.SourceConnection;
import com.testframe.plugin.model.Step;
import io.github.jsonassert.AssertionResult;
import io.github.jsonassert.JsonassertParser;
import io.github.jsonassert.JsonataAssertionService;
import io.github.jsonassert.JsonataEvaluator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The card runner (agents/specs/runner-protocol.md; execution order in agents/specs/project-format.md):
 * {@code {{bindings}}}, openapi requests ({@link HttpCaller}), mongo calls ({@link MongoRunner}),
 * jsonassert scripts and conditionals (the Java engine), rawvalue literals and simulated types,
 * publishing one variable per card, writing request/mongo results into the next JSON card and
 * stopping at the first error card. Every card runs in its own (virtual) thread under a
 * watchdog: the card timeout, the run deadline and cancellation abort it. The engine never
 * reads the project: every input comes in the call.
 */
public class RunEngine {

    /** The environment's resolved sources by {@code {type}/{name}}, plus problems known before the run. */
    public record RunContext(String environment, Map<String, SourceConnection> sources,
                             Map<String, String> sourceErrors, Map<String, String> cardProblems) {

        public RunContext {
            sources = sources == null ? Map.of() : sources;
            sourceErrors = sourceErrors == null ? Map.of() : sourceErrors;
            cardProblems = cardProblems == null ? Map.of() : cardProblems;
        }
    }

    /** {@code stopped}: null, error, deadline or cancelled. */
    public record FrameOutcome(List<CardResult> results, Map<String, Object> vars, String stopped, long elapsedMs) {
    }

    /** A single card run; {@code result} is null when the run was cancelled. */
    public record CardOutcome(CardResult result, Map<String, Object> vars, String stopped) {
    }

    /** The first JSON card after the executed card in its step. */
    public record ResultCard(String varName, String code) {
    }

    public static final String STOP_ERROR = "error";
    public static final String STOP_DEADLINE = "deadline";
    public static final String STOP_CANCELLED = "cancelled";

    private static final Pattern ENDPOINT = Pattern.compile("^(GET|POST|PUT|PATCH|DELETE)\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABSOLUTE = Pattern.compile("(?i)^https?://.*");
    private static final String SIMULATED = "Simulated: this source type is not executed live yet.";
    private static final long WATCHDOG_GRACE_MS = 2000;
    private static final TypeReference<Object> ANY = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();

    private final MongoRunner mongo;
    private final HttpCaller http;
    private final JsonataAssertionService assertions;
    private final ObjectMapper json;

    public RunEngine(MongoRunner mongo, HttpCaller http, JsonataAssertionService assertions, ObjectMapper json) {
        this.mongo = mongo;
        this.http = http;
        this.assertions = assertions;
        this.json = json;
    }

    // ------------------------------------------------------------------- runs

    /**
     * Runs a frame: steps in order, cards in order. A request body comes from the nearest JSON
     * card before the card; a request or mongo success is also written into the first JSON card
     * after it (published, and its rewritten body comes back in that card's {@code code}). A
     * {@code conditional} that is false skips the rest of its step; the run stops at the first
     * error card, at the deadline or when cancelled.
     */
    public FrameOutcome runFrame(List<Step> steps, RunContext ctx, RunControl control, RunListener listener) {
        long started = System.nanoTime();
        Instant runStart = Instant.now();
        Map<String, Object> vars = new LinkedHashMap<>();
        List<CardResult> results = new ArrayList<>();
        List<Step> all = steps == null ? List.of() : steps;
        for (int s = 0; s < all.size(); s++) {
            Step step = all.get(s);
            int number = step.number() == null ? s + 1 : step.number();
            List<Card> cards = new ArrayList<>(step.cards() == null ? List.of() : step.cards());
            List<String> sentCode = cards.stream().map(RunEngine::code).toList();
            for (int i = 0; i < cards.size(); i++) {
                if (control.isCancelled()) {
                    return new FrameOutcome(results, vars, STOP_CANCELLED, elapsedMs(started));
                }
                Card card = cards.get(i);
                String payloadCode = null;
                for (int p = i - 1; p >= 0; p--) {
                    if (isJsonCard(cards.get(p))) {
                        payloadCode = code(cards.get(p));
                        break;
                    }
                }
                int target = -1;
                for (int q = i + 1; q < cards.size(); q++) {
                    if (isJsonCard(cards.get(q))) {
                        target = q;
                        break;
                    }
                }
                ResultCard resultCard = target < 0 ? null : new ResultCard(varNameOf(cards.get(target)), code(cards.get(target)));
                Instant at = Instant.now();
                listener.cardStarted(number, card, at);
                Executed executed = guarded(card, number, payloadCode, resultCard, vars, ctx, control, started, runStart, at);
                if (executed == null) {
                    return new FrameOutcome(results, vars, STOP_CANCELLED, elapsedMs(started));
                }
                vars.putAll(executed.published());
                if (executed.resultCardCode() != null && target >= 0) {
                    cards.set(target, cards.get(target).withCode(executed.resultCardCode()));
                }
                CardResult result = executed.result();
                if (!Objects.equals(code(cards.get(i)), sentCode.get(i))) {
                    result = result.withCode(code(cards.get(i)));
                }
                boolean skipRest = isConditional(card) && !result.isError()
                        && Boolean.FALSE.equals(vars.get(varNameOf(card)));
                List<Card> rest = skipRest ? cards.subList(i + 1, cards.size()) : List.of();
                if (skipRest && result.detail() != null && result.detail().get("condition") instanceof Map<?, ?> cond) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> condition = (Map<String, Object>) cond;
                    condition.put("skipped", rest.stream().map(RunEngine::varNameOf).toList());
                }
                results.add(result);
                listener.cardFinished(number, result);
                if (result.isError()) {
                    return new FrameOutcome(results, vars, executed.stop() == null ? STOP_ERROR : executed.stop(),
                            elapsedMs(started));
                }
                if (skipRest) {
                    for (Card skipped : rest) {
                        CardResult r = skipped(skipped, number, varNameOf(card), started);
                        results.add(r);
                        listener.cardFinished(number, r);
                    }
                    break;
                }
            }
        }
        return new FrameOutcome(results, vars, null, elapsedMs(started));
    }

    /**
     * Runs one card (the editor's Run button) with the variables the editor already has: a
     * conditional just reports its boolean, a successful request/mongo run publishes
     * {@code resultCard}'s variable.
     */
    public CardOutcome runCard(int step, Card card, Map<String, Object> inputVars, String payload, ResultCard resultCard,
                               RunContext ctx, RunControl control) {
        long started = System.nanoTime();
        Map<String, Object> vars = inputVars == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputVars);
        Instant at = Instant.now();
        Executed executed = guarded(card, step, payload, resultCard, vars, ctx, control, started, at, at);
        if (executed == null) {
            return new CardOutcome(null, vars, STOP_CANCELLED);
        }
        vars.putAll(executed.published());
        return new CardOutcome(executed.result(), vars, executed.stop());
    }

    // ---------------------------------------------------------------- watchdog

    /** What one card produced; the run thread applies it (abandoned workers cannot touch the run's state). */
    private record Executed(CardResult result, Map<String, Object> published, String resultCardCode, String stop) {
    }

    private Executed guarded(Card card, int step, String payloadCode, ResultCard resultCard, Map<String, Object> vars,
                             RunContext ctx, RunControl control, long runStarted, Instant runStart, Instant at) {
        Label label = label(card, step);
        long cardStarted = System.nanoTime();
        long offset = Math.max(0, at.toEpochMilli() - runStart.toEpochMilli());
        if (control.deadlineReached()) {
            return new Executed(error(label, "run deadline exceeded", "run deadline exceeded", at, offset, 0, Map.of()),
                    Map.of(), null, STOP_DEADLINE);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(vars);
        long timeout = timeoutOf(card);
        Future<Executed> future = WORKERS.submit(() -> execute(card, label, payloadCode, resultCard, snapshot, ctx,
                timeout, at, offset));
        control.running(future);
        // +1 ms: remainingMs() truncates, so a wait clamped by the deadline would end just before
        // it and the card would be reported as its own timeout instead of the run deadline
        long wait = Math.min(timeout + WATCHDOG_GRACE_MS, control.remainingMs() + 1);
        try {
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            long elapsed = elapsedMs(cardStarted);
            if (control.deadlineReached()) {
                return new Executed(error(label, "run deadline exceeded", "run deadline exceeded", at, offset, elapsed, Map.of()),
                        Map.of(), null, STOP_DEADLINE);
            }
            String message = "timed out after " + timeout + "ms";
            return new Executed(error(label, message, message, at, offset, elapsed, Map.of()), Map.of(), null, null);
        } catch (CancellationException e) {
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            control.cancel();
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            if (control.isCancelled()) {
                return null;
            }
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = reason(cause);
            return new Executed(error(label, message, message, at, offset, elapsedMs(cardStarted), Map.of()), Map.of(), null, null);
        } finally {
            control.running(null);
        }
    }

    private long timeoutOf(Card card) {
        String type = Catalog.cardTypeOf(card.sourceType(), card.subtype());
        int ms = card.timeout() == null ? Catalog.defaultTimeout(type) : card.timeout();
        return Math.clamp(ms, Limits.MIN_CARD_TIMEOUT_MS, Limits.MAX_CARD_TIMEOUT_MS);
    }

    // ------------------------------------------------------------------ engine

    /** {@code [Step n] {title}} of a card. */
    private record Label(String varName, String prefix) {
    }

    private static Label label(Card card, int step) {
        String varName = varNameOf(card);
        String title = Names.isBlank(card.title()) ? varName : Names.trim(card.title());
        return new Label(varName, "[Step " + step + "] " + title + ": ");
    }

    /** Runs in a worker thread: reads {@code vars} (a snapshot), returns what the card publishes. */
    private Executed execute(Card card, Label label, String payloadCode, ResultCard resultCard, Map<String, Object> vars,
                             RunContext ctx, long timeout, Instant at, long offset) {
        long began = System.nanoTime();
        Ctx c = new Ctx(label, at, offset, began);
        String problem = ctx.cardProblems().get(label.varName());
        if (problem != null) {
            return c.fail(problem);
        }
        String sourceType = Names.trim(card.sourceType());
        String subtype = Names.trim(card.subtype());
        String kindProblem = sourceType.isEmpty() ? "the card has no type-source" : Catalog.subtypeProblem(sourceType, subtype);
        if (kindProblem != null) {
            return c.fail(sourceType.isEmpty() || Catalog.type(sourceType).isEmpty() ? kindProblem : "subtype " + kindProblem);
        }
        String code = code(card);
        String codeProblem = CodeRules.problem(sourceType, subtype, code);
        if (codeProblem != null) {
            return c.fail(sourceType + " " + codeProblem);
        }
        return switch (sourceType) {
            case "jsonassert" -> JsonassertCode.CONDITIONAL.equals(subtype)
                    ? runConditional(c, code, vars)
                    : runScript(c, code, vars);
            case "rawvalue" -> runRawValue(c, code, vars);
            case "openapi" -> runRequest(c, card, payloadCode, resultCard, vars, ctx, timeout);
            case "mongo" -> runMongo(c, card, subtype, code, resultCard, vars, ctx, timeout);
            default -> runSimulated(c, card, resultCard, ctx);
        };
    }

    /** Per-card bookkeeping: timing and result construction. */
    private final class Ctx {
        final Label label;
        final Instant at;
        final long offset;
        final long began;
        final Map<String, Object> detail = new LinkedHashMap<>();
        final Map<String, Object> published = new LinkedHashMap<>();
        final Map<String, Object> bindings = new LinkedHashMap<>();
        String resultCardCode;

        Ctx(Label label, Instant at, long offset, long began) {
            this.label = label;
            this.at = at;
            this.offset = offset;
            this.began = began;
        }

        Executed done(String status, String summary, String output) {
            if (!bindings.isEmpty()) {
                Map<String, Object> ordered = new LinkedHashMap<>();
                ordered.put("bindings", bindings);
                ordered.putAll(detail);
                detail.clear();
                detail.putAll(ordered);
            }
            if (!published.isEmpty()) {
                detail.put("published", published);
            }
            CardResult result = new CardResult(label.varName(), status, label.prefix() + summary, output == null ? "" : output,
                    at.toString(), offset, elapsedMs(began), null, null, detail);
            return new Executed(result, published, resultCardCode, null);
        }

        Executed fail(String message) {
            return done(CardResult.ERROR, message, message);
        }

        void publish(String varName, Object value) {
            published.put(varName, value);
        }
    }

    private Executed runRawValue(Ctx c, String code, Map<String, Object> vars) {
        if (Names.isBlank(code)) {
            return c.done(CardResult.SUCCESS, "OK (empty)", "");
        }
        Object parsed;
        try {
            parsed = parse(code);
        } catch (RuntimeException e) {
            return c.fail("invalid JSON: " + reason(e));
        }
        Object value = Bindings.substitute(parsed, vars, json, c.bindings);
        c.detail.put("value", value);
        c.publish(c.label.varName(), value);
        return c.done(CardResult.SUCCESS, "OK", pretty(value));
    }

    /**
     * A jsonassert {@code script}: statements in order against every variable published so far.
     * A failing assert fails the card; the last plain expression's value is the card's variable
     * (without one, the last assertion's outcome is).
     */
    private Executed runScript(Ctx c, String code, Map<String, Object> vars) {
        List<Map<String, Object>> statements = new ArrayList<>();
        c.detail.put("statements", statements);
        Object value = null;
        boolean hasValue = false;
        String summary = "OK";
        for (JsonassertCode.Statement statement : JsonassertCode.numbered(code)) {
            Map<String, Object> context = new LinkedHashMap<>(vars);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("line", statement.line());
            entry.put("statement", statement.text());
            statements.add(entry);
            if (JsonassertCode.isAssert(statement.text())) {
                entry.put("kind", "assert");
                AssertionResult outcome;
                try {
                    outcome = assertions.evaluate(statement.text(), context);
                } catch (RuntimeException e) {
                    entry.put("passed", false);
                    entry.put("error", reason(e));
                    return c.fail("assertion error: " + reason(e));
                }
                Map<String, Object> assertion = new LinkedHashMap<>();
                assertion.put("passed", outcome.passed());
                assertion.put("actual", outcome.actual());
                assertion.put("expected", outcome.expected());
                assertion.put("operator", outcome.operator());
                assertion.put("jsonata", outcome.jsonata());
                assertion.put("error", outcome.error());
                entry.putAll(assertion);
                summary = "assert " + (outcome.passed() ? "OK" : "FAILED assertion")
                        + " (" + json.writeValueAsString(outcome.actual()) + " " + outcome.operator() + " "
                        + json.writeValueAsString(outcome.expected()) + ")";
                if (!hasValue) {
                    value = assertion;
                }
                if (!outcome.passed()) {
                    c.publish(c.label.varName(), assertion);
                    return c.done(CardResult.ERROR, summary, pretty(assertion));
                }
            } else {
                entry.put("kind", "expression");
                try {
                    value = evaluate(statement.text(), context);
                    hasValue = true;
                    entry.put("value", value);
                } catch (RuntimeException e) {
                    entry.put("error", reason(e));
                    return c.fail("expression error: " + reason(e));
                }
            }
        }
        c.publish(c.label.varName(), value);
        return c.done(CardResult.SUCCESS, summary, pretty(value));
    }

    /** A jsonassert {@code conditional}: one boolean expression; a non-boolean or an evaluation error is a card error. */
    private Executed runConditional(Ctx c, String code, Map<String, Object> vars) {
        List<String> statements = JsonassertCode.statements(code);
        if (statements.isEmpty()) {
            return c.fail("the conditional has no expression");
        }
        String expression = statements.getFirst();
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("expression", expression);
        c.detail.put("condition", condition);
        Object value;
        try {
            value = evaluate(expression, new LinkedHashMap<>(vars));
        } catch (RuntimeException e) {
            return c.fail("condition error: " + reason(e));
        }
        condition.put("value", value);
        if (!(value instanceof Boolean decision)) {
            return c.fail("the condition must be boolean, got " + json.writeValueAsString(value));
        }
        condition.put("decision", decision ? "continue" : "skip-rest-of-step");
        condition.put("skipped", List.of());
        c.publish(c.label.varName(), decision);
        String summary = decision ? "condition true" : "condition false: skipping rest of step";
        return c.done(CardResult.SUCCESS, summary, String.valueOf(decision));
    }

    /** An openapi request card: the operation of its source, or an absolute endpoint sent as is. */
    private Executed runRequest(Ctx c, Card card, String payloadCode, ResultCard resultCard, Map<String, Object> vars,
                                RunContext ctx, long timeout) {
        String openapiId = Names.trim(card.openapiId());
        Matcher endpoint = ENDPOINT.matcher(Names.trim(card.endpoint()));
        boolean hasEndpoint = endpoint.matches();
        if (openapiId.isEmpty() && !hasEndpoint) {
            return c.fail("the card names no operation (openapi-id or endpoint \"METHOD /path\")");
        }
        String method = hasEndpoint ? endpoint.group(1).toUpperCase(Locale.ROOT) : null;
        String path = hasEndpoint ? endpoint.group(2) : null;
        Map<String, String> headers = new LinkedHashMap<>();
        String url;
        if (openapiId.isEmpty() && ABSOLUTE.matcher(path).matches()) {
            url = path; // an absolute endpoint is sent as is (no source, no source headers)
        } else {
            String ref = card.sourceRef();
            if (Names.isBlank(card.sourceName())) {
                return c.fail("bind an openapi source (relative endpoint " + (path == null ? openapiId : path) + ")");
            }
            String sourceProblem = sourceProblem(ref, ctx);
            if (sourceProblem != null) {
                return c.fail(sourceProblem);
            }
            SourceConnection source = ctx.sources().get(ref);
            if (!openapiId.isEmpty()) {
                Operation op = source.operations() == null ? null : source.operations().stream()
                        .filter(o -> openapiId.equals(o.operationId())).findFirst().orElse(null);
                if (op == null) {
                    return c.fail("openapi-id \"" + openapiId + "\" is not an operation of " + ref);
                }
                method = op.method().toUpperCase(Locale.ROOT);
                path = op.path();
            }
            if (Names.isBlank(source.apiBase())) {
                return c.fail("source " + ref + " has no apiBase");
            }
            if (ABSOLUTE.matcher(path).matches()) {
                // The operation's path comes from a spec fetched over the network, so it is
                // untrusted; the source's headers (which can hold credentials from a
                // .private.yaml layer) go with every request of its cards and must never reach
                // another host.
                if (!sameHost(source.apiBase(), path)) {
                    return c.fail("openapi-id \"" + openapiId + "\" points to another host (" + path
                            + "): the headers of " + ref + " are only sent to its own host");
                }
                url = path;
            } else {
                url = join(source.apiBase(), path);
            }
            if (source.headers() != null) {
                source.headers().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
            }
        }
        Object bodyValue = null;
        String body = null;
        if (payloadCode != null && !Names.isBlank(payloadCode)) {
            try {
                bodyValue = Bindings.substitute(parse(payloadCode), vars, json, c.bindings);
                body = json.writeValueAsString(bodyValue);
                headers.putIfAbsent("content-type", "application/json");
            } catch (RuntimeException ignored) {
                bodyValue = null; // an unparsable payload: the request runs without body
            }
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("url", url);
        request.put("headers", headers);
        request.put("body", bodyValue);
        c.detail.put("request", request);

        HttpCaller.Response res = http.call(method, url, headers, body, timeout);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", res.status());
        if (res.status() == 0) {
            response.put("error", res.error());
            response.put("elapsedMs", res.elapsedMs());
            c.detail.put("response", response);
            return c.fail(res.error() + " (" + res.elapsedMs() + "ms)");
        }
        Object published;
        try {
            published = parse(res.body());
        } catch (RuntimeException e) {
            published = res.body(); // not JSON: the raw text
        }
        response.put("headers", res.headers());
        response.put("elapsedMs", res.elapsedMs());
        response.put("body", published);
        response.put("truncated", res.truncated());
        c.detail.put("response", response);
        String summary = "HTTP " + res.status() + " (" + res.elapsedMs() + "ms)";
        if (!res.ok()) {
            return c.done(CardResult.ERROR, summary, res.body());
        }
        c.publish(c.label.varName(), published);
        feedResultCard(c, resultCard, published, published instanceof String s ? s : pretty(published));
        return c.done(CardResult.SUCCESS, summary, published instanceof String s ? s : pretty(published));
    }

    /** A mongo card: its JSON array of arguments with bindings resolved, validated, then executed. */
    private Executed runMongo(Ctx c, Card card, String subtype, String code, ResultCard resultCard,
                              Map<String, Object> vars, RunContext ctx, long timeout) {
        if (Names.isBlank(card.sourceName())) {
            return c.fail("bind a mongo source (source: mongo/{name})");
        }
        String ref = card.sourceRef();
        String sourceProblem = sourceProblem(ref, ctx);
        if (sourceProblem != null) {
            return c.fail(sourceProblem);
        }
        String uri = ctx.sources().get(ref).uri();
        if (Names.isBlank(uri)) {
            return c.fail("source " + ref + " has no uri");
        }
        long began = System.nanoTime();
        try {
            Object args = Names.isBlank(code) ? List.of() : Bindings.substitute(parse(code), vars, json, c.bindings);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("source", ref);
            call.put("database", MongoRunner.databaseOf(uri));
            call.put("collection", card.collection());
            call.put("operation", subtype);
            call.put("args", args);
            c.detail.put("call", call);
            MongoCall query = MongoCall.parse(subtype, card.collection(), json.writeValueAsString(args));
            Map<String, Object> envelope = json.readValue(mongo.run(uri, query, timeout), MAP);
            c.detail.put("result", envelope);
            Object value = envelope.get("result");
            c.publish(c.label.varName(), value);
            feedResultCard(c, resultCard, value, pretty(value));
            return c.done(CardResult.SUCCESS, "mongo " + envelope.get("collection") + "." + envelope.get("operation")
                    + " → " + envelope.get("count") + " (" + elapsedMs(began) + "ms)", pretty(value));
        } catch (RuntimeException e) {
            String message = reason(e);
            return c.done(CardResult.ERROR, "mongo " + message + " (" + elapsedMs(began) + "ms)", message);
        }
    }

    /** sql, kafka, grpc: simulated; they publish the body of the JSON card after them. */
    private Executed runSimulated(Ctx c, Card card, ResultCard resultCard, RunContext ctx) {
        if (!Names.isBlank(card.sourceName())) {
            String problem = sourceProblem(card.sourceRef(), ctx);
            if (problem != null) {
                return c.fail(problem);
            }
        }
        c.detail.put("simulated", true);
        if (resultCard != null && !Names.isBlank(resultCard.code())) {
            try {
                Object value = parse(resultCard.code());
                c.publish(c.label.varName(), value);
                return c.done(CardResult.SUCCESS, "simulated", pretty(value));
            } catch (RuntimeException ignored) {
                // not parseable: nothing to publish
            }
        }
        return c.done(CardResult.SUCCESS, "simulated", SIMULATED);
    }

    private void feedResultCard(Ctx c, ResultCard resultCard, Object value, String text) {
        if (resultCard != null && !Names.isBlank(resultCard.varName())) {
            c.publish(Names.trim(resultCard.varName()), value);
            c.resultCardCode = text;
        }
    }

    /** Why a card cannot use {@code ref} in this run, or null. */
    private static String sourceProblem(String ref, RunContext ctx) {
        String known = ctx.sourceErrors().get(ref);
        if (known != null) {
            return known;
        }
        if (!ctx.sources().containsKey(ref)) {
            return "source " + ref + " has no base-url in environment " + ctx.environment();
        }
        return null;
    }

    // ----------------------------------------------------------------- helpers

    private CardResult error(Label label, String summary, String output, Instant at, long offset, long elapsed,
                             Map<String, Object> detail) {
        return new CardResult(label.varName(), CardResult.ERROR, label.prefix() + summary, output, at.toString(), offset,
                elapsed, null, null, new LinkedHashMap<>(detail));
    }

    private static CardResult skipped(Card card, int step, String by, long runStarted) {
        Label label = label(card, step);
        return new CardResult(label.varName(), CardResult.SKIPPED, label.prefix() + "skipped (condition false)", "",
                Instant.now().toString(), elapsedMs(runStarted), 0, null, by, new LinkedHashMap<>());
    }

    /** A bare jsonassert expression evaluated by the Java engine against {@code context}. */
    private static Object evaluate(String expression, Map<String, Object> context) {
        return JsonataEvaluator.collapse(new JsonataEvaluator().evaluate(new JsonassertParser().parse(expression), context));
    }

    private static boolean isConditional(Card card) {
        return "jsonassert".equals(Names.trim(card.sourceType())) && JsonassertCode.CONDITIONAL.equals(Names.trim(card.subtype()));
    }

    public static boolean isJsonCard(Card card) {
        return "json".equals(Catalog.cardTypeOf(card.sourceType(), card.subtype()))
                && "rawvalue".equals(Names.trim(card.sourceType()));
    }

    public static String varNameOf(Card card) {
        String varName = Names.trim(card.varName());
        return !varName.isEmpty() ? varName : Names.toVarName(card.title());
    }

    private static String code(Card card) {
        return card.code() == null ? "" : card.code();
    }

    private static String join(String base, String path) {
        String b = base.replaceAll("/+$", "");
        return path.startsWith("/") ? b + path : b + "/" + path;
    }

    /**
     * Whether the absolute {@code url} of an operation stays on the host of the source's
     * {@code apiBase}. An unusable URL is not the same host (deny by default): the request is
     * refused before any header of the source leaves the plugin.
     */
    static boolean sameHost(String apiBase, String url) {
        URI base = uriOf(apiBase);
        URI target = uriOf(url);
        return base != null && target != null && base.getHost() != null
                && base.getHost().equalsIgnoreCase(target.getHost());
    }

    private static URI uriOf(String value) {
        try {
            return value == null ? null : new URI(value);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private Object parse(String text) {
        if (Names.isBlank(text)) {
            throw new IllegalArgumentException("no JSON");
        }
        return json.readValue(text, ANY);
    }

    private String pretty(Object value) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private static String reason(Throwable e) {
        String message = e.getMessage();
        if (e instanceof IllegalArgumentException && message != null) {
            return message;
        }
        return message == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1e6);
    }
}
