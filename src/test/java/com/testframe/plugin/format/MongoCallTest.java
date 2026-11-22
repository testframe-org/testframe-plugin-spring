package com.testframe.plugin.format;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoCallTest {

    private static String problem(String subtype, String code) {
        return MongoCall.problem(subtype, code);
    }

    @Test
    void findTakesFilterProjectionAndOptions() {
        MongoCall c = MongoCall.parse("find", "products", """
                [{"ativo": true, "_id": {"$oid": "6b022609da8071eb915cbe8a"}}, {"nome": 1},
                 {"sort": {"preco": -1}, "limit": 5, "skip": 2, "maxTimeMS": 300, "collation": {"locale": "pt"},
                  "hint": "nome_1", "comment": "c"}]
                """);
        assertEquals("products", c.collection());
        assertTrue(c.filter().get("_id").isObjectId(), "extended JSON is understood");
        assertEquals(1, c.projection().getInt32("nome").getValue());
        assertEquals(5, c.intOption("limit"));
        assertEquals("nome_1", c.hintOption().asString().getValue());
        assertEquals("pt", c.collationOption().getLocale());
        MongoCall bare = MongoCall.parse("find", "products", "[{}]");
        assertTrue(bare.filter().isEmpty());
        assertNull(bare.projection(), "trailing optional arguments may be omitted");
        assertEquals("arg 1 (filter): required, the body is one JSON array of arguments, find: [filter, projection?, options?]",
                problem("find", "[]"));
    }

    @Test
    void arityTypesAndUnknownOptionKeys() {
        assertEquals("find takes at most 3 arguments: the body is one JSON array of arguments, find: [filter, projection?, options?]",
                problem("find", "[{}, {}, {}, {}]"));
        assertEquals("arg 1 (filter): must be an object", problem("find", "[[]]"));
        assertEquals("arg 3 (options): unknown key \"upsert\" (find accepts sort, limit, skip, maxTimeMS, collation, hint, comment)",
                problem("find", "[{}, {}, {\"upsert\": true}]"));
        assertEquals("arg 3 (options): limit must be a non-negative integer", problem("find", "[{}, null, {\"limit\": -1}]"));
        assertEquals("arg 2 (options): batchSize must be a positive integer", problem("aggregation", "[[{}], {\"batchSize\": 0}]"));
        assertEquals("arg 3 (options): arrayFilters must be an array of objects",
                problem("updateOne", "[{\"a\": 1}, {\"$set\": {\"b\": 2}}, {\"arrayFilters\": [1]}]"));
        assertTrue(problem("find", "{\"filter\": {}}").startsWith("the body is one JSON array of arguments, find:"),
                "the body is an array");
        assertTrue(problem("find", "db.products.find({})").startsWith("invalid JSON: "));
        assertEquals("unknown mongo subtype \"drop\" (find, aggregation, deleteOne, deleteMany, updateOne, updateMany, insertOne, insertMany)",
                problem("drop", "[]"));
        assertEquals(List.of("arg 1 (filter): must be an object", "arg 2 (projection): must be an object"),
                MongoCall.problems("find", "[1, 2]"), "one problem per argument");
    }

    @Test
    void aggregationTakesAPipelineAndRejectsWriteStages() {
        MongoCall c = MongoCall.parse("aggregation", "products", """
                [[{"$match": {"ativo": true}}, {"$group": {"_id": "$categoria", "n": {"$sum": 1}}}], {"allowDiskUse": true, "let": {"x": 1}}]
                """);
        assertEquals(2, c.pipeline().size());
        assertEquals(Boolean.TRUE, c.boolOption("allowDiskUse"));
        assertEquals("arg 1 (pipeline): needs at least one stage", problem("aggregation", "[[]]"));
        assertEquals("arg 1 (pipeline): stage \"$out\" is not allowed in mongo cards", problem("aggregation", "[[{\"$out\": \"x\"}]]"));
        assertEquals("arg 1 (pipeline): stage \"$merge\" is not allowed in mongo cards",
                problem("aggregation", "[[{\"$facet\": {\"a\": [{\"$merge\": \"y\"}]}}]]"));
    }

    @Test
    void writesNeedANonEmptyFilterAndOperatorUpdates() {
        assertNull(problem("deleteMany", "[{\"tmp\": true}, {\"comment\": \"c\"}]"));
        assertEquals("arg 1 (filter): deleteMany needs a non-empty filter (an empty filter would delete the whole collection)",
                problem("deleteMany", "[{}]"));
        assertEquals("arg 1 (filter): updateOne needs a non-empty filter (an empty filter would update the whole collection)",
                problem("updateOne", "[{}, {\"$set\": {\"a\": 1}}]"));
        assertEquals("arg 2 (update): takes only $-operators (\"nome\" would replace the document), e.g. {\"$set\": {\"nome\": ...}}",
                problem("updateMany", "[{\"a\": 1}, {\"nome\": \"x\"}]"));
        assertEquals("arg 2 (update): required, the body is one JSON array of arguments, updateOne: [filter, update, options?]",
                problem("updateOne", "[{\"a\": 1}]"));
        assertNull(problem("updateMany", "[{\"a\": 1}, [{\"$set\": {\"b\": \"$a\"}}], {\"upsert\": false}]"), "update pipelines");
        MongoCall u = MongoCall.parse("updateOne", "c", "[{\"k\": \"a\"}, {\"$set\": {\"v\": 1}}, {\"upsert\": true}]");
        assertEquals(Boolean.TRUE, u.boolOption("upsert"));
        assertTrue(u.update().isDocument());
    }

    @Test
    void insertsTakeDataDocuments() {
        MongoCall one = MongoCall.parse("insertOne", "c", "[{\"k\": \"a\"}, {\"bypassDocumentValidation\": true}]");
        assertEquals(1, one.documents().size());
        MongoCall many = MongoCall.parse("insertMany", "c", "[[{\"k\": 1}, {\"k\": 2}], {\"ordered\": false}]");
        assertEquals(2, many.documents().size());
        assertEquals("arg 1 (document): must be a non-empty object", problem("insertOne", "[{}]"));
        assertEquals("arg 1 (document): cannot have the operator key \"$set\" (documents hold data)",
                problem("insertOne", "[{\"$set\": {\"a\": 1}}]"));
        assertEquals("arg 1 (documents): must be an array of at least one document", problem("insertMany", "[[]]"));
        assertEquals("arg 1 (documents): document 2 must be a non-empty object", problem("insertMany", "[[{\"a\": 1}, {}]]"));
        assertEquals("arg 2 (options): unknown key \"upsert\" (insertMany accepts ordered, comment, bypassDocumentValidation)",
                problem("insertMany", "[[{\"a\": 1}], {\"upsert\": true}]"));
    }

    @Test
    void bindingsStandForAnyValueAndTheCollectionIsChecked() {
        assertNull(problem("find", "[{\"nome\": \"{{first.nome}}\"}, null, {\"limit\": {{n}}}]"));
        assertNull(problem("deleteOne", "[{\"_id\": \"{{created.id}}\"}]"));
        assertNull(problem("insertMany", "[\"{{docs}}\"]"));
        assertThrows(IllegalArgumentException.class, () -> MongoCall.parse("find", "x", "[{}, null, {\"limit\": \"{{n}}\"}]"),
                "once resolved, the real types are checked again");
        assertEquals("collection: system collections cannot be used",
                assertThrows(IllegalArgumentException.class, () -> MongoCall.parse("find", "system.users", "[{}]")).getMessage());
        assertNull(MongoCall.collectionProblem("order.items"));
        assertEquals("\"a$b\" is not a collection name (no $, no spaces around, no null character)", MongoCall.collectionProblem("a$b"));
    }

    @Test
    void nullSkipsAnOptionalArgument() {
        MongoCall c = MongoCall.parse("find", "products", "[{}, null, {\"limit\": 5}]");
        assertEquals(5, c.intOption("limit"));
        assertNull(c.projection());
    }
}