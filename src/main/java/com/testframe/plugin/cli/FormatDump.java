package com.testframe.plugin.cli;

import com.testframe.plugin.Json;
import com.testframe.plugin.format.Catalog;
import com.testframe.plugin.format.FrameDecoder;
import com.testframe.plugin.format.ProjectDir;
import com.testframe.plugin.format.SourcesDecoder;
import com.testframe.plugin.model.Card;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java side of the format parity gate ({@code bun run format:check} at the repo root): prints
 * the read-only decoders' view of one project as the canonical JSON defined in the gate
 * (repo-root {@code scripts/format-check.ts}), which compares it with the IDE's own dump
 * ({@code modules/testframe-ide/scripts/format-dump.ts}). Read-only: nothing is written and no web
 * server starts.
 *
 * <p>Usage: {@code java -jar testframe-plugin-spring.jar format-dump <projectDir>}.
 * Exit codes: 0 dumped, 2 bad invocation or project, 4 internal error.
 */
public final class FormatDump {

    public static final String COMMAND = "format-dump";

    /** The dump format both sides produce; bump it when the canonical shape changes. */
    public static final int FORMAT = 1;

    /** Stands in for a resolved value whose winning layer is a {@code .private.yaml} file. */
    static final String PRIVATE = "<private>";

    public static int main(String[] args, PrintStream out, PrintStream err) {
        Path projectDir = parse(args, err);
        if (projectDir == null) {
            return 2;
        }
        ProjectDir dir;
        try {
            dir = ProjectDir.at(projectDir);
        } catch (IllegalArgumentException e) {
            err.println(COMMAND + ": " + e.getMessage());
            return 2;
        }
        try {
            out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(dump(dir)));
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println(COMMAND + ": internal error: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + e.getMessage()));
            return 4;
        }
    }

    private static Path parse(String[] args, PrintStream err) {
        Path projectDir = null;
        for (int i = 1; i < args.length; i++) { // args[0] is the command
            String arg = args[i];
            if (arg.startsWith("--")) {
                err.println(COMMAND + ": unknown option " + arg);
                return null;
            }
            if (projectDir != null) {
                err.println(COMMAND + ": unexpected argument " + arg);
                return null;
            }
            projectDir = Path.of(arg);
        }
        if (projectDir == null) {
            err.println("usage: java -jar testframe-plugin-spring.jar " + COMMAND + " <projectDir>");
        }
        return projectDir;
    }

    /** The canonical view of one project: its frames and its sources (every environment). */
    static Map<String, Object> dump(ProjectDir dir) throws IOException {
        List<String> frameProblems = new ArrayList<>();
        List<Map<String, Object>> frames = new ArrayList<>();
        for (String name : dir.frameNames()) {
            // read problems come back relative to the frame folder; the dump keys them by frames/ path
            List<String> read = new ArrayList<>();
            FrameDecoder.DecodedFrame frame = FrameDecoder.decode(name, dir.frameFiles(name, read));
            read.forEach(problem -> frameProblems.add(name + "/" + problem));
            List<Map<String, Object>> steps = new ArrayList<>();
            for (FrameDecoder.DecodedStep step : frame.steps()) {
                List<Map<String, Object>> cards = new ArrayList<>();
                for (FrameDecoder.DecodedCard decoded : step.cards()) {
                    // an invalid card file is reported and dropped, as the IDE's reader does
                    if (decoded.problems().isEmpty()) {
                        cards.add(card(decoded.card()));
                    }
                }
                steps.add(map("number", step.number(), "cards", cards));
            }
            frames.add(map("name", frame.name(), "steps", steps));
            frameProblems.addAll(frame.errors());
        }
        Collections.sort(frameProblems);

        List<String> sourceProblems = new ArrayList<>();
        SourcesDecoder.Sources sources = SourcesDecoder.decode(dir.sourceLayers(sourceProblems));
        sourceProblems.addAll(sources.errors());
        Collections.sort(sourceProblems);

        List<Map<String, Object>> resolved = new ArrayList<>();
        if (sources.valid()) {
            for (String env : sources.environments()) {
                List<Map<String, Object>> entries = new ArrayList<>();
                SourcesDecoder.resolve(sources, env).sources().forEach((ref, source) -> entries.add(resolved(source)));
                resolved.add(map("environment", env, "sources", entries));
            }
        }

        return map(
                "format", FORMAT,
                "project", dir.name(),
                "frames", frames,
                "frameProblems", frameProblems,
                "sources", map(
                        "environments", sources.environments(),
                        "declared", sources.refs(),
                        "problems", sourceProblems,
                        "resolved", resolved));
    }

    /**
     * One card as the decoders read it, in the canonical projection: the fields both sides derive
     * from the file (layout, private notes and last runs are not part of the format parity), with
     * an absent value and an empty one both dumped as null and a missing {@code timeout} as the
     * card type's default.
     */
    private static Map<String, Object> card(Card card) {
        String type = Catalog.cardTypeOf(card.sourceType(), card.subtype());
        return map(
                "varName", text(card.varName()),
                "title", text(card.title()),
                "type", type,
                "sourceType", text(card.sourceType()),
                "sourceName", text(card.sourceName()),
                "subtype", text(card.subtype()),
                "collection", text(card.collection()),
                "endpoint", text(card.endpoint()),
                "openapiId", text(card.openapiId()),
                "timeout", card.timeout() == null ? Catalog.defaultTimeout(type) : card.timeout(),
                "code", card.code() == null ? "" : card.code());
    }

    /** One source resolved in one environment; a value from a private layer is never printed. */
    private static Map<String, Object> resolved(SourcesDecoder.ResolvedSource source) {
        List<String> privateFields = source.privateFields();
        Map<String, Object> headers = new LinkedHashMap<>();
        source.headers().forEach((name, value) ->
                headers.put(name, privateFields.contains(SourcesDecoder.HEADERS + "." + name) ? PRIVATE : value));
        return map(
                "ref", source.ref(),
                "baseUrl", privateFields.contains(SourcesDecoder.BASE_URL)
                        ? PRIVATE
                        : source.baseUrl() == null ? "" : source.baseUrl(),
                "configured", source.configured(),
                "headers", headers,
                "privateFields", privateFields);
    }

    private static Object text(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** A map with a fixed key order, so both dumps serialize field by field the same way. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            out.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return out;
    }

    private FormatDump() {
    }
}
