package com.testframe.plugin.cli;

import com.testframe.plugin.Json;
import com.testframe.plugin.format.ProjectDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical dump of the format parity gate (repo-root {@code scripts/format-check.ts}): the
 * projection both sides print, an invalid card dropped and reported, and a private value masked.
 */
class FormatDumpTest {

    @TempDir
    Path project;

    private static String card(String frontmatter, String body) {
        return "-------------------\n" + frontmatter + "\n-------------------\n" + body;
    }

    private void write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> frames(Map<String, Object> dump) {
        return (List<Map<String, Object>>) dump.get("frames");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cards(Map<String, Object> dump, int frame, int step) {
        List<Map<String, Object>> steps = (List<Map<String, Object>>) frames(dump).get(frame).get("steps");
        return (List<Map<String, Object>>) steps.get(step).get("cards");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sources(Map<String, Object> dump) {
        return (Map<String, Object>) dump.get("sources");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resolved(Map<String, Object> dump, int environment) {
        List<Map<String, Object>> resolved = (List<Map<String, Object>>) sources(dump).get("resolved");
        return (List<Map<String, Object>>) resolved.get(environment).get("sources");
    }

    private Map<String, Object> dump() throws IOException {
        return FormatDump.dump(ProjectDir.at(project));
    }

    @Test
    void dumpsFramesAndCardsInTheCanonicalProjection() throws IOException {
        write("testframe/frames/demo/step-1/flow.md", "-------------------\n-------------------\nget-products\ntotal\n");
        write("testframe/frames/demo/step-1/get-products.md", card(
                "title: Get products\nsource: mongo/products-db\nsubtype: find\ncollection: products",
                "```json\n[\n  { \"ativo\": true }\n]\n```\n"));
        write("testframe/frames/demo/step-1/total.md", card(
                "title: Total\nsource: rawvalue\nsubtype: json\ntimeout: 1500",
                "```json\n0\n```\n"));
        write("testframe/sources.yaml", "environments: [local]\nmongo:\n  products-db:\n    base-url: mongodb://localhost:27017/products\n");

        Map<String, Object> dump = dump();
        assertEquals(FormatDump.FORMAT, dump.get("format"));
        assertEquals(project.getFileName().toString(), dump.get("project"));
        assertEquals(List.of(), dump.get("frameProblems"));
        assertEquals("demo", frames(dump).get(0).get("name"));

        Map<String, Object> mongo = cards(dump, 0, 0).get(0);
        assertEquals("getProducts", mongo.get("varName"));
        assertEquals("query", mongo.get("type"), "the card type follows (type, subtype)");
        assertEquals("mongo", mongo.get("sourceType"));
        assertEquals("products-db", mongo.get("sourceName"));
        assertEquals("find", mongo.get("subtype"));
        assertEquals("products", mongo.get("collection"));
        assertEquals(5000, mongo.get("timeout"), "an absent timeout is the card type's default");
        assertEquals("[\n  { \"ativo\": true }\n]", mongo.get("code"));
        assertEquals(null, mongo.get("endpoint"), "an absent field is dumped as null");

        Map<String, Object> raw = cards(dump, 0, 0).get(1);
        assertEquals("rawvalue", raw.get("sourceType"));
        assertEquals(null, raw.get("sourceName"));
        assertEquals(1500, raw.get("timeout"));
        assertEquals("0", raw.get("code"));
    }

    @Test
    void anInvalidCardIsReportedAndDropped() throws IOException {
        write("testframe/frames/demo/step-1/flow.md", "-------------------\n-------------------\nbroken\n");
        write("testframe/frames/demo/step-1/broken.md", card("title: Broken\nsource: rawvalue", "```json\n1\n```\n"));
        write("testframe/sources.yaml", "environments: [local]\n");

        Map<String, Object> dump = dump();
        assertEquals(List.of(), cards(dump, 0, 0), "the IDE's reader drops an invalid card too");
        // Only the card problem: the decoders keep an invalid card best effort, so its flow.md
        // entry still resolves. The IDE's reader drops the card first and additionally reports
        // "flow.md: broken.md is listed but the card file is missing" - a difference that
        // `bun run format:check` shows on a malformed project (reported, not papered over).
        assertEquals(List.of("demo/step-1/broken.md: subtype: required for rawvalue (json)"), dump.get("frameProblems"));
    }

    @Test
    void resolvesEveryEnvironmentAndMasksPrivateValues() throws IOException {
        write("testframe/sources.yaml", """
                environments: [local, hml]
                openapi:
                  product-api:
                    base-url: http://localhost:18081/api/docs/openapi.json
                mongo:
                  products-db:
                    base-url: mongodb://localhost:27017/products
                """);
        write("testframe/sources.hml.yaml", """
                openapi:
                  product-api:
                    base-url: https://api.{env}.example.com/openapi.json
                    headers:
                      X-Api-Key: hml-key
                """);
        write("testframe/sources.hml.private.yaml", """
                mongo:
                  products-db:
                    base-url: mongodb://app:s3cret@hml.example.com:27017/products
                """);

        Map<String, Object> dump = dump();
        assertEquals(List.of("local", "hml"), sources(dump).get("environments"));
        assertEquals(List.of("openapi/product-api", "mongo/products-db"), sources(dump).get("declared"));
        assertEquals(List.of(), sources(dump).get("problems"));

        Map<String, Object> localApi = resolved(dump, 0).get(0);
        assertEquals("openapi/product-api", localApi.get("ref"));
        assertEquals("http://localhost:18081/api/docs/openapi.json", localApi.get("baseUrl"));
        assertEquals(true, localApi.get("configured"));
        assertEquals(Map.of(), localApi.get("headers"));
        assertEquals(List.of(), localApi.get("privateFields"));

        Map<String, Object> hmlApi = resolved(dump, 1).get(0);
        assertEquals("https://api.hml.example.com/openapi.json", hmlApi.get("baseUrl"), "{env} is substituted");
        assertEquals(Map.of("x-api-key", "hml-key"), hmlApi.get("headers"), "header names are lower-case");

        Map<String, Object> hmlMongo = resolved(dump, 1).get(1);
        assertEquals(FormatDump.PRIVATE, hmlMongo.get("baseUrl"));
        assertEquals(List.of("base-url"), hmlMongo.get("privateFields"));
    }

    @Test
    void invalidSourcesAreReportedWithoutAResolution() throws IOException {
        write("testframe/sources.yaml", "openapi:\n  product-api:\n    base-url: http://x/openapi.json\n");

        Map<String, Object> dump = dump();
        assertEquals(List.of(), sources(dump).get("environments"));
        assertEquals(List.of(), sources(dump).get("resolved"), "an invalid layer file resolves nothing");
        assertEquals(List.of("sources.yaml: environments is required (a non-empty list of environment names)"),
                sources(dump).get("problems"));
    }

    @Test
    void printsTheDumpAsJsonAndExitsZero() throws IOException {
        write("testframe/frames/demo/step-1/flow.md", "-------------------\n-------------------\n");
        write("testframe/sources.yaml", "environments: [local]\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = FormatDump.main(new String[]{FormatDump.COMMAND, project.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(0, code);
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        Map<String, Object> printed = Json.MAPPER.readValue(out.toString(StandardCharsets.UTF_8), Map.class);
        assertEquals(FormatDump.FORMAT, printed.get("format"));
        assertEquals(project.getFileName().toString(), printed.get("project"));
        assertEquals("demo", frames(printed).get(0).get("name"));
    }

    @Test
    void rejectsABadInvocationAndANonProject() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertEquals(2, FormatDump.main(new String[]{FormatDump.COMMAND}, new PrintStream(new ByteArrayOutputStream()), errStream));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("usage:"));
        assertEquals(2, FormatDump.main(new String[]{FormatDump.COMMAND, project.toString()},
                new PrintStream(new ByteArrayOutputStream()), errStream), "no testframe/ folder");
    }
}
