package com.testframe.plugin.format;

import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationAlternate;
import com.mongodb.client.model.CollationCaseFirst;
import com.mongodb.client.model.CollationMaxVariable;
import com.mongodb.client.model.CollationStrength;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A mongo card's call (spec: docs/project-format.md, "Mongo cards"): the collection (the card's
 * {@code collection:} frontmatter), the function named by the subtype, and the card body — one
 * JSON array whose items are the function's positional arguments, like the shell call
 * {@code db.products.find(<arg0>, <arg1>, <arg2>)}. Trailing optional arguments may be omitted.
 * <pre>
 *   find:                    [filter, projection?, options?]  options: sort, limit, skip, maxTimeMS, collation, hint, comment
 *   aggregation:             [pipeline, options?]             options: allowDiskUse, maxTimeMS, batchSize, collation, hint, comment, let
 *   deleteOne, deleteMany:   [filter, options?]               options: collation, hint, comment, let
 *   updateOne, updateMany:   [filter, update, options?]       options: upsert, arrayFilters, collation, hint, comment, let
 *   insertOne:               [document, options?]             options: comment, bypassDocumentValidation
 *   insertMany:              [documents, options?]            options: ordered, comment, bypassDocumentValidation
 * </pre>
 * Guards: delete/update need a non-empty filter (no accidental wipe of a collection), an update
 * is {@code $}-operators only (or an update pipeline), inserted documents are non-empty and have
 * no top-level {@code $} keys, {@code $out}/{@code $merge} are rejected everywhere, unknown
 * option keys are rejected. Problems name the argument: {@code arg 3 (options): unknown key "foo"}. Values are JSON (MongoDB extended JSON such as
 * {"$oid": "..."} is understood).
 */
