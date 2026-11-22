package com.testframe.plugin.project;

import com.testframe.plugin.PluginInfo;
import com.testframe.plugin.engine.OpenApiSpec;
import com.testframe.plugin.engine.Redactor;
import com.testframe.plugin.engine.RunControl;
import com.testframe.plugin.engine.RunEngine;
import com.testframe.plugin.engine.RunListener;
import com.testframe.plugin.format.Catalog;
import com.testframe.plugin.format.FrameDecoder;
import com.testframe.plugin.format.Names;
import com.testframe.plugin.format.ProjectDir;
import com.testframe.plugin.format.SourcesDecoder;
import com.testframe.plugin.model.Card;
import com.testframe.plugin.model.CardResult;
import com.testframe.plugin.model.SourceConnection;
import com.testframe.plugin.model.Step;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs the frames of a project read from disk (the tour API and the headless CLI;
 * docs/test-tour.md): decodes the frame and the layered sources read-only, resolves the sources
 * in the chosen environment (private layers included), fetches each openapi spec it needs once
 * per run in memory, applies the input overrides and runs the frame through the
 * {@link RunEngine}, emitting the run events. Nothing is written to the project.
 */
public class ProjectRuns {

    /** Fetches an OpenAPI spec document with the source's resolved headers; throws with the reason. */
    @FunctionalInterface
    public interface SpecFetcher {
        String fetch(String url, Map<String, String> headers) throws IOException;
    }

    /** An input card: a rawvalue card that the run does not overwrite. {@code code} is its saved value. */
    public record InputCard(int step, String varName, String title, String code) {
    }

    /** A decoded frame with its inputs and the source refs its cards use. */
    public record LoadedFrame(FrameDecoder.DecodedFrame frame, List<InputCard> inputs, List<String> sourceRefs) {
    }

    /** A frame ready to run: inputs applied, sources resolved in {@code environment}. */
    public record Prepared(ProjectDir project, String environment, LoadedFrame loaded, List<Step> steps,
                           Map<String, Object> inputs, SourcesDecoder.Resolution resolution) {

        public Redactor redactor() {
            return new Redactor(resolution.secrets());
        }

        public String frame() {
            return loaded.frame().name();
        }
    }

    /** What one frame run produced (the CLI report and the tour stream are built from it). */
    public record FrameSummary(String frame, String status, String stopped, long elapsedMs, Map<String, Integer> counts,
                               List<CardResult> results, Map<String, Object> inputs, Map<String, Object> sources) {
    }

    public static final String PASSED = "passed";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED = "interrupted";

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final RunEngine engine;
    private final SpecFetcher fetcher;
    private final ObjectMapper json;

    public ProjectRuns(RunEngine engine, SpecFetcher fetcher, ObjectMapper json) {
        this.engine = engine;
        this.fetcher = fetcher;
        this.json = json;
    }

    // ---------------------------------------------------------------- reading

    /** The decoded {@code sources*.yaml} layers (unreadable files are problems too). */
    public SourcesDecoder.Sources sources(ProjectDir dir) throws IOException {
        List<String> readErrors = new ArrayList<>();
        Map<String, String> layers = dir.sourceLayers(readErrors);
        SourcesDecoder.Sources sources = SourcesDecoder.decode(layers);
        if (readErrors.isEmpty()) {
            return sources;
        }
        List<String> errors = new ArrayList<>(readErrors);
        errors.addAll(sources.errors());
        return new SourcesDecoder.Sources(sources.environments(), sources.layers(), sources.declared(), errors);
    }

    /** One frame; 404 when the project has no such frame. */
    public LoadedFrame frame(ProjectDir dir, String name) throws IOException {
        if (!dir.hasFrame(name)) {
            throw new ProblemException(404, "unknown frame: " + name);
        }
        List<String> readErrors = new ArrayList<>();
        List<ProjectDir.ProjectFile> files = dir.frameFiles(name, readErrors);
        FrameDecoder.DecodedFrame decoded = FrameDecoder.decode(name, files);
        if (!readErrors.isEmpty()) {
            List<String> errors = new ArrayList<>(readErrors.stream().map(e -> name + "/" + e).toList());
            errors.addAll(decoded.errors());
            decoded = new FrameDecoder.DecodedFrame(decoded.name(), decoded.steps(), errors);
        }
        return new LoadedFrame(decoded, inputs(decoded), sourceRefs(decoded));
    }

