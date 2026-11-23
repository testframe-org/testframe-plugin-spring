package com.testframe.plugin.format;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restricted loader of the {@code sources*.yaml} layers: maps, lists and scalars only, typed
 * with the YAML 1.2 core schema, and every construct that could make the parser build an object or
 * expand a document (anchors, aliases, tags, duplicate keys, complex keys, a second document) is an
 * error. The messages are only asserted loosely here — the wording of the {@code format/} problems
 * is owned by the TS × Java parity work, while {@code SafeYamlErrorTest} owns the rule that a parse
 * error never echoes the document.
 */
class SafeYamlTest {

    // -------------------------------------------------------------------- accepted shapes

    @Test
    void anEmptyDocumentIsAnEmptyMapping() {
        assertEquals(Map.of(), entries(SafeYaml.load("")));
        assertEquals(Map.of(), entries(SafeYaml.load(null)));
        assertEquals(Map.of(), entries(SafeYaml.load("# only a comment\n")));
    }

    @Test
    void nestedMapsListsAndScalarsAreLoadedInDocumentOrder() {
        SafeYaml.Value loaded = SafeYaml.load("""
                environments: [local, hml]
                openapi:
                  product-api:
                    base-url: http://localhost:18081/spec.json
                    headers:
                      x-tenant: testframe
                mongo:
                  products-db:
                    base-url: mongodb://localhost:27017/products
                """);

        assertInstanceOf(SafeYaml.Mapping.class, loaded);
        assertEquals(List.of("environments", "openapi", "mongo"), List.copyOf(entries(loaded).keySet()));

        SafeYaml.Mapping root = (SafeYaml.Mapping) loaded;
        assertInstanceOf(SafeYaml.Sequence.class, root.entries().get("environments"));
        assertEquals(List.of("local", "hml"),
                ((SafeYaml.Sequence) root.entries().get("environments")).items().stream()
                        .map(item -> ((SafeYaml.Scalar) item).text()).toList());

        SafeYaml.Mapping openapi = (SafeYaml.Mapping) root.entries().get("openapi");
        SafeYaml.Mapping source = (SafeYaml.Mapping) openapi.entries().get("product-api");
        assertEquals("http://localhost:18081/spec.json", text(source.entries().get("base-url")));
        SafeYaml.Mapping headers = (SafeYaml.Mapping) source.entries().get("headers");
        assertEquals("testframe", text(headers.entries().get("x-tenant")));
    }

    @Test
    void scalarsAreTypedWithTheYaml12CoreSchema() {
        SafeYaml.Mapping loaded = (SafeYaml.Mapping) SafeYaml.load("""
                a-string: products
                quoted-bool: "true"
                quoted-number: "42"
                a-bool: true
                another-bool: FALSE
                an-int: 42
                a-negative-int: -7
                a-hex-int: 0x2A
                an-octal-int: 0o52
                a-float: 1.5
                an-exponent-float: 1e3
                infinity: .inf
                not-a-number: .nan
                a-null: null
                a-tilde-null: ~
                an-empty-value:
                empty-string: ""
                """);

        assertEquals("string", kind(loaded, "a-string"));
        assertEquals("string", kind(loaded, "quoted-bool"), "a quoted scalar is always a string");
        assertEquals("string", kind(loaded, "quoted-number"));
        assertEquals("string", kind(loaded, "empty-string"));
        assertEquals("bool", kind(loaded, "a-bool"));
        assertEquals("bool", kind(loaded, "another-bool"));
        assertEquals("int", kind(loaded, "an-int"));
        assertEquals("int", kind(loaded, "a-negative-int"));
        assertEquals("int", kind(loaded, "a-hex-int"));
        assertEquals("int", kind(loaded, "an-octal-int"));
        assertEquals("float", kind(loaded, "a-float"));
        assertEquals("float", kind(loaded, "an-exponent-float"));
        assertEquals("float", kind(loaded, "infinity"));
        assertEquals("float", kind(loaded, "not-a-number"));
        assertEquals("null", kind(loaded, "a-null"));
        assertEquals("null", kind(loaded, "a-tilde-null"));
        assertEquals("null", kind(loaded, "an-empty-value"));
        assertEquals("true", text(loaded.entries().get("a-bool")), "the text is kept as written");
    }

