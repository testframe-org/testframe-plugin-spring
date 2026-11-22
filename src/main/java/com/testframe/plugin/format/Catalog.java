package com.testframe.plugin.format;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Source types, their card subtypes and the card defaults derived from them (spec:
 * docs/project-format.md, "Source types and subtypes"). A source has a TYPE (its key in
 * {@code sources.yaml}); a card bound to it picks a SUBTYPE of that type when the type has
 * subtypes. The card type and the default timeout follow (type, subtype).
 */
public final class Catalog {

    /**
     * What a card of one (type, subtype) is: {@code subtype} is null for types without
     * subtypes; {@code form} is the editor form that fills it (request, swagger, mongo, query,
     * script, condition, json, code).
     */
    public record Kind(String subtype, String cardType, String language, String form) {
    }

    /**
     * A source type: {@code language} is its default fence language (each kind has its own);
     * {@code hasSources} = cards name a
     * source of the type ({@code source: {type}/{name}}), otherwise the card writes the type
     * alone and holds its own code (rawvalue, jsonassert); {@code kinds} has one entry per
     * subtype (or one without subtype).
     */
    public record Type(String name, String language, boolean hasSources, List<Kind> kinds) {

        public boolean hasSubtypes() {
            return kinds.getFirst().subtype() != null;
        }

        public List<String> subtypes() {
            return hasSubtypes() ? kinds.stream().map(Kind::subtype).toList() : List.of();
        }

    }

    public static final String RAWVALUE = "rawvalue";

    public static final List<Type> TYPES = List.of(
            new Type("openapi", "js", true, List.of(
                    new Kind("restapi", "request", "js", "request"),
                    // the openapi source's base-url is the OpenAPI spec, which defines every endpoint
                    new Kind("swagger", "request", "json", "swagger"),
                    new Kind("graphql", "request", "js", "request"))),
            new Type("mongo", "json", true, List.of(
                    // the body is the array of the call's positional arguments (format/MongoCall)
                    new Kind("find", "query", "json", "mongo"),
                    new Kind("aggregation", "query", "json", "mongo"),
                    new Kind("deleteOne", "query", "json", "mongo"),
                    new Kind("deleteMany", "query", "json", "mongo"),
                    new Kind("updateOne", "query", "json", "mongo"),
                    new Kind("updateMany", "query", "json", "mongo"),
                    new Kind("insertOne", "query", "json", "mongo"),
                    new Kind("insertMany", "query", "json", "mongo"))),
            new Type("sql", "sql", true, List.of(
                    new Kind("oracle", "sql", "sql", "query"),
                    new Kind("postgres", "sql", "sql", "query"),
                    new Kind("mysql", "sql", "sql", "query"),
                    new Kind("sqlserver", "sql", "sql", "query"))),
            // jsonassert code always lives in the card itself: no named sources
            // script: assert lines and/or expressions (the last expression's value is published);
            // conditional: one boolean expression, false skips the rest of the step
            new Type("jsonassert", "jsonassert", false, List.of(
                    new Kind("script", "jsonassert", "jsonassert", "script"),
                    new Kind("conditional", "condition", "jsonassert", "condition"))),
            new Type("kafka", "js", true, List.of(new Kind(null, "js", "js", "code"))),
            new Type("grpc", "js", true, List.of(new Kind(null, "js", "js", "code"))),
            // a literal value held by the card; json is its only subtype for now
            new Type(RAWVALUE, "json", false, List.of(new Kind("json", "json", "json", "json"))));

    public static final List<String> SOURCE_TYPES = TYPES.stream().map(Type::name).toList();


    private Catalog() {
    }

    /** The type named {@code name}; a blank name is the rawvalue type (literal value cards). */
    public static Optional<Type> type(String name) {
        String key = name == null || name.isBlank() ? RAWVALUE : name;
        return TYPES.stream().filter(t -> t.name().equals(key)).findFirst();
    }

    /** The card kind of (type, subtype), or empty when the type is unknown or the subtype invalid. */
    public static Optional<Kind> kind(String typeName, String subtype) {
        String sub = subtype == null || subtype.isBlank() ? null : subtype;
        return type(typeName).flatMap(t -> t.kinds().stream()
                .filter(k -> Objects.equals(k.subtype(), sub))
                .findFirst());
    }

    /**
     * The card kind used for defaults while (type, subtype) is still invalid (a draft): the
     * type's first kind, or the rawvalue kind for unknown types. Validation reports the problem.
     */
    private static Kind kindOrFallback(String typeName, String subtype) {
        return kind(typeName, subtype)
                .or(() -> type(typeName).map(t -> t.kinds().getFirst()))
                .orElseGet(() -> type(RAWVALUE).orElseThrow().kinds().getFirst());
    }

    /**
     * Problem of a card's (type, subtype) pair, or null: types with subtypes require one of
     * theirs, types without subtypes (and rawvalue cards) accept none.
     */
    public static String subtypeProblem(String typeName, String subtype) {
        Optional<Type> type = type(typeName);
        if (type.isEmpty()) {
            return "unknown type-source \"" + typeName + "\"";
        }
        boolean blank = subtype == null || subtype.isBlank();
        Type t = type.get();
        if (!t.hasSubtypes()) {
            return blank ? null : t.name() + " has no subtypes";
        }
        if (blank) {
            return "required for " + t.name() + " (" + String.join(", ", t.subtypes()) + ")";
        }
        return t.subtypes().contains(subtype) ? null
                : "unknown subtype \"" + subtype + "\" of " + t.name() + " (" + String.join(", ", t.subtypes()) + ")";
    }

    /** request (openapi), query (mongo), sql, jsonassert/condition (jsonassert), js (kafka, grpc), json (rawvalue). */
    public static String cardTypeOf(String typeName, String subtype) {
        return kindOrFallback(typeName, subtype).cardType();
    }



    public static int defaultTimeout(String cardType) {
        return switch (cardType) {
            case "jsonassert", "json" -> 2000;
            case "condition" -> 1000;
            default -> 5000; // request, query, js, sql
        };
    }

    /** Whether cards of this type name a source ({@code source: {type}/{name}}); false for rawvalue and jsonassert. */
    public static boolean hasSources(String typeName) {
        return type(typeName).map(Type::hasSources).orElse(false);
    }

}