    /**
     * The frame's inputs (docs/test-tour.md, "Input cards"): rawvalue cards that are not the
     * result card of an openapi or mongo card (walking backwards in the step, such a card is met
     * before any rawvalue card or the start of the step).
     */
    public static List<InputCard> inputs(FrameDecoder.DecodedFrame frame) {
        List<InputCard> inputs = new ArrayList<>();
        for (FrameDecoder.DecodedStep step : frame.steps()) {
            List<FrameDecoder.DecodedCard> cards = step.cards();
            for (int i = 0; i < cards.size(); i++) {
                Card card = cards.get(i).card();
                if (!isRawValue(card)) {
                    continue;
                }
                boolean output = false;
                for (int p = i - 1; p >= 0; p--) {
                    Card before = cards.get(p).card();
                    if (isRawValue(before)) {
                        break;
                    }
                    String type = Names.trim(before.sourceType());
                    if (type.equals("openapi") || type.equals("mongo")) {
                        output = true;
                        break;
                    }
                }
                if (!output) {
                    String title = Names.isBlank(card.title()) ? card.varName() : card.title();
                    inputs.add(new InputCard(step.number(), card.varName(), title, card.code() == null ? "" : card.code()));
                }
            }
        }
        return inputs;
    }

    /** The named source refs ({@code {type}/{name}}) the frame's cards use, in first-use order. */
    public static List<String> sourceRefs(FrameDecoder.DecodedFrame frame) {
        Set<String> refs = new LinkedHashSet<>();
        for (FrameDecoder.DecodedCard c : frame.cards()) {
            Card card = c.card();
            if (Catalog.hasSources(Names.trim(card.sourceType())) && !Names.isBlank(card.sourceName())) {
                refs.add(card.sourceRef());
            }
        }
        return List.copyOf(refs);
    }

    private static boolean isRawValue(Card card) {
        return "rawvalue".equals(Names.trim(card.sourceType()));
    }

    // ---------------------------------------------------------------- prepare

    /**
     * Validates a run of {@code frameName} in {@code env} with the input overrides (var-name →
     * JSON text). Throws {@link ProblemException}: 400 for invalid sources files, a var-name
     * used twice, unknown or invalid inputs and sources not configured for the environment; 404
     * for an unknown environment or frame.
     */
    public Prepared prepare(ProjectDir dir, SourcesDecoder.Sources sources, String frameName, String env,
                            Map<String, String> overrides) throws IOException {
        if (!sources.valid()) {
            throw new ProblemException(400, sources.errors());
        }
        if (env == null || env.isBlank()) {
            throw new ProblemException(400, "environment is required");
        }
        if (!sources.hasEnvironment(env)) {
            throw new ProblemException(404, "unknown environment: " + env + " (" + String.join(", ", sources.environments()) + ")");
        }
        LoadedFrame loaded = frame(dir, frameName);
        List<String> problems = new ArrayList<>();

        Map<String, Integer> seen = new HashMap<>();
        for (FrameDecoder.DecodedCard c : loaded.frame().cards()) {
            if (seen.merge(c.card().varName(), 1, Integer::sum) == 2) {
                problems.add(frameName + ": var-name \"" + c.card().varName() + "\" is used twice");
            }
        }

        Map<String, InputCard> byVar = new LinkedHashMap<>();
        loaded.inputs().forEach(i -> byVar.put(i.varName(), i));
        Map<String, String> given = overrides == null ? Map.of() : overrides;
        for (Map.Entry<String, String> e : given.entrySet()) {
            if (!byVar.containsKey(e.getKey())) {
                problems.add(e.getKey() + ": not an input of frame " + frameName);
                continue;
            }
            try {
                STRICT.readTree(e.getValue() == null ? "" : e.getValue());
                if (e.getValue() == null || e.getValue().isBlank()) {
                    problems.add(e.getKey() + ": invalid JSON: empty");
                }
            } catch (JacksonException ex) {
                problems.add(e.getKey() + ": invalid JSON: " + ex.getOriginalMessage());
            }
        }

        SourcesDecoder.Resolution resolution = SourcesDecoder.resolve(sources, env);
        for (String ref : loaded.sourceRefs()) {
            if (!resolution.sources().containsKey(ref)) {
                problems.add(ref + ": not declared in sources.yaml");
            } else if (!resolution.configured(ref)) {
                problems.add(ref + ": has no base-url in environment " + env);
            }
        }
        if (!problems.isEmpty()) {
            throw new ProblemException(400, problems);
        }

        Map<String, Object> inputs = new LinkedHashMap<>();
        for (InputCard input : loaded.inputs()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            boolean overridden = given.containsKey(input.varName());
            entry.put("step", input.step());
            entry.put("overridden", overridden);
            entry.put("value", jsonOrText(overridden ? given.get(input.varName()) : input.code()));
            if (overridden) {
                entry.put("saved", jsonOrText(input.code()));
            }
            inputs.put(input.varName(), entry);
        }
        List<Step> steps = loaded.frame().toSteps().stream()
                .map(step -> new Step(step.number(), step.cards().stream()
                        .map(card -> given.containsKey(card.varName()) ? card.withCode(given.get(card.varName())) : card)
                        .toList()))
                .toList();
        return new Prepared(dir, env, loaded, steps, inputs, resolution);
    }