    @Test
    void aSequenceOfMapsIsLoaded() {
        SafeYaml.Sequence loaded = (SafeYaml.Sequence) SafeYaml.load("""
                - name: a
                  value: 1
                - name: b
                """);
        assertEquals(2, loaded.items().size());
        assertEquals("a", text(((SafeYaml.Mapping) loaded.items().getFirst()).entries().get("name")));
    }

    // ------------------------------------------------------------------ refused constructs

    @Test
    void anchorsAndAliasesAreRefused() {
        // an anchor always precedes the alias that uses it, so the anchor rule is what fires; the
        // alias rule behind it is defence in depth
        assertRefused("""
                defaults: &defaults
                  base-url: http://localhost:18081/spec.json
                openapi:
                  product-api:
                    <<: *defaults
                """, "anchors are not allowed");
        assertRefused("a: &anchor value\n", "anchors are not allowed");
        assertRefused("- &item a\n- *item\n", "anchors are not allowed");
    }

    @Test
    void explicitTagsAreRefused() {
        assertRefused("a: !!str value\n", "tags are not allowed");
        assertRefused("!!map\na: 1\n", "tags are not allowed");
        assertRefused("a: !!seq [1, 2]\n", "tags are not allowed");
    }

    @Test
    void duplicateKeysAreRefused() {
        assertRefused("openapi:\n  a:\n    base-url: x\nopenapi:\n  b:\n    base-url: y\n", "duplicate key");
        assertRefused("a: 1\na: 2\n", "duplicate key");
    }

    @Test
    void aKeyThatIsNotAPlainNameIsRefused() {
        assertRefused("? [a, b]\n: value\n", "keys must be plain names");
        assertRefused("? {a: 1}\n: value\n", "keys must be plain names");
    }

    @Test
    void aSecondDocumentIsRefused() {
        assertRefused("a: 1\n---\nb: 2\n", "multiple YAML documents are not allowed");
    }

    @Test
    void aTopLevelSequenceIsLoadedAndLeftToTheDecoders() {
        assertInstanceOf(SafeYaml.Sequence.class, SafeYaml.load("- 1\n- 2\n"));
    }

    @Test
    void runawayNestingIsRefused() {
        StringBuilder yaml = new StringBuilder();
        for (int i = 0; i < SafeYaml.MAX_DEPTH + 20; i++) {
            yaml.append("  ".repeat(i)).append("level").append(i).append(":\n");
        }
        yaml.append("  ".repeat(SafeYaml.MAX_DEPTH + 20)).append("value: 1\n");
        assertRefused(yaml.toString(), "nesting deeper than " + SafeYaml.MAX_DEPTH + " is not allowed");
    }

    @Test
    void nestingUpToTheLimitIsLoaded() {
        StringBuilder yaml = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            yaml.append("  ".repeat(i)).append("level").append(i).append(":\n");
        }
        yaml.append("  ".repeat(5)).append("base-url: http://h/s\n");
        assertInstanceOf(SafeYaml.Mapping.class, SafeYaml.load(yaml.toString()));
    }

    @Test
    void anOversizedDocumentIsRefused() {
        String huge = "a: \"" + "x".repeat((int) ProjectDir.MAX_FILE_BYTES) + "\"\n";
        SafeYaml.YamlProblem problem = assertThrows(SafeYaml.YamlProblem.class, () -> SafeYaml.load(huge));
        assertTrue(problem.getMessage().contains("size limit"), problem.getMessage());
    }

    // ------------------------------------------------------------------------- helpers

    /** The construct is refused, and the problem says which rule refused it. */
    private static void assertRefused(String yaml, String expected) {
        SafeYaml.YamlProblem problem = assertThrows(SafeYaml.YamlProblem.class, () -> SafeYaml.load(yaml),
                () -> "accepted: " + yaml);
        assertTrue(problem.getMessage().contains(expected),
                () -> "\"" + problem.getMessage() + "\" does not name " + expected);
    }

    private static Map<String, SafeYaml.Value> entries(SafeYaml.Value value) {
        return value instanceof SafeYaml.Mapping mapping ? mapping.entries() : Map.of();
    }

    private static String kind(SafeYaml.Mapping mapping, String key) {
        return ((SafeYaml.Scalar) mapping.entries().get(key)).kind();
    }

    private static String text(SafeYaml.Value value) {
        return ((SafeYaml.Scalar) value).text();
    }
}