public record MongoCall(String subtype, String collection, BsonDocument filter, BsonDocument projection,
                        List<BsonDocument> pipeline, BsonValue update, List<BsonDocument> documents,
                        BsonDocument options) {

    public static final String FIND = "find";
    public static final String AGGREGATION = "aggregation";
    public static final String DELETE_ONE = "deleteOne";
    public static final String DELETE_MANY = "deleteMany";
    public static final String UPDATE_ONE = "updateOne";
    public static final String UPDATE_MANY = "updateMany";
    public static final String INSERT_ONE = "insertOne";
    public static final String INSERT_MANY = "insertMany";
    public static final List<String> SUBTYPES = List.of(FIND, AGGREGATION, DELETE_ONE, DELETE_MANY, UPDATE_ONE,
            UPDATE_MANY, INSERT_ONE, INSERT_MANY);
    public static final List<String> WRITE_STAGES = List.of("$out", "$merge");

    /** An option key of the subtype's options argument: {@code kind} as for {@link Arg}. */
    public record Option(String name, String kind) {
    }

    /**
     * One positional argument: {@code kind} is object, array (an aggregation pipeline), update
     * (operators object or pipeline array), document (a non-empty object to insert), documents
     * (an array of them) or options; {@code options} lists the keys an options
     * argument accepts. Served by the catalog as the subtype's signature.
     */
    public record Arg(String name, String kind, boolean required, List<Option> options) {
    }

    private static final Option COLLATION = new Option("collation", "object");
    private static final Option HINT = new Option("hint", "hint");
    private static final Option COMMENT = new Option("comment", "string");
    private static final Option LET = new Option("let", "object");
    private static final Option MAX_TIME = new Option("maxTimeMS", "integer");

    private static final Arg FILTER = new Arg("filter", "object", true, List.of());

    /** The positional arguments of a subtype (empty for an unknown one). */
    public static List<Arg> signatureOf(String subtype) {
        return switch (subtype == null ? "" : subtype) {
            case FIND -> List.of(FILTER, new Arg("projection", "object", false, List.of()),
                    new Arg("options", "options", false, List.of(new Option("sort", "object"),
                            new Option("limit", "integer"), new Option("skip", "integer"), MAX_TIME, COLLATION, HINT, COMMENT)));
            case AGGREGATION -> List.of(new Arg("pipeline", "array", true, List.of()),
                    new Arg("options", "options", false, List.of(new Option("allowDiskUse", "boolean"), MAX_TIME,
                            new Option("batchSize", "positiveInteger"), COLLATION, HINT, COMMENT, LET)));
            case DELETE_ONE, DELETE_MANY -> List.of(FILTER,
                    new Arg("options", "options", false, List.of(COLLATION, HINT, COMMENT, LET)));
            case UPDATE_ONE, UPDATE_MANY -> List.of(FILTER, new Arg("update", "update", true, List.of()),
                    new Arg("options", "options", false, List.of(new Option("upsert", "boolean"),
                            new Option("arrayFilters", "objects"), COLLATION, HINT, COMMENT, LET)));
            case INSERT_ONE -> List.of(new Arg("document", "document", true, List.of()),
                    new Arg("options", "options", false, List.of(COMMENT, new Option("bypassDocumentValidation", "boolean"))));
            case INSERT_MANY -> List.of(new Arg("documents", "documents", true, List.of()),
                    new Arg("options", "options", false, List.of(new Option("ordered", "boolean"), COMMENT,
                            new Option("bypassDocumentValidation", "boolean"))));
            default -> List.of();
        };
    }

    public static boolean isWrite(String subtype) {
        return List.of(DELETE_ONE, DELETE_MANY, UPDATE_ONE, UPDATE_MANY, INSERT_ONE, INSERT_MANY).contains(subtype);
    }

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private static final Pattern QUOTED_BINDING = Pattern.compile("\"\\{\\{[^}]+\\}\\}\"");
    private static final Pattern BINDING = Pattern.compile("\\{\\{[^}]+\\}\\}");
    private static final List<String> COLLATION_KEYS = List.of("locale", "caseLevel", "caseFirst", "strength",
            "numericOrdering", "alternate", "maxVariable", "normalization", "backwards");

    /**
     * The code rule of a mongo body, or null when acceptable. Empty code is a draft;
     * {@code {{bindings}}} stand for any value (they are resolved at run time). Without a
     * subtype there is no rule to apply yet (the card validation reports the missing subtype).
     */
    public static String problem(String subtype, String code) {
        if (code == null || code.isBlank() || subtype == null || subtype.isBlank()) {
            return null;
        }
        List<String> problems = problems(subtype, code);
        return problems.isEmpty() ? null : problems.getFirst();
    }

    /** Every problem of a mongo body (one per argument at most; empty when acceptable or a draft). */
    public static List<String> problems(String subtype, String code) {
        if (code == null || code.isBlank() || subtype == null || subtype.isBlank()) {
            return List.of();
        }
        String probe = BINDING.matcher(QUOTED_BINDING.matcher(code).replaceAll("null")).replaceAll("null");
        List<String> issues = new ArrayList<>();
        try {
            read(subtype, null, probe, true, issues);
        } catch (IllegalArgumentException e) {
            issues.add(e.getMessage());
        }
        return issues;
    }

    /**
     * Problem of a collection name (the card's {@code collection:}), or null: non-empty, no
     * {@code $}, no null character, not a {@code system.} collection.
     */
    public static String collectionProblem(String collection) {
        if (collection == null || collection.isBlank()) {
            return "required on mongo cards (the collection the call works on)";
        }
        if (!collection.equals(collection.trim()) || collection.contains("$") || collection.indexOf('\0') >= 0) {
            return "\"" + collection + "\" is not a collection name (no $, no spaces around, no null character)";
        }
        if (collection.startsWith("system.")) {
            return "system collections cannot be used";
        }
        return null;
    }

    /** Parses a card's call (bindings already resolved); IllegalArgumentException names the problem. */
    public static MongoCall parse(String subtype, String collection, String json) {
        if (subtype == null || subtype.isBlank()) {
            throw new IllegalArgumentException("mongo cards need a subtype (" + String.join(", ", SUBTYPES) + ")");
        }
        String problem = collectionProblem(collection);
        if (problem != null) {
            throw new IllegalArgumentException("collection: " + problem);
        }
        return read(subtype, collection, json == null || json.isBlank() ? "[]" : json, false, null);
    }

    /**
     * {@code lenient}: null values (bindings in the code rule) are accepted for any argument or
     * option. With {@code issues}, argument problems are collected (one per argument) instead of
     * thrown; structural problems (JSON, shape, arity) always throw.
     */
    private static MongoCall read(String subtype, String collection, String json, boolean lenient, List<String> issues) {
        List<Arg> signature = signatureOf(subtype);
        if (signature.isEmpty()) {
            throw new IllegalArgumentException("unknown mongo subtype \"" + subtype + "\" (" + String.join(", ", SUBTYPES) + ")");
        }
        try {
            if (!STRICT.readTree(json).isArray()) {
                throw new IllegalArgumentException(hint(subtype, signature));
            }
        } catch (JacksonException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getOriginalMessage());
        }
        BsonArray args;
        try {
            args = BsonDocument.parse("{\"args\": " + json + "}").getArray("args");
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage());
        }
        if (args.size() > signature.size()) {
            throw new IllegalArgumentException(subtype + " takes at most " + signature.size() + " arguments: " + hint(subtype, signature));
        }
        for (int i = 0; i < signature.size(); i++) {
            Arg arg = signature.get(i);
            String label = "arg " + (i + 1) + " (" + arg.name() + "): ";
            try {
                if (i >= args.size()) {
                    if (arg.required()) {
                        throw new IllegalArgumentException("required, " + hint(subtype, signature));
                    }
                    continue;
                }
                BsonValue value = args.get(i);
                String writeStage = writeStageIn(value);
                if (writeStage != null) {
                    throw new IllegalArgumentException("stage \"" + writeStage + "\" is not allowed in mongo cards");
                }
                // null skips an optional argument (like the shell's find({}, null, {...}))
                if (!(value.isNull() && (lenient || !arg.required()))) {
                    checkArg(subtype, arg, value, lenient);
                }
            } catch (IllegalArgumentException e) {
                if (issues == null) {
                    throw new IllegalArgumentException(label + e.getMessage(), e);
                }
                issues.add(label + e.getMessage());
            }
        }
        if (issues != null) {
            return null;
        }

        BsonDocument filter = null;
        BsonDocument projection = null;
        List<BsonDocument> pipeline = List.of();
        BsonValue update = null;
        List<BsonDocument> documents = List.of();
        BsonDocument options = new BsonDocument();
        switch (subtype) {
            case FIND -> {
                filter = doc(args, 0);
                projection = doc(args, 1);
                options = doc(args, 2);
            }
            case AGGREGATION -> {
                pipeline = args.size() > 0 && args.get(0).isArray()
                        ? args.get(0).asArray().getValues().stream().filter(BsonValue::isDocument).map(BsonValue::asDocument).toList()
                        : List.of();
                options = doc(args, 1);
            }
            case DELETE_ONE, DELETE_MANY -> {
                filter = doc(args, 0);
                options = doc(args, 1);
            }
            case INSERT_ONE -> {
                documents = doc(args, 0) == null ? List.of() : List.of(doc(args, 0));
                options = doc(args, 1);
            }
            case INSERT_MANY -> {
                documents = args.size() > 0 && args.get(0).isArray()
                        ? args.get(0).asArray().getValues().stream().filter(BsonValue::isDocument).map(BsonValue::asDocument).toList()
                        : List.of();
                options = doc(args, 1);
            }
            default -> { // updates
                filter = doc(args, 0);
                update = args.size() > 1 ? args.get(1) : null;
                options = doc(args, 2);
            }
        }
        return new MongoCall(subtype, collection, filter, projection, pipeline, update, documents,
                options == null ? new BsonDocument() : options);
    }

    private static void checkArg(String subtype, Arg arg, BsonValue value, boolean lenient) {
        String name = arg.name();
        switch (arg.kind()) {
            case "object" -> {
                if (!value.isDocument()) {
                    throw new IllegalArgumentException("must be an object");
                }
                if (FILTER.name().equals(name) && isWrite(subtype) && value.asDocument().isEmpty()) {
                    throw new IllegalArgumentException(subtype + " needs a non-empty filter (an empty filter would "
                            + (subtype.startsWith("delete") ? "delete" : "update") + " the whole collection)");
                }
            }
            case "array" -> {
                if (!value.isArray()) {
                    throw new IllegalArgumentException("must be an array of stages");
                }
                stages(value.asArray(), lenient);
            }
            case "document" -> insertable(value, "");
            case "documents" -> {
                if (!value.isArray() || value.asArray().isEmpty()) {
                    throw new IllegalArgumentException("must be an array of at least one document");
                }
                for (int i = 0; i < value.asArray().size(); i++) {
                    BsonValue item = value.asArray().get(i);
                    if (!(lenient && item.isNull())) {
                        insertable(item, "document " + (i + 1) + " ");
                    }
                }
            }
            case "update" -> {
                if (value.isArray()) {
                    stages(value.asArray(), lenient);
                } else if (value.isDocument()) {
                    BsonDocument update = value.asDocument();
                    if (update.isEmpty()) {
                        throw new IllegalArgumentException("needs at least one $-operator, e.g. {\"$set\": {...}}");
                    }
                    for (String key : update.keySet()) {
                        if (!key.startsWith("$")) {
                            throw new IllegalArgumentException("takes only $-operators (\"" + key
                                    + "\" would replace the document), e.g. {\"$set\": {\"" + key + "\": ...}}");
                        }
                    }
                } else {
                    throw new IllegalArgumentException("must be an object of $-operators or an update pipeline (array)");
                }
            }
            case "options" -> {
                if (!value.isDocument()) {
                    throw new IllegalArgumentException("must be an object");
                }
                List<String> allowed = arg.options().stream().map(Option::name).toList();
                for (Map.Entry<String, BsonValue> e : value.asDocument().entrySet()) {
                    if (!allowed.contains(e.getKey())) {
                        throw new IllegalArgumentException("unknown key \"" + e.getKey() + "\" (" + subtype
                                + " accepts " + String.join(", ", allowed) + ")");
                    }
                    if (!(lenient && e.getValue().isNull())) {
                        checkOption(arg.options().get(allowed.indexOf(e.getKey())), e.getValue());
                    }
                }
            }
            default -> throw new IllegalStateException("unknown argument kind " + arg.kind());
        }
    }

    private static void stages(BsonArray stages, boolean lenient) {
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("needs at least one stage");
        }
        for (int i = 0; i < stages.size(); i++) {
            BsonValue stage = stages.get(i);
            if (!stage.isDocument() && !(lenient && stage.isNull())) {
                throw new IllegalArgumentException("stage " + (i + 1) + " must be an object");
            }
        }
    }

    /** A document to insert: a non-empty object without top-level {@code $} keys (operators). */
    private static void insertable(BsonValue value, String which) {
        if (!value.isDocument() || value.asDocument().isEmpty()) {
            throw new IllegalArgumentException(which + "must be a non-empty object");
        }
        for (String key : value.asDocument().keySet()) {
            if (key.startsWith("$")) {
                throw new IllegalArgumentException(which + "cannot have the operator key \"" + key + "\" (documents hold data)");
            }
        }
    }

    private static void checkOption(Option option, BsonValue value) {
        String name = option.name();
        switch (option.kind()) {
            case "object" -> {
                if (!value.isDocument()) {
                    throw new IllegalArgumentException(name + " must be an object");
                }
                if ("collation".equals(name)) {
                    collationOf(value.asDocument());
                }
            }
            case "objects" -> {
                if (!value.isArray() || value.asArray().stream().anyMatch(v -> !v.isDocument())) {
                    throw new IllegalArgumentException(name + " must be an array of objects");
                }
            }
            case "integer", "positiveInteger" -> {
                int min = "integer".equals(option.kind()) ? 0 : 1;
                if (!value.isNumber() || value.asNumber().doubleValue() < min
                        || value.asNumber().doubleValue() != Math.rint(value.asNumber().doubleValue())
                        || value.asNumber().doubleValue() > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(name + " must be " + (min == 0 ? "a non-negative" : "a positive") + " integer");
                }
            }
            case "boolean" -> {
                if (!value.isBoolean()) {
                    throw new IllegalArgumentException(name + " must be true or false");
                }
            }
            case "string" -> {
                if (!value.isString()) {
                    throw new IllegalArgumentException(name + " must be a string");
                }
            }
            case "hint" -> {
                if (!value.isString() && !value.isDocument()) {
                    throw new IllegalArgumentException(name + " must be an index name (string) or an index key object");
                }
            }
            default -> throw new IllegalStateException("unknown option kind " + option.kind());
        }
    }

    /** The driver collation of a {@code collation} option; IllegalArgumentException names a bad key or value. */
    public static Collation collationOf(BsonDocument doc) {
        for (String key : doc.keySet()) {
            if (!COLLATION_KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown collation key \"" + key + "\" (" + String.join(", ", COLLATION_KEYS) + ")");
            }
        }
        if (!doc.isString("locale")) {
            throw new IllegalArgumentException("collation needs a locale, e.g. {\"locale\": \"pt\"}");
        }
        try {
            Collation.Builder b = Collation.builder().locale(doc.getString("locale").getValue());
            if (doc.containsKey("caseLevel")) {
                b.caseLevel(doc.getBoolean("caseLevel").getValue());
            }
            if (doc.containsKey("caseFirst")) {
                b.collationCaseFirst(CollationCaseFirst.fromString(doc.getString("caseFirst").getValue()));
            }
            if (doc.containsKey("strength")) {
                b.collationStrength(CollationStrength.fromInt(doc.getNumber("strength").intValue()));
            }
            if (doc.containsKey("numericOrdering")) {
                b.numericOrdering(doc.getBoolean("numericOrdering").getValue());
            }
            if (doc.containsKey("alternate")) {
                b.collationAlternate(CollationAlternate.fromString(doc.getString("alternate").getValue()));
            }
            if (doc.containsKey("maxVariable")) {
                b.collationMaxVariable(CollationMaxVariable.fromString(doc.getString("maxVariable").getValue()));
            }
            if (doc.containsKey("normalization")) {
                b.normalization(doc.getBoolean("normalization").getValue());
            }
            if (doc.containsKey("backwards")) {
                b.backwards(doc.getBoolean("backwards").getValue());
            }
            return b.build();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid collation: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- options

    public Integer intOption(String key) {
        return options.isNumber(key) ? options.getNumber(key).intValue() : null;
    }

    public Boolean boolOption(String key) {
        return options.isBoolean(key) ? options.getBoolean(key).getValue() : null;
    }

    public String stringOption(String key) {
        return options.isString(key) ? options.getString(key).getValue() : null;
    }

    public BsonDocument documentOption(String key) {
        return options.isDocument(key) ? options.getDocument(key) : null;
    }

    public BsonValue hintOption() {
        BsonValue hint = options.get("hint");
        return hint != null && (hint.isString() || hint.isDocument()) ? hint : null;
    }

    public Collation collationOption() {
        BsonDocument collation = documentOption("collation");
        return collation == null ? null : collationOf(collation);
    }

    public List<BsonDocument> arrayFiltersOption() {
        return options.isArray("arrayFilters")
                ? options.getArray("arrayFilters").getValues().stream().map(BsonValue::asDocument).toList()
                : null;
    }

    // ---------------------------------------------------------------- helpers

    private static BsonDocument doc(BsonArray args, int index) {
        return args.size() > index && args.get(index).isDocument() ? args.get(index).asDocument() : null;
    }

    /** The first {@code $out}/{@code $merge} key found anywhere in the arguments, or null. */
    private static String writeStageIn(BsonValue value) {
        if (value.isDocument()) {
            for (Map.Entry<String, BsonValue> e : value.asDocument().entrySet()) {
                if (WRITE_STAGES.contains(e.getKey())) {
                    return e.getKey();
                }
                String nested = writeStageIn(e.getValue());
                if (nested != null) {
                    return nested;
                }
            }
        } else if (value.isArray()) {
            for (BsonValue item : value.asArray()) {
                String nested = writeStageIn(item);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /** The subtype's call shape, e.g. {@code find: [filter, projection?, options?]}. */
    public static String hint(String subtype, List<Arg> signature) {
        List<String> names = new ArrayList<>();
        for (Arg arg : signature) {
            names.add(arg.name() + (arg.required() ? "" : "?"));
        }
        return "the body is one JSON array of arguments, " + subtype + ": [" + String.join(", ", names) + "]";
    }
}