    // -------------------------------------------------------------------- run

    /**
     * Runs a prepared frame, emitting the run events (docs/test-tour.md, "Run events") to
     * {@code sink} as raw maps: the caller redacts them ({@link Prepared#redactor()}) right before
     * they leave the plugin. {@code runId} is null for the CLI.
     */
    public FrameSummary run(Prepared p, String runId, RunControl control, Consumer<Map<String, Object>> sink) {
        Map<String, SourceConnection> connections = new LinkedHashMap<>();
        Map<String, String> sourceErrors = new LinkedHashMap<>();
        Map<String, Object> sources = new LinkedHashMap<>();
        Redactor redactor = p.redactor();
        for (String ref : p.loaded().sourceRefs()) {
            SourcesDecoder.ResolvedSource source = p.resolution().sources().get(ref);
            if (source == null || !source.configured()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("baseUrl", source.privateFields().contains(SourcesDecoder.BASE_URL) ? Redactor.MASK : source.baseUrl());
            switch (source.type()) {
                case "openapi" -> {
                    try {
                        OpenApiSpec spec = OpenApiSpec.parse(source.baseUrl(), fetcher.fetch(source.baseUrl(), source.headers()));
                        connections.put(ref, SourceConnection.openapi(spec.apiBase(), source.headers(), spec.operations()));
                        entry.put("apiBase", spec.apiBase());
                    } catch (IOException | RuntimeException e) {
                        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        sourceErrors.put(ref, "cannot read the OpenAPI spec of " + ref + ": " + reason);
                        entry.put("error", sourceErrors.get(ref));
                    }
                    Map<String, String> headers = new LinkedHashMap<>();
                    source.headers().forEach((name, value) -> headers.put(name,
                            source.privateFields().contains(SourcesDecoder.HEADERS + "." + name) ? Redactor.MASK : value));
                    entry.put("headers", headers);
                }
                case "mongo" -> connections.put(ref, SourceConnection.mongo(source.baseUrl()));
                default -> connections.put(ref, SourceConnection.other(source.baseUrl()));
            }
            entry.put("privateFields", source.privateFields());
            if (hasShortSecret(source)) {
                entry.put("warning", "short secret, not substring-redacted");
            }
            sources.put(ref, entry);
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        int total = 0;
        for (Step step : p.steps()) {
            for (Card card : step.cards()) {
                total++;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("step", step.number());
                c.put("varName", card.varName());
                c.put("title", Names.isBlank(card.title()) ? card.varName() : card.title());
                c.put("source", card.sourceRef());
                c.put("subtype", card.subtype());
                putIfPresent(c, "collection", card.collection());
                putIfPresent(c, "endpoint", card.endpoint());
                putIfPresent(c, "openapiId", card.openapiId());
                c.put("code", card.code() == null ? "" : card.code());
                cards.add(c);
            }
        }

        Map<String, Object> started = new LinkedHashMap<>();
        started.put("event", "frame-started");
        if (runId != null) {
            started.put("runId", runId);
        }
        started.put("frame", p.frame());
        started.put("environment", p.environment());
        started.put("pluginVersion", PluginInfo.VERSION);
        started.put("at", Instant.now().toString());
        started.put("inputs", p.inputs());
        started.put("sources", sources);
        started.put("cards", cards);
        sink.accept(started);

        RunEngine.RunContext ctx = new RunEngine.RunContext(p.environment(), connections, sourceErrors,
                p.loaded().frame().cardProblems());
        List<CardResult> results = new ArrayList<>();
        String status;
        String stopped = null;
        long elapsed = 0;
        try {
            RunEngine.FrameOutcome outcome = engine.runFrame(p.steps(), ctx, control, new RunListener() {
                @Override
                public void cardStarted(int step, Card card, Instant at) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("event", "card-started");
                    e.put("step", step);
                    e.put("varName", card.varName());
                    e.put("at", at.toString());
                    sink.accept(e);
                }

                @Override
                public void cardFinished(int step, CardResult result) {
                    results.add(result);
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("event", "card-finished");
                    e.put("step", step);
                    e.put("result", result);
                    sink.accept(e);
                }
            });
            stopped = outcome.stopped();
            elapsed = outcome.elapsedMs();
            status = statusOf(stopped);
        } catch (RuntimeException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("event", "error");
            error.put("message", redactor.apply(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            sink.accept(error);
            status = INTERRUPTED;
            stopped = RunEngine.STOP_CANCELLED;
        }
        Map<String, Integer> counts = counts(results, total);
        Map<String, Object> finished = new LinkedHashMap<>();
        finished.put("event", "frame-finished");
        finished.put("status", status);
        finished.put("stopped", stopped);
        finished.put("elapsedMs", elapsed);
        finished.put("counts", counts);
        sink.accept(finished);
        return new FrameSummary(p.frame(), status, stopped, elapsed, counts, results, p.inputs(), sources);
    }

    /** passed (no error card), failed (stopped at an error or the deadline), interrupted (cancelled). */
    public static String statusOf(String stopped) {
        if (stopped == null) {
            return PASSED;
        }
        return RunEngine.STOP_CANCELLED.equals(stopped) ? INTERRUPTED : FAILED;
    }

    /** success / error / skipped / notRun counts of a frame of {@code total} cards. */
    public static Map<String, Integer> counts(List<CardResult> results, int total) {
        int success = 0;
        int error = 0;
        int skipped = 0;
        for (CardResult r : results) {
            switch (r.status()) {
                case CardResult.SUCCESS -> success++;
                case CardResult.ERROR -> error++;
                default -> skipped++;
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("success", success);
        counts.put("error", error);
        counts.put("skipped", skipped);
        counts.put("notRun", Math.max(0, total - results.size()));
        return counts;
    }

    private boolean hasShortSecret(SourcesDecoder.ResolvedSource source) {
        List<String> values = new ArrayList<>();
        if (source.privateFields().contains(SourcesDecoder.BASE_URL)) {
            values.addAll(SourcesDecoder.secretsOf(source.baseUrl(), false));
        }
        source.headers().forEach((name, value) -> {
            if (source.privateFields().contains(SourcesDecoder.HEADERS + "." + name)) {
                values.addAll(SourcesDecoder.secretsOf(value, true));
            }
        });
        return values.stream().anyMatch(v -> v.length() < Redactor.MIN_SUBSTRING);
    }

    private Object jsonOrText(String text) {
        try {
            return text == null || text.isBlank() ? text : json.readValue(text, Object.class);
        } catch (RuntimeException e) {
            return text;
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** The default fetcher: GET with the source's headers, 10 s timeout, 2xx required. */
    public static SpecFetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return (url, headers) -> {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").GET();
                headers.forEach(builder::header);
                HttpResponse<String> res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    throw new IOException("HTTP " + res.statusCode());
                }
                return res.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } catch (IllegalArgumentException e) {
                throw new IOException(e.getMessage(), e);
            }
        };
    }
}
