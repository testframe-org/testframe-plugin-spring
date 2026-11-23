package com.testframe.plugin.format;

import com.testframe.plugin.model.Card;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-only decoder of one card file ({@code frames/{frame}/step-{n}/{var-file}.md}; spec:
 * agents/specs/project-format.md, "Cards"). The var-name comes from the file name; the frontmatter holds
 * only what the path cannot express. An invalid card still decodes best effort (so a run can
 * report it as that card's error) together with its problems.
 */
public final class CardDecoder {

    private static final Pattern SOURCE_REF = Pattern.compile("([a-z]+)(?:/([a-z0-9][a-z0-9_-]*))?");
    private static final Pattern SIZE = Pattern.compile("\\d+x\\d+");
    private static final Pattern OPERATION_ID = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /** A decoded card: {@code errors} non-empty means the file is invalid (the card is best effort). */
    public record Decoded(Card card, List<String> errors) {
    }

    private CardDecoder() {
    }

    /** Decodes a card file. {@code fileBase} is the var-file (no extension). */
    public static Decoded decode(String fileBase, String content) {
        MdEnvelope.Parsed md = MdEnvelope.parse(content);
        Map<String, String> f = md.fields();
        List<String> errors = validate(f);
        String source = f.getOrDefault("source", "");
        String[] ref = source.split("/", 2);
        String sourceType = ref[0].isBlank() ? null : ref[0];
        String sourceName = ref.length > 1 ? ref[1] : null;
        Integer timeout = toInt(f.get("timeout"));
        Card card = new Card(
                Names.fileNameToVarName(fileBase),
                f.get("title"),
                sourceType,
                sourceName,
                f.get("subtype"),
                f.get("collection"),
                f.get("endpoint"),
                f.get("openapi-id"),
                f.containsKey("timeout") ? timeout : null,
                MdEnvelope.unfence(md.body()));
        return new Decoded(card, errors);
    }

    /** Frontmatter schema of a card file: one {@code "key: problem"} per violation. */
    public static List<String> validate(Map<String, String> f) {
        List<String> errors = new ArrayList<>();
        String title = f.get("title");
        if (title == null || title.isEmpty()) {
            errors.add("title: required");
        }
        String source = f.get("source");
        String sourceType = null;
        if (source == null || source.isEmpty()) {
            errors.add("source: required ({type-source}/{name-source}, or rawvalue)");
        } else if (!SOURCE_REF.matcher(source).matches()) {
            errors.add("source: source must be {type-source} or {type-source}/{name-source}");
        } else if (!Catalog.SOURCE_TYPES.contains(source.split("/")[0])) {
            errors.add("source: unknown type-source");
        } else {
            sourceType = source.split("/")[0];
            if (!Catalog.hasSources(sourceType) && source.contains("/")) {
                errors.add("source: " + sourceType + " cards have no name-source (write source: " + sourceType + ")");
            }
        }
        String subtype = f.get("subtype");
        if (sourceType != null) {
            String problem = Catalog.subtypeProblem(sourceType, subtype);
            if (problem != null) {
                errors.add("subtype: " + problem);
            }
        }
        String collection = f.get("collection");
        if ("mongo".equals(sourceType)) {
            String problem = MongoCall.collectionProblem(collection);
            if (problem != null) {
                errors.add("collection: " + problem);
            }
        } else if (collection != null && sourceType != null) {
            errors.add("collection: only mongo cards name a collection");
        }
        String openapiId = f.get("openapi-id");
        if (openapiId != null && sourceType != null && !"openapi".equals(sourceType)) {
            errors.add("openapi-id: only openapi cards name a spec operation");
        } else if (openapiId != null && !OPERATION_ID.matcher(openapiId).matches()) {
            errors.add("openapi-id: must be the spec's operationId (letters, digits, _ . -)");
        }
        if (f.containsKey("script")) {
            errors.add("script: sources have no scripts; the card holds its own code");
        }
        intProblem(f, "timeout", 100, 120_000).ifPresent(errors::add);
        if (f.containsKey("size") && !SIZE.matcher(f.get("size")).matches()) {
            errors.add("size: size must be {width}x{height}");
        }
        intProblem(f, "result-height", 24, 600).ifPresent(errors::add);
        return errors;
    }

    private static Optional<String> intProblem(Map<String, String> f, String key, int min, int max) {
        if (!f.containsKey(key)) {
            return Optional.empty();
        }
        Integer value = toInt(f.get(key));
        if (value == null) {
            return Optional.of(key + ": expected an integer");
        }
        if (value < min || value > max) {
            return Optional.of(key + (value < min ? ": must be >= " + min : ": must be <= " + max));
        }
        return Optional.empty();
    }

    /** JS {@code Number(text)} restricted to integers: "" → 0, "1e3" → 1000, otherwise null. */
    static Integer toInt(String text) {
        if (text == null) {
            return null;
        }
        String t = Names.trim(text);
        if (t.isEmpty()) {
            return 0;
        }
        if (!NUMBER.matcher(t).matches()) {
            return null;
        }
        try {
            return new BigDecimal(t).intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
